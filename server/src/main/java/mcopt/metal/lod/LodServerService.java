package mcopt.metal.lod;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.ticks.ProtoChunkTicks;
import org.jspecify.annotations.Nullable;

/**
 * mcopt-server: far terrain for players' mcopt clients (LodNet) from the world's generator (its seed and datapacks stay here: only the structure is
 * sent, for the client to paint) and its saved chunks, per player in the order asked (nearest first). Generation runs on low-priority threads that
 * wait while the server's ticks are slow; sends are metered per tick on the server thread. Settings: config/mcopt-server.properties.
 */
public final class LodServerService {
	private static final String VERSION = FabricLoader.getInstance().getModContainer("mcopt-server").map(m -> m.getMetadata().getVersion().getFriendlyString())
		.orElse("dev");

	private record Config(boolean generate, boolean chunks, int radius, int threads, int kbps, int queue, double lagMs) {
	}

	private static Config cfg;
	private static @Nullable MinecraftServer server;
	private static final Map<String, Dim> dims = new ConcurrentHashMap<>();
	private static final Map<UUID, Peer> peers = new ConcurrentHashMap<>();
	private static final List<Thread> workers = new ArrayList<>();
	private static volatile boolean running;
	private static final Object signal = new Object();

	private LodServerService() {
	}

	/** One dimension's generator and caches (made when a player first asks about it). */
	private static final class Dim {
		final ServerLevel level;
		final String id;
		final @Nullable LodNoise noise;
		final @Nullable LodForest forest;
		final Path cache, regions;
		final long token;
		final int roof;
		/** Region file headers (offsets then times), by region, while recently used. */
		final Map<Long, int[]> headers = java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(64, 0.75F, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<Long, int[]> e) {
				return this.size() > 256;
			}
		});

		Dim(ServerLevel level, String id) {
			this.level = level;
			this.id = id;
			LodNoise n = null;
			try {
				n = cfg.generate ? LodNoise.of(level) : null;
			} catch (RuntimeException | LinkageError e) {
				System.out.println("[mcopt-server] can't generate far terrain for " + id + " (its generator isn't one we read): " + e);
			}
			if (n != null) n.paint = false;
			this.noise = n;
			this.forest = n != null && LodConfig.TREES ? new LodForest(n) : null;
			Path root = level.getServer().getWorldPath(LevelResource.ROOT);
			this.cache = root.resolve("mcopt-lod-server").resolve(safe(id));
			this.regions = net.minecraft.world.level.dimension.DimensionType.getStorageFolder(level.dimension(), root).resolve("region");
			var dim = level.dimensionType();
			this.roof = dim.hasCeiling() ? dim.minY() + dim.logicalHeight() - 1 : Integer.MAX_VALUE;
			this.token = claim(this.cache, level.getSeed());
		}
	}

	/** One player's asks and what is ready to send them. */
	private static final class Peer {
		/** The player's entity (a new one after each respawn: the latest request's). */
		volatile ServerPlayer player;
		volatile @Nullable String dimension;
		final LinkedHashSet<Long> tiles = new LinkedHashSet<>();
		final LinkedHashSet<Long> regions = new LinkedHashSet<>();
		final LinkedHashSet<Long> chunks = new LinkedHashSet<>();
		final ArrayDeque<CustomPacketPayload> outbox = new ArrayDeque<>();
		int outBytes;
		long allowance;
		int turn;

		Peer(ServerPlayer player) {
			this.player = player;
		}

		void clear() {
			this.tiles.clear();
			this.regions.clear();
			this.chunks.clear();
		}
	}

	public static void init() {
		cfg = loadConfig();
		PayloadTypeRegistry.serverboundPlay().register(LodNet.Hello.TYPE, LodNet.Hello.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(LodNet.TileReq.TYPE, LodNet.TileReq.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(LodNet.RegionReq.TYPE, LodNet.RegionReq.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(LodNet.ChunkReq.TYPE, LodNet.ChunkReq.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(LodNet.DimInfo.TYPE, LodNet.DimInfo.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(LodNet.Tile.TYPE, LodNet.Tile.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(LodNet.Manifest.TYPE, LodNet.Manifest.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(LodNet.Chunks.TYPE, LodNet.Chunks.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(LodNet.Hello.TYPE, (p, ctx) -> hello(ctx.player(), p));
		ServerPlayNetworking.registerGlobalReceiver(LodNet.TileReq.TYPE, (p, ctx) -> tileReq(ctx.player(), p));
		ServerPlayNetworking.registerGlobalReceiver(LodNet.RegionReq.TYPE, (p, ctx) -> regionReq(ctx.player(), p));
		ServerPlayNetworking.registerGlobalReceiver(LodNet.ChunkReq.TYPE, (p, ctx) -> chunkReq(ctx.player(), p));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, srv) -> peers.remove(handler.player.getUUID()));
		ServerLifecycleEvents.SERVER_STARTED.register(LodServerService::start);
		ServerLifecycleEvents.SERVER_STOPPING.register(srv -> stop());
		ServerTickEvents.END_SERVER_TICK.register(LodServerService::tick);
		System.out.println("[mcopt-server] far terrain for mcopt clients: generated " + cfg.generate + ", saved chunks " + cfg.chunks + ", out to "
			+ cfg.radius + " chunks, " + cfg.threads + " threads, " + cfg.kbps + " KB/s a player");
	}

	private static void start(MinecraftServer srv) {
		server = srv;
		running = true;
		for (int i = 0; i < cfg.threads; i++) {
			Thread t = new Thread(LodServerService::work, "mcopt-lod-server-" + i);
			t.setDaemon(true);
			t.setPriority(Thread.MIN_PRIORITY);
			t.start();
			workers.add(t);
		}
	}

	private static void stop() {
		running = false;
		wake();
		for (Thread t : workers) t.interrupt();
		workers.clear();
		peers.clear();
		dims.clear();
		server = null;
	}

	// ---- requests (server thread) ----

	private static Peer peer(ServerPlayer p) {
		Peer peer = peers.computeIfAbsent(p.getUUID(), k -> new Peer(p));
		if (peer.player != p) peer.player = p;
		return peer;
	}

	/** The player's peer if they said hello, its entity brought up to date (respawns make a new one). */
	private static @Nullable Peer known(ServerPlayer p) {
		Peer peer = peers.get(p.getUUID());
		if (peer != null && peer.player != p) peer.player = p;
		return peer;
	}

	private static void hello(ServerPlayer player, LodNet.Hello p) {
		if (p.protocol() != LodNet.PROTOCOL) return;
		String id = player.level().dimension().identifier().toString();
		if (!id.equals(p.dimension())) return;
		Dim d = dim(player.level(), id);
		Peer peer = peer(player);
		synchronized (peer) {
			peer.dimension = id;
			peer.clear();
			peer.outbox.clear();
			peer.outBytes = 0;
		}
		int sea = d.noise != null ? d.noise.seaLevel : player.level().getSeaLevel();
		ServerPlayNetworking.send(player, new LodNet.DimInfo(LodNet.PROTOCOL, id, d.noise != null, cfg.chunks && Files.isDirectory(d.regions), sea, cfg.radius,
			d.token));
	}

	private static Dim dim(ServerLevel level, String id) {
		return dims.computeIfAbsent(id, k -> new Dim(level, id));
	}

	private static void tileReq(ServerPlayer player, LodNet.TileReq p) {
		Peer peer = known(player);
		if (peer == null || !p.dimension().equals(peer.dimension)) return;
		synchronized (peer) {
			// (a reset: the client left this dimension, nothing it asked is wanted)
			if (p.reset()) peer.clear();
			for (long k : p.keys()) {
				if (peer.tiles.size() >= cfg.queue) {
					// declined: the client asks again later
					if (!peer.tiles.contains(k)) send(peer, new LodNet.Tile(p.dimension(), k, new byte[0]));
					continue;
				}
				peer.tiles.add(k);
			}
		}
		wake();
	}

	private static void regionReq(ServerPlayer player, LodNet.RegionReq p) {
		Peer peer = known(player);
		if (peer == null || !p.dimension().equals(peer.dimension) || !cfg.chunks) return;
		synchronized (peer) {
			for (long r : p.regions()) {
				if (peer.regions.size() >= 4096) break;
				peer.regions.add(r);
			}
		}
		wake();
	}

	private static void chunkReq(ServerPlayer player, LodNet.ChunkReq p) {
		Peer peer = known(player);
		if (peer == null || !p.dimension().equals(peer.dimension) || !cfg.chunks) return;
		synchronized (peer) {
			for (long k : p.chunks()) {
				if (peer.chunks.size() >= cfg.queue * 16) break;
				peer.chunks.add(k);
			}
		}
		wake();
	}

	private static void wake() {
		synchronized (signal) {
			signal.notifyAll();
		}
	}

	// ---- sending (server thread, every tick) ----

	private static void tick(MinecraftServer srv) {
		long perTick = cfg.kbps * 1024L / 20;
		for (Peer peer : peers.values()) {
			synchronized (peer) {
				// (an allowance never piles up past a second's worth)
				peer.allowance = Math.min(peer.allowance + perTick, cfg.kbps * 1024L);
				while (!peer.outbox.isEmpty() && peer.allowance > 0) {
					CustomPacketPayload out = peer.outbox.poll();
					int size = size(out);
					peer.outBytes -= size;
					peer.allowance -= size;
					if (ServerPlayNetworking.canSend(peer.player, out.type())) ServerPlayNetworking.send(peer.player, out);
				}
			}
		}
		if (!peers.isEmpty()) wake();
	}

	private static int size(CustomPacketPayload p) {
		if (p instanceof LodNet.Tile t) return t.data().length + 64;
		if (p instanceof LodNet.Chunks c) return c.data().length + 64;
		if (p instanceof LodNet.Manifest m) return m.stamps().length * 5 + 64;
		return 64;
	}

	private static void send(Peer peer, CustomPacketPayload p) {
		synchronized (peer) {
			peer.outbox.add(p);
			peer.outBytes += size(p);
		}
	}

	// ---- work (the service's threads) ----

	private static void work() {
		while (running) {
			try {
				// the server first: while its ticks run long, far terrain waits
				MinecraftServer srv = server;
				if (srv == null) return;
				if (srv.getAverageTickTimeNanos() / 1e6 > cfg.lagMs) {
					Thread.sleep(50);
					continue;
				}
				if (!step()) {
					synchronized (signal) {
						signal.wait(250);
					}
				}
			} catch (InterruptedException e) {
				return;
			} catch (RuntimeException e) {
				System.out.println("[mcopt-server] far terrain: a job failed: " + e);
			}
		}
	}

	/** One unit of work for one player, players in turn: a region's times, then a tile, then a batch of chunks. */
	private static boolean step() {
		// (by turns taken, read once: other workers bump them meanwhile)
		Peer[] list = peers.values().toArray(new Peer[0]);
		if (list.length == 0) return false;
		long[] order = new long[list.length];
		for (int i = 0; i < list.length; i++) order[i] = (long) list[i].turn << 32 | i;
		java.util.Arrays.sort(order);
		for (long o : order) {
			Peer peer = list[(int) o];
			String dimId = peer.dimension;
			if (dimId == null) continue;
			Dim d = dims.get(dimId);
			if (d == null) continue;
			long[] regions = null;
			long tile = Long.MIN_VALUE;
			long[] chunks = null;
			synchronized (peer) {
				// (a player who left the dimension without a word: what they asked about it isn't wanted)
				if (!dimId.equals(peer.player.level().dimension().identifier().toString())) {
					peer.clear();
					continue;
				}
				// (a player whose outbox holds more than two seconds' worth waits for it to drain)
				if (peer.outBytes > cfg.kbps * 2048L) continue;
				if (!peer.regions.isEmpty()) {
					regions = new long[Math.min(16, peer.regions.size())];
					var ri = peer.regions.iterator();
					for (int i = 0; i < regions.length; i++) {
						regions[i] = ri.next();
						ri.remove();
					}
				} else {
					var it = peer.tiles.iterator();
					if (it.hasNext()) {
						tile = it.next();
						it.remove();
					} else if (!peer.chunks.isEmpty()) {
						int n = Math.min(64, peer.chunks.size());
						chunks = new long[n];
						var ci = peer.chunks.iterator();
						for (int i = 0; i < n; i++) {
							chunks[i] = ci.next();
							ci.remove();
						}
					} else {
						continue;
					}
				}
				peer.turn++;
			}
			if (regions != null) manifests(peer, d, regions);
			else if (tile != Long.MIN_VALUE) tile(peer, d, tile);
			else chunks(peer, d, chunks);
			return true;
		}
		return false;
	}

	/** Whether a block position is within the served radius of the player (what they ask past it isn't made). */
	private static boolean near(Peer peer, double x, double z, double margin) {
		var pos = peer.player.position();
		double r = cfg.radius * 16.0 + margin;
		return Math.abs(x - pos.x) <= r && Math.abs(z - pos.z) <= r;
	}

	private static void tile(Peer peer, Dim d, long key) {
		int level = LodTile.levelOf(key), tx = LodTile.txOf(key), tz = LodTile.tzOf(key);
		int span = LodTile.SIZE << level;
		var pos = peer.player.position();
		if (d.noise == null || !LodNet.serves(cfg.radius, level, tx * (double) span + span / 2.0 - pos.x, tz * (double) span + span / 2.0 - pos.z)) {
			send(peer, new LodNet.Tile(d.id, key, new byte[0]));
			return;
		}
		Path f = d.cache.resolve("L" + level).resolve(tx + "." + tz + ".t");
		byte[] data = null;
		try {
			if (Files.isRegularFile(f)) data = Files.readAllBytes(f);
		} catch (IOException e) {
			// (unreadable: generated again below)
		}
		if (data == null) {
			LodTile t = new LodTile(level, tx, tz);
			LodForest forest = d.forest;
			boolean exact = level < Math.min(2, LodConfig.TREE_LEVELS) && forest != null && forest.available();
			t.impostorTrees = !exact;
			d.noise.generate(t);
			if (exact) {
				if (level == 0) forest.offer(t);
				forest.plant(t);
				d.noise.dressTrees(t);
				d.noise.dressGround(t);
			}
			data = LodStructure.encodeTile(t);
			try {
				Files.createDirectories(f.getParent());
				Path tmp = f.resolveSibling(f.getFileName() + ".tmp" + Thread.currentThread().threadId());
				Files.write(tmp, data);
				Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException e) {
				System.out.println("[mcopt-server] far terrain cache write failed: " + e);
			}
		}
		send(peer, new LodNet.Tile(d.id, key, data));
	}

	/** A region file's header: 1024 offsets then 1024 save times (null when there's no file). */
	private static int @Nullable [] header(Dim d, int rx, int rz) {
		long key = (long) rx << 32 | rz & 0xFFFFFFFFL;
		int[] h = d.headers.get(key);
		if (h != null) return h;
		Path f = d.regions.resolve("r." + rx + "." + rz + ".mca");
		if (!Files.isRegularFile(f)) return null;
		byte[] b = new byte[8192];
		try (InputStream in = Files.newInputStream(f)) {
			if (in.readNBytes(b, 0, 8192) < 8192) return null;
		} catch (IOException e) {
			return null;
		}
		h = new int[2048];
		for (int i = 0; i < 2048; i++) h[i] = (b[i * 4] & 255) << 24 | (b[i * 4 + 1] & 255) << 16 | (b[i * 4 + 2] & 255) << 8 | (b[i * 4 + 3] & 255);
		d.headers.put(key, h);
		return h;
	}

	private static void manifests(Peer peer, Dim d, long[] regions) {
		for (long r : regions) {
			int rx = (int) (r >> 32), rz = (int) r;
			if (!near(peer, rx * 512.0 + 256, rz * 512.0 + 256, 512)) continue;
			// (asked again: the file may have changed since)
			d.headers.remove(r);
			int[] h = header(d, rx, rz);
			if (h == null) continue;
			int[] stamps = new int[1024];
			for (int i = 0; i < 1024; i++) stamps[i] = h[i] != 0 ? Math.max(1, h[1024 + i]) : 0;
			send(peer, new LodNet.Manifest(d.id, rx, rz, stamps));
		}
	}

	private static void chunks(Peer peer, Dim d, long[] keys) {
		ChunkMap map = d.level.getChunkSource().chunkMap;
		CompoundTag context = ChunkMap.getChunkDataFixContextTag(d.level.dimension(), d.level.getChunkSource().getGenerator().getTypeNameForDataFixer());
		int version = SharedConstants.getCurrentVersion().dataVersion().version();
		LodStructure.Out out = new LodStructure.Out();
		int count = 0;
		LodStructure.Out batch = new LodStructure.Out();
		for (long k : keys) {
			int cx = ChunkPos.getX(k), cz = ChunkPos.getZ(k);
			if (!near(peer, cx * 16.0 + 8, cz * 16.0 + 8, 16)) continue;
			int[] h = header(d, Math.floorDiv(cx, 32), Math.floorDiv(cz, 32));
			if (h == null) continue;
			int idx = (cz & 31) * 32 + (cx & 31);
			if (h[idx] == 0) continue;
			try {
				CompoundTag tag = map.read(new ChunkPos(cx, cz)).get(30, TimeUnit.SECONDS).orElse(null);
				if (tag == null) continue;
				tag = map.upgradeChunkTag(tag, -1, context, version);
				if (!SerializableChunkData.getChunkStatusFromTag(tag).isOrAfter(ChunkStatus.FULL)) continue;
				ProtoChunk chunk = protoChunk(d.level, SerializableChunkData.parse(d.level, d.level.palettedContainerFactory(), tag));
				batch.i32(Math.max(1, h[1024 + idx]));
				LodStructure.encodeChunk(LodChunks.snapshot(chunk, d.roof), batch);
				count++;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			} catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException | RuntimeException e) {
				// (a chunk that can't be read is skipped)
			}
		}
		if (count == 0) return;
		out.i32(count);
		byte[] body = batch.raw();
		out.bytes(body, body.length);
		send(peer, new LodNet.Chunks(d.id, out.deflated()));
	}

	/** A saved chunk's sections as a chunk of our own (the server's never sees it), with its saved surface heights. */
	private static ProtoChunk protoChunk(ServerLevel level, SerializableChunkData data) {
		var factory = level.palettedContainerFactory();
		LevelChunkSection[] sections = new LevelChunkSection[level.getSectionsCount()];
		for (SerializableChunkData.SectionData sd : data.sectionData()) {
			int i = level.getSectionIndexFromSectionY(sd.y());
			if (i >= 0 && i < sections.length && sd.chunkSection() != null) sections[i] = sd.chunkSection();
		}
		for (int i = 0; i < sections.length; i++) if (sections[i] == null) sections[i] = new LevelChunkSection(factory);
		ProtoChunk chunk = new ProtoChunk(data.chunkPos(), UpgradeData.EMPTY, sections, new ProtoChunkTicks<>(), new ProtoChunkTicks<>(), level, factory, null);
		long[] surface = data.heightmaps().get(Heightmap.Types.WORLD_SURFACE);
		if (surface != null) chunk.setHeightmap(Heightmap.Types.WORLD_SURFACE, surface);
		else Heightmap.primeHeightmaps(chunk, java.util.EnumSet.of(Heightmap.Types.WORLD_SURFACE));
		return chunk;
	}

	// ---- the cache's owner, settings ----

	/** The cache is this seed's, structure's and switches' (generator.txt; any other is moved aside, deleted); the token: a hash, never the seed. */
	private static long claim(Path dir, long seed) {
		String want = "seed " + seed + " structure " + LodStructure.VERSION + " mcopt " + VERSION + " trees " + LodConfig.TREES + " " + LodConfig.TREE_LEVELS
			+ " plants " + LodConfig.PLANTS + " crowns " + LodConfig.CROWN_LEVELS + " fine " + LodConfig.FINE_DENSITY + " " + LodConfig.FINE_LEVELS;
		Path marker = dir.resolve("generator.txt");
		try {
			if (Files.isRegularFile(marker) && !Files.readString(marker).strip().equals(want)) {
				Path stale = dir.resolveSibling(dir.getFileName() + ".stale-" + System.currentTimeMillis());
				Files.move(dir, stale);
				Thread t = new Thread(() -> delete(stale), "mcopt-lod-server-cache-delete");
				t.setDaemon(true);
				t.start();
			}
			Files.createDirectories(dir);
			Files.writeString(marker, want + "\n");
		} catch (IOException e) {
			System.out.println("[mcopt-server] can't check " + marker + ": " + e);
		}
		long h = seed * 0x9E3779B97F4A7C15L;
		h ^= h >>> 31;
		h *= 0xBF58476D1CE4E5B9L;
		return h ^ h >>> 29;
	}

	private static void delete(Path root) {
		try (var walk = Files.walk(root)) {
			walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
				try {
					Files.deleteIfExists(p);
				} catch (IOException e) {
					// (left for the next time)
				}
			});
		} catch (IOException | RuntimeException e) {
			System.out.println("[mcopt-server] can't delete " + root + ": " + e);
		}
	}

	private static String safe(String name) {
		String s = name.strip().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
		if (s.isEmpty() || s.chars().allMatch(c -> c == '.')) s = "_" + s;
		return s.length() > 96 ? s.substring(0, 96) : s;
	}

	private static Config loadConfig() {
		Path f = FabricLoader.getInstance().getConfigDir().resolve("mcopt-server.properties");
		Properties p = new Properties();
		if (Files.isRegularFile(f)) {
			try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
				p.load(r);
			} catch (IOException e) {
				System.out.println("[mcopt-server] can't read " + f + ": " + e);
			}
		} else {
			try {
				Files.createDirectories(f.getParent());
				Files.writeString(f, """
					# mcopt-server: far terrain for players with mcopt (written on first start; restart to apply).
					# Generate far terrain from the world's generator (the seed never leaves the server).
					generate=true
					# Send players the chunks saved in the world (builds and explored land, as they are).
					savedChunks=true
					# How far out, in chunks.
					radius=512
					# Generation threads (0: half the cores, at least 1). They run at the lowest priority.
					threads=0
					# Kilobytes a second for each player.
					kbps=2048
					# Most tiles a player can have waiting.
					queue=4096
					# Far terrain waits while the server's average tick takes longer than this (ms).
					lagMs=40
					# The generator: the game's trees, on how many levels (2: on level 1 too, as clients on Ultra draw them; more
					# work), and plants on level 0. Changing these makes the server's cache anew.
					trees=true
					treeLevels=1
					plants=true
					""", StandardCharsets.UTF_8);
			} catch (IOException e) {
				System.out.println("[mcopt-server] can't write " + f + ": " + e);
			}
		}
		// the generator's switches, as the client's -D flags name them (LodConfig reads them; a -D flag given wins)
		for (String k : new String[] {"trees", "treeLevels", "plants", "crownLevels", "fineDensity", "fineLevels"}) {
			String v = p.getProperty(k);
			if (v != null && System.getProperty("mcopt.lod." + k) == null) System.setProperty("mcopt.lod." + k, v.strip());
		}
		int cores = Runtime.getRuntime().availableProcessors();
		int threads = Integer.parseInt(p.getProperty("threads", "0").strip());
		return new Config(Boolean.parseBoolean(p.getProperty("generate", "true").strip()), Boolean.parseBoolean(p.getProperty("savedChunks", "true").strip()),
			Math.clamp(Integer.parseInt(p.getProperty("radius", "512").strip()), 16, 4096), threads > 0 ? threads : Math.max(1, cores / 2),
			Math.max(16, Integer.parseInt(p.getProperty("kbps", "2048").strip())), Math.max(64, Integer.parseInt(p.getProperty("queue", "4096").strip())),
			Double.parseDouble(p.getProperty("lagMs", "40").strip()));
	}
}

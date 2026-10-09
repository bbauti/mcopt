package mcopt.metal.lod;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * The client's side of LodNet: on a server running mcopt-server, the far terrain asks the server for what it can't make
 * here (the server has the world's generator and its saves; the client has neither). Tiles the client lacks or has only in
 * part (real chunks' cells) are asked as they're needed, most wanted first; each arrives as its structure, is painted here
 * (LodPaint) and fills the tile's cells that had no data. Saved chunks: the regions around the camera are asked for their
 * chunks' save times, and the chunks saved since the client last had them come as their columns, which become far terrain as
 * the chunks the client loads do (their times kept in the cache, so a chunk comes once until the server saves it again).
 *
 * Loaded only with Fabric API's networking (mcopt.metal.LodClientInit). Payloads are handled on the render thread; decoding
 * and painting run on the far terrain's workers.
 */
public final class LodRemote {
	/** What the server offers for the dimension the client is in. */
	record Link(String dimension, boolean generate, boolean chunks, int seaLevel, int radius) {
	}

	/** -Dmcopt.lod.server=false: never ask a server for far terrain. */
	static final boolean ON = Boolean.parseBoolean(System.getProperty("mcopt.lod.server", "true"));
	/** Most tiles asked and not answered yet: the server works them in order, so the rest wait here, nearest first. */
	private static final int IN_FLIGHT = 384;
	/** An ask unanswered this long is taken as dropped, and a tile the server declined is asked again after this (ns). */
	private static final long TIMEOUT_NS = 60_000_000_000L, RETRY_NS = 20_000_000_000L;

	private static boolean registered;
	private static final ConcurrentHashMap<String, BlockState> stateCache = new ConcurrentHashMap<>();
	static final java.util.concurrent.atomic.AtomicLong tilesReceived = new java.util.concurrent.atomic.AtomicLong(),
		chunksReceived = new java.util.concurrent.atomic.AtomicLong(), bytesReceived = new java.util.concurrent.atomic.AtomicLong();

	/**
	 * One field's conversation with the server (a dimension, from open to close). Tasks on the workers hold their session, so
	 * what finishes after the player moved on lands in the old one and goes nowhere.
	 */
	private static final class Session {
		final LodField field;
		final String dimension;
		final Registry<Biome> biomes;
		final Holder<Biome> fallback;
		/** Tiles to ask for (any thread adds; `queued` dedupes), those asked and not answered (when), and those declined (until when). */
		final ConcurrentLinkedQueue<Long> wanted = new ConcurrentLinkedQueue<>();
		final Set<Long> queued = ConcurrentHashMap.newKeySet();
		final ConcurrentHashMap<Long, Long> asked = new ConcurrentHashMap<>(), retryAt = new ConcurrentHashMap<>();
		/** Saved chunks: regions asked for, and per region the save times of the chunks the cache has. */
		final Set<Long> regionsAsked = ConcurrentHashMap.newKeySet();
		final ConcurrentHashMap<Long, int[]> stamps = new ConcurrentHashMap<>();
		final Set<Long> stampsDirty = ConcurrentHashMap.newKeySet();
		long lastStampSave = System.nanoTime(), lastScan, lastHello, lastSend;
		/** The Hello went out; the server answered (the next frame scans the resident tiles then). */
		boolean helloSent, scanNow;
		volatile boolean closed;

		Session(LodField field, String dimension, Registry<Biome> biomes, Holder<Biome> fallback) {
			this.field = field;
			this.dimension = dimension;
			this.biomes = biomes;
			this.fallback = fallback;
		}
	}

	/** Render thread writes; workers read. */
	private static volatile @Nullable Session session;

	private LodRemote() {
	}

	/** At startup (mcopt.metal.LodClientInit, with Fabric API's networking): the payloads and their handlers. */
	public static void register() {
		if (registered || !ON) return;
		registered = true;
		PayloadTypeRegistry.serverboundPlay().register(LodNet.Hello.TYPE, LodNet.Hello.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(LodNet.TileReq.TYPE, LodNet.TileReq.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(LodNet.RegionReq.TYPE, LodNet.RegionReq.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(LodNet.ChunkReq.TYPE, LodNet.ChunkReq.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(LodNet.DimInfo.TYPE, LodNet.DimInfo.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(LodNet.Tile.TYPE, LodNet.Tile.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(LodNet.Manifest.TYPE, LodNet.Manifest.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(LodNet.Chunks.TYPE, LodNet.Chunks.CODEC);
		ClientPlayNetworking.registerGlobalReceiver(LodNet.DimInfo.TYPE, (p, ctx) -> dimInfo(p));
		ClientPlayNetworking.registerGlobalReceiver(LodNet.Tile.TYPE, (p, ctx) -> tile(p));
		ClientPlayNetworking.registerGlobalReceiver(LodNet.Manifest.TYPE, (p, ctx) -> manifest(p));
		ClientPlayNetworking.registerGlobalReceiver(LodNet.Chunks.TYPE, (p, ctx) -> chunks(p));
		System.out.println("mcopt-lod: far terrain from servers running mcopt-server: on");
	}

	static boolean registered() {
		return registered;
	}

	/** Render thread: a field opened on a server; it asks whether the server has far terrain for this dimension. */
	static void open(LodField f, String dim) {
		if (!registered) return;
		session = null;
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;
		Registry<Biome> reg = mc.level.registryAccess().lookupOrThrow(Registries.BIOME);
		Holder<Biome> fallback = reg.get(Biomes.PLAINS).map(h -> (Holder<Biome>) h).orElse(null);
		if (fallback == null) return;
		Session s = new Session(f, dim, reg, fallback);
		session = s;
		hello(s);
	}

	/** Asks the server about the session's dimension (again once a second until the connection can carry it). */
	private static void hello(Session s) {
		s.lastHello = System.nanoTime();
		if (!ClientPlayNetworking.canSend(LodNet.Hello.TYPE)) return;
		ClientPlayNetworking.send(new LodNet.Hello(LodNet.PROTOCOL, s.dimension));
		s.helloSent = true;
	}

	/** Render thread: the field is closing (the world or the dimension changes). */
	static void close(LodField f) {
		Session s = session;
		if (s == null || s.field != f) return;
		s.closed = true;
		saveStamps(s, true);
		if (ClientPlayNetworking.canSend(LodNet.TileReq.TYPE)) {
			// (what the server still has queued for this dimension isn't wanted any more)
			ClientPlayNetworking.send(new LodNet.TileReq(s.dimension, true, new long[0]));
		}
		session = null;
	}

	/** Any thread: the field needs this tile and has only part of it, or none: ask the server. */
	static void want(LodField f, long key) {
		Session s = session;
		if (s == null || s.field != f || f.remote == null) return;
		want(s, key);
	}

	private static void want(Session s, long key) {
		if (s.asked.containsKey(key) || !s.queued.add(key)) return;
		s.wanted.add(key);
	}

	/** Render thread, once a frame: the asks gathered since the last frame, and the regions around the camera. */
	static void frame(LodField f, double camX, double camZ) {
		Session s = session;
		if (s == null || s.field != f) return;
		long now = System.nanoTime();
		if (!s.helloSent && now - s.lastHello > 1_000_000_000L) hello(s);
		Link link = f.remote;
		if (link == null) return;
		if (link.generate()) {
			if (s.scanNow || now - s.lastScan > 1_000_000_000L) {
				s.scanNow = false;
				s.lastScan = now;
				scan(s, link, camX, camZ, now);
			}
			send(s, camX, camZ);
		}
		if (link.chunks() && f.frame % 20 == 0) askRegions(s, link, camX, camZ);
		saveStamps(s, false);
	}

	/**
	 * Once a second: asks the server lost (unanswered too long), and the resident tiles still missing data that nobody asked
	 * for (made before the server answered, declined a while ago, or first seen past the server's reach and now within it).
	 */
	private static void scan(Session s, Link link, double camX, double camZ, long now) {
		s.asked.entrySet().removeIf(e -> now - e.getValue() > TIMEOUT_NS);
		s.retryAt.entrySet().removeIf(e -> now >= e.getValue());
		LodClip clip = s.field.clip;
		for (int l = 0; l < clip.levels; l++) {
			for (long k : clip.slotKey[l]) {
				if (k == -1L || s.field.completeKeys.contains(k) || s.asked.containsKey(k) || s.queued.contains(k) || s.retryAt.containsKey(k)) continue;
				if (!serves(link, k, camX, camZ)) continue;
				want(s, k);
			}
		}
	}

	private static boolean serves(Link link, long key, double camX, double camZ) {
		int level = LodTile.levelOf(key);
		double span = LodTile.SIZE << level;
		return LodNet.serves(link.radius(), level, LodTile.txOf(key) * span + span / 2 - camX, LodTile.tzOf(key) * span + span / 2 - camZ);
	}

	/**
	 * The wanted tiles the server serves, nearest first, as many as may be in flight. Sorted only when there's room for a
	 * batch (32) or a second has passed: not every frame while the server works through what's in flight.
	 */
	private static void send(Session s, double camX, double camZ) {
		int room = Math.min(LodNet.MAX_KEYS, IN_FLIGHT - s.asked.size());
		long now = System.nanoTime();
		if (room <= 0 || s.wanted.isEmpty() || room < 32 && now - s.lastSend < 1_000_000_000L) return;
		Link link = s.field.remote;
		if (link == null) return;
		s.lastSend = now;
		long[] keys = new long[s.queued.size() + 16];
		int count = 0;
		Long k;
		while ((k = s.wanted.poll()) != null) {
			if (count == keys.length) keys = java.util.Arrays.copyOf(keys, count * 2);
			keys[count++] = k;
		}
		// (by distance in tiles of their own level, each level's nearest first, the finest ahead at the same distance: the
		// priority's float bits over the key's index, sorted as longs)
		long[] order = new long[count];
		for (int i = 0; i < count; i++) {
			long key = keys[i];
			int level = LodTile.levelOf(key);
			double span = LodTile.SIZE << level;
			float pri = (float) (Math.hypot(LodTile.txOf(key) * span + span / 2 - camX, LodTile.tzOf(key) * span + span / 2 - camZ) / span + level * 0.5);
			order[i] = (long) Float.floatToIntBits(pri) << 32 | i;
		}
		java.util.Arrays.sort(order);
		long[] out = new long[Math.min(room, count)];
		int n = 0;
		LodClip clip = s.field.clip;
		for (long o : order) {
			long key = keys[(int) o];
			if (n < out.length && serves(link, key, camX, camZ) && clip.inWindow(LodTile.levelOf(key), LodTile.txOf(key), LodTile.tzOf(key))) {
				s.queued.remove(key);
				s.asked.put(key, now);
				out[n++] = key;
			} else if (n < out.length) {
				// (past the server's reach, or out of the window, for now: the scan asks again when it's needed and within)
				s.queued.remove(key);
			} else {
				s.wanted.add(key);
			}
		}
		if (n > 0) ClientPlayNetworking.send(new LodNet.TileReq(s.dimension, false, n == out.length ? out : java.util.Arrays.copyOf(out, n)));
	}

	/** The regions within reach the server hasn't been asked about, nearest first, a few at a time. */
	private static void askRegions(Session s, Link link, double camX, double camZ) {
		int reachRegions = (int) Math.ceil(Math.min(link.radius() * 16.0, LodConfig.reachBlocks()) / 512.0);
		int crx = Math.floorDiv((int) Math.floor(camX), 512), crz = Math.floorDiv((int) Math.floor(camZ), 512);
		long[] batch = new long[64];
		int n = 0;
		// rings out from the camera's region
		for (int r = 0; r <= reachRegions && n < batch.length; r++) {
			for (int dz = -r; dz <= r && n < batch.length; dz++) {
				for (int dx = -r; dx <= r && n < batch.length; dx++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
					long key = (long) (crx + dx) << 32 | (crz + dz) & 0xFFFFFFFFL;
					if (s.regionsAsked.add(key)) batch[n++] = key;
				}
			}
		}
		if (n > 0) ClientPlayNetworking.send(new LodNet.RegionReq(s.dimension, java.util.Arrays.copyOf(batch, n)));
	}

	// ---- handlers (render thread) ----

	private static void dimInfo(LodNet.DimInfo p) {
		Session s = session;
		if (s == null || p.protocol() != LodNet.PROTOCOL || !p.dimension().equals(s.dimension)) return;
		LodField f = s.field;
		f.remote = new Link(p.dimension(), p.generate(), p.chunks(), p.seaLevel(), p.radius());
		// (the next frame scans the resident tiles: those made before the server answered are asked then)
		s.scanNow = true;
		System.out.println("mcopt-lod: the server makes far terrain for " + p.dimension() + " (generated " + p.generate() + ", saved chunks " + p.chunks()
			+ ", out to " + p.radius() + " chunks)");
	}

	private static void tile(LodNet.Tile p) {
		Session s = session;
		LodField f = s != null ? s.field : null;
		Link link = f != null ? f.remote : null;
		if (s == null || link == null || !p.dimension().equals(s.dimension)) return;
		long key = p.key();
		s.asked.remove(key);
		if (p.data().length == 0) {
			// declined (past its reach, or its queue full): asked again after a while if still needed
			s.retryAt.put(key, System.nanoTime() + RETRY_NS);
			return;
		}
		tilesReceived.incrementAndGet();
		bytesReceived.addAndGet(p.data().length);
		f.submit(() -> {
			if (s.closed) return;
			int level = LodTile.levelOf(key), tx = LodTile.txOf(key), tz = LodTile.tzOf(key);
			LodTile t = LodStructure.decodeTile(p.data(), level, tx, tz, LodRemote::state, name -> biome(s.biomes, name), s.fallback);
			if (t == null) return;
			LodPaint.paint(t, link.seaLevel());
			int[] g = new int[LodTile.CELLS], c = new int[LodTile.CELLS];
			int[] cr = level < f.clip.crownLevels ? new int[LodTile.CELLS] : null;
			int[] tw = level == 0 && LodConfig.TEXTURES ? new int[LodTile.CELLS] : null;
			int[] rn = cr != null ? new int[LodTile.CELLS] : null;
			int[] pl = level == 0 && f.clip.plants ? new int[2 * LodTile.CELLS] : null;
			LodField.pack(t, g, c, cr, rn, pl);
			if (tw != null) System.arraycopy(t.tex, 0, tw, 0, LodTile.CELLS);
			f.post(() -> {
				if (!s.closed) f.remoteTile(key, g, c, cr, tw, rn, pl);
			});
		});
	}

	private static void manifest(LodNet.Manifest p) {
		Session s = session;
		if (s == null || s.field.remote == null || !p.dimension().equals(s.dimension) || p.stamps().length != 1024) return;
		long region = (long) p.rx() << 32 | p.rz() & 0xFFFFFFFFL;
		int[] have = stampsOf(s, region);
		long[] want = new long[1024];
		int n = 0;
		for (int i = 0; i < 1024; i++) {
			if (p.stamps()[i] == 0 || p.stamps()[i] <= have[i]) continue;
			want[n++] = ChunkPos.pack(p.rx() * 32 + (i & 31), p.rz() * 32 + (i >> 5));
		}
		if (n > 0) ClientPlayNetworking.send(new LodNet.ChunkReq(s.dimension, java.util.Arrays.copyOf(want, n)));
	}

	private static void chunks(LodNet.Chunks p) {
		Session s = session;
		if (s == null || s.field.remote == null || !p.dimension().equals(s.dimension)) return;
		bytesReceived.addAndGet(p.data().length);
		LodField f = s.field;
		f.submit(() -> {
			if (s.closed) return;
			try {
				LodStructure.In in = new LodStructure.In(LodStructure.inflate(p.data()));
				int count = in.i32();
				for (int k = 0; k < count && !s.closed; k++) {
					int stamp = in.i32();
					LodChunks.Snapshot c = LodStructure.decodeChunk(in, LodRemote::state, name -> biome(s.biomes, name), s.fallback);
					f.imported(LodChunks.summarize(c));
					chunksReceived.incrementAndGet();
					long region = (long) Math.floorDiv(c.chunkX(), 32) << 32 | Math.floorDiv(c.chunkZ(), 32) & 0xFFFFFFFFL;
					int[] have = stampsOf(s, region);
					have[(c.chunkZ() & 31) * 32 + (c.chunkX() & 31)] = stamp;
					s.stampsDirty.add(region);
				}
			} catch (java.util.zip.DataFormatException | RuntimeException e) {
				System.out.println("mcopt-lod: a server's chunks unreadable: " + e);
			}
		});
	}

	// ---- lookups ----

	private static @Nullable BlockState state(String name) {
		BlockState s = stateCache.get(name);
		if (s != null) return s;
		s = LodStructure.parseState(name);
		if (s != null) stateCache.put(name, s);
		return s;
	}

	private static @Nullable Holder<Biome> biome(Registry<Biome> reg, String name) {
		if (name.isEmpty()) return null;
		try {
			return reg.get(ResourceKey.create(Registries.BIOME, Identifier.parse(name))).map(h -> (Holder<Biome>) h).orElse(null);
		} catch (RuntimeException e) {
			return null;
		}
	}

	// ---- the save times of the chunks the cache has, per region (cache/chunks/rX.Z.st) ----

	private static int[] stampsOf(Session s, long region) {
		return s.stamps.computeIfAbsent(region, r -> {
			int[] st = new int[1024];
			Path file = stampFile(s.field, r);
			if (Files.isRegularFile(file)) {
				try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
					for (int i = 0; i < 1024; i++) st[i] = in.readInt();
				} catch (IOException e) {
					java.util.Arrays.fill(st, 0);
				}
			}
			return st;
		});
	}

	private static Path stampFile(LodField f, long region) {
		return f.cache.resolve("chunks").resolve("r." + (int) (region >> 32) + "." + (int) region + ".st");
	}

	/** Every 10 s (or now): the regions whose times changed, to the cache. */
	private static void saveStamps(Session s, boolean now) {
		if (s.stampsDirty.isEmpty() || !now && System.nanoTime() - s.lastStampSave < 10_000_000_000L) return;
		s.lastStampSave = System.nanoTime();
		for (var it = s.stampsDirty.iterator(); it.hasNext();) {
			long region = it.next();
			it.remove();
			int[] st = s.stamps.get(region);
			if (st == null) continue;
			Path file = stampFile(s.field, region);
			try {
				Files.createDirectories(file.getParent());
				Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
				try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(tmp))) {
					for (int v : st) out.writeInt(v);
				}
				Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException e) {
				System.out.println("mcopt-lod: can't save " + file + ": " + e);
			}
		}
	}
}

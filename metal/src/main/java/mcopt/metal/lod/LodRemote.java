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

	private static boolean registered;
	/** Render thread: the field asking, its dimension, and the biomes its tiles name. */
	private static @Nullable LodField field;
	private static @Nullable String dimension;
	private static volatile @Nullable Registry<Biome> biomes;
	private static volatile @Nullable Holder<Biome> fallbackBiome;
	/** Tiles to ask for (any thread adds), and those asked and not answered yet. */
	private static final ConcurrentLinkedQueue<Long> wanted = new ConcurrentLinkedQueue<>();
	private static final Set<Long> asked = ConcurrentHashMap.newKeySet();
	/** Saved chunks: regions asked for (this session), and per region the save times of the chunks the cache has. */
	private static final Set<Long> regionsAsked = ConcurrentHashMap.newKeySet();
	private static final ConcurrentHashMap<Long, int[]> stamps = new ConcurrentHashMap<>();
	private static final Set<Long> stampsDirty = ConcurrentHashMap.newKeySet();
	private static final ConcurrentHashMap<String, BlockState> stateCache = new ConcurrentHashMap<>();
	private static long lastStampSave = System.nanoTime();
	static long tilesReceived, chunksReceived, bytesReceived;

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
		reset();
		if (!ClientPlayNetworking.canSend(LodNet.Hello.TYPE)) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;
		Registry<Biome> reg = mc.level.registryAccess().lookupOrThrow(Registries.BIOME);
		biomes = reg;
		fallbackBiome = reg.get(Biomes.PLAINS).map(h -> (Holder<Biome>) h).orElse(null);
		field = f;
		dimension = dim;
		ClientPlayNetworking.send(new LodNet.Hello(LodNet.PROTOCOL, dim));
	}

	/** Render thread: the field is closing (the world or the dimension changes). */
	static void close(LodField f) {
		if (field != f) return;
		saveStamps(true);
		if (dimension != null && registered && ClientPlayNetworking.canSend(LodNet.TileReq.TYPE)) {
			// (what the server still has queued for this dimension isn't wanted any more)
			ClientPlayNetworking.send(new LodNet.TileReq(dimension, true, new long[0]));
		}
		reset();
	}

	private static void reset() {
		field = null;
		dimension = null;
		wanted.clear();
		asked.clear();
		regionsAsked.clear();
		stamps.clear();
		stampsDirty.clear();
	}

	/** Any thread: the field needs this tile and has only part of it, or none: ask the server (once a session). */
	static void want(LodField f, long key) {
		if (f.remote == null || !asked.add(key)) return;
		wanted.add(key);
	}

	/** Render thread, once a frame: the asks gathered since the last frame, and the regions around the camera. */
	static void frame(LodField f, double camX, double camZ) {
		if (field != f || dimension == null) return;
		Link link = f.remote;
		if (link == null) return;
		if (!wanted.isEmpty()) {
			long[] keys = new long[Math.min(LodNet.MAX_KEYS, wanted.size())];
			int n = 0;
			Long k;
			while (n < keys.length && (k = wanted.poll()) != null) keys[n++] = k;
			if (n > 0) ClientPlayNetworking.send(new LodNet.TileReq(dimension, false, n == keys.length ? keys : java.util.Arrays.copyOf(keys, n)));
		}
		if (link.chunks() && f.frame % 20 == 0) askRegions(f, link, camX, camZ);
		saveStamps(false);
	}

	/** The regions within reach the server hasn't been asked about, nearest first, a few at a time. */
	private static void askRegions(LodField f, Link link, double camX, double camZ) {
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
					if (regionsAsked.add(key)) batch[n++] = key;
				}
			}
		}
		if (n > 0) ClientPlayNetworking.send(new LodNet.RegionReq(dimension, java.util.Arrays.copyOf(batch, n)));
	}

	// ---- handlers (render thread) ----

	private static void dimInfo(LodNet.DimInfo p) {
		LodField f = field;
		if (f == null || p.protocol() != LodNet.PROTOCOL || !p.dimension().equals(dimension)) return;
		f.remote = new Link(p.dimension(), p.generate(), p.chunks(), p.seaLevel(), p.radius());
		System.out.println("mcopt-lod: the server makes far terrain for " + p.dimension() + " (generated " + p.generate() + ", saved chunks " + p.chunks()
			+ ", out to " + p.radius() + " chunks)");
		// the tiles already resident and not complete: asked now (they were made before the server answered)
		if (p.generate()) {
			LodClip clip = f.clip;
			for (int l = 0; l < clip.levels; l++) {
				for (long k : clip.slotKey[l]) if (k != -1L && !f.completeKeys.contains(k)) want(f, k);
			}
		}
	}

	private static void tile(LodNet.Tile p) {
		LodField f = field;
		Link link = f != null ? f.remote : null;
		if (f == null || link == null || !p.dimension().equals(dimension)) return;
		long key = p.key();
		asked.remove(key);
		if (p.data().length == 0) return;
		tilesReceived++;
		bytesReceived += p.data().length;
		Registry<Biome> reg = biomes;
		Holder<Biome> fallback = fallbackBiome;
		if (reg == null || fallback == null) return;
		f.submit(() -> {
			int level = LodTile.levelOf(key), tx = LodTile.txOf(key), tz = LodTile.tzOf(key);
			LodTile t = LodStructure.decodeTile(p.data(), level, tx, tz, LodRemote::state, name -> biome(reg, name), fallback);
			if (t == null) return;
			LodPaint.paint(t, link.seaLevel());
			int[] g = new int[LodTile.CELLS], c = new int[LodTile.CELLS];
			int[] cr = level < f.clip.crownLevels ? new int[LodTile.CELLS] : null;
			int[] tw = level == 0 && LodConfig.TEXTURES ? new int[LodTile.CELLS] : null;
			int[] rn = cr != null ? new int[LodTile.CELLS] : null;
			int[] pl = level == 0 && f.clip.plants ? new int[2 * LodTile.CELLS] : null;
			LodField.pack(t, g, c, cr, rn, pl);
			if (tw != null) System.arraycopy(t.tex, 0, tw, 0, LodTile.CELLS);
			f.post(() -> f.remoteTile(key, g, c, cr, tw, rn, pl));
		});
	}

	private static void manifest(LodNet.Manifest p) {
		LodField f = field;
		if (f == null || f.remote == null || !p.dimension().equals(dimension) || p.stamps().length != 1024) return;
		long region = (long) p.rx() << 32 | p.rz() & 0xFFFFFFFFL;
		int[] have = stampsOf(f, region);
		long[] want = new long[1024];
		int n = 0;
		for (int i = 0; i < 1024; i++) {
			if (p.stamps()[i] == 0 || p.stamps()[i] <= have[i]) continue;
			want[n++] = ChunkPos.pack(p.rx() * 32 + (i & 31), p.rz() * 32 + (i >> 5));
		}
		if (n > 0) ClientPlayNetworking.send(new LodNet.ChunkReq(dimension, java.util.Arrays.copyOf(want, n)));
	}

	private static void chunks(LodNet.Chunks p) {
		LodField f = field;
		if (f == null || f.remote == null || !p.dimension().equals(dimension)) return;
		bytesReceived += p.data().length;
		Registry<Biome> reg = biomes;
		Holder<Biome> fallback = fallbackBiome;
		if (reg == null || fallback == null) return;
		f.submit(() -> {
			try {
				LodStructure.In in = new LodStructure.In(LodStructure.inflate(p.data()));
				int count = in.i32();
				for (int k = 0; k < count; k++) {
					int stamp = in.i32();
					LodChunks.Snapshot s = LodStructure.decodeChunk(in, LodRemote::state, name -> biome(reg, name), fallback);
					f.imported(LodChunks.summarize(s));
					chunksReceived++;
					long region = (long) Math.floorDiv(s.chunkX(), 32) << 32 | Math.floorDiv(s.chunkZ(), 32) & 0xFFFFFFFFL;
					int[] have = stampsOf(f, region);
					have[(s.chunkZ() & 31) * 32 + (s.chunkX() & 31)] = stamp;
					stampsDirty.add(region);
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

	private static int[] stampsOf(LodField f, long region) {
		return stamps.computeIfAbsent(region, r -> {
			int[] s = new int[1024];
			Path file = stampFile(f, r);
			if (Files.isRegularFile(file)) {
				try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
					for (int i = 0; i < 1024; i++) s[i] = in.readInt();
				} catch (IOException e) {
					java.util.Arrays.fill(s, 0);
				}
			}
			return s;
		});
	}

	private static Path stampFile(LodField f, long region) {
		return f.cache.resolve("chunks").resolve("r." + (int) (region >> 32) + "." + (int) region + ".st");
	}

	/** Every 10 s (or now): the regions whose times changed, to the cache. */
	private static void saveStamps(boolean now) {
		LodField f = field;
		if (f == null || stampsDirty.isEmpty() || !now && System.nanoTime() - lastStampSave < 10_000_000_000L) return;
		lastStampSave = System.nanoTime();
		for (var it = stampsDirty.iterator(); it.hasNext();) {
			long region = it.next();
			it.remove();
			int[] s = stamps.get(region);
			if (s == null) continue;
			Path file = stampFile(f, region);
			try {
				Files.createDirectories(file.getParent());
				Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
				try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(tmp))) {
					for (int v : s) out.writeInt(v);
				}
				Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException e) {
				System.out.println("mcopt-lod: can't save " + file + ": " + e);
			}
		}
	}
}

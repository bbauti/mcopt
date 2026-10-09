package mcopt.metal.lod;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Voxy's saved far terrain (its finest level: every block) becomes ours, read straight from its files on a low-priority
 * thread, as LodImport reads the world's region files: nearest the camera first, within the reach, each chunk through the
 * same snapshot the game's chunks go through (LodChunks.Source). Only while Voxy isn't running (its database is live then).
 * In singleplayer the world's saved chunks win (LodImport reads those): Voxy's are taken where the world has none. Read once
 * per state of Voxy's files (voxy-import.txt in our cache). -Dmcopt.lod.importVoxy=false turns it off.
 *
 * The layout was learned from Voxy's source (github.com/MCRcortex/voxy); no code of it is used here. A save is
 * <world>/voxy (singleplayer) or .voxy/saves/<server address, ':' as '_'> (realms: realms), one directory per dimension
 * named by the first 32 hex digits of SHA-256(the level's hashed seed in decimal + its ResourceKey's toString()), holding
 * config.json and storage/ (RocksDB, LodRocks). Column family world_sections: key the section's long, big-endian (level
 * 4 bits at 60, y 8 at 52, z 24 at 28, x 24 at 4, signed), value zstd (or LZ4: a little-endian length, then a raw block;
 * or as it is) of: the key (LE long), a long whose low 16 bits are the palette's size N, 32768 LE shorts of palette indices
 * (voxel (x, y, z) at y << 10 | z << 5 | x), N LE longs of voxel ids (block id bits 27-46, 0 air; biome bits 47-55; light
 * 56-63). A section is 32 voxels a side; at level 0 a voxel is a block. Column family id_mappings: key a big-endian int
 * (type << 30 | id; 1 block state, 2 biome), value gzipped NBT: {id, block_state: {Name, Properties}} or {id, biome_id}.
 */
final class LodVoxyImport implements Runnable {
	static final boolean ON = Boolean.parseBoolean(System.getProperty("mcopt.lod.importVoxy", "true"));

	private final LodField field;
	private final Path storage, done;
	private final @Nullable Path regions;
	private final int minY;
	private final Registry<Block> blocks;
	private final Registry<Biome> biomes;
	private final Holder<Biome> fallbackBiome;
	private volatile boolean stopped;
	private final Thread thread;
	final AtomicLong chunks = new AtomicLong(), sections = new AtomicLong(), skipped = new AtomicLong();

	private LodVoxyImport(LodField field, Path storage, @Nullable Path regions, int minY, Registry<Block> blocks, Registry<Biome> biomes,
		Holder<Biome> fallbackBiome) {
		this.field = field;
		this.storage = storage;
		this.regions = regions;
		this.minY = minY;
		this.blocks = blocks;
		this.biomes = biomes;
		this.fallbackBiome = fallbackBiome;
		this.done = field.cache.resolve("voxy-import.txt");
		this.thread = new Thread(this, "mcopt-lod-voxy-import");
		this.thread.setDaemon(true);
		this.thread.setPriority(Thread.MIN_PRIORITY);
		this.thread.start();
	}

	/**
	 * Render thread, as a field opens: the importer when this dimension has a Voxy save and Voxy isn't loaded, else null.
	 * worldRoot: the singleplayer world's folder (null on a server); regions: its region folder for this dimension.
	 */
	static @Nullable LodVoxyImport start(LodField field, net.minecraft.client.multiplayer.ClientLevel level, @Nullable Path worldRoot, @Nullable Path regions) {
		if (!ON || !LodConfig.DISK_CACHE || net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("voxy")) return null;
		try {
			Minecraft mc = Minecraft.getInstance();
			Path base;
			if (worldRoot != null) {
				base = worldRoot.resolve("voxy");
			} else {
				var data = mc.getConnection() != null ? mc.getConnection().getServerData() : null;
				if (data == null) return null;
				base = mc.gameDirectory.toPath().resolve(".voxy").resolve("saves").resolve(data.isRealm() ? "realms" : data.ip.replace(":", "_"));
			}
			if (!Files.isDirectory(base)) return null;
			java.lang.reflect.Field f = net.minecraft.world.level.biome.BiomeManager.class.getDeclaredField("biomeZoomSeed");
			f.setAccessible(true);
			long seed = f.getLong(level.getBiomeManager());
			byte[] digest = MessageDigest.getInstance("SHA-256").digest((seed + level.dimension().toString()).getBytes(StandardCharsets.UTF_8));
			String id = java.util.HexFormat.of().formatHex(digest).substring(0, 32);
			Path storage = base.resolve(id).resolve("storage");
			if (!Files.isDirectory(storage)) return null;
			if (!supported(base.resolve("config.json"))) {
				System.out.println("mcopt-lod: Voxy's save at " + base + " uses a storage this importer doesn't read (only RocksDB, compressed or not)");
				return null;
			}
			Registry<Biome> reg = level.registryAccess().lookupOrThrow(Registries.BIOME);
			Holder<Biome> plains = reg.get(Biomes.PLAINS).map(h -> (Holder<Biome>) h).orElse(null);
			if (plains == null) return null;
			return new LodVoxyImport(field, storage, regions, level.getMinY(), level.registryAccess().lookupOrThrow(Registries.BLOCK), reg, plains);
		} catch (ReflectiveOperationException | RuntimeException | java.security.NoSuchAlgorithmException e) {
			System.out.println("mcopt-lod: Voxy import unavailable: " + e);
			return null;
		}
	}

	/** config.json: a section serializer over (a compressor over) RocksDB; anything else (LMDB, fragments) isn't read. */
	private static boolean supported(Path config) {
		if (!Files.isRegularFile(config)) return true;  // (Voxy writes it; without it, the default: zstd over RocksDB)
		try {
			var root = com.google.gson.JsonParser.parseString(Files.readString(config)).getAsJsonObject();
			var s = root.getAsJsonObject("sectionStorageConfig");
			if (s == null || !"Serializer".equals(type(s))) return false;
			var st = s.getAsJsonObject("storage");
			if (st != null && "CompressionAdaptor".equals(type(st))) {
				String c = type(st.getAsJsonObject("compressor"));
				if (!"ZSTD".equals(c) && !"LZ4".equals(c)) return false;
				st = st.getAsJsonObject("delegate");
			}
			return st != null && "RocksDB".equals(type(st));
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	private static @Nullable String type(com.google.gson.@Nullable JsonObject o) {
		return o != null && o.has("TYPE") ? o.get("TYPE").getAsString() : null;
	}

	void stop() {
		this.stopped = true;
		this.thread.interrupt();
	}

	@Override
	public void run() {
		try {
			long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
			while (!this.stopped && !this.field.settled && System.nanoTime() < until) Thread.sleep(500);
			if (this.stopped) return;
			String signature = signature(this.storage);
			if (Files.isRegularFile(this.done) && Files.readString(this.done).strip().equals(signature)) return;
			System.out.println("mcopt-lod: importing Voxy's save " + this.storage);
			try (LodRocks db = new LodRocks(this.storage)) {
				this.importAll(db);
			}
			if (this.stopped) return;
			Files.createDirectories(this.done.getParent());
			Files.writeString(this.done, signature + "\n");
			System.out.printf("mcopt-lod: imported %d chunks from Voxy's save (%d sections; %d chunks left to the world's own)%n", this.chunks.get(),
				this.sections.get(), this.skipped.get());
		} catch (InterruptedException e) {
			// closing
		} catch (IOException | RuntimeException e) {
			System.out.println("mcopt-lod: Voxy import stopped: " + e);
		}
	}

	/** What the import depends on: Voxy's data files (tables, logs, MANIFEST), their sizes and times. */
	private static String signature(Path dir) throws IOException {
		StringBuilder sb = new StringBuilder();
		try (var s = Files.list(dir)) {
			for (Path p : s.sorted().toList()) {
				String n = p.getFileName().toString();
				// (what RocksDB rewrites on every open, data changed or not: the lock, its logs, its options, its identity)
				if (n.equals("LOCK") || n.startsWith("LOG") || n.startsWith("OPTIONS-") || n.equals("IDENTITY") || n.equals("CURRENT")) continue;
				sb.append(n).append(':').append(Files.size(p)).append(':').append(Files.getLastModifiedTime(p).toMillis()).append(';');
			}
		}
		try {
			return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
		} catch (java.security.NoSuchAlgorithmException e) {
			return Integer.toHexString(sb.toString().hashCode());
		}
	}

	// ---- the save ----

	/** A section's voxels: palette indices and the palette's states and biomes (null biome: air). */
	record Section(short[] idx, BlockState[] states, @Nullable Holder<Biome>[] biomes) {
		BlockState at(int x, int y, int z) {
			return this.states[this.idx[(y & 31) << 10 | z << 5 | x] & 0xFFFF];
		}

		@Nullable Holder<Biome> biomeAt(int x, int y, int z) {
			return this.biomes[this.idx[(y & 31) << 10 | z << 5 | x] & 0xFFFF];
		}
	}

	private void importAll(LodRocks db) throws IOException, InterruptedException {
		Map<Integer, BlockState> states = new HashMap<>();
		Map<Integer, Holder<Biome>> biomeIds = new HashMap<>();
		mappings(db, this.blocks, name -> this.biomes.get(ResourceKey.create(Registries.BIOME, name)).map(x -> (Holder<Biome>) x).orElse(null),
			this.fallbackBiome, states, biomeIds);
		// the finest level's sections, by column of sections (32 x 32 blocks)
		Map<Long, List<long[]>> columns = new HashMap<>();
		// level 0 only (the key's top four bits), within the reach (its x and z): what's kept in memory is only that
		double reach = LodConfig.reachBlocks() + 64, camX = this.field.camX, camZ = this.field.camZ;
		var all = db.family("world_sections", k -> {
			if (k.length != 8 || (k[0] & 0xF0) != 0) return false;
			long key = ByteBuffer.wrap(k).getLong();
			int sx = (int) (key << 36 >> 40), sz = (int) (key << 12 >> 40);
			return Math.hypot(sx * 32 + 16 - camX, sz * 32 + 16 - camZ) <= reach;
		});
		for (LodRocks.Key k : all.keySet()) {
			byte[] b = k.bytes();
			if (b.length != 8) continue;
			long key = ByteBuffer.wrap(b).getLong();
			if (key >>> 60 != 0) continue;
			int sx = (int) (key << 36 >> 40), sy = (int) (key << 4 >> 56), sz = (int) (key << 12 >> 40);
			columns.computeIfAbsent((long) sx << 32 | sz & 0xFFFFFFFFL, c -> new ArrayList<>()).add(new long[] {key, sy});
		}
		List<Long> order = new ArrayList<>(columns.keySet());
		double cx = this.field.camX, cz = this.field.camZ;
		order.sort(java.util.Comparator.comparingDouble(c -> Math.hypot((int) (c >> 32) * 32 + 16 - cx, (int) (long) c * 32 + 16 - cz)));
		Map<Long, int[]> headers = new HashMap<>();
		for (long c : order) {
			if (this.stopped) return;
			int sx = (int) (c >> 32), sz = (int) c;
			if (Math.hypot(sx * 32 + 16 - this.field.camX, sz * 32 + 16 - this.field.camZ) > reach) continue;
			Map<Integer, Section> secs = new HashMap<>();
			for (long[] ks : columns.get(c)) {
				byte[] key = ByteBuffer.allocate(8).putLong(ks[0]).array();
				var val = all.get(new LodRocks.Key(key));
				Section s = val == null ? null : this.section(ks[0], val.bytes(), states, biomeIds);
				if (s != null) secs.put((int) ks[1], s);
			}
			this.sections.addAndGet(secs.size());
			if (secs.isEmpty()) continue;
			for (int q = 0; q < 4; q++) {
				int ox = (q & 1) * 16, oz = (q >> 1) * 16;
				int chunkX = sx * 2 + (q & 1), chunkZ = sz * 2 + (q >> 1);
				if (this.regions != null && this.saved(headers, chunkX, chunkZ)) {
					this.skipped.incrementAndGet();
					continue;
				}
				ChunkSource src = new ChunkSource(secs, ox, oz, this.minY, this.fallbackBiome);
				if (!src.hasData()) continue;
				this.pace();
				this.field.imported(LodChunks.summarize(LodChunks.snapshot(src, chunkX, chunkZ, this.field.roof, false)));
				this.chunks.incrementAndGet();
			}
		}
	}

	private @Nullable Section section(long key, byte[] raw, Map<Integer, BlockState> states, Map<Integer, Holder<Biome>> biomeIds) {
		return section(key, raw, states, biomeIds, this.fallbackBiome);
	}

	/** The palettes' block states and biomes by id (id_mappings). */
	static void mappings(LodRocks db, Registry<Block> blocks, java.util.function.Function<Identifier, @Nullable Holder<Biome>> biomeOf, Holder<Biome> fallback,
		Map<Integer, BlockState> states, Map<Integer, Holder<Biome>> biomeIds) throws IOException {
		for (var e : db.family("id_mappings").entrySet()) {
			byte[] k = e.getKey().bytes();
			if (k.length != 4) continue;
			int key = ByteBuffer.wrap(k).getInt(), type = key >>> 30, id = key & 0x3FFFFFFF;
			CompoundTag tag;
			try (InputStream in = new ByteArrayInputStream(e.getValue().bytes())) {
				tag = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
			} catch (IOException | RuntimeException ex) {
				continue;
			}
			if (type == 1) {
				// as Voxy writes it: the game's BlockState codec (in the form of the version that wrote it, brought up to date as
				// Voxy does when this one can't read it); a block this game doesn't have (a mod's) reads as stone: it keeps the shape
				net.minecraft.nbt.Tag bs = tag.get("block_state");
				BlockState s = bs == null ? null : parseState(bs);
				states.put(id, s != null && !s.isAir() ? s : Blocks.STONE.defaultBlockState());
			} else if (type == 2) {
				Identifier biome = Identifier.tryParse(tag.getStringOr("biome_id", ""));
				Holder<Biome> h = biome == null ? null : biomeOf.apply(biome);
				biomeIds.put(id, h != null ? h : fallback);
			}
		}
	}

	private static @Nullable BlockState parseState(net.minecraft.nbt.Tag tag) {
		var r = BlockState.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, tag).result();
		if (r.isPresent()) return r.get();
		try {
			var fixed = net.minecraft.util.datafix.DataFixers.getDataFixer().update(net.minecraft.util.datafix.fixes.References.BLOCK_STATE,
				new com.mojang.serialization.Dynamic<>(net.minecraft.nbt.NbtOps.INSTANCE, tag), 0,
				net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version()).getValue();
			return BlockState.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, fixed).result().orElse(null);
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** A section's value decoded (null: unreadable, or another section's). */
	static @Nullable Section section(long key, byte[] raw, Map<Integer, BlockState> states, Map<Integer, Holder<Biome>> biomeIds, Holder<Biome> fallbackBiome) {
		byte[] v;
		try {
			v = decompress(raw);
		} catch (IOException e) {
			return null;
		}
		ByteBuffer b = ByteBuffer.wrap(v).order(ByteOrder.LITTLE_ENDIAN);
		if (v.length < 16 + 65536 + 8 || b.getLong(0) != key) return null;
		int n = (int) (b.getLong(8) & 0xFFFF);
		if (n < 1 || v.length < 16 + 65536 + 8L * n) return null;
		short[] idx = new short[32768];
		b.position(16);
		b.asShortBuffer().get(idx);
		BlockState[] st = new BlockState[n];
		@SuppressWarnings("unchecked")
		Holder<Biome>[] bi = new Holder[n];
		BlockState air = Blocks.AIR.defaultBlockState();
		for (int i = 0; i < n; i++) {
			long id = b.getLong(16 + 65536 + 8 * i);
			int block = (int) (id >>> 27 & 0xFFFFF), biome = (int) (id >>> 47 & 0x1FF);
			BlockState s = block == 0 ? air : states.getOrDefault(block, Blocks.STONE.defaultBlockState());
			st[i] = s;
			bi[i] = block == 0 ? null : biomeIds.getOrDefault(biome, fallbackBiome);
		}
		for (short s : idx) if ((s & 0xFFFF) >= n) return null;
		return new Section(idx, st, bi);
	}

	/** zstd (its magic), LZ4 (a little-endian length, then a raw block) or as stored. */
	private static byte[] decompress(byte[] raw) throws IOException {
		if (raw.length >= 4 && (raw[0] & 0xFF) == 0x28 && (raw[1] & 0xFF) == 0xB5 && (raw[2] & 0xFF) == 0x2F && (raw[3] & 0xFF) == 0xFD) {
			return LodZstd.get().decompress(raw, 1 << 24);
		}
		if (raw.length > 4) {
			int len = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).getInt(0);
			if (len >= 65552 && len <= 65552 + 8 * 32768) {
				try {
					byte[] out = new byte[len];
					net.jpountz.lz4.LZ4Factory.fastestJavaInstance().safeDecompressor().decompress(raw, 4, raw.length - 4, out, 0, len);
					return out;
				} catch (RuntimeException e) {
					// not LZ4: as stored
				}
			}
		}
		return raw;
	}

	/** Whether the world's own region files hold this chunk (singleplayer: LodImport takes those). */
	private boolean saved(Map<Long, int[]> headers, int chunkX, int chunkZ) {
		int rx = Math.floorDiv(chunkX, 32), rz = Math.floorDiv(chunkZ, 32);
		int[] h = headers.computeIfAbsent((long) rx << 32 | rz & 0xFFFFFFFFL, r -> {
			int[] offsets = new int[1024];
			Path f = this.regions.resolve("r." + rx + "." + rz + ".mca");
			if (!Files.isRegularFile(f)) return offsets;
			try (InputStream in = Files.newInputStream(f)) {
				byte[] b = in.readNBytes(4096);
				if (b.length == 4096) ByteBuffer.wrap(b).asIntBuffer().get(offsets);
			} catch (IOException e) {
				// (none read: Voxy's chunk is taken)
			}
			return offsets;
		});
		return h[(chunkZ & 31) * 32 + (chunkX & 31)] != 0;
	}

	/** Waits while the field is busy, as LodImport does. */
	private void pace() throws InterruptedException {
		while (!this.stopped && (this.field.resultsWaiting() > 256 || this.field.queued() > 64 || LodYield.importsWait())) Thread.sleep(20);
	}

	/** One chunk (16 x 16 columns at (ox, oz) of a column of sections) as LodChunks.snapshot reads blocks. */
	static final class ChunkSource implements LodChunks.Source {
		private final Map<Integer, Section> secs;
		private final int ox, oz, minY;
		private final int[] surface = new int[256];
		private final Holder<Biome>[] topBiome;
		private final Holder<Biome> fallback;
		private boolean any;

		@SuppressWarnings("unchecked")
		ChunkSource(Map<Integer, Section> secs, int ox, int oz, int minY, Holder<Biome> fallback) {
			this.secs = secs;
			this.ox = ox;
			this.oz = oz;
			this.minY = minY;
			this.fallback = fallback;
			this.topBiome = new Holder[256];
			int[] ys = secs.keySet().stream().mapToInt(Integer::intValue).sorted().toArray();
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					int i = z * 16 + x, top = minY;
					Holder<Biome> b = null;
					search:
					for (int k = ys.length - 1; k >= 0; k--) {
						Section s = secs.get(ys[k]);
						for (int y = 31; y >= 0; y--) {
							if (!s.at(ox + x, y, oz + z).isAir()) {
								top = ys[k] * 32 + y + 1;
								b = s.biomeAt(ox + x, y, oz + z);
								break search;
							}
						}
					}
					this.surface[i] = Math.max(top, minY);
					this.topBiome[i] = b;
					if (top > minY) this.any = true;
				}
			}
		}

		boolean hasData() {
			return this.any;
		}

		@Override
		public int minY() {
			return this.minY;
		}

		@Override
		public int surface(int x, int z) {
			return this.surface[z * 16 + x];
		}

		@Override
		public BlockState state(int x, int y, int z) {
			Section s = this.secs.get(Math.floorDiv(y, 32));
			return s == null ? Blocks.AIR.defaultBlockState() : s.at(this.ox + x, y, this.oz + z);
		}

		@Override
		public boolean emptySection(int y) {
			return !this.secs.containsKey(Math.floorDiv(y, 32));
		}

		@Override
		public Holder<Biome> biome(int x, int y, int z) {
			Section s = this.secs.get(Math.floorDiv(y, 32));
			Holder<Biome> b = s == null ? null : s.biomeAt(this.ox + x, y, this.oz + z);
			if (b == null) b = this.topBiome[z * 16 + x];
			return b != null ? b : this.fallback;
		}
	}
}

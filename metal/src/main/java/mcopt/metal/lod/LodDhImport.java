package mcopt.metal.lod;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
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
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.jspecify.annotations.Nullable;

/**
 * Distant Horizons' saved far terrain (its finest detail: every block column) becomes ours, read straight from its
 * database on a low-priority thread as LodVoxyImport reads Voxy's: within the reach, each chunk through the same snapshot
 * the game's chunks go through (LodChunks.Source). Only while Distant Horizons isn't running (its database is live then).
 * In singleplayer the world's saved chunks win (LodImport reads those). Read once per state of the database
 * (dh-import.txt in our cache). -Dmcopt.lod.importDh=false turns it off.
 *
 * The format, from Distant Horizons' source (2.x): DistantHorizons.sqlite in the dimension's data folder (singleplayer),
 * or Distant_Horizons_server_data/<server>/<...dimension, ':' as "@@">/ (a server). Table FullData, a row per section:
 * DetailLevel (0: a column a block), PosX, PosZ (a 64 x 64-column section at (PosX * 64, PosZ * 64)), Data and the four
 * adjacent-border blobs (NorthAdjData...), ColumnGenerationStep (a byte a column, 0: none), Mapping, DataFormatVersion (1,
 * 2), CompressionMode (0 none, 1 LZ4 frames, 2 zstd stream, 3 xz: not read, 4 a zstd frame). A column is a list of runs
 * from the top down, each a mapping index, a height and its bottom (relative to the world's lowest y). Mapping: an int
 * count, then per entry an unsigned short length and its bytes: "<biome>_DH-BSW_<block>", the block "AIR" or
 * "ns:path_STATE_{name:value}..." (properties sorted by name).
 */
final class LodDhImport implements Runnable {
	static final boolean ON = Boolean.parseBoolean(System.getProperty("mcopt.lod.importDh", "true"));
	static final int WIDTH = 64;

	private final LodField field;
	private final Path db, done;
	private final @Nullable Path regions;
	private final int minY;
	private final Registry<Block> blocks;
	private final Registry<Biome> biomes;
	private final Holder<Biome> fallbackBiome;
	private volatile boolean stopped;
	private final Thread thread;
	final AtomicLong chunks = new AtomicLong(), sections = new AtomicLong(), skipped = new AtomicLong();

	private LodDhImport(LodField field, Path db, @Nullable Path regions, int minY, Registry<Block> blocks, Registry<Biome> biomes, Holder<Biome> fallbackBiome) {
		this.field = field;
		this.db = db;
		this.regions = regions;
		this.minY = minY;
		this.blocks = blocks;
		this.biomes = biomes;
		this.fallbackBiome = fallbackBiome;
		this.done = field.cache.resolve("dh-import.txt");
		this.thread = new Thread(this, "mcopt-lod-dh-import");
		this.thread.setDaemon(true);
		this.thread.setPriority(Thread.MIN_PRIORITY);
		this.thread.start();
	}

	/** Render thread, as a field opens: the importer when this dimension has a Distant Horizons database, else null. */
	static @Nullable LodDhImport start(LodField field, net.minecraft.client.multiplayer.ClientLevel level, @Nullable Path worldRoot, @Nullable Path regions) {
		var loader = net.fabricmc.loader.api.FabricLoader.getInstance();
		if (!ON || !LodConfig.DISK_CACHE || loader.isModLoaded("distanthorizons")) return null;
		try {
			Path db = worldRoot != null
				? net.minecraft.world.level.dimension.DimensionType.getStorageFolder(level.dimension(), worldRoot).resolve("data").resolve("DistantHorizons.sqlite")
				: serverDatabase(level);
			if (db == null || !Files.isRegularFile(db)) return null;
			Registry<Biome> reg = level.registryAccess().lookupOrThrow(Registries.BIOME);
			Holder<Biome> plains = reg.get(Biomes.PLAINS).map(h -> (Holder<Biome>) h).orElse(null);
			if (plains == null) return null;
			return new LodDhImport(field, db, regions, level.getMinY(), level.registryAccess().lookupOrThrow(Registries.BLOCK), reg, plains);
		} catch (IOException | RuntimeException e) {
			System.out.println("mcopt-lod: Distant Horizons import unavailable: " + e);
			return null;
		}
	}

	/**
	 * A server's: Distant Horizons names its folder after the server's name (by default; or its address, with or without
	 * port and version), and the dimension's after its id with ':' as "@@" (newer versions put the hashed seed before it).
	 * The newest database of a folder that names this server and ends with this dimension.
	 */
	private static @Nullable Path serverDatabase(net.minecraft.client.multiplayer.ClientLevel level) throws IOException {
		Minecraft mc = Minecraft.getInstance();
		var data = mc.getConnection() != null ? mc.getConnection().getServerData() : null;
		Path root = mc.gameDirectory.toPath().resolve("Distant_Horizons_server_data");
		if (data == null || !Files.isDirectory(root)) return null;
		String name = clean(data.name), ip = clean(data.ip.contains(":") ? data.ip.substring(0, data.ip.lastIndexOf(':')) : data.ip);
		String dim = level.dimension().identifier().toString().replace(":", "@@");
		Path best = null;
		long bestTime = Long.MIN_VALUE;
		try (var servers = Files.list(root)) {
			for (Path s : servers.toList()) {
				String folder = java.net.URLDecoder.decode(s.getFileName().toString().replace("+", "%2B"), StandardCharsets.UTF_8);
				boolean ours = !name.isEmpty() && (folder.equals(name) || folder.startsWith(name + ", IP ")) || !ip.isEmpty() && (folder.equals(ip)
					|| folder.contains(", IP " + ip));
				if (!ours || !Files.isDirectory(s)) continue;
				try (var dims = Files.list(s)) {
					for (Path d : dims.toList()) {
						String dn = d.getFileName().toString();
						if (!dn.equals(dim) && !dn.endsWith("@" + dim)) continue;
						Path f = d.resolve("DistantHorizons.sqlite");
						if (!Files.isRegularFile(f)) continue;
						long t = Files.getLastModifiedTime(f).toMillis();
						if (t > bestTime) {
							bestTime = t;
							best = f;
						}
					}
				}
			}
		}
		return best;
	}

	/** Distant Horizons' file name cleaning (characters a file name can't have dropped). */
	private static String clean(String s) {
		return s == null ? "" : s.replaceAll("[\\\\/:*?\"<>|]", "");
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
			String signature = signature(this.db);
			if (Files.isRegularFile(this.done) && Files.readString(this.done).strip().equals(signature)) return;
			try (LodSqlite sql = new LodSqlite(this.db)) {
				this.importAll(sql);
			}
			if (this.stopped) return;
			Files.createDirectories(this.done.getParent());
			Files.writeString(this.done, signature + "\n");
			System.out.printf("mcopt-lod: imported %d chunks from Distant Horizons' save (%d sections; %d chunks left to the world's own)%n", this.chunks.get(),
				this.sections.get(), this.skipped.get());
		} catch (InterruptedException e) {
			// closing
		} catch (IOException | RuntimeException e) {
			System.out.println("mcopt-lod: Distant Horizons import stopped: " + e);
		}
	}

	private static String signature(Path db) throws IOException {
		StringBuilder sb = new StringBuilder(db.toString());
		for (Path p : new Path[] {db, db.resolveSibling(db.getFileName() + "-wal")}) {
			if (Files.isRegularFile(p)) sb.append(';').append(Files.size(p)).append(':').append(Files.getLastModifiedTime(p).toMillis());
		}
		try {
			return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
		} catch (java.security.NoSuchAlgorithmException e) {
			return Integer.toHexString(sb.toString().hashCode());
		}
	}

	// ---- the database ----

	/** A section's columns: per column (x * 64 + z) its runs from the top down: bottom (relative), height, state, biome. */
	record Section(int[][] bottom, int[][] height, BlockState[][] state, Holder<Biome>[][] biome, boolean[] generated) {
	}

	private void importAll(LodSqlite sql) throws IOException, InterruptedException {
		LodSqlite.Table t = sql.table("FullData");
		if (t == null) throw new IOException("no FullData table (a database older than Distant Horizons 2.1)");
		int cLevel = t.column("DetailLevel"), cX = t.column("PosX"), cZ = t.column("PosZ"), cData = t.column("Data"), cGen = t.column("ColumnGenerationStep"),
			cMap = t.column("Mapping"), cFormat = t.column("DataFormatVersion"), cMode = t.column("CompressionMode");
		int[] cAdj = {t.column("NorthAdjData"), t.column("SouthAdjData"), t.column("EastAdjData"), t.column("WestAdjData")};
		if (cLevel < 0 || cX < 0 || cZ < 0 || cData < 0 || cMap < 0 || cMode < 0) throw new IOException("FullData lacks columns: " + t.columns());
		double reach = LodConfig.reachBlocks() + 64;
		Map<Long, int[]> headers = new HashMap<>();
		Map<String, Pair> pairs = new HashMap<>();
		int[] unreadable = {0};
		sql.rows(t.root(), row -> {
			if (this.stopped) return false;
			if (!(row[cLevel] instanceof Long lv) || lv != 0 || !(row[cX] instanceof Long px) || !(row[cZ] instanceof Long pz)) return true;
			int x0 = (int) (long) px * WIDTH, z0 = (int) (long) pz * WIDTH;
			if (Math.hypot(x0 + WIDTH / 2.0 - this.field.camX, z0 + WIDTH / 2.0 - this.field.camZ) > reach) return true;
			Section s;
			try {
				byte[][] adj = new byte[4][];
				for (int k = 0; k < 4; k++) adj[k] = cAdj[k] >= 0 && row[cAdj[k]] instanceof byte[] b ? b : null;
				s = this.section(row[cData] instanceof byte[] d ? d : null, adj, cGen >= 0 && row[cGen] instanceof byte[] g ? g : null,
					row[cMap] instanceof byte[] m ? m : null, cFormat >= 0 && row[cFormat] instanceof Long f ? (int) (long) f : 2,
					row[cMode] instanceof Long md ? (int) (long) md : 0, pairs);
			} catch (IOException | RuntimeException e) {
				if (unreadable[0]++ < 3) System.out.println("mcopt-lod: Distant Horizons section " + px + "," + pz + " unreadable: " + e);
				return true;
			}
			if (s == null) return true;
			this.sections.incrementAndGet();
			for (int q = 0; q < 16; q++) {
				int ox = (q & 3) * 16, oz = (q >> 2) * 16;
				int chunkX = (x0 + ox) >> 4, chunkZ = (z0 + oz) >> 4;
				if (this.regions != null && this.saved(headers, chunkX, chunkZ)) {
					this.skipped.incrementAndGet();
					continue;
				}
				ChunkSource src = new ChunkSource(s, ox, oz, this.minY, this.fallbackBiome);
				if (!src.complete()) continue;
				try {
					this.pace();
				} catch (InterruptedException e) {
					this.stopped = true;
					return false;
				}
				this.field.imported(LodChunks.summarize(LodChunks.snapshot(src, chunkX, chunkZ, this.field.roof, false)));
				this.chunks.incrementAndGet();
			}
			return true;
		});
	}

	/** A mapping entry: its block state and biome. */
	record Pair(BlockState state, Holder<Biome> biome) {
	}

	@Nullable Section section(byte @Nullable [] data, byte[][] adj, byte @Nullable [] gen, byte @Nullable [] mapping, int format, int mode,
		Map<String, Pair> pairs) throws IOException {
		return section(data, adj, gen, mapping, format, mode, pairs, this::pair, this.fallbackBiome);
	}

	/** One section's row decoded (null: nothing in it); pairOf: a mapping entry's block state and biome. */
	static @Nullable Section section(byte @Nullable [] data, byte[][] adj, byte @Nullable [] gen, byte @Nullable [] mapping, int format, int mode,
		Map<String, Pair> pairs, java.util.function.Function<String, Pair> pairOf, Holder<Biome> fallbackBiome) throws IOException {
		if (data == null || mapping == null) return null;
		if (mode == 3) throw new IOException("xz-compressed (LZMA2) data isn't read");
		// the mapping: per id, its block state and biome
		List<Pair> ids = new ArrayList<>();
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(decompress(mapping, mode)))) {
			int n = in.readInt();
			if (n < 0 || n > 1 << 20) throw new IOException("bad mapping size " + n);
			for (int i = 0; i < n; i++) {
				byte[] b = new byte[in.readUnsignedShort()];
				in.readFully(b);
				String entry = new String(b, StandardCharsets.ISO_8859_1);
				ids.add(pairs.computeIfAbsent(entry, pairOf));
			}
		}
		int[][] bottom = new int[WIDTH * WIDTH][], height = new int[WIDTH * WIDTH][];
		long[][] points = new long[WIDTH * WIDTH][];
		if (format == 1) {
			// format 1: per column a short count, then its data points as longs
			try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(decompress(data, mode)))) {
				for (int i = 0; i < WIDTH * WIDTH; i++) {
					int n = in.readShort();
					if (n < 0) throw new IOException("bad column size");
					long[] p = new long[n];
					for (int k = 0; k < n; k++) p[k] = in.readLong();
					points[i] = p;
				}
			}
		} else {
			// format 2: the inner columns, then the four borders (each column once; the corners twice, the same)
			readV2(decompress(data, mode), 1, WIDTH - 1, 1, WIDTH - 1, points);
			int[][] ranges = {{0, WIDTH, 0, 1}, {0, WIDTH, WIDTH - 1, WIDTH}, {WIDTH - 1, WIDTH, 0, WIDTH}, {0, 1, 0, WIDTH}};
			for (int k = 0; k < 4; k++) if (adj[k] != null) readV2(decompress(adj[k], mode), ranges[k][0], ranges[k][1], ranges[k][2], ranges[k][3], points);
		}
		@SuppressWarnings("unchecked")
		Holder<Biome>[][] biome = new Holder[WIDTH * WIDTH][];
		BlockState[][] state = new BlockState[WIDTH * WIDTH][];
		boolean[] generated = new boolean[WIDTH * WIDTH];
		byte[] steps = gen != null ? decompress(gen, mode) : null;
		BlockState air = Blocks.AIR.defaultBlockState();
		for (int i = 0; i < WIDTH * WIDTH; i++) {
			long[] p = points[i];
			generated[i] = p != null && p.length > 0 && (steps == null || i >= steps.length || steps[i] != 0);
			int n = p == null ? 0 : p.length;
			bottom[i] = new int[n];
			height[i] = new int[n];
			state[i] = new BlockState[n];
			biome[i] = new Holder[n];
			for (int k = 0; k < n; k++) {
				long d = p[k];
				int id = (int) (d & 0x7FFFFFFFL);
				height[i][k] = (int) (d >>> 32 & 0xFFF);
				bottom[i][k] = (int) (d >>> 44 & 0xFFF);
				Pair pr = id < ids.size() ? ids.get(id) : null;
				state[i][k] = pr != null ? pr.state : air;
				biome[i][k] = pr != null ? pr.biome : fallbackBiome;
			}
		}
		return new Section(bottom, height, state, biome, generated);
	}

	/**
	 * Format 2's columns x in [x0, x1), z in [z0, z1) (x outer): the run counts; ids with two flags (bit 0: the bottom isn't
	 * right under the run above, bit 1: light follows); heights; the bottoms (each the one before less its height, plus a
	 * zigzag correction where flagged; carried across columns); the lights.
	 */
	static void readV2(byte[] b, int x0, int x1, int z0, int z1, long[][] out) throws IOException {
		int[] at = {0};
		for (int x = x0; x < x1; x++) for (int z = z0; z < z1; z++) out[x * WIDTH + z] = new long[varint(b, at)];
		int[][] flags = new int[WIDTH * WIDTH][];
		for (int x = x0; x < x1; x++) {
			for (int z = z0; z < z1; z++) {
				long[] col = out[x * WIDTH + z];
				int[] f = new int[col.length];
				for (int i = 0; i < col.length; i++) {
					int e = varint(b, at);
					col[i] = e >>> 2;
					f[i] = e & 3;
				}
				flags[x * WIDTH + z] = f;
			}
		}
		for (int x = x0; x < x1; x++) {
			for (int z = z0; z < z1; z++) {
				long[] col = out[x * WIDTH + z];
				for (int i = 0; i < col.length; i++) col[i] |= (long) (varint(b, at) & 0xFFF) << 32;
			}
		}
		int prev = 0;
		for (int x = x0; x < x1; x++) {
			for (int z = z0; z < z1; z++) {
				long[] col = out[x * WIDTH + z];
				int[] f = flags[x * WIDTH + z];
				for (int i = 0; i < col.length; i++) {
					int err = (f[i] & 1) != 0 ? zigzag(varint(b, at)) : 0;
					int h = (int) (col[i] >>> 32 & 0xFFF);
					int bottomY = prev - h + err;
					col[i] |= (long) (bottomY & 0xFFF) << 44;
					prev = bottomY;
				}
			}
		}
		// (the lights: not needed, but read past)
		for (int x = x0; x < x1; x++) {
			for (int z = z0; z < z1; z++) {
				int[] f = flags[x * WIDTH + z];
				for (int v : f) if ((v & 2) != 0) at[0]++;
			}
		}
		if (at[0] > b.length) throw new IOException("data cut short");
	}

	private static int varint(byte[] b, int[] at) throws IOException {
		int v = 0;
		for (int shift = 0; shift < 32; shift += 7) {
			if (at[0] >= b.length) throw new IOException("data cut short");
			int c = b[at[0]++] & 0xFF;
			v |= (c & 0x7F) << shift;
			if ((c & 0x80) == 0) return v;
		}
		throw new IOException("bad varint");
	}

	private static int zigzag(int n) {
		return (n >>> 1) ^ -(n & 1);
	}

	/** A blob as Distant Horizons compressed it. */
	static byte[] decompress(byte[] b, int mode) throws IOException {
		switch (mode) {
			case 0:
				return b;
			case 1:
				try (InputStream in = new net.jpountz.lz4.LZ4FrameInputStream(new ByteArrayInputStream(b))) {
					return in.readAllBytes();
				}
			case 2:
			case 4:
				return LodZstd.get().decompress(b, 1 << 26);
			default:
				throw new IOException("compression " + mode + " isn't read");
		}
	}

	private Pair pair(String entry) {
		return pair(entry, this.blocks, id -> this.biomes.get(ResourceKey.create(Registries.BIOME, id)).map(x -> (Holder<Biome>) x).orElse(null), this.fallbackBiome);
	}

	/** "<biome>_DH-BSW_<block>" as a block state and biome of this game's (unknown: stone and the fallback biome). */
	static Pair pair(String entry, Registry<Block> blocks, java.util.function.Function<Identifier, @Nullable Holder<Biome>> biomeOf, Holder<Biome> fallback) {
		int sep = entry.indexOf("_DH-BSW_");
		String biomeName = sep >= 0 ? entry.substring(0, sep) : "", blockName = sep >= 0 ? entry.substring(sep + 8) : entry;
		Identifier bid = Identifier.tryParse(biomeName);
		Holder<Biome> biome = bid == null ? null : biomeOf.apply(bid);
		return new Pair(state(blocks, blockName), biome != null ? biome : fallback);
	}

	/** "AIR", or "ns:path_STATE_{name:value}..." (properties sorted by name, values as the game prints them). */
	static BlockState state(Registry<Block> blocks, String s) {
		if (s.equals("AIR")) return Blocks.AIR.defaultBlockState();
		int st = s.indexOf("_STATE_");
		String name = st >= 0 ? s.substring(0, st) : s, props = st >= 0 ? s.substring(st + 7) : "";
		Identifier id = Identifier.tryParse(name);
		Block block = id == null ? null : blocks.getOptional(id).orElse(null);
		if (block == null) return Blocks.STONE.defaultBlockState();
		if (props.isEmpty()) return block.defaultBlockState();
		// as Distant Horizons matches them: each of the block's states printed the same way, compared ignoring case
		for (BlockState c : block.getStateDefinition().getPossibleStates()) {
			if (properties(c).equalsIgnoreCase(props)) return c;
		}
		return block.defaultBlockState();
	}

	private static String properties(BlockState s) {
		List<Property<?>> ps = new ArrayList<>(s.getProperties());
		ps.sort((a, b) -> a.getName().compareTo(b.getName()));
		StringBuilder sb = new StringBuilder();
		for (Property<?> p : ps) sb.append('{').append(p.getName()).append(':').append(s.getValue(p)).append('}');
		return sb.toString();
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
				if (b.length == 4096) java.nio.ByteBuffer.wrap(b).asIntBuffer().get(offsets);
			} catch (IOException e) {
				// (none read: the import's chunk is taken)
			}
			return offsets;
		});
		return h[(chunkZ & 31) * 32 + (chunkX & 31)] != 0;
	}

	private void pace() throws InterruptedException {
		while (!this.stopped && (this.field.resultsWaiting() > 256 || this.field.queued() > 64 || LodYield.ON && LodYield.pressure)) Thread.sleep(20);
	}

	/** One chunk (16 x 16 columns at (ox, oz) of a section) as LodChunks.snapshot reads blocks. */
	static final class ChunkSource implements LodChunks.Source {
		private final Section s;
		private final int ox, oz, minY;
		private final Holder<Biome> fallback;

		ChunkSource(Section s, int ox, int oz, int minY, Holder<Biome> fallback) {
			this.s = s;
			this.ox = ox;
			this.oz = oz;
			this.minY = minY;
			this.fallback = fallback;
		}

		private int col(int x, int z) {
			return (this.ox + x) * WIDTH + this.oz + z;
		}

		/** Every column has its data (a chunk partly generated isn't taken: its holes would show). */
		boolean complete() {
			for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) if (!this.s.generated[this.col(x, z)]) return false;
			return true;
		}

		@Override
		public int minY() {
			return this.minY;
		}

		@Override
		public int surface(int x, int z) {
			int c = this.col(x, z);
			BlockState[] st = this.s.state[c];
			for (int k = 0; k < st.length; k++) {
				if (!st[k].isAir()) return this.minY + this.s.bottom[c][k] + this.s.height[c][k];
			}
			return this.minY;
		}

		@Override
		public BlockState state(int x, int y, int z) {
			int c = this.col(x, z), rel = y - this.minY;
			int[] bottom = this.s.bottom[c], height = this.s.height[c];
			for (int k = 0; k < bottom.length; k++) {
				if (rel >= bottom[k] && rel < bottom[k] + height[k]) return this.s.state[c][k];
			}
			return Blocks.AIR.defaultBlockState();
		}

		@Override
		public boolean emptySection(int y) {
			return false;
		}

		@Override
		public Holder<Biome> biome(int x, int y, int z) {
			int c = this.col(x, z), rel = y - this.minY;
			int[] bottom = this.s.bottom[c], height = this.s.height[c];
			Holder<Biome> top = null;
			for (int k = 0; k < bottom.length; k++) {
				if (!this.s.state[c][k].isAir() && top == null) top = this.s.biome[c][k];
				if (rel >= bottom[k] && rel < bottom[k] + height[k] && !this.s.state[c][k].isAir()) return this.s.biome[c][k];
			}
			return top != null ? top : this.fallback;
		}
	}
}

package mcopt.metal.lod;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.FrustumIntersection;
import org.jspecify.annotations.Nullable;

/**
 * The far terrain of one dimension of one world: keeps the clipmap's windows filled. Each level needs the tiles of its
 * ring (between the finer level's switch distance and its own); the coarsest level needs its whole window, so whatever a
 * finer level still lacks falls back to it. Missing tiles go to the workers, most urgent first (the larger on screen, the
 * sooner; tripled when out of view); a worker loads the tile from the disk cache or generates it from the world
 * generator's noise, packs it into the clipmap's words, and the render thread writes it into the window.
 *
 * The game's own chunks overwrite the generated estimate: level 0 cell for cell, coarser levels at their sample points.
 * Tiles that took real chunks are saved back to the cache from the clipmap every few seconds.
 */
final class LodField {
	/** The cache files' format: 11, words planar (CacheOut); 10 (FORMAT_PLAIN), the words in order, is still read. */
	static final int FORMAT = 11, FORMAT_PLAIN = 10;
	/** The world generator's noise; null: no generation (a server, a flat or modded generator, a roof): only real chunks and the cache. */
	final @Nullable LodNoise noise;
	/** The top y real chunks are read from below (a dimension with a roof: under it), Integer.MAX_VALUE: none. */
	final int roof;
	final LodClip clip;
	/** The game's own trees on level-0 tiles (null: -Dmcopt.lod.trees=false). */
	final @org.jspecify.annotations.Nullable LodForest forest;
	final Path cache;
	private final ConcurrentHashMap<Long, Boolean> pending = new ConcurrentHashMap<>();
	private final PriorityBlockingQueue<Job> jobs = new PriorityBlockingQueue<>();
	/** -Dmcopt.lod.yield: generation jobs the gate turned away, out of the queue until the pressure is off (put back, they'd block its head). */
	private final ConcurrentLinkedQueue<Job> gated = new ConcurrentLinkedQueue<>();
	private final ConcurrentLinkedQueue<Runnable> results = new ConcurrentLinkedQueue<>();
	private final List<Thread> threads = new ArrayList<>();
	private volatile boolean closed;
	private final AtomicLong seq = new AtomicLong();
	/** Window origins per level as the workers see them (a job whose tile left its window is dropped). */
	private final int[] winTx, winTz;

	// stats
	final AtomicLong generated = new AtomicLong(), loaded = new AtomicLong(), genNanos = new AtomicLong(), chunksSummarized = new AtomicLong();
	/** Tiles started empty (no noise and nothing cached): real chunks fill them. */
	final AtomicLong empty = new AtomicLong();
	/** -Dmcopt.lod.chunkTiles: level-0 tiles built from the game's chunks alone, generations skipped because of them. */
	final AtomicLong chunkTiles = new AtomicLong(), chunkSkipped = new AtomicLong();
	/**
	 * -Dmcopt.lod.chunkTiles=true: a level-0 tile not yet generated whose 16 chunks the client already has is built from those
	 * chunks alone, and its generation is skipped. The words are what generation followed by the chunks would leave (the chunks
	 * write every level-0 column), sooner and without the noise: in a flight the tiles under the render distance stop
	 * competing with the ones ahead.
	 */
	static final boolean CHUNK_TILES = Boolean.getBoolean("mcopt.lod.chunkTiles");
	/** Level-0 tiles built from chunks (render thread adds; workers read). */
	private final java.util.Set<Long> chunkBuilt = java.util.concurrent.ConcurrentHashMap.newKeySet();
	/** Render thread: chunks snapshotted and the time it took (ns). */
	long snapshots, snapNanos;
	long startNanos = System.nanoTime(), settledNanos, firstSettledNanos;
	volatile boolean settled;
	/** The camera's position at the last update (for other threads: the importer's order). */
	volatile double camX, camZ;
	/** A crown cell's GEOM_CLEAR and depth bits: over water the depth bits are the water's, which a crown word doesn't carry. */
	private static int crownClear(int clear, boolean wet) {
		return wet ? clear & ~LodClip.depthBits(127) : clear;
	}

	/** Singleplayer: the saved chunks' importer (LodImport), else null. */
	@Nullable LodImport importer;
	int needed, missing;

	private record Job(double priority, long seq, long key, Runnable task) implements Comparable<Job> {
		@Override
		public int compareTo(Job o) {
			int c = Double.compare(this.priority, o.priority);
			return c != 0 ? c : Long.compare(this.seq, o.seq);
		}
	}

	LodField(@Nullable LodNoise noise, LodClip clip, Path cache, int roof) {
		this.noise = noise;
		this.roof = roof;
		this.clip = clip;
		this.cache = cache;
		this.forest = LodConfig.TREES && noise != null ? new LodForest(noise) : null;
		this.winTx = new int[clip.levels];
		this.winTz = new int[clip.levels];
		for (int i = 0; i < LodConfig.THREADS; i++) {
			Thread t = new Thread(this::work, "mcopt-lod-" + i);
			t.setDaemon(true);
			t.setPriority(Thread.MIN_PRIORITY + 1);
			t.start();
			this.threads.add(t);
		}
		System.out.printf("mcopt-lod: field ready: reach %d chunks, %d levels of %d x %d cells (%.0f MB), switch at %s, %d workers, cache %s%n",
			LodConfig.RADIUS_CHUNKS, clip.levels, clip.n, clip.n, clip.bytes() / 1048576.0, java.util.Arrays.toString(clip.switchDist), LodConfig.THREADS, cache);
	}

	void close() {
		this.closed = true;
		if (this.importer != null) this.importer.stop();
		if (this.scanner != null) {
			// (its re-sort drains and re-adds the queue: done before the queue is drained here)
			this.scanner.shutdownNow();
			try {
				this.scanner.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		// what the workers handed back and the held chunks: into the tiles (or their patches) before anything is saved
		this.drainResults();
		for (LodChunks.Summary s : this.held.values()) this.applyChunk(s, 1, this.clip.levels);
		this.held.clear();
		// (a worker's job in flight, a save or a generation, finishes before the last saves here; then what it handed back. Only
		// waiting workers are interrupted, out of the queue or a pause: an interrupt in a save's file write would lose it. 2
		// seconds for all of them: a tile's generation takes milliseconds, and an exit never waits longer than that)
		long until = System.nanoTime() + 2_000_000_000L;
		boolean alive = true;
		while (alive && System.nanoTime() < until) {
			alive = false;
			for (Thread t : this.threads) {
				if (!t.isAlive()) continue;
				alive = true;
				Thread.State st = t.getState();
				if (st == Thread.State.WAITING || st == Thread.State.TIMED_WAITING) t.interrupt();
			}
			if (!alive) break;
			try {
				Thread.sleep(5);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		this.drainResults();
		// the saves still queued (real chunks' tiles: what was seen this session) are written now, not dropped with the queue
		java.util.ArrayList<Job> left = new java.util.ArrayList<>();
		this.jobs.drainTo(left);
		int saved = 0;
		for (Job j : left) {
			if (j.key != SAVE_KEY || j.task == null) continue;
			j.task.run();
			saved++;
		}
		// patches still queued (held back while their tile loaded, and that load won't finish now): into their files
		for (Long k : new ArrayList<>(this.queued.keySet())) this.patchFile(k);
		saved += this.saveDirty(true);
		if (saved > 0) System.out.println("mcopt-lod: saved " + saved + " tiles real chunks changed");
	}

	/** Render thread at close: every result the workers handed back, each on its own (one failing doesn't stop the saves). */
	private void drainResults() {
		Runnable r;
		while ((r = this.results.poll()) != null) {
			try {
				r.run();
			} catch (RuntimeException e) {
				System.out.println("mcopt-lod: closing: a result failed: " + e);
			}
		}
	}

	/** A job's key for a disk-cache save (its task does the work). */
	private static final long SAVE_KEY = -1L;
	/** Saves go before everything (a save is a few hundred microseconds, and what it keeps can't be had again offline). */
	private static final double SAVE_PRIORITY = -1e9;

	private void work() {
		while (!this.closed) {
			Job j;
			try {
				j = this.jobs.take();
			} catch (InterruptedException e) {
				return;
			}
			// (without generation a tile is a cache read: nothing to yield to the server for; nor is a tile the disk cache has)
			if (j.task == null && LodYield.ON && this.noise != null && !LodYield.admit(LodTile.levelOf(j.key))
				&& !(LodConfig.DISK_CACHE && Files.isRegularFile(this.file(j.key)))) {
				this.gated.add(j);   // -Dmcopt.lod.yield: the server has chunk work near the player, so this job waits (releaseGated)
				continue;
			}
			try {
				if (j.task != null) j.task.run();
				else this.generate(j.key);
			} catch (Throwable t) {
				System.out.println("mcopt-lod: job failed: " + t);
				t.printStackTrace(System.out);
				if (j.task == null) {
					this.pending.remove(j.key);
					this.requeuePatch(j.key);
				}
			}
		}
	}

	private Path file(long key) {
		return this.cache.resolve("L" + LodTile.levelOf(key)).resolve(LodTile.txOf(key) + "." + LodTile.tzOf(key) + ".lod");
	}

	/** Render thread, each frame: without pressure, the jobs the yield gate held back go back into the queue. */
	void releaseGated() {
		if (LodYield.pressure) return;
		for (Job j; (j = this.gated.poll()) != null;) this.jobs.add(j);
	}

	private boolean stillWanted(long key) {
		int l = LodTile.levelOf(key), x = LodTile.txOf(key) - this.winTx[l], z = LodTile.tzOf(key) - this.winTz[l];
		return x >= 0 && z >= 0 && x < this.clip.tilesPerSide && z < this.clip.tilesPerSide;
	}

	private void generate(long key) {
		if (!this.stillWanted(key)) {
			this.pending.remove(key);
			this.requeuePatch(key);
			return;
		}
		int level = LodTile.levelOf(key), tx = LodTile.txOf(key), tz = LodTile.tzOf(key);
		if (CHUNK_TILES && level == 0 && this.chunkBuilt.contains(key)) {
			// built from the game's chunks meanwhile (the render thread checks again before anything is put)
			this.chunkSkipped.incrementAndGet();
			this.results.add(() -> {
				this.pending.remove(key);
				this.requeuePatch(key);
				// evicted since: the next scan asks for it again, and then it's generated
				if (!this.clip.resident(0, tx, tz)) this.chunkBuilt.remove(key);
			});
			return;
		}
		int[] g = new int[LodTile.CELLS], c = new int[LodTile.CELLS];
		int[] cr = level < this.clip.crownLevels ? new int[LodTile.CELLS] : null;
		int[] tw = level == 0 && LodConfig.TEXTURES ? new int[LodTile.CELLS] : null;
		int[] rn = cr != null ? new int[LodTile.CELLS] : null;
		int[] pl = level == 0 && this.clip.plants ? new int[2 * LodTile.CELLS] : null;
		this.produce(key, g, c, cr, tw, rn, pl);
		this.results.add(() -> {
			this.pending.remove(key);
			if (CHUNK_TILES && level == 0 && this.chunkBuilt.contains(key)) {
				if (this.clip.resident(0, tx, tz)) {
					this.requeuePatch(key);
					return;
				}
				this.chunkBuilt.remove(key);
			}
			if (this.clip.put(level, tx, tz, g, c, cr, tw, rn, pl)) {
				// (patches made while it loaded, older than the chunks waiting for it)
				this.applyPending(key);
				List<LodChunks.Summary> real = level == 0 ? this.waiting.remove(key) : null;
				if (real != null) for (LodChunks.Summary s : real) this.applyChunk(s);
			} else {
				this.requeuePatch(key);
			}
			this.arrived(key);
		});
	}

	/** A tile's words: cached, generated (and saved) or, with no noise, empty. Under the tile's lock: a patchFile of the same file waits. */
	private void produce(long key, int[] g, int[] c, int @Nullable [] cr, int @Nullable [] tw, int @Nullable [] rn, int @Nullable [] pl) {
		synchronized (this.lock(key)) {
			this.produce0(key, g, c, cr, tw, rn, pl, true);
		}
	}

	/** consume: a queued patch goes into the words (and its file) now, so the tile is put with it. True when the words came from the cache file. */
	private boolean produce0(long key, int[] g, int[] c, int @Nullable [] cr, int @Nullable [] tw, int @Nullable [] rn, int @Nullable [] pl,
		boolean consume) {
		int level = LodTile.levelOf(key), tx = LodTile.txOf(key), tz = LodTile.tzOf(key);
		long start = System.nanoTime();
		boolean fromDisk = LodConfig.DISK_CACHE && this.load(key, g, c, cr, tw, rn, pl);
		if (!fromDisk && this.noise == null) {
			// nothing to generate from: an empty tile (no cell valid) for real chunks to fill (and nothing of a file that failed to load halfway)
			for (int[] a : new int[][] {g, c, cr, tw, rn, pl}) if (a != null) java.util.Arrays.fill(a, 0);
			this.empty.incrementAndGet();
		} else if (!fromDisk) {
			LodTile t = new LodTile(level, tx, tz);
			LodForest forest = this.forest;
			boolean exact = level < Math.min(2, LodConfig.TREE_LEVELS) && forest != null && forest.available();
			t.impostorTrees = !exact;
			this.noise.generate(t);
			if (exact) {
				if (level == 0) forest.offer(t);
				forest.plant(t);
				this.noise.dressTrees(t);
				this.noise.dressGround(t);
			}
			pack(t, g, c, cr, rn, pl);
			if (tw != null) System.arraycopy(t.tex, 0, tw, 0, LodTile.CELLS);
			this.genNanos.addAndGet(System.nanoTime() - start);
			this.generated.incrementAndGet();
			if (LodConfig.DISK_CACHE) this.save(key, g, c, cr, tw, rn, pl);
		} else {
			this.loaded.incrementAndGet();
		}
		Patch q = consume ? this.queued.remove(key) : null;
		if (q != null) {
			// (a generated tile was saved above; a loaded or empty one only needs it if the patch changed a word)
			if (applyTo(q, g, c, cr, tw, rn, pl) && LodConfig.DISK_CACHE) this.save0(key, g, c, cr, tw, rn, pl);
			this.patched.incrementAndGet();
		}
		return fromDisk;
	}

	/** Striped locks over the cache's files: a tile's file is read, generated and written by one thread at a time. */
	private final Object[] locks = java.util.stream.Stream.generate(Object::new).limit(4096).toArray();

	private Object lock(long key) {
		return this.locks[(int) ((key ^ key >>> 32) * 0x9E3779B97F4A7C15L >>> 52)];
	}

	/**
	 * A generated tile as clipmap words. With cr (levels that keep crowns), a tree crown floating over its ground becomes a
	 * crown cell: the geometry word its top and thickness, the color word its colors, the crown word the ground under it.
	 */
	static void pack(LodTile t, int[] g, int[] c, int @org.jspecify.annotations.Nullable [] cr, int @org.jspecify.annotations.Nullable [] rn,
		int @org.jspecify.annotations.Nullable [] pl) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int i = 0; i < t.cells(); i++) {
			if (pl != null) {
				pl[i] = 0;
				pl[LodTile.CELLS + i] = 0;
			}
			boolean wet = t.water[i] != LodTile.DRY && t.water[i] > t.height[i];
			int surface = wet ? t.water[i] : t.height[i];
			if (!wet && t.height[i] <= t.voidY && t.canopyHi[i] < t.canopyLo[i]) {
				// nothing solid in the column (void): no cell
				g[i] = 0;
				c[i] = 0;
				if (cr != null) cr[i] = 0;
				if (rn != null) rn[i] = 0;
				continue;
			}
			if (cr != null && t.canopyHi[i] >= t.canopyLo[i] && !t.standing[i] && t.canopyHi[i] + 1 > surface) {
				// (the wet flag marks a water top: a crown's top is leaves)
				g[i] = LodClip.crownGeomWord(t.canopyHi[i] + 1, t.canopyHi[i] + 1 - t.canopyLo[i], false);
				c[i] = LodClip.colorWord(t.crownTop[i], t.crownSide[i]);
				// (clear water under a crown: the crown word holds one color, the water over its floor)
				cr[i] = LodClip.crownWord(surface, wet && t.clear[i] > 0 ? LodColors.waterOver(t.side[i], t.top[i], t.clear[i]) : t.top[i]);
				if (rn != null) rn[i] = t.crownRuns[i];
				continue;
			}
			g[i] = LodClip.geomWord(surface, wet) | (t.fringe[i] && !wet && t.level == 0 ? LodClip.GEOM_FRINGE : 0)
				| (wet ? LodClip.depthBits(t.clear[i]) : 0);
			c[i] = LodClip.colorWord(t.top[i], t.side[i]);
			BlockState plant = t.plantLower[i];
			if (pl != null && plant != null && !wet && t.level == 0) {
				// the game's random offset of the plant (its position's hash), as it will be drawn there
				var off = plant.getOffset(pos.set(t.x0 + (i % t.size), surface, t.z0 + (i / t.size)));
				BlockState upper = t.plantUpper[i];
				pl[i] = LodClip.plantWordA(LodPalette.id(plant), upper != null ? LodPalette.id(upper) : 0, t.plantBlocks[i]);
				pl[LodTile.CELLS + i] = LodClip.plantWordB(t.plantColor[i], off.x(), off.y(), off.z());
				if ((pl[i] & 1023) != 0) g[i] |= LodClip.plantBits(t.plantBlocks[i]);
			}
			if (cr != null) cr[i] = LodClip.belowWord(t.below[i]);
			if (rn != null) rn[i] = 0;
		}
	}

	private boolean load(long key, int[] g, int[] c, int @org.jspecify.annotations.Nullable [] cr, int @org.jspecify.annotations.Nullable [] tw,
		int @org.jspecify.annotations.Nullable [] rn, int @org.jspecify.annotations.Nullable [] pl) {
		Path f = this.file(key);
		if (!Files.exists(f)) return false;
		byte[] raw;
		// the whole file inflated in one go (a read per word through the inflater cost more than the inflating)
		java.util.zip.Inflater inflater = new java.util.zip.Inflater();
		try (InputStream in = new InflaterInputStream(Files.newInputStream(f), inflater, 65536)) {
			raw = in.readAllBytes();
		} catch (IOException | RuntimeException e) {
			return false;
		} finally {
			inflater.end();
		}
		try {
			CacheIn in = new CacheIn(raw);
			int format = in.readInt();
			if (format != FORMAT && format != FORMAT_PLAIN) return false;
			boolean planar = format == FORMAT;
			in.ints(g, LodTile.CELLS, planar);
			in.ints(c, LodTile.CELLS, planar);
			boolean hasCrowns = in.readBoolean();
			int[] scratch = this.scratch.get();
			if (hasCrowns) in.ints(cr != null ? cr : scratch, LodTile.CELLS, planar);
			else if (cr != null) java.util.Arrays.fill(cr, 0);
			if (hasCrowns) in.ints(rn != null ? rn : scratch, LodTile.CELLS, planar);
			else if (rn != null) java.util.Arrays.fill(rn, 0);
			// palette numbers are this session's: the cache keeps the block states' names
			boolean hasTex = in.readBoolean();
			if (hasTex) {
				int[] ids = in.names();
				in.ints(scratch, LodTile.CELLS, planar);
				for (int i = 0; i < LodTile.CELLS; i++) {
					int w = scratch[i];
					if (tw != null) tw[i] = LodPalette.word(remap(ids, w & 1023), remap(ids, (w >> 10) & 1023), remap(ids, (w >> 20) & 1023));
				}
			} else if (tw != null) {
				java.util.Arrays.fill(tw, 0);
			}
			// plants: their words, block numbers as names like the texture words
			boolean hasPlants = in.readBoolean();
			if (hasPlants) {
				int[] ids = in.names();
				int[] both = this.scratch2.get();
				in.ints(both, 2 * LodTile.CELLS, planar);
				for (int i = 0; i < LodTile.CELLS; i++) {
					int a = both[2 * i], b = both[2 * i + 1];
					if (pl != null) {
						pl[i] = remap(ids, a & 1023) | remap(ids, (a >> 10) & 1023) << 10 | a & ~0xFFFFF;
						pl[LodTile.CELLS + i] = b;
					}
				}
			} else if (pl != null) {
				java.util.Arrays.fill(pl, 0);
			}
			if (pl == null || !hasPlants) for (int i = 0; i < LodTile.CELLS; i++) g[i] &= ~LodClip.GEOM_PLANT_BITS;
			return true;
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	private static int remap(int[] ids, int local) {
		return local > 0 && local < ids.length ? ids[local] : 0;
	}

	/** Per worker: a tile's worth of words read into nowhere (or remapped from), and a plant tile's two. */
	private final ThreadLocal<int[]> scratch = ThreadLocal.withInitial(() -> new int[LodTile.CELLS]),
		scratch2 = ThreadLocal.withInitial(() -> new int[2 * LodTile.CELLS]);

	private void save(long key, int[] g, int[] c, int @org.jspecify.annotations.Nullable [] cr, int @org.jspecify.annotations.Nullable [] tw,
		int @org.jspecify.annotations.Nullable [] rn, int @org.jspecify.annotations.Nullable [] pl) {
		synchronized (this.lock(key)) {
			this.save0(key, g, c, cr, tw, rn, pl);
		}
	}

	private void save0(long key, int[] g, int[] c, int @Nullable [] cr, int @Nullable [] tw, int @Nullable [] rn, int @Nullable [] pl) {
		Path f = this.file(key);
		try {
			Files.createDirectories(f.getParent());
			Path tmp = f.resolveSibling(f.getFileName() + ".tmp" + Thread.currentThread().threadId());
			CacheOut out = new CacheOut();
			out.writeInt(FORMAT);
			out.ints(g, LodTile.CELLS);
			out.ints(c, LodTile.CELLS);
			out.writeBoolean(cr != null);
			if (cr != null) {
				out.ints(cr, LodTile.CELLS);
				out.ints(rn != null ? rn : new int[LodTile.CELLS], LodTile.CELLS);
			}
			out.writeBoolean(tw != null);
			if (tw != null) {
				// palette numbers as a local table of block state names (numbers differ between sessions)
				it.unimi.dsi.fastutil.ints.Int2IntLinkedOpenHashMap local = new it.unimi.dsi.fastutil.ints.Int2IntLinkedOpenHashMap();
				local.put(0, 0);
				int[] words = new int[LodTile.CELLS];
				for (int i = 0; i < LodTile.CELLS; i++) {
					int w = tw[i];
					words[i] = local(local, w & 1023) | local(local, (w >> 10) & 1023) << 10 | local(local, (w >> 20) & 1023) << 20;
				}
				out.names(local);
				out.ints(words, LodTile.CELLS);
			}
			out.writeBoolean(pl != null);
			if (pl != null) {
				it.unimi.dsi.fastutil.ints.Int2IntLinkedOpenHashMap local = new it.unimi.dsi.fastutil.ints.Int2IntLinkedOpenHashMap();
				local.put(0, 0);
				int[] both = new int[2 * LodTile.CELLS];
				for (int i = 0; i < LodTile.CELLS; i++) {
					int w = pl[i];
					both[2 * i] = local(local, w & 1023) | local(local, (w >> 10) & 1023) << 10 | w & ~0xFFFFF;
					both[2 * i + 1] = pl[LodTile.CELLS + i];
				}
				out.names(local);
				out.ints(both, 2 * LodTile.CELLS);
			}
			Deflater d = new Deflater(Deflater.BEST_SPEED);
			try (OutputStream os = new DeflaterOutputStream(Files.newOutputStream(tmp), d, 65536)) {
				out.writeTo(os);
			} finally {
				d.end();
			}
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			System.out.println("mcopt-lod: cache write failed: " + e);
		}
	}

	private static int local(it.unimi.dsi.fastutil.ints.Int2IntLinkedOpenHashMap local, int id) {
		int v = local.getOrDefault(id, -1);
		if (v < 0) local.put(id, v = local.size());
		return v;
	}

	/**
	 * A cache file's words: FORMAT planar (every word's lowest byte, then every second byte...: neighbors share their high bytes, so
	 * deflate packs them several times smaller), FORMAT_PLAIN one after the other, big-endian.
	 */
	private static final class CacheIn {
		private final java.nio.ByteBuffer buf;

		CacheIn(byte[] raw) {
			this.buf = java.nio.ByteBuffer.wrap(raw);
		}

		int readInt() {
			return this.buf.getInt();
		}

		boolean readBoolean() {
			return this.buf.get() != 0;
		}

		void ints(int[] into, int n, boolean planar) {
			if (!planar) {
				this.buf.asIntBuffer().get(into, 0, n);
				this.buf.position(this.buf.position() + 4 * n);
				return;
			}
			byte[] a = this.buf.array();
			int p = this.buf.arrayOffset() + this.buf.position();
			if (p + 4 * n > this.buf.arrayOffset() + this.buf.limit()) throw new java.nio.BufferUnderflowException();
			for (int i = 0; i < n; i++) {
				into[i] = (a[p + i] & 255) | (a[p + n + i] & 255) << 8 | (a[p + 2 * n + i] & 255) << 16 | (a[p + 3 * n + i] & 255) << 24;
			}
			this.buf.position(this.buf.position() + 4 * n);
		}

		/** A local table of block state names (as DataOutputStream.writeUTF wrote them) as this session's palette numbers. */
		int[] names() throws java.io.UTFDataFormatException {
			int n = this.readInt();
			if (n < 0 || n > 1 << 16) throw new java.io.UTFDataFormatException("bad name count " + n);
			int[] ids = new int[n];
			for (int k = 0; k < n; k++) {
				int len = this.buf.getShort() & 0xFFFF;
				byte[] utf = new byte[len];
				this.buf.get(utf);
				ids[k] = LodPalette.idOf(modifiedUtf8(utf));
			}
			return ids;
		}
	}

	/** Decodes Java's modified UTF-8 (writeUTF's encoding; block state names are ASCII in practice). */
	static String modifiedUtf8(byte[] b) throws java.io.UTFDataFormatException {
		char[] out = new char[b.length];
		int n = 0;
		for (int i = 0; i < b.length;) {
			int c = b[i] & 255;
			if (c < 0x80) {
				out[n++] = (char) c;
				i++;
			} else if ((c & 0xE0) == 0xC0 && i + 1 < b.length) {
				out[n++] = (char) ((c & 0x1F) << 6 | (b[i + 1] & 0x3F));
				i += 2;
			} else if ((c & 0xF0) == 0xE0 && i + 2 < b.length) {
				out[n++] = (char) ((c & 0x0F) << 12 | (b[i + 1] & 0x3F) << 6 | (b[i + 2] & 0x3F));
				i += 3;
			} else {
				throw new java.io.UTFDataFormatException("malformed name");
			}
		}
		return new String(out, 0, n);
	}

	/** A cache file being written: words planar (FORMAT), deflated in one go at the end. */
	private static final class CacheOut {
		private byte[] buf = new byte[64 * 1024];
		private int size;

		private void ensure(int more) {
			if (this.size + more > this.buf.length) this.buf = java.util.Arrays.copyOf(this.buf, Math.max(this.buf.length * 2, this.size + more));
		}

		void writeInt(int v) {
			this.ensure(4);
			this.buf[this.size++] = (byte) (v >>> 24);
			this.buf[this.size++] = (byte) (v >>> 16);
			this.buf[this.size++] = (byte) (v >>> 8);
			this.buf[this.size++] = (byte) v;
		}

		void writeBoolean(boolean v) {
			this.ensure(1);
			this.buf[this.size++] = (byte) (v ? 1 : 0);
		}

		void ints(int[] from, int n) {
			this.ensure(4 * n);
			byte[] a = this.buf;
			int p = this.size;
			for (int i = 0; i < n; i++) {
				int v = from[i];
				a[p + i] = (byte) v;
				a[p + n + i] = (byte) (v >>> 8);
				a[p + 2 * n + i] = (byte) (v >>> 16);
				a[p + 3 * n + i] = (byte) (v >>> 24);
			}
			this.size += 4 * n;
		}

		/** The local table's palette numbers as names, in the table's order (writeUTF's encoding). */
		void names(it.unimi.dsi.fastutil.ints.Int2IntLinkedOpenHashMap local) throws IOException {
			java.io.ByteArrayOutputStream names = new java.io.ByteArrayOutputStream();
			try (DataOutputStream d = new DataOutputStream(names)) {
				d.writeInt(local.size());
				for (int id : local.keySet()) d.writeUTF(LodPalette.nameOf(id));
			}
			byte[] b = names.toByteArray();
			this.ensure(b.length);
			System.arraycopy(b, 0, this.buf, this.size, b.length);
			this.size += b.length;
		}

		void writeTo(OutputStream os) throws IOException {
			os.write(this.buf, 0, this.size);
		}
	}

	// ---- the game's own chunks ----

	private final java.util.HashMap<Long, List<LodChunks.Summary>> waiting = new java.util.HashMap<>();
	/** Level and tile of every tile real chunks changed since it was saved. */
	private final java.util.Set<Long> dirtyTiles = new java.util.HashSet<>();

	/** Render thread: a chunk the client loaded or is unloading becomes far terrain (colors worked out on a worker). */
	void chunk(net.minecraft.world.level.chunk.LevelChunk chunk) {
		long t0 = System.nanoTime();
		LodChunks.Snapshot snap = LodChunks.snapshot(chunk, this.roof);
		this.snapNanos += System.nanoTime() - t0;
		this.snapshots++;
		this.jobs.add(new Job(-2, this.seq.incrementAndGet(), 0, () -> {
			LodChunks.Summary sum = LodChunks.summarize(snap);
			this.chunksSummarized.incrementAndGet();
			this.results.add(() -> this.applyChunk(sum));
		}));
	}

	/**
	 * LodImport (any thread): a saved chunk's summary, on the render thread unless the client has the chunk loaded (its own is newer):
	 * level 0 only where its tile is resident (out of its ring it isn't drawn, and waiting would hold every imported chunk of the window).
	 */
	void imported(LodChunks.Summary s) {
		this.results.add(() -> {
			var level = net.minecraft.client.Minecraft.getInstance().level;
			if (level != null && level.getChunkSource().hasChunk(s.chunkX(), s.chunkZ())) return;
			int span = this.clip.span(0), tx = Math.floorDiv(s.chunkX() * 16, span), tz = Math.floorDiv(s.chunkZ() * 16, span);
			if (this.clip.resident(0, tx, tz)) this.applyChunk(s, 0, 1);
			else if (PATCHES) this.patch(s, 0, tx, tz);
			if (this.clip.levels > 1) this.applyChunk(s, 1, this.clip.levels);
		});
	}

	/** Worker results the render thread hasn't taken yet. */
	int resultsWaiting() {
		return this.results.size();
	}

	/** Writes a chunk's columns into every resident tile that covers it: level 0 every column, coarser at sample points. */
	private void applyChunk(LodChunks.Summary s) {
		// -Dmcopt.lod.chunkHold: a chunk in view and not handed off yet keeps its coarser levels as they are for now (a rewrite
		// there would show); they're written once it's handed off or out of view (releaseHeld)
		java.util.function.LongPredicate hold = this.hold;
		long ck = LodForest.chunkKey(s.chunkX(), s.chunkZ());
		if (hold != null && this.clip.levels > 1 && hold.test(ck)) {
			this.applyChunk(s, 0, 1);
			this.held.put(ck, s);
			return;
		}
		this.held.remove(ck);
		this.applyChunk(s, 0, this.clip.levels);
	}

	/** -Dmcopt.lod.chunkHold: Lod's test (chunk in view, not handed off); null: off. Render thread. */
	java.util.function.@org.jspecify.annotations.Nullable LongPredicate hold;
	private final java.util.HashMap<Long, LodChunks.Summary> held = new java.util.HashMap<>();
	long heldReleased;

	/** Render thread, once a frame after the mask: the held chunks whose coarser levels can be written now. */
	void releaseHeld() {
		java.util.function.LongPredicate hold = this.hold;
		if (this.held.isEmpty() || hold == null) return;
		for (var it = this.held.entrySet().iterator(); it.hasNext();) {
			var e = it.next();
			if (hold.test(e.getKey())) continue;
			it.remove();
			this.applyChunk(e.getValue(), 1, this.clip.levels);
			this.heldReleased++;
		}
	}

	int heldCount() {
		return this.held.size();
	}

	private void applyChunk(LodChunks.Summary s, int fromLevel, int toLevel) {
		int bx = s.chunkX() * 16, bz = s.chunkZ() * 16;
		for (int level = fromLevel; level < toLevel; level++) {
			int span = this.clip.span(level);
			int tx = Math.floorDiv(bx, span), tz = Math.floorDiv(bz, span);
			if (!this.clip.resident(level, tx, tz)) {
				if (level == 0 && this.clip.inWindow(0, tx, tz)) {
					List<LodChunks.Summary> list = this.waiting.computeIfAbsent(LodTile.key(0, tx, tz), k -> new ArrayList<>());
					list.removeIf(o -> o.chunkX() == s.chunkX() && o.chunkZ() == s.chunkZ());
					list.add(s);
					if (CHUNK_TILES && list.size() == (LodTile.SIZE / 16) * (LodTile.SIZE / 16)) this.buildFromChunks(tx, tz, list);
				} else if (level > 0 && PATCHES) {
					// a coarser tile not in memory: its cache file takes the cells (what was seen stays when seen from farther away)
					this.patch(s, level, tx, tz);
				}
				continue;
			}
			if (level == 0 && LodConfig.VERIFY) this.verify(s);
			int cell = 1 << level;
			// cells whose sample point (their corner, as generated) is in the chunk
			int c0x = Math.ceilDiv(bx, cell), c1x = Math.floorDiv(bx + 15, cell);
			int c0z = Math.ceilDiv(bz, cell), c1z = Math.floorDiv(bz + 15, cell);
			boolean changed = false;
			for (int cz = c0z; cz <= c1z; cz++) {
				for (int cx = c0x; cx <= c1x; cx++) {
					int k = (cz * cell - bz) * 16 + (cx * cell - bx);
					boolean wet = s.water()[k] != LodTile.DRY && s.water()[k] > s.height()[k];
					int clear = (s.clear()[k] ? LodClip.GEOM_CLEAR : 0) | LodClip.depthBits(s.depth()[k]);
					int surface = wet ? s.water()[k] : s.height()[k];
					if (s.crownLo()[k] > surface) {
						// leaves over air: a crown floating over the ground under it
						changed |= this.clip.putCell(level, cx, cz, LodClip.crownGeomWord(s.crownHi()[k], s.crownHi()[k] - s.crownLo()[k], false) | crownClear(clear, wet),
							LodClip.colorWord(s.top()[k], s.side()[k]), LodClip.crownWord(surface, s.groundColor()[k]), s.tex()[k], s.runs()[k], 0, 0);
					} else if (level == 0) {
						int pa = s.plantA()[k];
						changed |= this.clip.putCell(level, cx, cz, LodClip.geomWord(Math.max(surface, s.crownHi()[k]), wet) | clear | (s.fringe()[k] && !wet ? LodClip.GEOM_FRINGE : 0)
							| ((pa & 1023) != 0 && !wet ? LodClip.plantBits((pa >> 20 & 3) + 1) : 0),
							LodClip.colorWord(s.top()[k], s.side()[k]), LodClip.belowWord(s.below()[k]), s.tex()[k], 0, pa, s.plantB()[k]);
					} else {
						// coarser cells: their walls are mostly below the top block
						changed |= this.clip.putCell(level, cx, cz, LodClip.geomWord(Math.max(surface, s.crownHi()[k]), wet) | clear,
							LodClip.colorWord(s.top()[k], LodColors.mix(s.side()[k], s.below()[k], 0.6F)), 0, 0, 0, 0, 0);
					}
				}
			}
			// (a chunk unloaded or sent again unchanged writes what it wrote when it loaded: nothing to remesh, publish or save)
			if (!changed) continue;
			this.touched.add(LodTile.key(level, tx, tz));
			this.dirtyTiles.add(LodTile.key(level, tx, tz));
		}
	}

	/** Render thread: tiles real chunks wrote into, refreshed together (a refresh per chunk copied and remeshed the tile per chunk per level). */
	private final it.unimi.dsi.fastutil.longs.LongOpenHashSet touched = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
	private long touchedFlushed;

	/**
	 * Render thread, after the chunks were applied, at most every 100 ms: every touched tile still resident refreshed (its maxima, its
	 * mesh). Once a frame, a server's chunks at ~1000 fps came a frame apart: 13,000 meshes (a 64-112 KB copy each, on this thread)
	 * for 1,800 chunks and ~3,600 tiles in 20 s, each chunk's tile meshed again at every level.
	 */
	void flushTouched() {
		long now = System.nanoTime();
		if (this.touched.isEmpty() || now - this.touchedFlushed < 100_000_000L) return;
		this.touchedFlushed = now;
		for (var it = this.touched.iterator(); it.hasNext();) {
			long key = it.nextLong();
			int level = LodTile.levelOf(key), tx = LodTile.txOf(key), tz = LodTile.tzOf(key);
			if (this.clip.resident(level, tx, tz)) this.clip.refresh(level, tx, tz);
		}
		this.touched.clear();
	}

	/**
	 * -Dmcopt.lod.patches=false: real chunks only change the tiles in memory. On (the default), a tile not in memory takes their cells in its
	 * cache file: else a place seen only from close by is missing (or generated) from farther away, as on a server, where nothing else draws it.
	 */
	static final boolean PATCHES = LodConfig.DISK_CACHE && Boolean.parseBoolean(System.getProperty("mcopt.lod.patches", "true"));

	/** Real chunks' cells for a tile not in memory, as applyChunk writes them (last wins). generate: no cache file needed (in window or no noise). */
	private static final class Patch {
		final it.unimi.dsi.fastutil.ints.IntArrayList cells = new it.unimi.dsi.fastutil.ints.IntArrayList(), g = new it.unimi.dsi.fastutil.ints.IntArrayList(),
			c = new it.unimi.dsi.fastutil.ints.IntArrayList(), cr = new it.unimi.dsi.fastutil.ints.IntArrayList(), runs = new it.unimi.dsi.fastutil.ints.IntArrayList(),
			tw = new it.unimi.dsi.fastutil.ints.IntArrayList(), pa = new it.unimi.dsi.fastutil.ints.IntArrayList(), pb = new it.unimi.dsi.fastutil.ints.IntArrayList();
		boolean generate;

		/** This patch followed by a later one. */
		Patch then(Patch later) {
			this.cells.addAll(later.cells);
			this.g.addAll(later.g);
			this.c.addAll(later.c);
			this.cr.addAll(later.cr);
			this.runs.addAll(later.runs);
			this.tw.addAll(later.tw);
			this.pa.addAll(later.pa);
			this.pb.addAll(later.pb);
			this.generate |= later.generate;
			return this;
		}
	}

	/** Render thread: patches waiting for the next save (by tile key), newer than the queued ones. */
	private final java.util.HashMap<Long, Patch> patches = new java.util.HashMap<>();
	/**
	 * Patches handed to the workers, not yet written (older first when merged): taken by whichever comes first under the tile's lock, its
	 * patch job (into the file) or the tile's loading (into its words), or by the render thread when the tile is put meanwhile.
	 */
	private final ConcurrentHashMap<Long, Patch> queued = new ConcurrentHashMap<>();
	final AtomicLong patched = new AtomicLong();

	/** A chunk's cells on a level whose tile isn't resident, as applyChunk would write them, into the tile's patch. */
	private void patch(LodChunks.Summary s, int level, int tx, int tz) {
		int bx = s.chunkX() * 16, bz = s.chunkZ() * 16, cell = 1 << level;
		int c0x = Math.ceilDiv(bx, cell), c1x = Math.floorDiv(bx + 15, cell);
		int c0z = Math.ceilDiv(bz, cell), c1z = Math.floorDiv(bz + 15, cell);
		if (c0x > c1x || c0z > c1z) return;
		Patch p = this.patches.computeIfAbsent(LodTile.key(level, tx, tz), k -> new Patch());
		if (this.noise == null || this.clip.inWindow(level, tx, tz)) p.generate = true;
		int x0 = tx * LodTile.SIZE, z0 = tz * LodTile.SIZE;
		for (int cz = c0z; cz <= c1z; cz++) {
			for (int cx = c0x; cx <= c1x; cx++) {
				int k = (cz * cell - bz) * 16 + (cx * cell - bx);
				boolean wet = s.water()[k] != LodTile.DRY && s.water()[k] > s.height()[k];
				int clear = (s.clear()[k] ? LodClip.GEOM_CLEAR : 0) | LodClip.depthBits(s.depth()[k]);
				int surface = wet ? s.water()[k] : s.height()[k];
				p.cells.add((cz - z0) * LodTile.SIZE + (cx - x0));
				if (s.crownLo()[k] > surface) {
					p.g.add(LodClip.crownGeomWord(s.crownHi()[k], s.crownHi()[k] - s.crownLo()[k], false) | crownClear(clear, wet));
					p.c.add(LodClip.colorWord(s.top()[k], s.side()[k]));
					p.cr.add(LodClip.crownWord(surface, s.groundColor()[k]));
					p.runs.add(s.runs()[k]);
					p.tw.add(s.tex()[k]);
					p.pa.add(0);
					p.pb.add(0);
				} else if (level == 0) {
					int pa = s.plantA()[k];
					p.g.add(LodClip.geomWord(Math.max(surface, s.crownHi()[k]), wet) | clear | (s.fringe()[k] && !wet ? LodClip.GEOM_FRINGE : 0)
						| ((pa & 1023) != 0 && !wet ? LodClip.plantBits((pa >> 20 & 3) + 1) : 0));
					p.c.add(LodClip.colorWord(s.top()[k], s.side()[k]));
					p.cr.add(LodClip.belowWord(s.below()[k]));
					p.runs.add(0);
					p.tw.add(s.tex()[k]);
					p.pa.add(pa);
					p.pb.add(s.plantB()[k]);
				} else {
					p.g.add(LodClip.geomWord(Math.max(surface, s.crownHi()[k]), wet) | clear);
					p.c.add(LodClip.colorWord(s.top()[k], LodColors.mix(s.side()[k], s.below()[k], 0.6F)));
					p.cr.add(0);
					p.runs.add(0);
					p.tw.add(0);
					p.pa.add(0);
					p.pb.add(0);
				}
			}
		}
	}

	/** With the saves: each patch into its tile if resident, else queued with a job at the saves' priority (ordered with them); sync: written now. */
	private int flushPatches(boolean sync) {
		if (this.patches.isEmpty()) return 0;
		int n = 0;
		for (var e : this.patches.entrySet()) {
			long key = e.getKey();
			Patch p = e.getValue();
			if (this.applyResident(key, p)) continue;
			n++;
			this.queued.merge(key, p, Patch::then);
			if (sync) this.patchFile(key);
			else this.jobs.add(new Job(SAVE_PRIORITY, this.seq.incrementAndGet(), SAVE_KEY, () -> this.patchFile(key)));
		}
		this.patches.clear();
		return n;
	}

	/** Render thread: the patch's cells into its tile if resident (marked dirty for the next save); false if not resident. */
	private boolean applyResident(long key, Patch p) {
		int level = LodTile.levelOf(key), tx = LodTile.txOf(key), tz = LodTile.tzOf(key);
		if (!this.clip.resident(level, tx, tz)) return false;
		int x0 = tx * LodTile.SIZE, z0 = tz * LodTile.SIZE;
		boolean changed = false;
		for (int i = 0; i < p.cells.size(); i++) {
			int cell = p.cells.getInt(i);
			changed |= this.clip.putCell(level, x0 + cell % LodTile.SIZE, z0 + cell / LodTile.SIZE, p.g.getInt(i), p.c.getInt(i), p.cr.getInt(i), p.tw.getInt(i),
				p.runs.getInt(i), p.pa.getInt(i), p.pb.getInt(i));
		}
		if (changed) {
			this.touched.add(key);
			this.dirtyTiles.add(key);
		}
		return true;
	}

	/** A patch job for the tile, if a patch is queued for it (a load that ended without putting the tile left it there). */
	private void requeuePatch(long key) {
		if (this.queued.containsKey(key)) this.jobs.add(new Job(SAVE_PRIORITY, this.seq.incrementAndGet(), SAVE_KEY, () -> this.patchFile(key)));
	}

	/** Render thread, right after a tile is put: the patches made for it while it wasn't resident, older first. */
	private void applyPending(long key) {
		Patch q = this.queued.remove(key);
		if (q != null) this.applyResident(key, q);
		Patch p = this.patches.remove(key);
		if (p != null) this.applyResident(key, p);
	}

	/** A patch into a tile's words (as putCell keeps them: crowns and plants only where the level has them); true when a word changed. */
	private static boolean applyTo(Patch p, int[] g, int[] c, int @Nullable [] cr, int @Nullable [] tw, int @Nullable [] rn, int @Nullable [] pl) {
		boolean changed = false;
		for (int i = 0; i < p.cells.size(); i++) {
			int cell = p.cells.getInt(i), gw = p.g.getInt(i);
			if (pl == null) gw &= ~LodClip.GEOM_PLANT_BITS;
			if (cr == null) gw &= ~LodClip.GEOM_CROWN_BITS;
			changed |= g[cell] != gw || c[cell] != p.c.getInt(i) || cr != null && cr[cell] != p.cr.getInt(i) || rn != null && rn[cell] != p.runs.getInt(i)
				|| tw != null && tw[cell] != p.tw.getInt(i) || pl != null && (pl[cell] != p.pa.getInt(i) || pl[LodTile.CELLS + cell] != p.pb.getInt(i));
			g[cell] = gw;
			c[cell] = p.c.getInt(i);
			if (cr != null) cr[cell] = p.cr.getInt(i);
			if (rn != null) rn[cell] = p.runs.getInt(i);
			if (tw != null) tw[cell] = p.tw.getInt(i);
			if (pl != null) {
				pl[cell] = p.pa.getInt(i);
				pl[LodTile.CELLS + cell] = p.pb.getInt(i);
			}
		}
		return changed;
	}

	/** Worker (or the render thread at close): the tile's queued patch into its cache file, under the tile's lock. */
	private void patchFile(long key) {
		int level = LodTile.levelOf(key);
		synchronized (this.lock(key)) {
			// the tile is being loaded: it takes the patch when it's put (applyPending), or a job is queued again if it isn't
			if (!this.closed && this.pending.containsKey(key)) return;
			// a save of the tile from memory is still queued: it goes first (it'd overwrite the patched file), then queues this job again
			if (!this.closed && this.saveQueued.containsKey(key)) return;
			Patch p = this.queued.remove(key);
			// (taken by the tile's loading meanwhile, or by the render thread: nothing left to write)
			if (p == null) return;
			int[] g = new int[LodTile.CELLS], c = new int[LodTile.CELLS];
			int[] cr = level < this.clip.crownLevels ? new int[LodTile.CELLS] : null;
			int[] tw = level == 0 && LodConfig.TEXTURES ? new int[LodTile.CELLS] : null;
			int[] rn = cr != null ? new int[LodTile.CELLS] : null;
			int[] pl = level == 0 && this.clip.plants ? new int[2 * LodTile.CELLS] : null;
			// a tile with nothing cached isn't generated for a patch outside its window (it may never be drawn), nor at close
			if (this.noise != null && (!p.generate || this.closed) && !Files.exists(this.file(key))) return;
			boolean fromDisk = this.produce0(key, g, c, cr, tw, rn, pl, false);
			// (a file that already has these cells, from an earlier patch or the tile saved after the chunk loaded: not written again)
			if (applyTo(p, g, c, cr, tw, rn, pl) || !fromDisk) this.save0(key, g, c, cr, tw, rn, pl);
		}
		this.patched.incrementAndGet();
	}

	/** -Dmcopt.lod.chunkTiles: a level-0 tile from its 16 chunks' summaries, words as applyChunk writes them, in one put. */
	private void buildFromChunks(int tx, int tz, List<LodChunks.Summary> list) {
		long key = LodTile.key(0, tx, tz);
		int[] g = new int[LodTile.CELLS], c = new int[LodTile.CELLS];
		int[] cr = this.clip.crownLevels > 0 ? new int[LodTile.CELLS] : null;
		int[] tw = LodConfig.TEXTURES ? new int[LodTile.CELLS] : null;
		int[] rn = cr != null ? new int[LodTile.CELLS] : null;
		int[] pl = this.clip.plants ? new int[2 * LodTile.CELLS] : null;
		int x0 = tx * LodTile.SIZE, z0 = tz * LodTile.SIZE;
		for (LodChunks.Summary s : list) {
			int bx = s.chunkX() * 16, bz = s.chunkZ() * 16;
			for (int k = 0; k < 256; k++) {
				int i = (bz + (k >> 4) - z0) * LodTile.SIZE + (bx + (k & 15) - x0);
				boolean wet = s.water()[k] != LodTile.DRY && s.water()[k] > s.height()[k];
				int clear = (s.clear()[k] ? LodClip.GEOM_CLEAR : 0) | LodClip.depthBits(s.depth()[k]);
				int surface = wet ? s.water()[k] : s.height()[k];
				int gw, cw, crw, runs = 0, pa = 0, pb = 0;
				if (s.crownLo()[k] > surface) {
					gw = LodClip.crownGeomWord(s.crownHi()[k], s.crownHi()[k] - s.crownLo()[k], false) | crownClear(clear, wet);
					cw = LodClip.colorWord(s.top()[k], s.side()[k]);
					crw = LodClip.crownWord(surface, s.groundColor()[k]);
					runs = s.runs()[k];
				} else {
					pa = s.plantA()[k];
					pb = s.plantB()[k];
					gw = LodClip.geomWord(Math.max(surface, s.crownHi()[k]), wet) | clear | (s.fringe()[k] && !wet ? LodClip.GEOM_FRINGE : 0)
						| ((pa & 1023) != 0 && !wet ? LodClip.plantBits((pa >> 20 & 3) + 1) : 0);
					cw = LodClip.colorWord(s.top()[k], s.side()[k]);
					crw = LodClip.belowWord(s.below()[k]);
				}
				// as putCell masks them
				if (pl == null) gw &= ~LodClip.GEOM_PLANT_BITS;
				if (cr == null) gw &= ~LodClip.GEOM_CROWN_BITS;
				g[i] = gw;
				c[i] = cw;
				if (cr != null) cr[i] = crw;
				if (rn != null) rn[i] = runs;
				if (tw != null) tw[i] = s.tex()[k];
				if (pl != null) {
					pl[i] = pa;
					pl[LodTile.CELLS + i] = pb;
				}
			}
		}
		if (!this.clip.put(0, tx, tz, g, c, cr, tw, rn, pl)) return;
		// (every cell was just written from the live chunks: patches made before are older)
		this.queued.remove(key);
		this.patches.remove(key);
		this.chunkBuilt.add(key);
		this.waiting.remove(key);
		this.chunkTiles.incrementAndGet();
		this.dirtyTiles.add(key);
		if (this.chunkBuilt.size() > 4096) this.chunkBuilt.removeIf(k -> !this.clip.resident(0, LodTile.txOf(k), LodTile.tzOf(k)));
		this.arrived(key);
	}

	// ---- -Dmcopt.lod.verify: generated far terrain against the real chunks, column by column ----

	private final java.util.Set<Long> verified = new java.util.HashSet<>();
	/** columns, no data, ground exact, ground off by 1, crown agree (both or neither), crowns both, crowns exact (bottom, top, runs, ground),
	 * plants agree (presence), plants both, plants same block, top block same, side block same, top color within 2 (of 31/63) */
	private final long[] vs = new long[16];
	private int verifyLogged, groundLogged, chunkLogged;
	/** Open dry ground without trees: real minus generated surface y, -8..8 (clamped). */
	private final long[] dyHist = new long[17];
	/** Columns: dry in both, wet only in the real chunk, wet only in far terrain, wet in both. */
	private final long[] wetStats = new long[4];
	/** Per verified chunk with misses: surface columns off << 16 | crown columns off. */
	private final java.util.HashMap<Long, Integer> chunkMiss = new java.util.HashMap<>();

	private void verify(LodChunks.Summary s) {
		long key = LodForest.chunkKey(s.chunkX(), s.chunkZ());
		if (!this.verified.add(key)) return;
		int bx = s.chunkX() * 16, bz = s.chunkZ() * 16;
		long groundBefore = this.vs[0] - this.vs[1] - this.vs[2], crownBefore = this.vs[0] - this.vs[1] - this.vs[4];
		for (int k = 0; k < 256; k++) {
			int x = bx + (k & 15), z = bz + (k >> 4);
			int[] w = this.clip.cellWords(x, z);
			this.vs[0]++;
			if ((w[0] & LodClip.GEOM_VALID) == 0) {
				this.vs[1]++;
				continue;
			}
			boolean wet = s.water()[k] != LodTile.DRY && s.water()[k] > s.height()[k];
			int surface = wet ? s.water()[k] : s.height()[k];
			boolean realCrown = s.crownLo()[k] > surface;
			boolean lodWet = (w[0] & LodClip.GEOM_WET) != 0;
			// (a frozen lake's top is ice in the real chunk: water all the same)
			BlockState realTopState = LodPalette.stateOf(s.tex()[k] & 1023);
			boolean realWater = wet || realTopState != null && realTopState.is(net.minecraft.world.level.block.Blocks.ICE);
			this.wetStats[(realWater ? 1 : 0) + (lodWet ? 2 : 0)]++;
			boolean lodCrown = (w[0] & LodClip.GEOM_CROWN) != 0;
			int lodTop = (w[0] & 0xFFF) - 512;
			if (realCrown == lodCrown) this.vs[4]++;
			if (realCrown && lodCrown) {
				this.vs[5]++;
				int lodGround = (w[2] & 0xFFF) - 512, thick = (w[0] >>> 15) & 63;
				if (lodGround == surface) this.vs[2]++;
				else if (Math.abs(lodGround - surface) <= 1) this.vs[3]++;
				if (lodTop == s.crownHi()[k] && lodTop - thick == s.crownLo()[k] && w[3] == s.runs()[k] && lodGround == surface) this.vs[6]++;
			} else if (!realCrown && !lodCrown) {
				int real = Math.max(surface, s.crownHi()[k]);
				if (lodTop == real) this.vs[2]++;
				else if (Math.abs(lodTop - real) <= 1) this.vs[3]++;
				// open ground (no tree in either): how far off, and what is there
				BlockState realTop = LodPalette.stateOf(s.tex()[k] & 1023), lodTopState = LodPalette.stateOf(w[4] & 1023);
				boolean tree = realTop != null && (realTop.is(net.minecraft.tags.BlockTags.LEAVES) || realTop.is(net.minecraft.tags.BlockTags.LOGS))
					|| lodTopState != null && (lodTopState.is(net.minecraft.tags.BlockTags.LEAVES) || lodTopState.is(net.minecraft.tags.BlockTags.LOGS));
				if (!tree && !wet) {
					int dy = real - lodTop;
					this.dyHist[Math.clamp(dy + 8, 0, 16)]++;
					if (Math.abs(dy) > 1 && this.groundLogged < 30) {
						this.groundLogged++;
						System.out.printf("mcopt-lod: verify: ground at %d,%d: real %d (%s), far terrain %d (%s)%n", x, z, real, realTop, lodTop, lodTopState);
					}
				}
			}
			if (realCrown != lodCrown && this.verifyLogged < 12) {
				this.verifyLogged++;
				System.out.printf("mcopt-lod: verify: crown mismatch at %d,%d: real %s (lo %d hi %d ground %d), far terrain %s (top %d thick %d)%n", x, z,
					realCrown ? "crown" : "no crown", s.crownLo()[k], s.crownHi()[k], surface, lodCrown ? "crown" : "no crown", lodTop, (w[0] >>> 15) & 63);
			}
			int realPlant = s.plantA()[k] & 1023, lodPlant = w[5] & 1023;
			if (realPlant != 0 == (lodPlant != 0)) this.vs[7]++;
			if (realPlant != 0 && lodPlant != 0) {
				this.vs[8]++;
				if (realPlant == lodPlant) this.vs[9]++;
			}
			if (!realCrown && !lodCrown && !wet && LodConfig.TEXTURES) {
				this.vs[13]++;
				if (sameSprite(s.tex()[k] & 1023, w[4] & 1023, true)) this.vs[10]++;
				if (sameSprite(s.tex()[k] >> 10 & 1023, w[4] >> 10 & 1023, false)) this.vs[11]++;
				int a = LodClip.rgb565(s.top()[k]), b = w[1] & 0xFFFF;
				if (Math.abs((a >> 11) - (b >> 11)) <= 2 && Math.abs((a >> 5 & 63) - (b >> 5 & 63)) <= 4 && Math.abs((a & 31) - (b & 31)) <= 2) this.vs[12]++;
			}
		}
		// per chunk: columns whose surface or crown disagree (clusters: structures, lakes, cascades of trees)
		int groundMiss = (int) (this.vs[0] - this.vs[1] - this.vs[2] - groundBefore), crownMiss = (int) (this.vs[0] - this.vs[1] - this.vs[4] - crownBefore);
		if (groundMiss + crownMiss > 0) this.chunkMiss.put(key, groundMiss << 16 | crownMiss);
		if (groundMiss >= 192 && this.chunkLogged < 16) {
			// a whole chunk off: what is there (its middle column and a corner)
			this.chunkLogged++;
			StringBuilder b = new StringBuilder();
			for (int k : new int[] {8 * 16 + 8, 0}) {
				int[] w = this.clip.cellWords(bx + (k & 15), bz + (k >> 4));
				b.append(String.format(" [%d,%d real h %d water %d top %s under-tex %s | far terrain y %d%s top %s]", bx + (k & 15), bz + (k >> 4), s.height()[k],
					s.water()[k] == LodTile.DRY ? -1 : s.water()[k], LodPalette.stateOf(s.tex()[k] & 1023), LodPalette.stateOf(s.tex()[k] >> 20 & 1023),
					(w[0] & 0xFFF) - 512, (w[0] & LodClip.GEOM_WET) != 0 ? " wet" : "", LodPalette.stateOf(w[4] & 1023)));
			}
			System.out.println("mcopt-lod: verify: chunk off" + b);
		}
		if (this.verified.size() % 64 == 0) {
			long n = this.vs[0] - this.vs[1], dry = Math.max(1, this.vs[13]);
			System.out.printf("mcopt-lod: verify: %d chunks, %d columns (%d without data): ground exact %.2f%% (+-1 %.2f%%), crowns agree %.2f%% (%d both, exact %.2f%%), "
					+ "plants agree %.2f%% (%d both, same block %.2f%%); dry open ground: top block %.2f%%, side block %.2f%%, top color %.2f%%%n",
				this.verified.size(), this.vs[0], this.vs[1], 100.0 * this.vs[2] / Math.max(1, n), 100.0 * (this.vs[2] + this.vs[3]) / Math.max(1, n),
				100.0 * this.vs[4] / Math.max(1, n), this.vs[5], 100.0 * this.vs[6] / Math.max(1, this.vs[5]), 100.0 * this.vs[7] / Math.max(1, n), this.vs[8],
				100.0 * this.vs[9] / Math.max(1, this.vs[8]), 100.0 * this.vs[10] / dry, 100.0 * this.vs[11] / dry, 100.0 * this.vs[12] / dry);
			System.out.println("mcopt-lod: verify: open ground real - far terrain y, -8..8: " + java.util.Arrays.toString(this.dyHist));
			System.out.println("mcopt-lod: verify: water (dry both, real only, far terrain only, both): " + java.util.Arrays.toString(this.wetStats));
			int[] buckets = new int[6];   // chunks by surface misses: 0, 1-15, 16-63, 64-127, 128-191, 192+
			int[] crownBuckets = new int[6];
			for (long v : this.verified) {
				int m = this.chunkMiss.getOrDefault(v, 0), gm = m >>> 16, cm = m & 0xFFFF;
				buckets[gm == 0 ? 0 : gm < 16 ? 1 : gm < 64 ? 2 : gm < 128 ? 3 : gm < 192 ? 4 : 5]++;
				crownBuckets[cm == 0 ? 0 : cm < 16 ? 1 : cm < 64 ? 2 : cm < 128 ? 3 : cm < 192 ? 4 : 5]++;
			}
			System.out.println("mcopt-lod: verify: chunks by columns off (0, 1-15, 16-63, 64-127, 128-191, 192+): surface " + java.util.Arrays.toString(buckets)
				+ ", crowns " + java.util.Arrays.toString(crownBuckets));
			StringBuilder worst = new StringBuilder();
			this.chunkMiss.entrySet().stream().sorted((a, b) -> Integer.compare(b.getValue() >>> 16, a.getValue() >>> 16)).limit(12)
				.forEach(e -> worst.append(' ').append((int) (e.getKey() >> 32) * 16).append(',').append((int) (long) e.getKey() * 16).append(':')
					.append(e.getValue() >>> 16).append('/').append(e.getValue() & 0xFFFF));
			System.out.println("mcopt-lod: verify: worst chunks (block x,z: surface/crown columns off):" + worst);
		}
	}

	/** Whether two palette numbers draw the same sprite on that face (a snow layer's top is a snow block's). */
	private static boolean sameSprite(int a, int b, boolean top) {
		if (a == b) return true;
		BlockState sa = LodPalette.stateOf(a), sb = LodPalette.stateOf(b);
		if (sa == null || sb == null) return false;
		float[] ua = top ? LodColors.look(sa).topUv() : LodColors.look(sa).sideUv(), ub = top ? LodColors.look(sb).topUv() : LodColors.look(sb).sideUv();
		return java.util.Arrays.equals(ua, ub);
	}

	/** -Dmcopt.lod.saveSeconds=S: how often tiles real chunks changed go back to the disk cache (default 5). */
	private static final long SAVE_NANOS = (long) (Double.parseDouble(System.getProperty("mcopt.lod.saveSeconds", "5")) * 1e9);
	private long lastSave = System.nanoTime();
	/**
	 * -Dmcopt.lod.saveFrameMs: the render thread's time a frame for a save round's read-backs (0.5 ms). All in one frame: ~350 after a join at
	 * RD 32, 20-40 us each, a 7-14 ms frame (1-3 ms every 5 s flying). Tiles stay in dirtyTiles till read back: beforeRecenter still saves them.
	 */
	private static final long SAVE_FRAME_NANOS = (long) (Double.parseDouble(System.getProperty("mcopt.lod.saveFrameMs", "0.5")) * 1e6);
	/** The save round under way: the tiles dirty when it came due, read back over the next frames (once each, with their newest words). */
	private final java.util.ArrayDeque<Long> saveRound = new java.util.ArrayDeque<>();

	/** Every few seconds: tiles real chunks changed go back to the disk cache (read back from the clipmap, saved on a worker). */
	int saveDirty(boolean all) {
		if (!LodConfig.DISK_CACHE) return 0;
		int n = 0;
		if (all) {
			this.saveRound.clear();
			if (this.dirtyTiles.isEmpty() && this.patches.isEmpty()) return 0;
			n += this.flushPatches(true);
			for (long key : this.dirtyTiles) n += this.saveTile(key, true) ? 1 : 0;
			this.dirtyTiles.clear();
			return n;
		}
		long now = System.nanoTime();
		if (this.saveRound.isEmpty()) {
			if (this.dirtyTiles.isEmpty() && this.patches.isEmpty() || now - this.lastSave < SAVE_NANOS) return 0;
			this.lastSave = now;
			n += this.flushPatches(false);
			this.saveRound.addAll(this.dirtyTiles);
		}
		while (!this.saveRound.isEmpty()) {
			long key = this.saveRound.poll();
			// (saved since: beforeRecenter saves the tiles leaving their window)
			if (!this.dirtyTiles.remove(key)) continue;
			n += this.saveTile(key, false) ? 1 : 0;
			if (System.nanoTime() - now > SAVE_FRAME_NANOS) break;
		}
		return n;
	}

	/** A resident tile's words to the disk cache: read back now, written now (sync) or on a worker. */
	private boolean saveTile(long key, boolean sync) {
		int level = LodTile.levelOf(key), tx = LodTile.txOf(key), tz = LodTile.tzOf(key);
		if (!this.clip.resident(level, tx, tz)) return false;
		int[] g = new int[LodTile.CELLS], c = new int[LodTile.CELLS];
		int[] cr = level < this.clip.crownLevels ? new int[LodTile.CELLS] : null;
		int[] tw = level == 0 && LodConfig.TEXTURES ? new int[LodTile.CELLS] : null;
		int[] rn = cr != null ? new int[LodTile.CELLS] : null;
		int[] pl = level == 0 && this.clip.plants ? new int[2 * LodTile.CELLS] : null;
		// (the newest words: real chunks' cells may still be staged, or published but not yet copied by the GPU)
		this.clip.readForSave(level, tx, tz, g, c, cr, tw, rn, pl);
		if (sync) {
			this.saveQueued.remove(key);
			this.save(key, g, c, cr, tw, rn, pl);
			return true;
		}
		long mine = this.seq.incrementAndGet();
		this.saveQueued.put(key, mine);
		this.jobs.add(new Job(SAVE_PRIORITY, mine, SAVE_KEY, () -> {
			synchronized (this.lock(key)) {
				try {
					// (a newer save of the tile was scheduled since: it has newer words; two workers could write them in either order)
					if (this.saveQueued.getOrDefault(key, mine) != mine) return;
					this.save0(key, g, c, cr, tw, rn, pl);
				} finally {
					// (the last queued save of the tile lets a patch that waited for it go)
					if (this.saveQueued.remove(key, mine)) this.requeuePatch(key);
				}
			}
		}));
		return true;
	}

	/** Per tile with a save from memory still queued: the newest one's number (only it writes). */
	private final ConcurrentHashMap<Long, Long> saveQueued = new ConcurrentHashMap<>();

	/**
	 * Before the windows move to (cx, cz): dirty tiles leaving their window are saved first (recenter clears their slots, the only copy of
	 * what real chunks wrote), and level 0's waiting chunks outside its new window dropped. A coarser window only moves when level 0's does.
	 */
	private void beforeRecenter(double cx, double cz) {
		this.camX = cx;
		this.camZ = cz;
		int h = this.clip.tilesPerSide / 2, tps = this.clip.tilesPerSide;
		int w0x = Math.floorDiv((int) Math.floor(cx), this.clip.span(0)) - h, w0z = Math.floorDiv((int) Math.floor(cz), this.clip.span(0)) - h;
		if (w0x == this.clip.winTx[0] && w0z == this.clip.winTz[0]) return;
		if (LodConfig.DISK_CACHE && !this.dirtyTiles.isEmpty()) {
			for (var it = this.dirtyTiles.iterator(); it.hasNext();) {
				long key = it.next();
				int l = LodTile.levelOf(key), span = this.clip.span(l);
				int x = LodTile.txOf(key) - (Math.floorDiv((int) Math.floor(cx), span) - h), z = LodTile.tzOf(key) - (Math.floorDiv((int) Math.floor(cz), span) - h);
				if (x >= 0 && z >= 0 && x < tps && z < tps) continue;
				this.saveTile(key, false);
				it.remove();
			}
		}
		if (!this.waiting.isEmpty()) {
			this.waiting.keySet().removeIf(k -> {
				int x = LodTile.txOf(k) - w0x, z = LodTile.tzOf(k) - w0z;
				return x < 0 || z < 0 || x >= tps || z >= tps;
			});
		}
	}

	// ---- render thread ----

	long frame;
	private boolean dirty = true;
	private double reqX = Double.NaN, reqZ;

	/** Takes the workers' results (bounded per frame so a burst can't stall one). */
	void integrate() {
		long deadline = System.nanoTime() + 1_500_000;
		Runnable r;
		while ((r = this.results.poll()) != null) {
			r.run();
			if (System.nanoTime() > deadline) break;
		}
	}

	/**
	 * Moves the windows with the camera and queues what they lack. The scan over every level's window runs when the camera
	 * moved a few blocks or tiles arrived.
	 */
	void update(double cx, double cz, FrustumIntersection frustum, double camY) {
		this.beforeRecenter(cx, cz);
		boolean moved = this.clip.recenter(cx, cz);
		for (int l = 0; l < this.clip.levels; l++) {
			this.winTx[l] = this.clip.winTx[l];
			this.winTz[l] = this.clip.winTz[l];
		}
		if (!moved && !this.dirty && Math.abs(cx - this.reqX) < 16 && Math.abs(cz - this.reqZ) < 16) return;
		this.dirty = false;
		this.missingKeys.clear();
		this.reqX = cx;
		this.reqZ = cz;
		double reach = LodConfig.reachBlocks();
		int needed = 0, missing = 0;
		int top = this.clip.levels - 1;
		for (int l = 0; l <= top; l++) {
			int span = this.clip.span(l);
			// the ring this level draws: from the finer level's switch distance (less a tile) to its own (plus a tile)
			double inner = l == 0 || l == top ? -1 : this.clip.switchDist[l - 1] - span;
			double outer = Math.min(this.clip.switchDist[l] + span, reach + span);
			for (int z = 0; z < this.clip.tilesPerSide; z++) {
				for (int x = 0; x < this.clip.tilesPerSide; x++) {
					int tx = this.clip.winTx[l] + x, tz = this.clip.winTz[l] + z;
					double x0 = (double) tx * span, z0 = (double) tz * span;
					double dx = Math.max(0, Math.max(x0 - cx, cx - (x0 + span))), dz = Math.max(0, Math.max(z0 - cz, cz - (z0 + span)));
					double near = Math.sqrt(dx * dx + dz * dz);
					if (near > outer) continue;
					// entirely inside the inner radius: the finer level covers it
					double fx = Math.max(Math.abs(x0 - cx), Math.abs(x0 + span - cx)), fz = Math.max(Math.abs(z0 - cz), Math.abs(z0 + span - cz));
					if (Math.sqrt(fx * fx + fz * fz) < inner) continue;
					needed++;
					if (this.clip.resident(l, tx, tz)) continue;
					missing++;
					long key = LodTile.key(l, tx, tz);
					this.missingKeys.add(key);
					if (this.pending.putIfAbsent(key, Boolean.TRUE) != null) continue;
					double priority = near / span;
					if (l == top) priority -= 4;  // the fallback for everything comes first
					if (frustum != null && !frustum.testAab((float) (x0 - cx), (float) (-64 - camY), (float) (z0 - cz), (float) (x0 + span - cx),
						(float) (320 - camY), (float) (z0 + span - cz))) priority = priority * 3 + 2;
					this.jobs.add(new Job(priority, this.seq.incrementAndGet(), key, null));
				}
			}
		}
		this.needed = needed;
		this.missing = missing;
		if (missing == 0 && !this.settled) {
			this.settled = true;
			this.settledNanos = System.nanoTime();
			if (this.firstSettledNanos == 0) this.firstSettledNanos = this.settledNanos;
		}
		if (missing > 0) this.settled = false;
	}

	/**
	 * -Dmcopt.lod.prio=screen: the queue in the order the screen needs: the coarsest level's tiles in view first (the fallback
	 * under everything), then tiles in view by projected area (largest first), then tiles that come into view within a second
	 * at the camera's current velocity, then the rest by distance; level-0 tiles the real terrain covers whole last. The
	 * queue is sorted again when the camera turns (10 degrees), moves (16 blocks) or every half second while tiles are missing.
	 */
	static final boolean PRIO_SCREEN = "screen".equals(System.getProperty("mcopt.lod.prio", ""));
	private final org.joml.Vector3f scanFwd = new org.joml.Vector3f();
	private double velX, velZ, lastX = Double.NaN, lastZ;
	private long lastT, scanT;

	/** The screen-ordered scan's thread (PRIO_SCREEN): the render thread only hands it a snapshot and takes its result. */
	private final java.util.concurrent.ExecutorService scanner = PRIO_SCREEN ? java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "mcopt-lod-scan");
		t.setDaemon(true);
		t.setPriority(Thread.NORM_PRIORITY - 1);
		return t;
	}) : null;
	private final java.util.concurrent.atomic.AtomicBoolean scanning = new java.util.concurrent.atomic.AtomicBoolean();
	/** The real terrain's reach (render distance x 16 blocks), set by Lod every frame: level-0 tiles wholly inside it are real chunks' to fill. */
	volatile double rdBlocks;
	/** Scan thread only: when each missing tile was first seen missing in view (its priority grows with the wait). */
	private final java.util.HashMap<Long, Long> firstSeen = new java.util.HashMap<>();
	private volatile ScanResult scanResult;

	private record ScanResult(int needed, int missing, java.util.HashSet<Long> missingKeys) {
	}

	/**
	 * As update, ordering the queue by what the screen needs (PRIO_SCREEN). The render thread moves the windows and decides
	 * when to scan; the scan itself (every level's window, projected areas, the queue re-sorted) runs on its own thread from
	 * a snapshot of the camera and the mask, and its result (what is missing) is taken over at a later frame.
	 */
	void updateScreen(double cx, double cz, FrustumIntersection frustum, double camY, org.joml.Matrix4f viewProj, org.joml.Vector3f fwd,
		java.util.function.Supplier<LodSeam.ChunkMask> maskSnapshot) {
		long now = System.nanoTime();
		if (!Double.isNaN(this.lastX) && now > this.lastT) {
			double dt = (now - this.lastT) / 1e9, a = Math.min(1, dt / 0.25);
			this.velX += ((cx - this.lastX) / dt - this.velX) * a;
			this.velZ += ((cz - this.lastZ) / dt - this.velZ) * a;
		}
		this.lastX = cx;
		this.lastZ = cz;
		this.lastT = now;
		this.beforeRecenter(cx, cz);
		boolean moved = this.clip.recenter(cx, cz);
		for (int l = 0; l < this.clip.levels; l++) {
			this.winTx[l] = this.clip.winTx[l];
			this.winTz[l] = this.clip.winTz[l];
		}
		ScanResult r = this.scanResult;
		if (r != null) {
			this.scanResult = null;
			r.missingKeys().removeIf(k -> this.clip.resident(LodTile.levelOf(k), LodTile.txOf(k), LodTile.tzOf(k)));
			this.missingKeys.clear();
			this.missingKeys.addAll(r.missingKeys());
			this.needed = r.needed();
			this.missing = this.missingKeys.size();
			if (this.missing == 0 && !this.settled) {
				this.settled = true;
				this.settledNanos = System.nanoTime();
				if (this.firstSettledNanos == 0) this.firstSettledNanos = this.settledNanos;
			}
			if (this.missing > 0) this.settled = false;
		}
		boolean turned = fwd.dot(this.scanFwd) < Math.cos(Math.toRadians(10));
		boolean stale = this.missing > 0 && now - this.scanT > 500_000_000L;
		if (!moved && !this.dirty && !turned && !stale && Math.abs(cx - this.reqX) < 16 && Math.abs(cz - this.reqZ) < 16) return;
		if (!this.scanning.compareAndSet(false, true)) return;
		this.dirty = false;
		this.scanFwd.set(fwd);
		this.scanT = now;
		this.reqX = cx;
		this.reqZ = cz;
		int[] wx = this.winTx.clone(), wz = this.winTz.clone();
		org.joml.Matrix4f vp = new org.joml.Matrix4f(viewProj);
		LodSeam.ChunkMask masked = maskSnapshot.get();
		double px = cx + this.velX, pz = cz + this.velZ;
		this.scanner.execute(() -> {
			try {
				this.scanResult = this.scanScreen(cx, cz, camY, px, pz, vp, wx, wz, masked);
			} catch (RuntimeException e) {
				System.out.println("mcopt-lod: scan failed: " + e);
			} finally {
				this.scanning.set(false);
			}
		});
	}

	private ScanResult scanScreen(double cx, double cz, double camY, double px, double pz, org.joml.Matrix4f viewProj, int[] winTx, int[] winTz,
		LodSeam.ChunkMask masked) {
		FrustumIntersection frustum = new FrustumIntersection(viewProj, false);
		double reach = LodConfig.reachBlocks();
		int needed = 0, missing = 0;
		int top = this.clip.levels - 1;
		java.util.HashSet<Long> keys = new java.util.HashSet<>();
		long now = System.nanoTime();
		java.util.HashMap<Long, Double> again = new java.util.HashMap<>();
		org.joml.Vector4f v = new org.joml.Vector4f();
		// (px, pz: where the camera is a second from now)
		boolean fast = Math.hypot(px - cx, pz - cz) > 8;
		for (int l = 0; l <= top; l++) {
			int span = this.clip.span(l);
			double inner = l == 0 || l == top ? -1 : this.clip.switchDist[l - 1] - span;
			double outer = Math.min(this.clip.switchDist[l] + span, reach + span);
			for (int z = 0; z < this.clip.tilesPerSide; z++) {
				for (int x = 0; x < this.clip.tilesPerSide; x++) {
					int tx = winTx[l] + x, tz = winTz[l] + z;
					double x0 = (double) tx * span, z0 = (double) tz * span;
					double dx = Math.max(0, Math.max(x0 - cx, cx - (x0 + span))), dz = Math.max(0, Math.max(z0 - cz, cz - (z0 + span)));
					double near = Math.sqrt(dx * dx + dz * dz);
					if (near > outer) continue;
					double fx = Math.max(Math.abs(x0 - cx), Math.abs(x0 + span - cx)), fz = Math.max(Math.abs(z0 - cz), Math.abs(z0 + span - cz));
					if (Math.sqrt(fx * fx + fz * fz) < inner) continue;
					needed++;
					if (this.clip.resident(l, tx, tz)) continue;
					missing++;
					long key = LodTile.key(l, tx, tz);
					keys.add(key);
					double priority;
					if (l == 0 && allMasked(tx, tz, masked)) {
						priority = 1000 + near / span;
					} else if (l == 0 && fast && Math.sqrt(fx * fx + fz * fz) < this.rdBlocks - 16) {
						// moving fast, wholly inside the render distance: real chunks will overwrite it (until then level 1 stands in;
						// a camera at rest gets these first: they're what it sees while its real chunks load)
						priority = 900 + near / span;
					} else if (frustum.testAab((float) (x0 - cx), (float) (-64 - camY), (float) (z0 - cz), (float) (x0 + span - cx), (float) (320 - camY),
						(float) (z0 + span - cz))) {
						// in view: the larger first, and a tile that has waited a second counts as twice as large (none starves)
						double waited = (now - this.firstSeen.computeIfAbsent(key, k -> now)) / 1e9;
						priority = (l == top ? -100 : 0) - log2(area(viewProj, x0 - cx, 40 - camY, z0 - cz, span, 120, v)) - waited;
					} else if (frustum.testAab((float) (x0 - px), (float) (-64 - camY), (float) (z0 - pz), (float) (x0 + span - px), (float) (320 - camY),
						(float) (z0 + span - pz))) {
						priority = (l == top ? -50 : 30) - log2(area(viewProj, x0 - px, 40 - camY, z0 - pz, span, 120, v));
					} else {
						// the coarsest level (the fallback under everything) before any finer tile out of view
						priority = (l == top ? 50 : 100) + near / span;
					}
					if (this.pending.putIfAbsent(key, Boolean.TRUE) != null) {
						again.put(key, priority);
						continue;
					}
					this.jobs.add(new Job(priority, this.seq.incrementAndGet(), key, null));
				}
			}
		}
		if (!again.isEmpty()) {
			// queued generation jobs take their new priority (jobs a worker took meanwhile are simply gone from the drain)
			java.util.ArrayList<Job> all = new java.util.ArrayList<>();
			this.jobs.drainTo(all);
			for (Job j : all) {
				Double p = j.task == null ? again.get(j.key) : null;
				this.jobs.add(p == null ? j : new Job(p, j.seq, j.key, null));
			}
		}
		this.firstSeen.keySet().retainAll(keys);
		return new ScanResult(needed, missing, keys);
	}

	private static double log2(double a) {
		return Math.log(Math.max(1, a)) / Math.log(2);
	}

	private static boolean allMasked(int tx, int tz, LodSeam.ChunkMask masked) {
		for (int z = 0; z < 4; z++) {
			for (int x = 0; x < 4; x++) {
				if (!masked.masked(tx * 4 + x, tz * 4 + z)) return false;
			}
		}
		return true;
	}

	/** Screen pixels of a box's projected bounds (clipped to the screen; corners behind the camera clamp to its plane). */
	static double area(org.joml.Matrix4f m, double x0, double y0, double z0, double span, double height, org.joml.Vector4f v) {
		float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
		for (int i = 0; i < 8; i++) {
			v.set((float) (x0 + ((i & 1) != 0 ? span : 0)), (float) (y0 + ((i & 2) != 0 ? height : 0)), (float) (z0 + ((i & 4) != 0 ? span : 0)), 1);
			m.transform(v);
			float w = Math.max(v.w, 1e-2F);
			float nx = v.x / w, ny = v.y / w;
			minX = Math.min(minX, nx);
			maxX = Math.max(maxX, nx);
			minY = Math.min(minY, ny);
			maxY = Math.max(maxY, ny);
		}
		double ax = Math.max(0, Math.min(1, maxX) - Math.max(-1, minX)), ay = Math.max(0, Math.min(1, maxY) - Math.max(-1, minY));
		return ax * ay * 0.25 * 3456 * 2234;
	}

	/** Tiles the last scan found missing; arrivals tick them off (settled when none is left), only a move scans again. */
	private final java.util.HashSet<Long> missingKeys = new java.util.HashSet<>();

	private void arrived(long key) {
		if (this.missingKeys.remove(key)) {
			this.missing = this.missingKeys.size();
			if (this.missing == 0 && !this.settled) {
				this.settled = true;
				this.settledNanos = System.nanoTime();
				if (this.firstSettledNanos == 0) this.firstSettledNanos = this.settledNanos;
			}
		}
	}

	int pendingJobs() {
		return this.pending.size();
	}

	int queued() {
		return this.jobs.size();
	}
}

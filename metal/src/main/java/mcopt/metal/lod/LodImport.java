package mcopt.metal.lod;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.ticks.ProtoChunkTicks;

/**
 * Singleplayer: the dimension's saved chunks (its region files) become far terrain, as the chunks the client loads do. The
 * places explored before far terrain was on (or while it was off) then show as they are, builds included, instead of as
 * generated. -Dmcopt.lod.import=false turns it off.
 *
 * One low-priority thread, once the field has settled (what the screen needs comes first): regions nearest the camera
 * first, their chunks within the reach, read through the server's own chunk storage (its IO worker: a chunk being saved is
 * read as saved), brought up to this version's format, and parsed into a chunk of our own without touching the server's
 * state. A region whose chunks were all taken is noted in the cache (import.txt, with its file's time) and only read again
 * once that file has changed. It waits while the field has results to take or saves to make, and while the server lacks
 * chunks around the player (LodYield's pressure).
 */
final class LodImport implements Runnable {
	static final boolean ON = Boolean.parseBoolean(System.getProperty("mcopt.lod.import", "true"));

	private final LodField field;
	private final ServerLevel level;
	private final Path regions, done;
	private volatile boolean stopped;
	private final Thread thread;
	final AtomicLong chunks = new AtomicLong(), regionsDone = new AtomicLong(), failed = new AtomicLong();

	LodImport(LodField field, ServerLevel level, Path worldRoot, Path cache) {
		this.field = field;
		this.level = level;
		this.regions = net.minecraft.world.level.dimension.DimensionType.getStorageFolder(level.dimension(), worldRoot).resolve("region");
		this.done = cache.resolve("import.txt");
		this.thread = new Thread(this, "mcopt-lod-import");
		this.thread.setDaemon(true);
		this.thread.setPriority(Thread.MIN_PRIORITY);
		this.thread.start();
	}

	void stop() {
		this.stopped = true;
		this.thread.interrupt();
	}

	@Override
	public void run() {
		try {
			// what the screen needs first: the field settled, or a minute
			long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
			while (!this.stopped && !this.field.settled && System.nanoTime() < until) Thread.sleep(500);
			if (this.stopped || !Files.isDirectory(this.regions)) return;
			Map<String, Long> done = this.readDone();
			List<Path> files = new ArrayList<>();
			try (var s = Files.list(this.regions)) {
				s.filter(p -> p.getFileName().toString().matches("r\\.-?\\d+\\.-?\\d+\\.mca")).forEach(files::add);
			}
			double cx = this.field.camX, cz = this.field.camZ;
			files.sort(java.util.Comparator.comparingDouble(p -> regionDistance(p, cx, cz)));
			double reach = LodConfig.reachBlocks();
			int taken = 0;
			for (Path f : files) {
				if (this.stopped) return;
				String name = f.getFileName().toString();
				long mtime = Files.getLastModifiedTime(f).toMillis();
				Long seen = done.get(name);
				if (seen != null && seen == mtime) continue;
				if (regionDistance(f, this.field.camX, this.field.camZ) > reach) continue;
				if (this.region(f)) {
					done.put(name, mtime);
					this.regionsDone.incrementAndGet();
					if (++taken % 8 == 0) this.writeDone(done);
				}
			}
			this.writeDone(done);
			if (this.chunks.get() > 0) {
				System.out.println(String.format("mcopt-lod: imported %d saved chunks from %d regions of %s (%d unreadable)%n", this.chunks.get(), this.regionsDone.get(),
					this.level.dimension().identifier(), this.failed.get()).stripTrailing());
			}
		} catch (InterruptedException e) {
			// closing
		} catch (IOException | RuntimeException e) {
			System.out.println("mcopt-lod: import stopped: " + e);
		}
	}

	/** A region's chunks, nearest the camera first; true when every one was taken (none was past the reach). */
	private boolean region(Path file) throws IOException, InterruptedException {
		String[] p = file.getFileName().toString().split("\\.");
		int rx = Integer.parseInt(p[1]), rz = Integer.parseInt(p[2]);
		byte[] header = new byte[4096];
		try (InputStream in = Files.newInputStream(file)) {
			if (in.readNBytes(header, 0, 4096) < 4096) return true;
		}
		List<ChunkPos> present = new ArrayList<>();
		for (int i = 0; i < 1024; i++) {
			int off = (header[i * 4] & 255) << 24 | (header[i * 4 + 1] & 255) << 16 | (header[i * 4 + 2] & 255) << 8 | (header[i * 4 + 3] & 255);
			if (off != 0) present.add(new ChunkPos(rx * 32 + (i & 31), rz * 32 + (i >> 5)));
		}
		double cx = this.field.camX, cz = this.field.camZ, reach = LodConfig.reachBlocks();
		present.sort(java.util.Comparator.comparingDouble(c -> Math.hypot(c.getMiddleBlockX() - cx, c.getMiddleBlockZ() - cz)));
		boolean all = true;
		ChunkMap map = this.level.getChunkSource().chunkMap;
		CompoundTag context = ChunkMap.getChunkDataFixContextTag(this.level.dimension(), this.level.getChunkSource().getGenerator().getTypeNameForDataFixer());
		int version = SharedConstants.getCurrentVersion().dataVersion().version();
		for (ChunkPos pos : present) {
			if (this.stopped) return false;
			if (Math.hypot(pos.getMiddleBlockX() - this.field.camX, pos.getMiddleBlockZ() - this.field.camZ) > reach) {
				all = false;
				continue;
			}
			this.pace();
			try {
				CompoundTag tag = map.read(pos).get(30, TimeUnit.SECONDS).orElse(null);
				if (tag == null) continue;
				tag = map.upgradeChunkTag(tag, -1, context, version);
				if (!SerializableChunkData.getChunkStatusFromTag(tag).isOrAfter(ChunkStatus.FULL)) continue;
				ProtoChunk chunk = this.chunk(SerializableChunkData.parse(this.level, this.level.palettedContainerFactory(), tag));
				if (chunk == null) continue;
				LodChunks.Summary s = LodChunks.summarize(LodChunks.snapshot(chunk, this.field.roof));
				this.field.imported(s);
				this.chunks.incrementAndGet();
			} catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException | RuntimeException e) {
				if (this.failed.getAndIncrement() < 4) System.out.println("mcopt-lod: import: chunk " + pos + " unreadable: " + e);
			}
		}
		return all;
	}

	/** The saved chunk's sections as a chunk of our own (the server's never sees it), with its saved surface heights. */
	private @org.jspecify.annotations.Nullable ProtoChunk chunk(SerializableChunkData data) {
		var factory = this.level.palettedContainerFactory();
		LevelChunkSection[] sections = new LevelChunkSection[this.level.getSectionsCount()];
		for (SerializableChunkData.SectionData sd : data.sectionData()) {
			int i = this.level.getSectionIndexFromSectionY(sd.y());
			if (i >= 0 && i < sections.length && sd.chunkSection() != null) sections[i] = sd.chunkSection();
		}
		for (int i = 0; i < sections.length; i++) if (sections[i] == null) sections[i] = new LevelChunkSection(factory);
		ProtoChunk chunk = new ProtoChunk(data.chunkPos(), UpgradeData.EMPTY, sections, new ProtoChunkTicks<>(), new ProtoChunkTicks<>(), this.level, factory, null);
		long[] surface = data.heightmaps().get(Heightmap.Types.WORLD_SURFACE);
		if (surface != null) chunk.setHeightmap(Heightmap.Types.WORLD_SURFACE, surface);
		else Heightmap.primeHeightmaps(chunk, java.util.EnumSet.of(Heightmap.Types.WORLD_SURFACE));
		return chunk;
	}

	/** Waits while the field is busy (results to take, saves and patches queued) or the server lacks chunks near the player. */
	private void pace() throws InterruptedException {
		while (!this.stopped && (this.field.resultsWaiting() > 256 || this.field.queued() > 64 || LodYield.importsWait())) Thread.sleep(20);
	}

	private static double regionDistance(Path f, double cx, double cz) {
		String[] p = f.getFileName().toString().split("\\.");
		double x0 = Integer.parseInt(p[1]) * 512.0, z0 = Integer.parseInt(p[2]) * 512.0;
		double dx = Math.max(0, Math.max(x0 - cx, cx - (x0 + 512))), dz = Math.max(0, Math.max(z0 - cz, cz - (z0 + 512)));
		return Math.hypot(dx, dz);
	}

	private Map<String, Long> readDone() {
		Map<String, Long> m = new HashMap<>();
		try {
			if (Files.isRegularFile(this.done)) {
				for (String line : Files.readAllLines(this.done, StandardCharsets.UTF_8)) {
					String[] p = line.strip().split(" ");
					if (p.length == 2) m.put(p[0], Long.parseLong(p[1]));
				}
			}
		} catch (IOException | NumberFormatException e) {
			System.out.println("mcopt-lod: import: can't read " + this.done + ": " + e);
		}
		return m;
	}

	private void writeDone(Map<String, Long> done) {
		StringBuilder sb = new StringBuilder();
		done.forEach((k, v) -> sb.append(k).append(' ').append(v).append('\n'));
		try {
			Files.createDirectories(this.done.getParent());
			Path tmp = this.done.resolveSibling("import.txt.tmp");
			Files.writeString(tmp, sb, StandardCharsets.UTF_8);
			Files.move(tmp, this.done, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			System.out.println("mcopt-lod: import: can't write " + this.done + ": " + e);
		}
	}
}

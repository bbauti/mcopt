package mcopt.metal.lod;

import java.util.Locale;

/**
 * The far terrain's quality presets (-Dmcopt.lod.quality): the defaults of its window, reach and detail, picked for the Mac
 * it runs on with auto. Every setting a preset sets can still be set on its own (LodConfig), and that wins.
 */
public enum LodQuality {
	/** GPUs under 10 cores (the A18 Pro's 5, M1-M3's 7-9): the small-GPU mode, a 512-cell window, 256 chunks, no plants. */
	LOW(true, 512, 256, false, 1),
	/** Between the two (never picked by auto): a 1024-cell window (level 0 to ~450 blocks), 384 chunks. */
	MEDIUM(false, 1024, 384, true, 1),
	/** 10-29 core GPUs (the measured default on the M4's 10): a 2048-cell window, 512 chunks. */
	HIGH(false, 2048, 512, true, 1),
	/** 30 cores or more (Max, Ultra): 1024 chunks, the game's own trees on level 1 too. */
	ULTRA(false, 2048, 1024, true, 2);

	final boolean small, plants;
	final int n, radiusChunks, treeLevels;

	LodQuality(boolean small, int n, int radiusChunks, boolean plants, int treeLevels) {
		this.small = small;
		this.n = n;
		this.radiusChunks = radiusChunks;
		this.plants = plants;
		this.treeLevels = treeLevels;
	}

	/** The preset a -Dmcopt.lod.quality value names; auto (or anything unknown) picks by the hardware. */
	static LodQuality resolve(String name) {
		String n = name.strip().toLowerCase(Locale.ROOT);
		for (LodQuality q : values()) {
			if (q.name().toLowerCase(Locale.ROOT).equals(n)) return q;
		}
		if (!n.equals("auto")) System.out.println("mcopt-lod: no quality preset named '" + name + "', using auto");
		LodQuality q = auto(mcopt.metal.Profile.gpuCores(), Runtime.getRuntime().maxMemory());
		System.out.println("mcopt-lod: quality auto: " + q.name().toLowerCase(Locale.ROOT) + " (gpu cores " + mcopt.metal.Profile.gpuCores() + ")");
		return q;
	}

	/** By the GPU's cores (-1: unknown), the far terrain's cost (its cull and quads); a heap under 3 GB (clipmap, cache) gets medium at most. */
	static LodQuality auto(int gpuCores, long maxHeap) {
		LodQuality q = gpuCores < 0 ? MEDIUM : gpuCores < 10 ? LOW : gpuCores < 30 ? HIGH : ULTRA;
		if (maxHeap > 0 && maxHeap < 3L << 30 && q.ordinal() > MEDIUM.ordinal()) q = MEDIUM;
		return q;
	}
}

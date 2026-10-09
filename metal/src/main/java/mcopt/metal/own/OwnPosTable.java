package mcopt.metal.own;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.lwjgl.system.MemoryUtil;

/**
 * -Dmcopt.own.mesh.exactPos=true (with -Dmcopt.own.compact): exact vertex positions in the 16-byte compact vertex. A coordinate on
 * the 1/2048-block grid in [-8, 22) keeps its grid code (c / 2048 - 8); any other coordinate (vanilla's snow-layer inner face at
 * 0.002 / 16, random block offsets of plants, fluid heights, ...) gets a code >= 61440 indexing a table of its exact float, kept in
 * the arena's first 256 units (4096 floats; the arena is bound wherever positions are decoded, and growth copies it along). The
 * table only grows (one entry per distinct coordinate, section-relative); a full table falls back to the nearest grid code
 * (counted, said once; within 1/4096 of a block, as before). It can't be larger: the 16-bit code has 4096 values above the grid's;
 * plant offsets and fluid heights fill it in seconds in most worlds. Every producer of compact positions encodes through here.
 */
final class OwnPosTable {
	static final boolean ON = Boolean.getBoolean("mcopt.own.mesh.exactPos") && Boolean.getBoolean("mcopt.own.compact");
	static final int BASE = 61440, SIZE = 4096, UNITS = SIZE * 4 / 64;
	private static final ConcurrentHashMap<Integer, Integer> CODES = new ConcurrentHashMap<>();
	private static final float[] VALUES = new float[SIZE];
	private static volatile int count;
	private static final AtomicLong OVERFLOW = new AtomicLong();
	private static long syncLogAt;

	private OwnPosTable() {
	}

	/** The compact code of section-relative coordinate p. */
	static int encode(float p) {
		int g = Math.max(0, Math.min(65535, Math.round((p + 8f) * 2048f)));
		if (g < BASE && g / 2048f - 8f == p) return g;
		Integer c = CODES.get(Float.floatToRawIntBits(p));
		if (c != null) return c;
		// a full table stays full: misses skip the lock (then ~1.8 million in 15 minutes from all meshing workers, each queued here
		// holding the arena's read lock, which a growth and the render thread behind it wait out)
		if (count >= SIZE) return overflow(g);
		synchronized (VALUES) {
			c = CODES.get(Float.floatToRawIntBits(p));
			if (c != null) return c;
			int i = count;
			if (i >= SIZE) return overflow(g);
			VALUES[i] = p;
			count = i + 1;  // (volatile: the value is visible before the count)
			CODES.put(Float.floatToRawIntBits(p), BASE + i);
			return BASE + i;
		}
	}

	private static int overflow(int g) {
		if (OVERFLOW.incrementAndGet() == 1) {
			System.out.println("mcopt-own mesh exactPos: table full (" + SIZE + " coordinates); further off-grid ones are rounded to the 1/2048-block"
				+ " grid (within 1/4096 of a block, as before exactPos)");
		}
		return Math.min(g, BASE - 1);
	}

	/** The coordinate of code c (CPU side, as the shader decodes it). */
	static float decode(int c) {
		return c >= BASE && ON ? VALUES[c - BASE] : c / 2048f - 8f;
	}

	/**
	 * Copies entries [from, count) into the arena at address a (its first UNITS units) and returns the new count; the render
	 * thread calls it after applying the frame's publish events (each event was queued after the entries its quads use).
	 */
	static int sync(long a, int from) {
		int n = count;
		if (n != from) {
			long now = System.nanoTime();
			if (now > syncLogAt) {
				syncLogAt = now + 5_000_000_000L;
				System.out.println("mcopt-own mesh exactPos: table " + n + " of " + SIZE + " entries, " + OVERFLOW.get() + " rounded");
			}
		}
		for (int i = from; i < n; i++) MemoryUtil.memPutFloat(a + 4L * i, VALUES[i]);
		return n;
	}

	static int size() {
		return count;
	}
}

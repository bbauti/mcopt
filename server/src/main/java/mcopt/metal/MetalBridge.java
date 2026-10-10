package mcopt.metal;

/** mcopt-server: no native library on a server (LodNativeNoise's NEON backend never runs here; its Java kernels do). */
public final class MetalBridge {
	private MetalBridge() {
	}

	public static java.lang.foreign.SymbolLookup library() {
		throw new UnsupportedOperationException("mcopt-server has no native library");
	}
}

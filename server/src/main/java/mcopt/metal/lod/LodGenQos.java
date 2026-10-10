package mcopt.metal.lod;

/** mcopt-server: no thread QoS classes (macOS's; the workers run at the lowest priority instead). */
public final class LodGenQos {
	static final int LOD = -1;

	private LodGenQos() {
	}

	public static void once(int cls, String role) {
	}
}

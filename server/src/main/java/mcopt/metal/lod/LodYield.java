package mcopt.metal.lod;

/** mcopt-server: no yielding to the client's chunk loading (the service paces itself by the server's tick time). */
final class LodYield {
	static final boolean ON = false;
	static volatile int top = Integer.MAX_VALUE;

	private LodYield() {
	}

	static void enter(int level, int top) {
	}

	static void checkpoint() {
	}

	static void exit() {
	}
}

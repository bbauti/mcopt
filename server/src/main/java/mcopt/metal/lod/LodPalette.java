package mcopt.metal.lod;

import net.minecraft.world.level.block.state.BlockState;

/** mcopt-server: texture numbers are the client's (LodNoise without paint never asks). */
final class LodPalette {
	private LodPalette() {
	}

	static int id(BlockState state) {
		return 0;
	}

	static int word(int top, int side, int below) {
		return 0;
	}
}

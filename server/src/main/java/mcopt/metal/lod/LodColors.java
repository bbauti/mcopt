package mcopt.metal.lod;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

/**
 * mcopt-server: the client's block looks aren't here (they come from its textures). The generator runs without paint on a
 * server (LodNoise.paint false), so nothing here is asked for a color; only whether a plant is drawn as crossed quads, which
 * a server tells from the block itself (no collision, an offset like grass and flowers have).
 */
final class LodColors {
	record Look(int top, int side, int topTint, int sideTint, int constant, float[] topUv, float[] sideUv, boolean cross, int[] profile) {
	}

	private LodColors() {
	}

	static Look look(BlockState s) {
		boolean cross = !s.isAir() && s.getFluidState().isEmpty() && s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()
			&& s.hasOffsetFunction();
		return new Look(0, 0, 0, 0, 0, new float[4], new float[4], cross, new int[4]);
	}

	static int top(BlockState state, Biome b, int x, int z) {
		return 0;
	}

	static int side(BlockState state, Biome b, int x, int z) {
		return 0;
	}

	static boolean fringed(BlockState s) {
		return false;
	}

	static int mix(int a, int b, float f) {
		return a;
	}

	static int multiply(int a, int b) {
		return a;
	}
}

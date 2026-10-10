package mcopt.metal.lod;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

/** mcopt-server: no colors (LodNoise without paint never asks); a plant is any block without collision (the client keeps its crossed-quad models). */
final class LodColors {
	record Look(int top, int side, int topTint, int sideTint, int constant, float[] topUv, float[] sideUv, boolean cross, int[] profile) {
	}

	private LodColors() {
	}

	static Look look(BlockState s) {
		boolean cross = !s.isAir() && s.getFluidState().isEmpty() && s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
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

	static int waterOver(int floor, int water, int depth) {
		return water;
	}

	static int multiply(int a, int b) {
		return a;
	}
}

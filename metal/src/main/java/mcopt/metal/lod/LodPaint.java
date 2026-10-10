package mcopt.metal.lod;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * A structure tile (a server's: LodNoise without paint) painted on the client, as LodNoise paints its own tiles: the
 * surface (its blocks' colors under the biome's tint, clear water over its floor, ice, snow, impostor canopies), then
 * where the game's trees were planted their crowns and trunks, then level 0's plants and the ground under crowns. The
 * code follows LodNoise.surfaceMaterials, dressTrees and dressGround step for step: a server's tile looks as one made here.
 */
final class LodPaint {
	private static final BlockState SNOW = Blocks.SNOW_BLOCK.defaultBlockState(), ICE = Blocks.ICE.defaultBlockState(), WATER = Blocks.WATER.defaultBlockState();

	private LodPaint() {
	}

	static void paint(LodTile t, int seaLevel) {
		plants(t);
		surface(t, seaLevel);
		// (trees planted: LodNoise dresses them and the ground only then)
		if (!t.impostorTrees) {
			trees(t, seaLevel);
			ground(t);
		}
	}

	/** Plants as this client draws them: a server's are any block without collision, this side's only crossed-quad models (as LodForest). */
	private static void plants(LodTile t) {
		for (int i = 0; i < t.cells(); i++) {
			BlockState p = t.plantLower[i];
			if (p == null) continue;
			if (!LodColors.look(p).cross()) {
				t.plantLower[i] = null;
				t.plantUpper[i] = null;
				t.plantBlocks[i] = 0;
			} else if (t.plantUpper[i] != null && !LodColors.look(t.plantUpper[i]).cross()) {
				t.plantUpper[i] = null;
				t.plantBlocks[i] = 1;
			}
		}
	}

	/** LodNoise.surfaceMaterials' colors, from the structure it recorded. */
	private static void surface(LodTile t, int seaLevel) {
		int c = t.cell(), x0 = t.minX(), z0 = t.minZ(), size = t.size;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int z = 0; z < size; z++) {
			for (int x = 0; x < size; x++) {
				int i = z * size + x;
				BlockState surface = t.state[i];
				if (surface == null) continue;
				int bx = x0 + x * c + c / 2, bz = z0 + z * c + c / 2;
				BlockState below = t.belowState[i] != null ? t.belowState[i] : surface;
				Biome b = t.biome[i].value();
				int topColor = LodColors.top(surface, b, bx, bz), belowColor = LodColors.top(below, b, bx, bz);
				int sideColor = c == 1 ? LodColors.side(surface, b, bx, bz) : LodColors.mix(LodColors.side(surface, b, bx, bz), belowColor, 0.6F);
				t.below[i] = belowColor;
				t.fringe[i] = LodColors.fringed(surface);
				BlockState texTop = surface, texSide = surface;
				if (t.water[i] != LodTile.DRY) {
					pos.set(bx, t.water[i], bz);
					if (b.coldEnoughToSnow(pos, seaLevel)) {
						topColor = LodColors.top(ICE, b, bx, bz);
						texTop = ICE;
					} else {
						texTop = null;
						t.clear[i] = (byte) Math.clamp(t.water[i] - t.ground[i], 1, 127);
						sideColor = topColor;
						t.below[i] = topColor;
						topColor = LodColors.top(WATER, b, bx, bz);
					}
				} else {
					pos.set(bx, t.ground[i], bz);
					if (b.coldEnoughToSnow(pos, seaLevel) && surface.isSolidRender()) {
						topColor = LodColors.top(SNOW, b, bx, bz);
						texTop = SNOW;
						if (surface.hasProperty(BlockStateProperties.SNOWY)) {
							texSide = surface.setValue(BlockStateProperties.SNOWY, true);
							int snowySide = LodColors.side(texSide, b, bx, bz);
							sideColor = c == 1 ? snowySide : LodColors.mix(snowySide, belowColor, 0.6F);
							t.fringe[i] = false;
						}
					}
					if (t.impostor[i] > 0) {
						// (the server raised the column by the canopy already)
						int raw = LodTrees.leaves(t.biome[i], b, bx, bz), leaves = raw;
						float cover = LodTrees.cover(t.biome[i], c);
						pos.set(bx, t.height[i], bz);
						if (b.coldEnoughToSnow(pos, seaLevel)) leaves = LodColors.mix(leaves, LodColors.top(SNOW, b, bx, bz), 0.45F);
						topColor = c >= 16 ? LodColors.mix(topColor, leaves, cover) : leaves;
						sideColor = LodColors.mix(sideColor, LodColors.multiply(raw, 0x8C8C8C), c >= 16 ? cover : 0.9F);
					}
				}
				t.top[i] = topColor;
				t.side[i] = sideColor;
				if (c == 1 && LodConfig.TEXTURES) t.tex[i] = LodPalette.word(texTop == null ? 0 : LodPalette.id(texTop), LodPalette.id(texSide), LodPalette.id(below));
			}
		}
	}

	/** LodNoise.dressTrees' colors (and, on levels without floating crowns here, the standing it implies). */
	private static void trees(LodTile t, int seaLevel) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int c = t.cell();
		for (int z = 0; z < t.size; z++) {
			for (int x = 0; x < t.size; x++) {
				int i = z * t.size + x;
				BlockState top = t.canopyState[i];
				if (t.canopyHi[i] < t.canopyLo[i] || top == null) continue;
				int bx = t.x0 + x * c, bz = t.z0 + z * c;
				Biome b = t.biome[i].value();
				pos.set(bx, t.canopyHi[i] + 1, bz);
				boolean snowy = top.is(net.minecraft.tags.BlockTags.LEAVES) && b.coldEnoughToSnow(pos, seaLevel);
				int topColor = LodColors.top(snowy ? SNOW : top, b, bx, bz);
				int sideColor = LodColors.side(top, b, bx, bz);
				t.crownTop[i] = topColor;
				t.crownSide[i] = sideColor;
				BlockState ground = t.state[i] != null ? t.state[i] : top;
				if (LodConfig.TEXTURES) t.tex[i] = LodPalette.word(LodPalette.id(snowy ? SNOW : top), LodPalette.id(top), LodPalette.id(ground));
				if (t.standing[i] || t.level >= LodConfig.CROWN_LEVELS) {
					BlockState trunk = t.trunk[i];
					t.height[i] = (short) Math.max(t.height[i], t.canopyHi[i] + 1);
					t.top[i] = topColor;
					t.side[i] = sideColor;
					t.below[i] = trunk != null ? LodColors.side(trunk, b, bx, bz) : sideColor;
					t.fringe[i] = false;
					t.standing[i] = true;
					if (LodConfig.TEXTURES) t.tex[i] = LodPalette.word(LodPalette.id(snowy ? SNOW : top), LodPalette.id(top), LodPalette.id(trunk != null ? trunk : top));
				}
			}
		}
	}

	/** LodNoise.dressGround: level 0's plants' colors, and the ground under crowns and plants. */
	private static void ground(LodTile t) {
		if (t.level != 0) return;
		for (int z = 0; z < t.size; z++) {
			for (int x = 0; x < t.size; x++) {
				int i = z * t.size + x, bx = t.x0 + x, bz = t.z0 + z;
				Biome b = t.biome[i].value();
				boolean crown = t.canopyHi[i] >= t.canopyLo[i] && !t.standing[i];
				BlockState p = t.plantLower[i];
				if (p != null) t.plantColor[i] = LodColors.side(p, b, bx, bz);
				if (p == null && !crown || t.standing[i] || t.state[i] == null || t.water[i] != LodTile.DRY && t.water[i] > t.ground[i]) continue;
				BlockState ground = t.state[i];
				t.top[i] = LodColors.top(ground, b, bx, bz);
				if (!crown) {
					t.side[i] = LodColors.side(ground, b, bx, bz);
					t.fringe[i] = LodColors.fringed(ground);
					if (LodConfig.TEXTURES) t.tex[i] = LodPalette.word(LodPalette.id(ground), LodPalette.id(ground), (t.tex[i] >> 20) & 1023);
				}
			}
		}
	}
}

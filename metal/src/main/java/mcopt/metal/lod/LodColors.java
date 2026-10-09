package mcopt.metal.lod;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.jspecify.annotations.Nullable;

/**
 * What a block looks like from far away: the average color of its top face's texture (and of its side's), and how the game
 * tints it (grass, foliage, water by biome, or a constant). Learned once per block state from its baked model and the
 * texture files, on whichever thread asks first.
 */
final class LodColors {
	static final int TINT_NONE = 0, TINT_GRASS = 1, TINT_FOLIAGE = 2, TINT_WATER = 3, TINT_DRY_FOLIAGE = 4, TINT_CONSTANT = 5;

	/**
	 * top / side: RGB of the textures (untinted); tint kind and, for constants, its RGB; the two sprites' rectangles in the
	 * block atlas (u0, v0, u1, v1; zeros when unknown), for drawing the near far terrain with the real textures. cross: the
	 * model is a plant's crossed quads (grass, ferns, flowers; side is their sprite); profile: per sixteenth of the side
	 * sprite's width, how high (sixteenths) its opaque texels reach, a byte each, four to an int.
	 */
	record Look(int top, int side, int topTint, int sideTint, int constant, float[] topUv, float[] sideUv, boolean cross, int[] profile) {
	}

	private static final ConcurrentHashMap<BlockState, Look> LOOKS = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<Identifier, Integer> TEXTURES = new ConcurrentHashMap<>();
	private static final Look FALLBACK = new Look(0x7F7F7F, 0x6F6F6F, TINT_NONE, TINT_NONE, 0, new float[4], new float[4], false, new int[4]);
	private static final ConcurrentHashMap<Identifier, int[]> PROFILES = new ConcurrentHashMap<>();

	private LodColors() {
	}

	static Look look(BlockState state) {
		Look l = LOOKS.get(state);
		if (l != null) return l;
		try {
			l = compute(state);
		} catch (RuntimeException e) {
			l = FALLBACK;
		}
		l = override(state, l);
		LOOKS.put(state, l);
		return l;
	}

	/**
	 * Colors set for blocks by id, over what their textures give (for modded blocks whose models the averaging can't read:
	 * dynamic or connected textures): {top, side} RGB, -1 keeping the computed one. An override is the color as drawn, untinted.
	 * From config/mcopt-lod-colors.properties (modid:block=RRGGBB or modid:block=RRGGBB,RRGGBB for top and side) and
	 * McoptFarTerrain.setBlockColor.
	 */
	private static final ConcurrentHashMap<String, int[]> OVERRIDES = new ConcurrentHashMap<>(loadOverrides());

	private static Look override(BlockState state, Look l) {
		if (OVERRIDES.isEmpty()) return l;
		int[] o = OVERRIDES.get(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
		if (o == null) return l;
		int top = o[0] >= 0 ? o[0] : l.top(), side = o[1] >= 0 ? o[1] : l.side();
		// an overridden face is drawn flat in its color: no sprite (the textured path scales the sprite by color / average, which
		// for an override would be the raw texture)
		return new Look(top, side, o[0] >= 0 ? TINT_NONE : l.topTint(), o[1] >= 0 ? TINT_NONE : l.sideTint(), l.constant(),
			o[0] >= 0 ? new float[4] : l.topUv(), o[1] >= 0 ? new float[4] : l.sideUv(), l.cross(), l.profile());
	}

	/** The resources were reloaded (a resource pack changed): every look is read again from the new models and textures. */
	static void reloaded() {
		LOOKS.clear();
		TEXTURES.clear();
		PROFILES.clear();
	}

	/** A block's colors from now on (tiles already made keep theirs until made again); -1 keeps the computed color. */
	static void setOverride(String blockId, int top, int side) {
		String id = blockId.contains(":") ? blockId : "minecraft:" + blockId;
		OVERRIDES.put(id, new int[] {top < 0 ? -1 : top & 0xFFFFFF, side < 0 ? -1 : side & 0xFFFFFF});
		LOOKS.keySet().removeIf(s -> net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString().equals(id));
	}

	private static java.util.Map<String, int[]> loadOverrides() {
		java.util.Map<String, int[]> out = new java.util.HashMap<>();
		java.nio.file.Path f = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("mcopt-lod-colors.properties");
		if (!java.nio.file.Files.isRegularFile(f)) return out;
		java.util.List<String> lines;
		try {
			lines = java.nio.file.Files.readAllLines(f, java.nio.charset.StandardCharsets.UTF_8);
		} catch (java.io.IOException e) {
			System.out.println("mcopt-lod: can't read " + f + ": " + e);
			return out;
		}
		// (lines split at their '=' here: java.util.Properties would split modid:block at its ':')
		for (String line : lines) {
			// (a byte order mark some editors put first is no part of the key)
			String t = line.replace("\uFEFF", "").strip();
			if (t.isEmpty() || t.startsWith("#") || t.startsWith("!")) continue;
			int eq = t.indexOf('=');
			if (eq <= 0) {
				System.out.println("mcopt-lod: " + f.getFileName() + ": '" + t + "' isn't block=RRGGBB (ignored)");
				continue;
			}
			String k = t.substring(0, eq).strip();
			String[] v = t.substring(eq + 1).split(",");
			try {
				int top = rgb(v[0]), side = v.length > 1 ? rgb(v[1]) : top;
				out.put(k.contains(":") ? k : "minecraft:" + k, new int[] {top, side});
			} catch (NumberFormatException e) {
				System.out.println("mcopt-lod: " + f.getFileName() + ": " + k + " isn't RRGGBB or RRGGBB,RRGGBB (ignored)");
			}
		}
		System.out.println("mcopt-lod: " + out.size() + " block colors from " + f.getFileName());
		return out;
	}

	private static int rgb(String s) {
		String t = s.strip();
		if (t.equals("-") || t.isEmpty()) return -1;
		if (t.startsWith("#")) t = t.substring(1);
		if (t.startsWith("0x") || t.startsWith("0X")) t = t.substring(2);
		return Integer.parseInt(t, 16) & 0xFFFFFF;
	}

	/** The color of a block's top seen at a column in biome b. */
	static int top(BlockState state, Biome b, int x, int z) {
		Look l = look(state);
		return tint(l.top, l.topTint, l.constant, b, x, z);
	}

	static int side(BlockState state, Biome b, int x, int z) {
		Look l = look(state);
		return tint(l.side, l.sideTint, l.constant, b, x, z);
	}

	static int tint(int rgb, int kind, int constant, Biome b, int x, int z) {
		int t = switch (kind) {
			case TINT_GRASS -> b.getGrassColor(x, z);
			case TINT_FOLIAGE -> b.getFoliageColor();
			case TINT_WATER -> b.getWaterColor();
			case TINT_DRY_FOLIAGE -> b.getDryFoliageColor();
			case TINT_CONSTANT -> constant;
			default -> 0xFFFFFF;
		};
		return multiply(rgb, t);
	}

	/** Blocks whose side texture carries a band of their top along its upper edge (seen from afar: a line of the top's color). */
	static boolean fringed(BlockState s) {
		return s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.PODZOL) || s.is(Blocks.MYCELIUM);
	}

	static int mix(int a, int b, float f) {
		int r = (int) (((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
		int g = (int) (((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
		int bl = (int) ((a & 255) * (1 - f) + (b & 255) * f);
		return r << 16 | g << 8 | bl;
	}

	static int multiply(int a, int b) {
		int r = ((a >> 16) & 255) * ((b >> 16) & 255) / 255;
		int g = ((a >> 8) & 255) * ((b >> 8) & 255) / 255;
		int bl = (a & 255) * (b & 255) / 255;
		return r << 16 | g << 8 | bl;
	}

	private static Look compute(BlockState state) {
		Minecraft mc = Minecraft.getInstance();
		BlockStateModel model = mc.getModelManager().getBlockStateModelSet().get(state);
		List<BlockStateModelPart> parts = new ArrayList<>();
		model.collectParts(RandomSource.create(42L), parts);
		BakedQuad up = first(parts, Direction.UP), side = first(parts, Direction.NORTH);
		if (side == null) side = first(parts, Direction.EAST);
		// a plant's crossed quads (diagonal, upright): their sprite and tint are the plant's
		BakedQuad diagonal = diagonal(parts);
		if (diagonal != null) side = diagonal;
		TextureAtlasSprite particle = model.particleMaterial().sprite();
		TextureAtlasSprite topSprite = up != null ? up.materialInfo().sprite() : particle;
		TextureAtlasSprite sideSprite = side != null ? side.materialInfo().sprite() : topSprite;
		int topRgb = average(topSprite), sideRgb = average(sideSprite);
		int[] topTint = tintOf(state, up), sideTint = tintOf(state, side);
		if (state.getBlock() == Blocks.WATER) {
			topTint = new int[] {TINT_WATER, 0};
			sideTint = topTint;
		}
		// The grass block's side is dirt under a tinted overlay: from afar mostly dirt, so it stays untinted.
		int[] profile = diagonal != null ? profile(sideSprite) : new int[4];
		return new Look(topRgb, sideRgb, topTint[0], sideTint[0], topTint[0] == TINT_CONSTANT ? topTint[1] : sideTint[1], uv(topSprite), uv(sideSprite),
			diagonal != null, profile);
	}

	private static @Nullable BakedQuad diagonal(List<BlockStateModelPart> parts) {
		for (BlockStateModelPart p : parts) {
			for (BakedQuad q : p.getQuads(null)) {
				var a = q.position0();
				var b = q.position1();
				var c = q.position2();
				float ux = b.x() - a.x(), uy = b.y() - a.y(), uz = b.z() - a.z(), vx = c.x() - a.x(), vy = c.y() - a.y(), vz = c.z() - a.z();
				float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
				float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
				if (len < 1e-6F) continue;
				if (Math.abs(ny) < 0.3F * len && Math.abs(nx) > 0.5F * len && Math.abs(nz) > 0.5F * len) return q;
			}
		}
		return null;
	}

	/** Per sixteenth of the sprite's width: how high its opaque texels reach (0-16), a byte each. */
	static int[] profile(TextureAtlasSprite sprite) {
		return PROFILES.computeIfAbsent(sprite.contents().name(), LodColors::loadProfile);
	}

	private static int[] loadProfile(Identifier name) {
		int[] out = new int[4];
		Identifier file = name.withPath("textures/" + name.getPath() + ".png");
		Optional<Resource> res = Minecraft.getInstance().getResourceManager().getResource(file);
		if (res.isEmpty()) return out;
		try (InputStream in = res.get().open(); NativeImage img = NativeImage.read(in)) {
			int w = img.getWidth(), h = Math.min(img.getHeight(), w);
			for (int k = 0; k < 16; k++) {
				int x0 = k * w / 16, x1 = Math.max(x0 + 1, (k + 1) * w / 16);
				int topRow = h;
				for (int x = x0; x < x1; x++) {
					for (int y = 0; y < h; y++) {
						if ((img.getPixel(x, y) >>> 24) >= 128) {
							topRow = Math.min(topRow, y);
							break;
						}
					}
				}
				int reach = topRow >= h ? 0 : Math.clamp((int) Math.ceil((h - topRow) * 16.0 / h), 1, 16);
				out[k >> 2] |= reach << ((k & 3) * 8);
			}
		} catch (Exception e) {
			return new int[4];
		}
		return out;
	}

	private static float[] uv(TextureAtlasSprite s) {
		// the missing texture (a block without a model of its own): no sprite, the far terrain keeps the flat color
		if (s.contents().name().getPath().equals("missingno")) return new float[4];
		return new float[] {s.getU0(), s.getV0(), s.getU1(), s.getV1()};
	}

	private static @Nullable BakedQuad first(List<BlockStateModelPart> parts, Direction d) {
		for (BlockStateModelPart p : parts) {
			List<BakedQuad> q = p.getQuads(d);
			if (!q.isEmpty()) return q.getFirst();
		}
		for (BlockStateModelPart p : parts) {
			for (BakedQuad q : p.getQuads(null)) if (q.direction() == d) return q;
		}
		return null;
	}

	/** {kind, constant} of the tint the game applies to this quad, found by asking its tint source with a probing level. */
	private static int[] tintOf(BlockState state, @Nullable BakedQuad quad) {
		if (quad == null || quad.materialInfo().tintIndex() < 0) return new int[] {TINT_NONE, 0};
		BlockTintSource source = Minecraft.getInstance().getBlockColors().getTintSource(state, quad.materialInfo().tintIndex());
		if (source == null) return new int[] {TINT_NONE, 0};
		Probe probe = new Probe();
		int c;
		try {
			c = source.colorInWorld(state, probe, BlockPos.ZERO);
		} catch (RuntimeException e) {
			return new int[] {TINT_NONE, 0};
		}
		if (probe.asked == BiomeColors.GRASS_COLOR_RESOLVER) return new int[] {TINT_GRASS, 0};
		if (probe.asked == BiomeColors.FOLIAGE_COLOR_RESOLVER) return new int[] {TINT_FOLIAGE, 0};
		if (probe.asked == BiomeColors.WATER_COLOR_RESOLVER) return new int[] {TINT_WATER, 0};
		if (probe.asked == BiomeColors.DRY_FOLIAGE_COLOR_RESOLVER) return new int[] {TINT_DRY_FOLIAGE, 0};
		if (probe.asked != null) return new int[] {TINT_GRASS, 0};
		return c == -1 ? new int[] {TINT_NONE, 0} : new int[] {TINT_CONSTANT, c & 0xFFFFFF};
	}

	/** Average of a sprite's first frame, weighted by alpha (cutout textures count only what is drawn). */
	static int average(TextureAtlasSprite sprite) {
		Identifier name = sprite.contents().name();
		return TEXTURES.computeIfAbsent(name, LodColors::load);
	}

	private static int load(Identifier name) {
		Identifier file = name.withPath("textures/" + name.getPath() + ".png");
		Optional<Resource> res = Minecraft.getInstance().getResourceManager().getResource(file);
		if (res.isEmpty()) return 0x7F7F7F;
		try (InputStream in = res.get().open(); NativeImage img = NativeImage.read(in)) {
			int w = img.getWidth(), h = Math.min(img.getHeight(), w);  // animated: frames stacked vertically
			long r = 0, g = 0, b = 0, a = 0;
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					int p = img.getPixel(x, y);
					int al = p >>> 24;
					if (al < 16) continue;
					// average in linear light, as the eye sees a far, unresolved texture
					r += (long) lin((p >> 16) & 255) * al;
					g += (long) lin((p >> 8) & 255) * al;
					b += (long) lin(p & 255) * al;
					a += al;
				}
			}
			if (a == 0) return 0x7F7F7F;
			return srgb((int) (r / a)) << 16 | srgb((int) (g / a)) << 8 | srgb((int) (b / a));
		} catch (Exception e) {
			return 0x7F7F7F;
		}
	}

	private static final int[] LIN = new int[256];
	static {
		for (int i = 0; i < 256; i++) {
			double c = i / 255.0;
			LIN[i] = (int) Math.round(65535 * (c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4)));
		}
	}

	private static int lin(int c) {
		return LIN[c];
	}

	private static int srgb(int l) {
		double c = l / 65535.0;
		double s = c <= 0.0031308 ? c * 12.92 : 1.055 * Math.pow(c, 1 / 2.4) - 0.055;
		return Math.max(0, Math.min(255, (int) Math.round(s * 255)));
	}

	/** A level that only answers tint questions, recording which resolver was asked. */
	private static final class Probe implements BlockAndTintGetter {
		@Nullable ColorResolver asked;

		@Override
		public CardinalLighting cardinalLighting() {
			return CardinalLighting.DEFAULT;
		}

		@Override
		public int getBlockTint(BlockPos pos, ColorResolver color) {
			this.asked = color;
			return 0xFFFFFF;
		}

		@Override
		public LevelLightEngine getLightEngine() {
			return LevelLightEngine.EMPTY;
		}

		@Override
		public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
			return null;
		}

		@Override
		public BlockState getBlockState(BlockPos pos) {
			return Blocks.AIR.defaultBlockState();
		}

		@Override
		public FluidState getFluidState(BlockPos pos) {
			return Fluids.EMPTY.defaultFluidState();
		}

		@Override
		public int getHeight() {
			return 384;
		}

		@Override
		public int getMinY() {
			return -64;
		}
	}
}

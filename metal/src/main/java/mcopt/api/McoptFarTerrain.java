package mcopt.api;

/**
 * mcopt's far terrain (its LODs), for other mods. Every method is safe to call whether or not far terrain is on, and from
 * any thread unless it says otherwise: with far terrain off (mcopt.lod unset) none of mcopt's far-terrain classes is
 * loaded and the queries answer as if nothing were drawn.
 *
 * <pre>{@code
 * if (FabricLoader.getInstance().isModLoaded("mcopt-metal") && McoptFarTerrain.isActive()) {
 *     int reach = McoptFarTerrain.reachBlocks();   // e.g. stretch your own fog to it
 * }
 * }</pre>
 */
public final class McoptFarTerrain {
	/** This API's version: raised when methods are added (never removed within a major version). */
	public static final int API_VERSION = 1;
	private static final boolean ENABLED = mcopt.metal.LodSwitch.ENABLED;

	private McoptFarTerrain() {
	}

	/** Whether far terrain is turned on for this game (mcopt.lod=true); it may still draw nothing in the current world. */
	public static boolean isEnabled() {
		return ENABLED;
	}

	/** Whether far terrain draws in the current world right now (on, a world loaded, and not hidden). */
	public static boolean isActive() {
		return ENABLED && mcopt.metal.lod.Lod.activeReach() > 0;
	}

	/**
	 * How far far terrain reaches from the camera now, in blocks: what the camera's far plane and the game's fog extend to
	 * while it draws; 0 when it doesn't.
	 */
	public static int reachBlocks() {
		return ENABLED ? (int) mcopt.metal.lod.Lod.activeReach() : 0;
	}

	/**
	 * Shows or hides far terrain (the player's toggle key does the same). Hidden, it keeps following the camera and loading,
	 * so showing it again is immediate; the fog and far plane are the game's own meanwhile.
	 */
	public static void setDrawEnabled(boolean draw) {
		if (ENABLED) mcopt.metal.lod.Lod.setDrawEnabled(draw);
	}

	/** Whether far terrain is shown (not hidden by setDrawEnabled or the toggle key). */
	public static boolean isDrawEnabled() {
		return ENABLED && mcopt.metal.lod.Lod.drawEnabled();
	}

	/**
	 * Reads a chunk the client has into far terrain again at the next frame: for a mod that changed many of its blocks at once
	 * (far terrain otherwise takes a chunk's changes when the client unloads it). Does nothing for a chunk the client lacks.
	 */
	public static void refreshChunk(int chunkX, int chunkZ) {
		if (ENABLED) mcopt.metal.lod.Lod.refreshChunk(chunkX, chunkZ);
	}

	/**
	 * Sets the colors far terrain draws a block with (its top face, its sides; 0xRRGGBB, as drawn, without biome tint; -1
	 * keeps the color mcopt reads from the block's textures), e.g. for a block whose model or textures it can't average
	 * (dynamic models, connected textures). Takes effect for far terrain made from now on (cached tiles keep their colors
	 * until made again). Players can do the same in config/mcopt-lod-colors.properties (modid:block=RRGGBB[,RRGGBB]).
	 */
	public static void setBlockColor(String blockId, int topRgb, int sideRgb) {
		if (ENABLED) mcopt.metal.lod.Lod.setBlockColor(blockId, topRgb, sideRgb);
	}
}

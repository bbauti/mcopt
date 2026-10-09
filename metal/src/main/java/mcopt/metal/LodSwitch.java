package mcopt.metal;

/**
 * Whether far terrain is on, decided once for every reader (the mixin plugin, LodConfig, the API) without loading any of
 * far terrain's classes: -Dmcopt.lod=true or any -Dmcopt.lod.radius, unless Distant Horizons is installed too (two far
 * terrains would each draw the whole distance, one over the other); -Dmcopt.lod.withDistantHorizons=true keeps it on then.
 */
public final class LodSwitch {
	public static final boolean ENABLED = enabled();

	private LodSwitch() {
	}

	private static boolean enabled() {
		Profile.apply(); // (the flags from config/mcopt.properties first)
		boolean on = Boolean.getBoolean("mcopt.lod") || System.getProperty("mcopt.lod.radius") != null;
		if (!on) return false;
		boolean dh;
		try {
			dh = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("distanthorizons");
		} catch (Throwable t) {
			dh = false;
		}
		if (dh && !Boolean.getBoolean("mcopt.lod.withDistantHorizons")) {
			System.out.println("[mcopt] far terrain off: Distant Horizons draws the distance (-Dmcopt.lod.withDistantHorizons=true keeps both)");
			return false;
		}
		return true;
	}
}

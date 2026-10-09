package mcopt.metal;

/**
 * Whether far terrain is on, decided once for every reader without loading far terrain's classes: -Dmcopt.lod=true or any -Dmcopt.lod.radius,
 * unless Distant Horizons is installed too (each would draw the whole distance, one over the other); -Dmcopt.lod.withDistantHorizons=true keeps it.
 */
public final class LodSwitch {
	public static final boolean ENABLED = enabled();

	private LodSwitch() {
	}

	private static boolean enabled() {
		Profile.apply(); // (the flags from config/mcopt.properties first)
		if (!Boolean.getBoolean("mcopt.lod") && System.getProperty("mcopt.lod.radius") == null) return false;
		try {
			if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("distanthorizons")) return true;
		} catch (Throwable t) {
			return true;
		}
		if (Boolean.getBoolean("mcopt.lod.withDistantHorizons")) return true;
		System.out.println("[mcopt] far terrain off: Distant Horizons draws the distance (-Dmcopt.lod.withDistantHorizons=true keeps both)");
		return false;
	}
}

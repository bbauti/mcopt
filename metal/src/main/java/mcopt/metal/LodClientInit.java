package mcopt.metal;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

/**
 * The client entrypoint: the profile's line for the log (Profile.logOverrides), and far terrain's networking with servers running mcopt-server (mcopt.metal.lod.LodRemote), only when
 * far terrain is on and Fabric API's networking is installed (without it, nothing of LodRemote loads).
 */
public final class LodClientInit implements ClientModInitializer {
	/** Whether LodRemote was registered (far terrain on, Fabric API's networking present). */
	public static volatile boolean networking;

	@Override
	public void onInitializeClient() {
		Profile.logOverrides();
		if (!LodSwitch.ENABLED) return;
		if (!FabricLoader.getInstance().isModLoaded("fabric-networking-api-v1")) {
			System.out.println("[mcopt] far terrain from servers needs Fabric API (not installed): far terrain on servers comes from what you see there only");
			return;
		}
		try {
			mcopt.metal.lod.LodRemote.register();
			networking = true;
		} catch (Throwable t) {
			System.out.println("[mcopt] far terrain's networking unavailable: " + t);
		}
	}
}

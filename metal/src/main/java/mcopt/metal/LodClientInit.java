package mcopt.metal;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

/** The client entrypoint: printf into the log, Profile.logOverrides, and far terrain's networking with mcopt-server (LodRemote). */
public final class LodClientInit implements ClientModInitializer {
	/** Whether LodRemote was registered (far terrain on, Fabric API's networking present). */
	public static volatile boolean networking;

	@Override
	public void onInitializeClient() {
		// the game's System.out logs println alone: printf (format, then write) went to stdout, around latest.log
		java.io.PrintStream log = System.out;
		System.setOut(new java.io.PrintStream(log, true) {
			@Override
			public void println(String s) {
				log.println(s);
			}

			@Override
			public void println(Object o) {
				log.println(o);
			}

			@Override
			public java.io.PrintStream format(String f, Object... a) {
				return this.format(java.util.Locale.getDefault(java.util.Locale.Category.FORMAT), f, a);
			}

			@Override
			public java.io.PrintStream format(java.util.Locale l, String f, Object... a) {
				log.println(String.format(l, f, a).stripTrailing());
				return this;
			}
		});
		Profile.logOverrides();
		if (!LodSwitch.ENABLED) return;
		if (!FabricLoader.getInstance().isModLoaded("fabric-networking-api-v1")) { // (without it, nothing of LodRemote may load)
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

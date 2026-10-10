package mcopt.metal;

import net.fabricmc.api.ClientModInitializer;

/** The client entrypoint: printf into the log, and Profile.logOverrides. */
public final class LodClientInit implements ClientModInitializer {
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
	}
}

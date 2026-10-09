package mcopt.metal.lod;

import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

/** Where a world's far terrain is cached when the game has no save of its own for it (a server: by its address). */
final class LodWorlds {
	private LodWorlds() {
	}

	/** The cache directory's name for the server the client is on: its address, made safe as a file name. */
	static String serverDir(Minecraft mc) {
		ServerData data = mc.getCurrentServer();
		String ip = data != null && data.ip != null && !data.ip.isBlank() ? data.ip : "unknown";
		return "server_" + safe(ip);
	}

	static String safe(String name) {
		String s = name.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
		// (no "." or ".." as a whole name, nothing too long for a file system)
		if (s.isEmpty() || s.chars().allMatch(c -> c == '.')) s = "_" + s;
		return s.length() > 96 ? s.substring(0, 96) : s;
	}
}

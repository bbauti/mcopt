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

	/**
	 * A singleplayer world's cache (a dimension's directory) belongs to the world with this seed: a world.txt there names it.
	 * A different seed (a new world made under an old one's folder name) moves the old cache aside (deleted on a background
	 * thread) so the new world starts clean; a cache without the file is taken as this world's (made before the file existed).
	 */
	static void claim(java.nio.file.Path dir, long seed) {
		java.nio.file.Path marker = dir.resolve("world.txt");
		String want = "seed " + seed;
		try {
			if (java.nio.file.Files.isRegularFile(marker)) {
				String have = java.nio.file.Files.readString(marker).strip();
				if (have.equals(want)) return;
				java.nio.file.Path stale = dir.resolveSibling(dir.getFileName() + ".stale-" + System.currentTimeMillis());
				java.nio.file.Files.move(dir, stale);
				System.out.println("mcopt-lod: " + dir + " was another world's (" + have + "): set aside, deleted in the background");
				Thread t = new Thread(() -> delete(stale), "mcopt-lod-cache-delete");
				t.setDaemon(true);
				t.setPriority(Thread.MIN_PRIORITY);
				t.start();
			}
			java.nio.file.Files.createDirectories(dir);
			java.nio.file.Files.writeString(marker, want + "\n");
		} catch (java.io.IOException | RuntimeException e) {
			System.out.println("mcopt-lod: can't check " + marker + ": " + e);
		}
	}

	private static void delete(java.nio.file.Path root) {
		try (var walk = java.nio.file.Files.walk(root)) {
			walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
				try {
					java.nio.file.Files.deleteIfExists(p);
				} catch (java.io.IOException e) {
					// (left for the next time)
				}
			});
		} catch (java.io.IOException | RuntimeException e) {
			System.out.println("mcopt-lod: can't delete " + root + ": " + e);
		}
	}

	/**
	 * "_" and the world's hashed seed as the server sent it (what the client's biome lookups use), or "" when it can't be read
	 * or is 0 (a server that hides it): worlds behind one address then share a cache, as before.
	 */
	static String seedSuffix(net.minecraft.client.multiplayer.ClientLevel level) {
		try {
			java.lang.reflect.Field f = net.minecraft.world.level.biome.BiomeManager.class.getDeclaredField("biomeZoomSeed");
			f.setAccessible(true);
			long seed = f.getLong(level.getBiomeManager());
			return seed == 0 ? "" : "_" + Long.toHexString(seed);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return "";
		}
	}

	static String safe(String name) {
		String s = name.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
		// (no "." or ".." as a whole name, nothing too long for a file system)
		if (s.isEmpty() || s.chars().allMatch(c -> c == '.')) s = "_" + s;
		return s.length() > 96 ? s.substring(0, 96) : s;
	}
}

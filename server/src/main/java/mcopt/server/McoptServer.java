package mcopt.server;

import net.fabricmc.api.DedicatedServerModInitializer;

/** mcopt-server's entrypoint (dedicated servers): far terrain for mcopt clients (mcopt.metal.lod.LodServerService). */
public final class McoptServer implements DedicatedServerModInitializer {
	@Override
	public void onInitializeServer() {
		mcopt.metal.lod.LodServerService.init();
	}
}

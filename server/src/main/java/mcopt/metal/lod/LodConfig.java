package mcopt.metal.lod;

/**
 * mcopt-server: the generator's switches (the client's LodConfig has the same names and meanings): the structure a server
 * makes is the most a client draws (plants, the game's trees on level 0, crowns floating on levels 0-1); each client paints
 * and keeps what it uses. Set with -D flags or in config/mcopt-server.properties (LodServerService reads it first).
 */
public final class LodConfig {
	public static final boolean TEXTURES = true;
	public static final boolean PLANTS = Boolean.parseBoolean(System.getProperty("mcopt.lod.plants", "true"));
	public static final boolean TREES = Boolean.parseBoolean(System.getProperty("mcopt.lod.trees", "true"));
	public static final int TREE_LEVELS = Integer.getInteger("mcopt.lod.treeLevels", 1);
	public static final int CROWN_LEVELS = Integer.getInteger("mcopt.lod.crownLevels", 2);
	public static final boolean FINE_DENSITY = Boolean.parseBoolean(System.getProperty("mcopt.lod.fineDensity", "true"));
	public static final int FINE_LEVELS = Integer.getInteger("mcopt.lod.fineLevels", 2);

	private LodConfig() {
	}
}

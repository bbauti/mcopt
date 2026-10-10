package mcopt.metal.lod;

/** mcopt-server: the client's switches, at the most it draws; -D flags or config/mcopt-server.properties (LodServerService sets the flags first). */
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

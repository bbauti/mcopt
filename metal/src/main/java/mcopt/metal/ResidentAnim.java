package mcopt.metal;

/** -Dmcopt.metal.residentAnim: see MetalDevice. The flag and the marker its mixin sets while a sprite animation's frames are created. */
public final class ResidentAnim {
	public static final boolean ON = Boolean.getBoolean("mcopt.metal.residentAnim");
	public static volatile boolean creating;

	private ResidentAnim() {
	}
}

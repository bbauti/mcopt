package mcopt.metal;

import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import java.util.Collection;
import java.util.List;
import org.lwjgl.sdl.SDLMetal;

/**
 * A CAMetalLayer on the SDL window. The drawable is fetched at blit time rather than frame start: holding it for
 * the whole frame only adds latency and can stall on a drawable the display hasn't released yet.
 */
final class MetalSurface implements GpuSurfaceBackend {
	/** With vsync off, present only the last frame that makes each refresh (see mc_pace); -Dmcopt.metal.pace=false presents every frame. */
	private static final boolean PACE = Boolean.parseBoolean(System.getProperty("mcopt.metal.pace", "true"));
	/** How long before a refresh a finished frame has to reach the compositor to be shown on it. */
	/** -Dmcopt.metal.paceMarginMs: how long before a refresh the paced present must be done (the compositor's latch); default 2. */
	private static final double PACE_MARGIN_S = Double.parseDouble(System.getProperty("mcopt.metal.paceMarginMs", "2")) / 1000;
	/** -Dmcopt.metal.drawables=2|3: the layer's maximumDrawableCount (default 3). */
	private static final int DRAWABLES = Integer.getInteger("mcopt.metal.drawables", 0);
	/**
	 * -Dmcopt.metal.paceSync=true: the paced presents (vsync off) go out with display sync on, so each latches at a vblank. On
	 * the laptop's 120 Hz 5K display at ~1800 fps, paced presents without display sync reach the screen in pairs ~1.9 ms apart
	 * every 16.7 ms (a 60 Hz cadence); with vsync the grid is a clean 8.33 ms.
	 */
	private static final boolean PACE_SYNC = Boolean.getBoolean("mcopt.metal.paceSync");
	/**
	 * -Dmcopt.metal.paceAdapt=true: the pacer's margin learns the compositor's latch from scanout times (mc_pace in mcmetal.m): a
	 * paced frame shown more than half a refresh after the refresh it was aimed at adds lead, a frame on time takes a little away.
	 * =watch: presents are only measured (the stats line), with the caller's margin.
	 */
	static final int PACE_ADAPT = "watch".equals(System.getProperty("mcopt.metal.paceAdapt")) ? 2 : Boolean.getBoolean("mcopt.metal.paceAdapt") ? 1 : 0;
	private boolean paced;
	private long followAt;
	private boolean followLogged;
	private final long window;
	private final long ctx;
	private final MetalEncoder encoder;
	private final long view;
	private final long layer;

	MetalSurface(long ctx, MetalEncoder encoder, long window) {
		this.ctx = ctx;
		this.encoder = encoder;
		this.window = window;
		this.view = SDLMetal.SDL_Metal_CreateView(window);
		this.layer = SDLMetal.SDL_Metal_GetLayer(this.view);
	}

	@Override
	public void configure(GpuSurface.Configuration config) {
		boolean vsync = config.presentMode() == GpuSurface.PresentMode.FIFO;
		this.paced = !vsync && PACE;
		if (PACE_ADAPT > 0) Native.paceAdapt(this.paced ? PACE_ADAPT : 0);
		Native.layerConfigure(this.ctx, this.layer, config.width(), config.height(), (vsync || PACE_SYNC && this.paced ? 1 : 0) | DRAWABLES << 8);
	}

	@Override
	public boolean isSuboptimal() {
		return false;
	}

	@Override
	public void acquireNextTexture() {
	}

	@Override
	public void blitFromTexture(CommandEncoderBackend commandEncoder, GpuTextureView textureView) {
		// mcopt.rec hook: the opt-in recorder (-Dmcopt.rec, mcopt.metal.rec) takes the finished frame, GUI included. Rec.ON is a constant false without it.
		if (mcopt.metal.rec.Rec.ON) mcopt.metal.rec.Rec.frame(this.encoder, textureView);
		long t0 = WaitStats.ON ? System.nanoTime() : 0;
		boolean skip = this.paced && !Native.pace(PACE_MARGIN_S);
		if (this.paced) this.followDisplay();   // (after mc_pace, which starts the display link)
		if (skip) {
			if (WaitStats.ON) WaitStats.wait(WaitStats.PACE, System.nanoTime() - t0, 0);
			return;
		}
		long t1 = WaitStats.ON ? System.nanoTime() : 0;
		if (WaitStats.ON && this.paced) WaitStats.wait(WaitStats.PACE, t1 - t0, 0);
		if (MetalEncoder.PRESENT_ACQUIRE) {
			this.encoder.presentAcquire(this.layer, textureView); // no nextDrawable here: the present side acquires it
			return;
		}
		MetalEvents.Operation event = MetalEvents.begin("nextDrawable", -1, 0);
		long drawable;
		try {
			drawable = Native.layerNext(this.layer);
		} finally {
			MetalEvents.end(event);
		}
		if (WaitStats.ON) WaitStats.wait(WaitStats.DRAWABLE, System.nanoTime() - t1, 0);
		if (drawable == 0) return; // no drawable (window hidden): skip the frame's present
		this.encoder.presentTexture(drawable, textureView);
		this.encoder.afterGpuFinishes(() -> Native.release(drawable));
	}

	/** Once a second: the pacer takes its refreshes from the display under the window's center (it began on the main display). */
	private void followDisplay() {
		long now = System.nanoTime();
		if (now < this.followAt) return;
		this.followAt = now + 1_000_000_000L;
		try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
			java.nio.IntBuffer x = stack.mallocInt(1), y = stack.mallocInt(1), w = stack.mallocInt(1), h = stack.mallocInt(1);
			if (!org.lwjgl.sdl.SDLVideo.SDL_GetWindowPosition(this.window, x, y) || !org.lwjgl.sdl.SDLVideo.SDL_GetWindowSize(this.window, w, h)) return;
			double hz = Native.paceFollow(x.get(0) + w.get(0) / 2.0, y.get(0) + h.get(0) / 2.0);
			if (hz != 0) System.out.println("mcopt-metal: present pacing follows the window's display" + (hz > 0 ? String.format(" (%.2f Hz)", hz) : ""));
			else if (!this.followLogged) System.out.println("mcopt-metal: present pacing on the main display, where the window is");
			this.followLogged = true;
		}
	}

	@Override
	public void present() {
		// The present was scheduled on the frame's command buffer in blitFromTexture and happens when it's committed.
	}

	@Override
	public Collection<GpuSurface.PresentMode> supportedPresentModes() {
		return List.of(GpuSurface.PresentMode.IMMEDIATE, GpuSurface.PresentMode.FIFO);
	}

	@Override
	public void close() {
		SDLMetal.SDL_Metal_DestroyView(this.view);
	}
}

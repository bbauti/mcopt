package mcopt.metal.mixin.own;

import java.util.List;
import java.util.Map;
import mcopt.metal.own.OwnTerrain;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * -Dmcopt.own.int.lists=true: vanilla's per-frame section draw lists (LevelRenderer.extractSectionDrawGroups, every visible section x
 * every layer: its draw, then its slice in vanilla's uber buffers) are skipped when vanilla's uber buffers hold no geometry at all,
 * which is the case while our near terrain takes every layer (nothing of ours is ever uploaded there). Exact: with every uber buffer
 * empty, getRenderSectionSlice returns null for every section and layer, so vanilla's walk adds no draw, no section info and no
 * index count; the skip returns the same 0 with the same empty lists. Any vanilla-held layer (improved transparency, a layer outside
 * the compact range, the frame after a toggle) makes a buffer non-empty and the walk runs as before. Checked under vanilla's own
 * lock, as its walk reads the buffers.
 */
@Mixin(LevelRenderer.class)
abstract class OwnIntListsMixin {
	@Unique
	private static final String MCOPT$MODE = System.getProperty("mcopt.own.int.lists", "false");
	@Unique
	private static final boolean MCOPT$ON = "true".equals(MCOPT$MODE), MCOPT$VERIFY = "verify".equals(MCOPT$MODE);
	@Unique
	private boolean mcopt$wouldSkip;
	@Unique
	private long mcopt$checked, mcopt$bad, mcopt$skipped, mcopt$lastLog;

	@Inject(method = "extractSectionDrawGroups", at = @At("HEAD"), cancellable = true)
	private void mcopt$skipVanillaLists(boolean respectTranslucentOrder, List<?> sectionInfos, Map<?, ?> drawGroups, CallbackInfoReturnable<Integer> cir) {
		this.mcopt$wouldSkip = false;
		if (!MCOPT$ON && !MCOPT$VERIFY || OwnTerrain.get() == null) return;
		SectionRenderDispatcher d = ((LevelRenderer) (Object) this).sectionRenderDispatcher();
		if (d == null) return;
		d.lock();
		try {
			for (Object buffers : ((OwnIntDispatcherAccess) d).mcopt$uberBuffers().values()) {
				if (!((OwnIntUberAccess) ((OwnIntUberBuffersAccess) buffers).mcopt$vertexBuffer()).mcopt$allocations().isEmpty()) return;
			}
		} finally {
			d.unlock();
		}
		this.mcopt$wouldSkip = true;
		if (MCOPT$ON) cir.setReturnValue(0);
	}

	/** -Dmcopt.own.int.lists=verify: vanilla's walk runs; whenever the skip would have applied, it must have produced nothing. */
	@Inject(method = "extractSectionDrawGroups", at = @At("RETURN"))
	private void mcopt$verifyVanillaLists(boolean respectTranslucentOrder, List<?> sectionInfos, Map<?, ?> drawGroups, CallbackInfoReturnable<Integer> cir) {
		if (!MCOPT$VERIFY) return;
		this.mcopt$checked++;
		if (this.mcopt$wouldSkip) {
			this.mcopt$skipped++;
			boolean empty = cir.getReturnValueI() == 0 && sectionInfos.isEmpty();
			for (Object l : drawGroups.values()) empty &= ((List<?>) l).isEmpty();
			if (!empty) this.mcopt$bad++;
		}
		long now = System.nanoTime();
		if (now - this.mcopt$lastLog > 5_000_000_000L) {
			System.out.println("mcopt-own lists verify: " + this.mcopt$checked + " frames, skip would apply in " + this.mcopt$skipped + ", mismatches " + this.mcopt$bad);
			this.mcopt$lastLog = now;
		}
	}
}

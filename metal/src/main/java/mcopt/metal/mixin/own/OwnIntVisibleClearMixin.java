package mcopt.metal.mixin.own;

import mcopt.metal.own.OwnVisible;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** -Dmcopt.own.int.visible (OwnVisible): a new generation whenever vanilla clears its visible list; its section set dropped with the ViewArea. */
@Mixin(LevelRenderer.class)
abstract class OwnIntVisibleClearMixin {
	@Inject(method = "clearVisibleSections", at = @At("HEAD"))
	private void mcopt$visibleCleared(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		if (OwnVisible.ON || OwnVisible.VERIFY) OwnVisible.cleared();
	}

	/** Every RenderSection is dropped with the ViewArea (joining or reloading: invalidateCompiledGeometry; leaving: resetLevelRenderData). */
	@Inject(method = {"invalidateCompiledGeometry", "resetLevelRenderData"}, at = @At("RETURN"))
	private void mcopt$visibleLevelReset(CallbackInfo ci) {
		if (OwnVisible.ON || OwnVisible.VERIFY) OwnVisible.levelReset();
	}
}

package mcopt.metal.mixin.own;

import java.util.concurrent.CompletableFuture;
import mcopt.metal.own.OwnVignette;
import net.minecraft.client.renderer.ShaderManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Invalidate before asynchronous reload; whitelist only after successful pipeline installation. */
@Mixin(ShaderManager.class)
abstract class OwnVignetteShaderMixin {
	@Inject(method = "reload", at = @At("HEAD"))
	private void mcopt$vignetteReload(CallbackInfoReturnable<CompletableFuture<Void>> ci) {
		OwnVignette.invalidateShaders();
	}

	@Inject(method = "apply", at = @At("TAIL"))
	private void mcopt$vignetteApplied(CallbackInfo ci) {
		OwnVignette.shadersApplied();
	}
}

package mcopt.metal.mixin.own;

import mcopt.metal.FrameLog;
import net.minecraft.client.renderer.texture.TextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Measurement only (-Dmcopt.own.int.frameLog): the texture manager's tick (texture animations) in the frame it runs in. */
@Mixin(TextureManager.class)
public class FrameLogTexturesMixin {
	@Inject(method = "tick", at = @At("HEAD"))
	private void mcopt$frameLogTexturesHead(CallbackInfo ci) {
		FrameLog.begin(FrameLog.TEXTURES);
	}

	@Inject(method = "tick", at = @At("RETURN"))
	private void mcopt$frameLogTexturesReturn(CallbackInfo ci) {
		FrameLog.end(FrameLog.TEXTURES);
	}
}

package mcopt.metal.mixin;

import mcopt.metal.ResidentAnim;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * -Dmcopt.metal.residentAnim: marks the texture creations of a sprite animation's frames (each frame is its own small texture, drawn into
 * the atlas on the ticks that need it) so the device keeps them resident. Does nothing without the flag.
 */
@Mixin(targets = "net.minecraft.client.renderer.texture.SpriteContents$AnimatedTexture")
public class AnimFramesResidentMixin {
	@Inject(method = "createAnimationState", at = @At("HEAD"))
	private void mcopt$residentHead(CallbackInfoReturnable<?> cir) {
		ResidentAnim.creating = ResidentAnim.ON;
	}

	@Inject(method = "createAnimationState", at = @At("RETURN"))
	private void mcopt$residentReturn(CallbackInfoReturnable<?> cir) {
		ResidentAnim.creating = false;
	}
}

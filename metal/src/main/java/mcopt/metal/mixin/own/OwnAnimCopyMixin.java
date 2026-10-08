package mcopt.metal.mixin.own;

import mcopt.metal.own.AnimCopy;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** -Dmcopt.own.int.animCopy: the atlas's animation draws through AnimCopy (a small target and copies) instead of passes over whole mips. */
@Mixin(TextureAtlas.class)
public class OwnAnimCopyMixin {
	@Inject(method = "uploadAnimationFrames", at = @At("HEAD"), cancellable = true)
	private void mcopt$animCopy(CallbackInfo ci) {
		if (mcopt.metal.own.AnimOnePass.upload((TextureAtlas) (Object) this) || !mcopt.metal.own.AnimOnePass.ON && AnimCopy.upload((TextureAtlas) (Object) this)) ci.cancel();
	}

	@Inject(method = "uploadAnimationFrames", at = @At("RETURN"))
	private void mcopt$animVerify(CallbackInfo ci) {
		AnimCopy.afterVanilla((TextureAtlas) (Object) this);
	}

	@Inject(method = "tick", at = @At("RETURN"))
	private void mcopt$animHash(CallbackInfo ci) {
		AnimCopy.ticked((TextureAtlas) (Object) this);
	}
}

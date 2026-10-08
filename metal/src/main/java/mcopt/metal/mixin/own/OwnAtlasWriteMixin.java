package mcopt.metal.mixin.own;

import mcopt.metal.own.AnimCopy;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** -Dmcopt.own.int.atlasWrite: marks the animated atlases' texture creation (blocks, gui, particles) so it gets shader-write usage. */
@Mixin(TextureAtlas.class)
public class OwnAtlasWriteMixin {
	@Inject(method = "createTexture", at = @At("HEAD"))
	private void mcopt$atlasWriteHead(int width, int height, int mipLevel, CallbackInfo ci) {
		AnimCopy.creatingAtlas((TextureAtlas) (Object) this, true);
	}

	@Inject(method = "createTexture", at = @At("RETURN"))
	private void mcopt$atlasWriteReturn(int width, int height, int mipLevel, CallbackInfo ci) {
		AnimCopy.creatingAtlas((TextureAtlas) (Object) this, false);
	}
}

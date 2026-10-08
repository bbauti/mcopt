package mcopt.metal.mixin.own;

import com.mojang.blaze3d.vertex.QuadInstance;
import mcopt.metal.own.OwnMaterials;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Native shading on our near terrain (OwnMaterials.ON), section compiles only: the G-buffer wants ambient occlusion apart from the
 * albedo (the lighting applies it to the ambient term, not to direct sun). Vanilla's lighter leaves each corner's occlusion as a
 * grey colour, which the tint then multiplies; here the grey is taken out before the tint (the corners start white, so they get
 * the tint exactly) and put back as the corners' alpha, which terrain vertices otherwise keep at 255. gbuffer.metal reads it so.
 */
@Mixin(ModelBlockRenderer.class)
abstract class OwnIntAoMixin {
	@Shadow
	@Final
	private QuadInstance quadInstance;
	@Unique
	private final int[] mcopt$ao = new int[4];
	@Unique
	private boolean mcopt$split;

	@Inject(method = "putQuadWithTint", at = @At("HEAD"))
	private void mcopt$aoOut(CallbackInfo ci) {
		this.mcopt$split = OwnMaterials.ON && OwnMaterials.compiling();
		if (!this.mcopt$split) return;
		for (int v = 0; v < 4; v++) {
			int c = this.quadInstance.getColor(v);
			this.mcopt$ao[v] = c >>> 8 & 0xFF;  // the grey's green channel (r = g = b)
			this.quadInstance.setColor(v, -1);
		}
	}

	@Inject(method = "putQuadWithTint", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/block/BlockQuadOutput;put(FFFLnet/minecraft/client/resources/model/geometry/BakedQuad;Lcom/mojang/blaze3d/vertex/QuadInstance;)V"))
	private void mcopt$aoIn(CallbackInfo ci) {
		if (!this.mcopt$split) return;
		for (int v = 0; v < 4; v++) this.quadInstance.setColor(v, this.quadInstance.getColor(v) & 0x00FFFFFF | this.mcopt$ao[v] << 24);
	}
}

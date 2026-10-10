package mcopt.metal.mixin.lod;

import mcopt.metal.lod.Lod;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Far terrain: the level's projection as GameRenderer.renderLevel uploads it (view bobbing, the hurt tilt, nausea, other mods' camera
 * rolls such as Camera Overhaul's), for Lod's frame. Optional: without it (another mod changed that call) the camera's projection alone.
 */
@Mixin(GameRenderer.class)
abstract class LodGameRendererMixin {
	@ModifyArg(method = "renderLevel", require = 0, at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"))
	private Matrix4f mcopt$lodProjection(Matrix4f projection) {
		Lod.levelProjection(projection);
		return projection;
	}
}

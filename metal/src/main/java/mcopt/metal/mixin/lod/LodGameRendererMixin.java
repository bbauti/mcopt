package mcopt.metal.mixin.lod;

import mcopt.metal.lod.Lod;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Far terrain: the level's projection as the game draws with it, the camera's projection times what GameRenderer.renderLevel
 * multiplies in before uploading it (view bobbing, the hurt tilt, nausea, and other mods' camera rolls applied there, such as
 * Camera Overhaul's): taken as it goes to the projection buffer, read back by Lod's frame. Optional: without it (another mod
 * changed that call) far terrain uses the camera's projection alone.
 */
@Mixin(GameRenderer.class)
abstract class LodGameRendererMixin {
	@ModifyArg(method = "renderLevel", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"),
		require = 0)
	private Matrix4f mcopt$lodProjection(Matrix4f projection) {
		Lod.levelProjection(projection);
		return projection;
	}
}

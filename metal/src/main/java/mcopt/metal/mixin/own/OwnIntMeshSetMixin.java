package mcopt.metal.mixin.own;

import mcopt.metal.own.OwnVisible;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** -Dmcopt.own.int.visible (OwnVisible): sections whose new mesh renders block entities (after the mesh is set). */
@Mixin(SectionRenderDispatcher.RenderSection.class)
abstract class OwnIntMeshSetMixin {
	@Inject(method = "setSectionMesh", at = @At("RETURN"))
	private void mcopt$meshSet(SectionMesh sectionMesh, CallbackInfoReturnable<SectionMesh> cir) {
		if (OwnVisible.ON || OwnVisible.VERIFY) OwnVisible.meshSet((SectionRenderDispatcher.RenderSection) (Object) this);
	}
}

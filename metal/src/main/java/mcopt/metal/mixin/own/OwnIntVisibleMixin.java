package mcopt.metal.mixin.own;

import java.util.List;
import mcopt.metal.own.OwnVisible;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** -Dmcopt.own.int.visible (OwnVisible): each section's position as vanilla adds it to its visible list. */
@Mixin(SectionOcclusionGraph.class)
abstract class OwnIntVisibleMixin {
	@Redirect(method = "lambda$addSectionsInFrustum$0", at = @At(value = "INVOKE", target = "Ljava/util/List;add(Ljava/lang/Object;)Z", ordinal = 0))
	private static boolean mcopt$stampVisible(List<Object> list, Object section) {
		if (OwnVisible.ON || OwnVisible.VERIFY) OwnVisible.added((SectionRenderDispatcher.RenderSection) section, list.size());
		return list.add(section);
	}
}

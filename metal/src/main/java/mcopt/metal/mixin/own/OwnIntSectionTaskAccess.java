package mcopt.metal.mixin.own;

import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** OwnLife: a section task's cancellation flag. */
@Mixin(SectionRenderDispatcher.RenderSection.SectionTask.class)
public interface OwnIntSectionTaskAccess {
	@Accessor("isCancelled")
	AtomicBoolean mcopt$cancelled();
}

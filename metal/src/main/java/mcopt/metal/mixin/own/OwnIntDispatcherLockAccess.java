package mcopt.metal.mixin.own;

import java.util.concurrent.locks.ReentrantLock;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** OwnLife's exception test: the dispatcher's copy lock. */
@Mixin(SectionRenderDispatcher.class)
public interface OwnIntDispatcherLockAccess {
	@Accessor("copyLock")
	ReentrantLock mcopt$copyLock();
}

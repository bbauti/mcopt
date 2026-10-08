package mcopt.metal.mixin.own;

import com.mojang.blaze3d.vertex.UberGpuBuffer;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** -Dmcopt.own.int.lists (OwnIntListsMixin): whether one of vanilla's uber buffers holds any section's geometry. */
@Mixin(UberGpuBuffer.class)
public interface OwnIntUberAccess {
	@Accessor("allocationMap")
	Map<?, ?> mcopt$allocations();
}

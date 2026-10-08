package mcopt.metal.mixin.ownmesh;

import mcopt.metal.own.OwnRegionAccess;
import net.minecraft.client.renderer.chunk.SectionCopy;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** -Dmcopt.own.mesh.prefill: a section copy's states and debug flag, for OwnRegion's unpack. */
@Mixin(SectionCopy.class)
abstract class OwnMeshSectionCopyMixin implements OwnRegionAccess.Copy {
	@Shadow
	@Final
	private @Nullable PalettedContainer<BlockState> section;
	@Shadow
	@Final
	private boolean debug;

	@Override
	public @Nullable PalettedContainer<BlockState> mcopt$section() {
		return this.section;
	}

	@Override
	public boolean mcopt$debug() {
		return this.debug;
	}
}

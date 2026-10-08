package mcopt.metal.mixin.ownmesh;

import mcopt.metal.own.OwnRegionAccess;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCopy;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** -Dmcopt.own.mesh.prefill: the region's section copies, for OwnRegion's unpack. */
@Mixin(RenderSectionRegion.class)
abstract class OwnMeshRegionAccessMixin implements OwnRegionAccess {
	@Shadow
	@Final
	private SectionCopy[] sections;

	@org.spongepowered.asm.mixin.Unique
	private mcopt.metal.own.@org.jspecify.annotations.Nullable OwnRegion mcopt$own;

	@Override
	public mcopt.metal.own.@org.jspecify.annotations.Nullable OwnRegion mcopt$own() {
		return this.mcopt$own;
	}

	@Override
	public void mcopt$own(mcopt.metal.own.@org.jspecify.annotations.Nullable OwnRegion own) {
		this.mcopt$own = own;
	}

	@Override
	public SectionCopy[] mcopt$sections() {
		return this.sections;
	}
}

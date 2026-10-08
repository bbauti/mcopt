package mcopt.metal.mixin.ownmesh;

import com.mojang.blaze3d.vertex.VertexSorting;
import mcopt.metal.own.OwnMeshStats;
import mcopt.metal.own.OwnMeshVerify;
import mcopt.metal.own.OwnMesher;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** -Dmcopt.own.mesh=true: our mesher instead of vanilla's compile; =verify: the digest check after it; .stats: per-section costs. */
@Mixin(value = SectionCompiler.class, priority = 900)
abstract class OwnMeshCompilerMixin {
	@Shadow
	@Final
	private boolean ambientOcclusion;
	@Shadow
	@Final
	private boolean cutoutLeaves;
	@Shadow
	@Final
	private BlockStateModelSet blockModelSet;
	@Shadow
	@Final
	private FluidStateModelSet fluidModelSet;
	@Shadow
	@Final
	private BlockColors blockColors;

	@Inject(method = "compile", at = @At("HEAD"), cancellable = true)
	private void mcopt$ownMesh(SectionPos sectionPos, RenderSectionRegion region, VertexSorting vertexSorting, SectionBufferBuilderPack builders,
		CallbackInfoReturnable<SectionCompiler.Results> cir) {
		if (OwnMesher.VERIFY) OwnMeshVerify.before(this.ambientOcclusion, this.cutoutLeaves, this.blockModelSet, this.fluidModelSet, this.blockColors,
			sectionPos, region, builders);
		if (OwnMeshStats.ON) OwnMeshStats.start();
		if (!OwnMesher.ON) return;
		SectionCompiler.Results results = OwnMesher.compile(this.ambientOcclusion, this.cutoutLeaves, this.blockModelSet, this.fluidModelSet, this.blockColors,
			sectionPos, region, vertexSorting, builders);
		if (results == null) return;
		if (OwnMeshStats.ON) OwnMeshStats.end();
		cir.setReturnValue(results);
	}

	@Inject(method = "compile", at = @At("RETURN"))
	private void mcopt$ownMeshVanilla(SectionPos sectionPos, RenderSectionRegion region, VertexSorting vertexSorting, SectionBufferBuilderPack builders,
		CallbackInfoReturnable<SectionCompiler.Results> cir) {
		if (cir.getReturnValue() == null) return;
		if (OwnMeshStats.ON) OwnMeshStats.end();
		if (OwnMesher.VERIFY) OwnMeshVerify.check(sectionPos, cir.getReturnValue());
	}
}

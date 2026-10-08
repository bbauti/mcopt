package mcopt.metal.mixin.ownmesh;

import mcopt.metal.own.OwnQuadHolder;
import mcopt.metal.own.OwnQuads;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** -Dmcopt.own.mesh=true: a compile's own quads, from our mesher to the compiled mesh. */
@Mixin(SectionCompiler.Results.class)
abstract class OwnMeshResultsMixin implements OwnQuadHolder {
	@Unique
	private @Nullable OwnQuads mcopt$quads;

	@Override
	public @Nullable OwnQuads mcopt$quads() {
		return this.mcopt$quads;
	}

	@Override
	public void mcopt$setQuads(@Nullable OwnQuads quads) {
		this.mcopt$quads = quads;
	}
}

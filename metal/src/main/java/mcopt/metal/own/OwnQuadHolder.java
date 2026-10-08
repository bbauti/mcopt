package mcopt.metal.own;

import org.jspecify.annotations.Nullable;

/** -Dmcopt.own.mesh: mixed into SectionCompiler.Results and CompiledSectionMesh, carries a compile's OwnQuads to the upload hook. */
public interface OwnQuadHolder {
	@Nullable OwnQuads mcopt$quads();

	void mcopt$setQuads(@Nullable OwnQuads quads);
}

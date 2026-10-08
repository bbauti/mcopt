package mcopt.metal.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** -Dmcopt.metal.atlasDouble: an animation's dirty flag (set for a sprite the spare copy lacks, so vanilla redraws its current frame there). */
@Mixin(targets = "net.minecraft.client.renderer.texture.SpriteContents$AnimationState")
public interface AtlasDoubleStateAccess {
	@Accessor("isDirty")
	boolean mcopt$adDirty();

	@Accessor("isDirty")
	void mcopt$adSetDirty(boolean dirty);
}

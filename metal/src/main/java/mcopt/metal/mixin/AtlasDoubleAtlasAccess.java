package mcopt.metal.mixin;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.List;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** -Dmcopt.metal.atlasDouble: the atlas's sprites, animation states, per-mip views (the animation passes' targets) and size. */
@Mixin(TextureAtlas.class)
public interface AtlasDoubleAtlasAccess {
	@Accessor("sprites")
	List<TextureAtlasSprite> mcopt$adSprites();

	@Accessor("animatedTexturesStates")
	List<SpriteContents.AnimationState> mcopt$adStates();

	@Accessor("mipViews")
	GpuTextureView[] mcopt$adMipViews();

	@Accessor("mipViews")
	void mcopt$adSetMipViews(GpuTextureView[] views);

	@Accessor("maxMipLevel")
	int mcopt$adMaxMip();

	@Accessor("width")
	int mcopt$adWidth();

	@Accessor("height")
	int mcopt$adHeight();
}

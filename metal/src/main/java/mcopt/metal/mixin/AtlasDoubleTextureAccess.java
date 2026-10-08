package mcopt.metal.mixin;

import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** -Dmcopt.metal.atlasDouble: the texture and view everything samples (swapped between the atlas's two copies). */
@Mixin(AbstractTexture.class)
public interface AtlasDoubleTextureAccess {
	@Accessor("texture")
	void mcopt$adSetTexture(GpuTexture texture);

	@Accessor("textureView")
	void mcopt$adSetTextureView(GpuTextureView view);

	@Accessor("textureView")
	GpuTextureView mcopt$adTextureView();
}

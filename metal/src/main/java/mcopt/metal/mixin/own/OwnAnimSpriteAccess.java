package mcopt.metal.mixin.own;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** -Dmcopt.own.int.animCopy: a sprite's padding (its animation quad is the sprite plus the padding on each side). */
@Mixin(TextureAtlasSprite.class)
public interface OwnAnimSpriteAccess {
	@Accessor("padding")
	int mcopt$padding();
}

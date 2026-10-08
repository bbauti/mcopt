package mcopt.metal.mixin.own;

import mcopt.metal.own.OwnSolid;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Solid-section occlusion: a chunk leaving the client stops occluding (OwnSolid). */
@Mixin(ClientLevel.class)
abstract class OwnSolidLevelMixin {
	@Inject(method = "unload", at = @At("HEAD"))
	private void mcopt$solidUnload(LevelChunk levelChunk, CallbackInfo ci) {
		OwnSolid.unloaded(levelChunk.getPos().x(), levelChunk.getPos().z());
	}
}

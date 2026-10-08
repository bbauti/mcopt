package mcopt.metal.mixin.ownmesh;

import mcopt.metal.own.OwnTintHook;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ColorResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * -Dmcopt.own.mesh.tint: a tint cache miss during our compile is blended from that compile's biome and colour arrays (same
 * integer result; vanilla's BlockTintCache still stores it). Any other caller: untouched.
 */
@Mixin(ClientLevel.class)
abstract class OwnMeshTintMixin {
	@Inject(method = "calculateBlockTint", at = @At("HEAD"), cancellable = true)
	private void mcopt$ownTint(BlockPos pos, ColorResolver colorResolver, CallbackInfoReturnable<Integer> cir) {
		Integer v = OwnTintHook.blend((ClientLevel) (Object) this, pos, colorResolver);
		if (v != null) cir.setReturnValue(v);
	}
}

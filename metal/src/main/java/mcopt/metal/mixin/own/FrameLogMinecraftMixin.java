package mcopt.metal.mixin.own;

import mcopt.metal.FrameLog;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Measurement only (-Dmcopt.own.int.frameLog): the client tick's time in the frame it runs in. */
@Mixin(Minecraft.class)
public class FrameLogMinecraftMixin {
	@Inject(method = "tick", at = @At("HEAD"))
	private void mcopt$frameLogTickHead(CallbackInfo ci) {
		FrameLog.begin(FrameLog.TICK);
	}

	@Inject(method = "tick", at = @At("RETURN"))
	private void mcopt$frameLogTickReturn(CallbackInfo ci) {
		FrameLog.end(FrameLog.TICK);
	}
}

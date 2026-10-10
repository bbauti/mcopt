package mcopt.metal.mixin.ui;

import mcopt.metal.LodSettingsScreen;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Options > Video Settings gets a "Far Terrain..." button: mcopt's far-terrain settings (LodSettingsScreen). */
@Mixin(VideoSettingsScreen.class)
abstract class LodVideoSettingsMixin extends OptionsSubScreen {
	private LodVideoSettingsMixin(Screen lastScreen, Options options, Component title) {
		super(lastScreen, options, title);
	}

	@Inject(method = "addOptions", at = @At("TAIL"))
	private void mcopt$farTerrainButton(CallbackInfo ci) {
		if (this.list == null) return;
		this.list.addBig(Button.builder(Component.literal("Far Terrain (mcopt)..."), b -> this.minecraft.gui.setScreen(new LodSettingsScreen(this)))
			.width(310).build());
	}
}

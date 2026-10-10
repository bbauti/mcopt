package mcopt.metal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import mcopt.api.McoptFarTerrain;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Far terrain's settings in the game (Options > Video Settings > Far Terrain...): its switches as buttons, written to
 * config/mcopt.properties on Done (they apply at the next launch: they're read once at startup), and Show, which hides
 * or shows it now. Reads the settings as the game runs with them; never loads far terrain's classes itself (with it off,
 * nothing of it loads).
 */
public final class LodSettingsScreen extends Screen {
	private static final String[] QUALITIES = {"auto", "low", "medium", "high", "ultra"};
	/** Reach choices in chunks; 0: the quality's own. */
	private static final Integer[] RADII = {0, 128, 192, 256, 384, 512, 768, 1024, 1536, 2048};

	private final Screen parent;
	private final Map<String, String> changed = new LinkedHashMap<>();
	private boolean restartNeeded;

	public LodSettingsScreen(Screen parent) {
		super(Component.literal("Far Terrain (mcopt)"));
		this.parent = parent;
	}

	private static boolean flag(String key, boolean def) {
		String v = System.getProperty(key);
		return v == null ? def : Boolean.parseBoolean(v.strip());
	}

	@Override
	protected void init() {
		List<CycleButton<?>> buttons = new ArrayList<>();
		boolean on = McoptFarTerrain.isEnabled();
		buttons.add(this.toggle("Far terrain", "mcopt.lod", on));
		String quality = System.getProperty("mcopt.lod.quality", "auto").strip().toLowerCase(Locale.ROOT);
		if (!List.of(QUALITIES).contains(quality)) quality = "auto";
		buttons.add(CycleButton.builder((Function<String, Component>) q -> Component.literal(q.substring(0, 1).toUpperCase(Locale.ROOT) + q.substring(1)), quality)
			.withValues(QUALITIES).create(0, 0, 150, 20, Component.literal("Quality"), (b, v) -> this.set("mcopt.lod.quality", v)));
		int radius = Integer.getInteger("mcopt.lod.radius", 0);
		Integer r0 = radius;
		if (!List.of(RADII).contains(r0)) r0 = 0;
		buttons.add(CycleButton.builder((Function<Integer, Component>) r -> Component.literal(r == 0 ? "By quality" : r + " chunks"), r0).withValues(RADII)
			.create(0, 0, 150, 20, Component.literal("Distance"), (b, v) -> this.set("mcopt.lod.radius", v == 0 ? null : String.valueOf(v))));
		buttons.add(this.toggle("On servers", "mcopt.lod.multiplayer", flag("mcopt.lod.multiplayer", true)));
		buttons.add(this.toggle("Import saved chunks", "mcopt.lod.import", flag("mcopt.lod.import", true)));
		buttons.add(this.toggle("Nether under its roof", "mcopt.lod.ceiling", flag("mcopt.lod.ceiling", true)));
		buttons.add(this.toggle("Trees", "mcopt.lod.trees", flag("mcopt.lod.trees", true)));
		buttons.add(this.toggle("Plants", "mcopt.lod.plants", flag("mcopt.lod.plants", !"low".equals(quality))));
		buttons.add(this.toggle("Block textures", "mcopt.lod.textures", flag("mcopt.lod.textures", true)));
		buttons.add(this.toggle("Clear water", "mcopt.lod.clearWater", flag("mcopt.lod.clearWater", true)));
		// (applies now: hides or shows it without a restart)
		CycleButton<Boolean> show = CycleButton.onOffBuilder(McoptFarTerrain.isDrawEnabled()).create(0, 0, 150, 20, Component.literal("Show now"),
			(b, v) -> McoptFarTerrain.setDrawEnabled(v));
		show.active = on;
		buttons.add(show);
		int x0 = this.width / 2 - 155, y0 = 40;
		for (int i = 0; i < buttons.size(); i++) {
			CycleButton<?> b = buttons.get(i);
			b.setPosition(x0 + (i % 2) * 160, y0 + (i / 2) * 24);
			this.addRenderableWidget(b);
		}
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose()).bounds(this.width / 2 - 100, this.height - 27, 200, 20).build());
	}

	private CycleButton<Boolean> toggle(String label, String key, boolean value) {
		return CycleButton.onOffBuilder(value).create(0, 0, 150, 20, Component.literal(label), (b, v) -> this.set(key, String.valueOf(v)));
	}

	private void set(String key, String value) {
		this.changed.put(key, value);
		this.restartNeeded = true;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		super.extractRenderState(g, mouseX, mouseY, partial);
		g.centeredText(this.font, this.title, this.width / 2, 15, 0xFFFFFFFF);
		String note = this.restartNeeded ? "Saved on Done; applies the next time the game starts." : "Far terrain's settings apply the next time the game starts.";
		g.centeredText(this.font, note, this.width / 2, this.height - 42, 0xFFA0A0A0);
	}

	@Override
	public void onClose() {
		if (!this.changed.isEmpty()) {
			// far terrain off: no radius either (a radius alone turns it on)
			if ("false".equals(this.changed.get("mcopt.lod"))) this.changed.put("mcopt.lod.radius", null);
			ConfigFile.set(this.changed);
		}
		this.minecraft.gui.setScreen(this.parent);
	}

	/** config/mcopt.properties edited in place: a key's line (commented out or not) replaced, else added; null comments it out. */
	static final class ConfigFile {
		private ConfigFile() {
		}

		static void set(Map<String, String> values) {
			Path cfg = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("mcopt.properties");
			try {
				List<String> lines = Files.isRegularFile(cfg) ? new ArrayList<>(Files.readAllLines(cfg, StandardCharsets.UTF_8)) : new ArrayList<>();
				for (var e : values.entrySet()) {
					String key = e.getKey(), line = e.getValue() == null ? null : key + "=" + e.getValue();
					int at = -1;
					for (int i = 0; i < lines.size(); i++) {
						String t = lines.get(i).strip();
						if (t.startsWith("#")) t = t.substring(1).strip();
						if (t.startsWith(key) && t.substring(key.length()).strip().startsWith("=")) {
							// (an uncommented line wins over a commented one)
							if (at < 0 || !lines.get(i).strip().startsWith("#")) at = i;
						}
					}
					if (line == null) {
						if (at >= 0 && !lines.get(at).strip().startsWith("#")) lines.set(at, "#" + lines.get(at).strip());
					} else if (at >= 0) {
						lines.set(at, line);
					} else {
						lines.add(line);
					}
				}
				Files.createDirectories(cfg.getParent());
				Path tmp = cfg.resolveSibling("mcopt.properties.tmp");
				Files.write(tmp, lines, StandardCharsets.UTF_8);
				Files.move(tmp, cfg, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
				System.out.println("[mcopt] settings saved to " + cfg + ": " + values);
			} catch (IOException e) {
				System.out.println("[mcopt] can't save settings to " + cfg + ": " + e);
			}
		}
	}
}

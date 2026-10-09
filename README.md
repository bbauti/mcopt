# mcopt

> [!NOTE]
> mcopt no longer requires Sodium 🎉

A Minecraft performance mod for Apple Silicon Macs. Since 0.3.0-alpha.1 it draws the world with its own Metal renderer.
**Very much alpha:** expect bugs, and back up your worlds.

## Requirements

- An Apple Silicon Mac on macOS 26 or later
- Java 25 (the official launcher and Prism download it for 26.3; nothing to install)
- Minecraft 26.3 with Fabric Loader 0.19.5 or newer

## Install

1. Install Fabric for Minecraft 26.3: https://fabricmc.net/use/installer/ (or in Prism Launcher: new instance, 26.3,
   loader Fabric).
2. Download the mcopt jar from [Releases](../../releases).
3. Put it in `~/Library/Application Support/minecraft/mods`. Create the `mods` folder if it's missing
   (Finder: Go > Go to Folder). In Prism: Edit the instance > Mods > Add file.
4. Start Minecraft with the Fabric profile.

Sodium isn't needed anymore: mcopt won't start alongside it, so take Sodium out of that folder if it's there. Fabric API
isn't required; keep it if your other mods need it.

To check it's working: press F3 (fn + F3 on a Mac keyboard); the bottom right says `Metal 3 (mcopt)`.

Shows an fps counter in the top-left corner (hidden with F1 or F3; fn + F1 or fn + F3 on a Mac keyboard).

## Settings

- The perf profile is on by default.
- `profile=none` in `~/Library/Application Support/minecraft/config/mcopt.properties` (in Prism, the instance's
  `minecraft/config` folder) turns it off. The first launch writes that file.
- Far terrain is an experimental opt-in: `mcopt.lod=true` in the same file. See [Far terrain](#far-terrain).

## Far terrain

mcopt's LODs: terrain past the render distance, out to 512 chunks by default, drawn by its Metal renderer.

- **Singleplayer:** generated ahead from the world's own noise (its density functions, biomes, surface rules and trees,
  datapacks included), so new terrain is there before you reach it; the chunks you load replace the estimate with what's
  really there.
- **Servers, flat and modded generators, the Nether:** built from the chunks the client receives (under the Nether's roof)
  and kept on disk per server and dimension: what you've seen once is there next time.
- **Your saved world too:** in singleplayer, the chunks already saved in the world (explored before, builds included) are
  read in the background once and replace the estimate there.
- **Kept on disk:** `mcopt-lod/` in the game folder, compressed. Places you've seen from close by stay when you see them
  from farther away. Saved every few seconds and when you leave the world.
- **F6** hides and shows it while playing.

Settings: Options > Video Settings > **Far Terrain (mcopt)...**, or in `config/mcopt.properties` (restart to apply):

| Setting | Default | |
|---|---|---|
| `mcopt.lod` | `false` | Far terrain on |
| `mcopt.lod.quality` | `auto` | `low`, `medium`, `high`, `ultra`; `auto` picks `low` under 10 GPU cores, `high` up to 29, `ultra` from 30 |
| `mcopt.lod.radius` | by quality (256 / 384 / 512 / 1024) | Reach in chunks |
| `mcopt.lod.multiplayer` | `true` | Far terrain on servers |
| `mcopt.lod.chunksOnly` | `true` | Far terrain where it can't be generated (servers, flat and modded generators, the Nether) |
| `mcopt.lod.ceiling` | `true` | The Nether seen under its roof |
| `mcopt.lod.dimensions` / `mcopt.lod.excludeDimensions` | (all) | Comma-separated dimension ids, e.g. `minecraft:the_end` |
| `mcopt.lod.toggleKey` | `F6` | `F1`-`F12`, or `none` |
| `mcopt.lod.threads` | cores - 4 | Generation threads |
| `mcopt.lod.plants`, `mcopt.lod.trees`, `mcopt.lod.textures` | by quality / `true` | Detail on the nearest far terrain |
| `mcopt.lod.import` | `true` | Read the singleplayer world's saved chunks |
| `mcopt.lod.cache` | `true` | The disk cache (`mcopt.lod.cacheDir` moves it) |

Modded blocks get their colors from their own textures. For blocks whose models can't be read that way (dynamic or
connected textures), `config/mcopt-lod-colors.properties` sets them: `modid:block=RRGGBB` or `modid:block=RRGGBB,RRGGBB`
(top, sides).

For mod developers: `mcopt.api.McoptFarTerrain` (is it drawing, its reach, hide/show, re-read a chunk, set a block's
colors). Safe to call with far terrain off.

## Numbers

fps spinning / flying, same test world, 1920x1080, render distance 16, VSync off, at least two runs each:

| Mac | vanilla | Sodium 0.9.3 | 0.2.0-alpha.3 | 0.3.0-alpha.1 |
|---|---|---|---|---|
| Mac mini M4 (10-core GPU, 16 GB) | 156 / 146 | 275-281 / 253-267 | 1202-1223 / 1068-1074 | 1209-1211 / 1068-1069 |
| MacBook Neo (A18 Pro, 5-core GPU, 8 GB) | 79-81 / 69-72 | 133-146 / 85-91 | 514-579 / 422-449 | 591-638 / 477-504 |
| Mac mini M6 (12-core GPU, 16 GB) | 297-328 / 283-291 | 454-460 / 406-413 | 1988-1991 / 1738-1742 | 2020-2022 / 1690-1704 |

The mini and Neo cells ran on 6c133ecc, whose only difference from 9865a0a3 is vGroup, which is gated off on those GPUs. The Neo's vanilla and Sodium cells come from the da566d00 run. This build differs from 9865a0a3 only in code that runs when Distant Horizons is installed.

For the most fps: Options > Video Settings, VSync off and Max Framerate Unlimited. The numbers above are with VSync off.

## Known issues

- Mods that require Sodium (for example Better Block Entities or Iris) can't be used with this build.
- Mods that call OpenGL directly can't draw on the Metal backend. To switch mcopt's renderer off, add
  `mcopt.metal=false` to `config/mcopt.properties` (or launch with `-Dmcopt.metal=false`).
- Distant Horizons runs on the Metal renderer next to mcopt's own terrain. With DH set to its OpenGL renderer, mcopt
  switches its renderer off for it.
- Far terrain is experimental. On Macs with fewer than 10 GPU cores, `quality=auto` picks `low`; it still costs fps
  there.
- On a server, far terrain only shows what you've already seen there (the client has no seed to generate from).
- With Distant Horizons installed, mcopt's far terrain stays off (DH draws the distance);
  `mcopt.lod.withDistantHorizons=true` keeps both.

## Plans

- Far terrain on by default, with tiers for smaller Macs
- Our own shaders (BSL-level) as an option
- Faster world generation (native noise, bit-exact)
- More exact server-tick and chunk optimizations, held for later alphas
- Faster startup
- Wider hardware testing

## Licence and credits

[Apache License 2.0](LICENSE). Keep the [NOTICE](NOTICE) file with any copy, and credit mcopt.

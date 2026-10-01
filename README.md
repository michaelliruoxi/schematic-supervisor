# Schematic Supervisor

A Fabric client mod for Minecraft 1.21.8 that builds a [Litematica](https://modrinth.com/mod/litematica)
schematic for you. It flies over the build, places it one layer at a time, restocks from chests you
register, tills and plants farmland, and checks the finished build twice. An optional Windows app,
the **monitor**, shows the progress and tells you when the build needs help.

> [!WARNING]
> Many servers forbid automation mods. Check the rules before you use this mod anywhere other than
> your own world or server.

- [Features](#features)
- [What it can build](#what-it-can-build)
- [Install the mod](#install-the-mod)
- [Install the monitor (optional)](#install-the-monitor-optional)
- [Build your first schematic](#build-your-first-schematic)
- [Commands](#commands)
- [Settings](#settings)
- [Troubleshooting](#troubleshooting)
- [Build from source](#build-from-source)
- [Development](#development)
- [More documentation](#more-documentation)

## Features

- **Builds on its own.** It finishes each layer across the whole footprint before the next one,
  working through the area chunk by chunk.
- **Restocks itself.** When it runs low, it flies to the chests you registered and takes what it
  needs. Buying from a server shop is possible but off by default.
- **Farms.** It tills farmland and plants wheat, top floor first.
- **Picks up where it left off.** Every Start first checks what is already built, so finished work
  is never redone, and progress survives closing the game.
- **Recovers from common problems.** It takes off again if it loses flight, and retries for a few
  minutes when the server lags. Anything it can't fix pauses the build with the reason and a fix.
- **Checks its work.** A build only counts as done after two matching passes over the whole volume.
- **Desktop monitor** (Windows, optional): progress, a chunk map, time remaining, Pause and
  Continue buttons, and alerts on your desktop or phone.

## What it can build

- **Blocks:** farmland, wheat, and any full block without properties, such as dirt, stone, planks,
  wool, concrete, glass, or glowstone. Blocks that face a direction or have other states (logs,
  stairs, slabs), blocks that fall (sand, gravel), and blocks that store things (chests) are not
  supported. When you load a schematic, the mod names any block it can't build and where it is.
- **One region:** the selected Litematica placement must have exactly one enabled sub-region, and no
  other enabled placement may overlap it.
- **Flight:** you must be allowed to fly, for example through a server's `/fly`. The mod takes off by
  itself but never grants flight or sends `/fly`.
- **Materials:** they come from chests you register near the build.

An example is included: [`schematics/wheatfarm_v2.litematic`](schematics/wheatfarm_v2.litematic), a
112 × 76 × 112 stacked wheat farm that covers 7 × 7 chunks. Its materials and build order are in
[`schematics/README.md`](schematics/README.md).

## Install the mod

You need Minecraft Java Edition 1.21.8 with Fabric Loader, plus these mods:

| Mod | Version | Where to get it |
| --- | --- | --- |
| Fabric Loader | 0.19.3 or newer | [Fabric installer](https://fabricmc.net/use/installer/) |
| Fabric API | 0.136.1+1.21.8 | [Modrinth](https://modrinth.com/mod/fabric-api) |
| MaLiLib | 0.25.7 | [Modrinth](https://modrinth.com/mod/malilib) |
| Litematica | 0.23.7 | [Modrinth](https://modrinth.com/mod/litematica) |
| Baritone API (Fabric) | 1.15.0 | [Baritone v1.15.0 on GitHub](https://github.com/cabaletta/baritone/releases/tag/v1.15.0): `baritone-api-fabric-1.15.0.jar` |
| Schematic Supervisor | 0.2.0 | [Releases](https://github.com/michaelliruoxi/schematic-supervisor/releases) |

1. Run the Fabric installer and install Fabric Loader for Minecraft 1.21.8.
2. Download the five mod jars in the table, each in its Minecraft 1.21.8 version. For Baritone, take
   the **API** build (`baritone-api-fabric-…`), not the standalone one.
3. Put the five jars in your `mods` folder. With the official launcher on Windows that is
   `%APPDATA%\.minecraft\mods`; other launchers have one per instance.
4. Start Minecraft with the Fabric profile. On first launch the mod creates its settings in
   `config\schematic-supervisor\` inside your game folder.

Don't use Litematica Printer with this mod: Start refuses to run while it's installed. During a build
the mod turns off Litematica's easy-place mode and stops Baritone from using throwaway blocks as
scaffolding, and it restores your settings when the build pauses or stops.

If the Releases page has no files yet, [build the mod yourself](#build-from-source). That also
collects the other four mod jars into one folder for you.

## Install the monitor (optional)

The monitor, `SchematicSupervisor.exe`, is a small Windows app. A card that stays on top of other
windows shows the build's progress and what it's doing; **Expand** opens a full window with a chunk
map, time remaining, materials, and every control (Start, Pause, Continue, Stop, Scan depots).

1. Download `SchematicSupervisor.exe` from
   [Releases](https://github.com/michaelliruoxi/schematic-supervisor/releases) and put it in any
   folder. It needs no installation and no Python.
2. Start Minecraft with the mod first, then double-click the exe.

It pairs with the mod by itself when your game folder is the official launcher's
(`%APPDATA%\.minecraft`). For any other launcher, point it at the pairing file in your game folder:

```powershell
.\SchematicSupervisor.exe --token-file "<game folder>\config\schematic-supervisor\protocol-token.txt"
```

The exe isn't code-signed, so Windows SmartScreen may warn you the first time; choose **More info**,
then **Run anyway**. Opening or closing the monitor never starts or stops a build. Phone alerts
(through ntfy or Discord) and the monitor's other options are described in
[`companion/README.md`](companion/README.md).

## Build your first schematic

1. **Join a world where you can fly** and go to the build site.
2. **Place the schematic.** In Litematica, load the schematic, create a placement where you want it
   built, and select that placement. If the schematic has several sub-regions, enable only one.
3. **Register your chests.** Put the materials in chests near the build. Look at a chest and run
   `/schematic-supervisor depot add`, or stand among them and run
   `/schematic-supervisor depot add-nearby` to register every chest within 8 blocks.
4. **Scan them.** Run `/schematic-supervisor depot scan`. The mod opens each chest to count what's
   inside; `/schematic-supervisor depot list` shows `unscanned` until a chest is done, and Start
   won't run until every scan has finished.
5. **Start.** Run `/schematic-supervisor start`, or press **Start** in the monitor's full window. The
   mod spends a few seconds checking what's already built, takes off, and starts building.

While it builds:

- **Keep your hands off the movement keys:** moving yourself pauses the build. You can open your
  inventory, the Esc menu, or chat, or switch to another window, and the build keeps going.
- **Pause and continue** at any time with `/schematic-supervisor pause` and
  `/schematic-supervisor resume`, or from the monitor. Closing the game saves the build paused;
  after you rejoin, run `resume`.
- **If the build pauses by itself,** `/schematic-supervisor status` or the monitor says why and how
  to fix it. Fix it, then run `resume`.

The build is finished when the status shows `DONE`.

## Commands

All commands run on your client; the server never sees them.

| Command | What it does |
| --- | --- |
| `/schematic-supervisor start` | Checks what's already built, then builds from the first unfinished part. |
| `/schematic-supervisor pause` | Pauses the build so it can be resumed. |
| `/schematic-supervisor resume` | Continues a paused build. |
| `/schematic-supervisor stop` | Ends the current run. The next `start` checks the build again and continues where it left off. |
| `/schematic-supervisor status` | Shows the state, progress, and the reason for any pause. `/schematic-supervisor` on its own does the same. |
| `/schematic-supervisor takeoff` | Takes off into flight, if the server lets you fly. |
| `/schematic-supervisor approach <x> <y> <z>` | While the build is paused, flies you to a spot inside it, for example to fix a block the build check reported. |
| `/schematic-supervisor depot add` | Registers the chest you're looking at. |
| `/schematic-supervisor depot add-nearby [radius]` | Registers every chest within `radius` blocks (default 8, at most 16 and 64 chests) while no build is running. |
| `/schematic-supervisor depot scan` | Scans every registered chest again. |
| `/schematic-supervisor depot list` | Lists the registered chests and what was found in them. |
| `/schematic-supervisor depot clear` | Forgets every registered chest in this world and dimension. |
| `/schematic-supervisor unload` | Puts a paused or stopped build aside, keeping its progress, so you can select another placement. |
| `/schematic-supervisor reset` | Discards the loaded build and its saved progress. Registered chests are kept. |
| `/schematic-supervisor buy-dirt` | Buys dirt from the server's shop while the build is paused or stopped. Needs [shop purchases](docs/operating.md#shop-purchases) turned on. |

## Settings

The settings live in `config\schematic-supervisor\settings.json` inside your game folder. The file is
created on first launch; edit it only while Minecraft is closed. A few you may want:

| Setting | Default | What it does |
| --- | --- | --- |
| `minimumFood` | `1` | Food items to keep in reserve. Use `0` on a server where you never get hungry. |
| `deferPlanting` | `false` | Builds, lights, and tills, but leaves the wheat unplanted. |
| `interactionCooldownTicks` | `4` | Minimum ticks between block placements and plantings. |
| `controlPort` | `8765` | The local port the monitor connects to. |

[Settings](docs/operating.md#settings) in the operating guide lists them all.

Shop purchases are off by default. To let the mod buy dirt, glowstone, and birch planks through a
server's `/shop`, create `config\schematic-supervisor\shop.json` as described in
[Shop purchases](docs/operating.md#shop-purchases). Its default menu layout matches one server's
shop, so another server needs its own layout in that file.

## Troubleshooting

- **Start won't run because you can't fly.** The mod needs the server's permission to fly and never
  grants it itself. Get flight, for example with `/fly`, then start again.
- **The mod refuses a block when the schematic loads.** That block isn't supported; see
  [What it can build](#what-it-can-build). Replace it in the schematic, or build that part yourself.
- **"Registered depots cannot satisfy the exact material shortage."** Your chests don't hold enough
  of a material. Add it to a registered chest, run `depot scan`, then `resume`.
- **The build check reports wrong or extra blocks.** The builder clears moss and stems, and a jack
  o'lantern where glowstone belongs, but leaves other blocks alone, and the build can't finish while
  they're there. Fix them by hand; while the build is paused, `approach <x> <y> <z>` flies you to
  them.
- **The build paused when I pressed a key.** Movement keys pause the build. Run `resume`.
- **Start refuses to run because of Litematica Printer.** Remove it from your `mods` folder.
- **The monitor can't connect.** Start Minecraft with the mod before the monitor. If your launcher
  doesn't use `%APPDATA%\.minecraft`, start the monitor with `--token-file`; see
  [Install the monitor](#install-the-monitor-optional).
- **The monitor's card is hidden behind Minecraft.** Run Minecraft windowed or borderless; exclusive
  fullscreen can cover it.

The [operating guide](docs/operating.md) explains how the build order, restocking, recovery, and
completion work in detail.

**Privacy:** the mod's control connection and the monitor use only `127.0.0.1` on your computer.
Neither sends anything over the internet unless you set up phone alerts or the optional agent
runner. The pairing file `protocol-token.txt` works like a password, so don't share it.

## Build from source

You need Git and JDK 21. The monitor also needs Python 3.11 or newer.

```powershell
git clone https://github.com/michaelliruoxi/schematic-supervisor.git
cd schematic-supervisor
.\gradlew.bat build stageAutomationMods
```

On macOS or Linux, run `./gradlew` instead of `.\gradlew.bat`. The build runs the tests and checks
the Baritone download against a pinned SHA-256 checksum. `build\automation-mods\` then holds the
mod jar and the exact Fabric API, MaLiLib, Litematica, and Baritone API jars it was tested with;
copy all five into your `mods` folder.

To build the monitor on Windows:

```powershell
cd companion
python -m pip install ".[build]"
.\build_exe.ps1 -PythonExe python
```

The exe is written to `companion\dist\SchematicSupervisor.exe`. To run the monitor from source
instead, run `python launcher.py` in `companion`.

## Development

| Path | Contents |
| --- | --- |
| `src/` | The Fabric mod and its Java tests |
| `companion/` | The monitor, the optional agent runner, and their tests |
| `scripts/` | Tools for a dedicated launcher profile, schematic inspection, and offline recovery, with tests in `scripts/tests/` |
| `schematics/` | The example farm and its measurements |
| `docs/` | Guides, with design notes in `docs/design/` |

`runtime/`, `data/`, `build/`, and `companion/dist/` are local folders that git ignores.

Run every test suite from the repository root (none of them start Minecraft):

```powershell
.\gradlew.bat check
python -m unittest discover -s scripts/tests
cd companion
python -m unittest discover -s tests
```

The classes that drive Minecraft (`MinecraftExecutionPort`, `MinecraftDepotPort`, and
`SupervisorRuntimeController`) can't run in a unit test, so their decisions live in small classes
that can: for example `ExecutionMode`, `ExecutionTickGate`, `TillTargetCheck`, `ControlAvailability`,
`TakeoffEnvironment`, and `WithdrawalTermination`. Put new decisions there too. Tests share four
fixtures: `SupervisorFakes` (fake ports for the core supervisor), `VanillaFlight` (one tick of
vanilla flight, including landing switching flight off), `WheatFarmModel` (the example farm's solid
blocks at any chunk-aligned origin), and `ChunkWindow` (the chunks a client has received). Give new
tests coordinates relative to an arbitrary origin rather than a real world's.

## More documentation

- [`docs/operating.md`](docs/operating.md): how a build runs, every setting, recovery and
  completion, and a detailed reference of the mod's behavior.
- [`companion/README.md`](companion/README.md): the monitor, phone alerts, and the local protocol.
- [`docs/profile.md`](docs/profile.md): scripts that install a separate launcher profile with the
  mod, add another schematic, update the mod, and recover interrupted operations.
- [`docs/agent-supervision.md`](docs/agent-supervision.md): the optional agent runner, which asks an
  AI model what to do about faults the mod can't resolve.
- [`schematics/README.md`](schematics/README.md): the example farm's measurements and build plan.
- [`docs/releasing.md`](docs/releasing.md): building the release files.
- [`docs/design/`](docs/design): design specs and performance notes.
- [`CHANGELOG.md`](CHANGELOG.md)

## License

[MIT](LICENSE). Schematic Supervisor builds on [Fabric](https://fabricmc.net/),
[Litematica](https://modrinth.com/mod/litematica), [MaLiLib](https://modrinth.com/mod/malilib), and
[Baritone](https://github.com/cabaletta/baritone).

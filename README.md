# Schematic Supervisor

Schematic Supervisor is a Fabric client mod and small Windows companion for
building supported Litematica schematics deterministically. The mod derives the
rectangular chunk footprint from the selected region and works one horizontal layer at a time, converts
farmland and wheat into practical till/plant actions, restocks materials from registered
chests, checkpoints the layer and chunk cursor, and verifies the finished volume twice.

The deterministic builder performs construction, inventory management, route recovery,
and verification, and recovers by itself from lost flight and brief server trouble. An
optional local connection exposes live observations and guarded controls. The runner
starts or resumes an armed ready build without a model call, leaves active work to the
mod, and consults the model only for an unresolved fault. The desktop monitor shows live
progress, alerts (optionally on your phone), and fix hints in an always-on-top card and a
full dashboard; see [`companion/README.md`](companion/README.md). Neither interface
chooses arbitrary coordinates or shop purchases.

Shop purchases are off unless `config\schematic-supervisor\shop.json` turns them on. Then
the mod buys dirt through a fixed `/shop` -> `Blocks` -> `Dirt` -> `Buy Stacks` flow sized
to the current inventory, and glowstone and birch planks through bounded routes whose menu
titles, buttons, pages, and slots come from that file. The defaults follow one server's shop
layout; see [Shop purchases](docs/operating.md#shop-purchases). Crafting, selling, and other
server commands remain unsupported.

A schematic may use farmland, wheat, and any full block without properties, such as dirt,
stone, planks, wool, concrete, or glowstone, in one enabled region. Blocks that need a
direction or other property (logs, stairs, slabs), fall (sand, gravel), or hold data (chests)
are refused when the placement loads. The bundled farm is described in
[`schematics/README.md`](schematics/README.md).

## Repository layout

| Path | Contents |
| --- | --- |
| `src/` | The Fabric client mod and its Java tests |
| `companion/` | The Windows monitor (`SchematicSupervisor.exe`), the agent bridge and runner, and their tests |
| `scripts/` | Tools for the dedicated automation profile, schematic inspection, and offline recovery; their tests are in `scripts/tests/` |
| `schematics/` | The bundled wheat farm and its measurements |
| `docs/` | Guides, with design notes in `docs/design/` |

These local folders are not in the repository: `runtime/` holds the dedicated game profile
and its installation backups, `data/` holds captured evidence and archived reports
(`data/reports/`), and `build/` and `companion/dist/` hold build output.

## Supported stack

- Minecraft 1.21.8
- Java 21
- Fabric Loader 0.19.3
- Fabric API 0.136.1+1.21.8
- Litematica 0.23.7
- MaLiLib 0.25.7
- Baritone API Fabric 1.15.0
- Python 3.11 or newer only when running or rebuilding the companion from source

The build verifies the downloaded Baritone API release against the pinned
SHA-256 digest before compiling.

## Install

Place the following in the Fabric profile's `mods` directory:

1. The Schematic Supervisor jar.
2. Fabric API.
3. MaLiLib.
4. Litematica.
5. Baritone API Fabric.

Do not install or enable Litematica Printer for a supervised run. The mod also
refuses to start a supervised run if its mod ID is present, disables Litematica
easy-place while active, prevents Baritone from using throwaway blocks as
scaffolding, and restores the previous settings when it pauses or stops.

The desktop monitor is optional; see [`companion/README.md`](companion/README.md). To install
the mod, its dependencies, and the farm into a separate launcher profile with one command,
see [`docs/profile.md`](docs/profile.md).

## Use

Before starting:

1. Load the intended world and enable your existing server flight ability.
   Start and Resume take off by themselves when the server allows flight, and
   `/schematic-supervisor takeoff` does the same on its own. The mod never grants
   flight permission or sends `/fly`.
2. Load and select exactly one enabled Litematica sub-region.
3. Let placement preflight validate its transformed dimensions, supported blocks,
   and resource limits. An unaligned minimum corner is supported.
4. Aim at each material chest and run
   `/schematic-supervisor depot add`.
5. Run `/schematic-supervisor depot scan` and wait for real chest scans to
   finish.
6. Optionally start the companion using the disabled configuration.
7. Run `/schematic-supervisor start`.

Available client-side commands:

```text
/schematic-supervisor start
/schematic-supervisor pause
/schematic-supervisor resume
/schematic-supervisor stop
/schematic-supervisor takeoff
/schematic-supervisor status
/schematic-supervisor reset
/schematic-supervisor unload
/schematic-supervisor buy-dirt
/schematic-supervisor depot add
/schematic-supervisor depot add-nearby [radius]
/schematic-supervisor depot scan
/schematic-supervisor depot list
/schematic-supervisor depot clear
```

Every Start first checks what is already built, then builds from the first unfinished piece.
[`docs/operating.md`](docs/operating.md) covers the build order, the build check, depots and
restocking, Pause/Stop/Unload/Reset, completion, settings, and the local connection.

## Build

From the repository root:

```powershell
.\gradlew.bat clean check build
```

Do not use `runClient` for offline verification. The remapped mod jar is written
to `build\libs\schematic-supervisor-0.2.0.jar`.

To rebuild the companion:

```powershell
Set-Location companion
python -m pip install ".[build]"
.\build_exe.ps1 -PythonExe python
```

The executable is written to
`companion\dist\SchematicSupervisor.exe`. Its safe, provider-disabled starting
configuration is `companion\config.disabled.example.json`.

## Offline verification

The full offline suite, from the repository root:

```powershell
.\gradlew.bat clean check build
python -m unittest discover -s scripts/tests -v
python scripts\generate_work_order.py schematics\wheatfarm_v2.litematic `
  --output data\wheatfarm_v2_work_order.json

Set-Location companion
python -m unittest discover -s tests -v
.\dist\SchematicSupervisor.exe --check-config `
  --config .\config.disabled.example.json
```

These checks compile against the real pinned Fabric/Litematica/Baritone
artifacts, exercise the pure supervisor and protocol state machines, decode the
actual schematic, build the mod jar and companion executable, and validate the
packaged companion without opening its window. They do not launch Minecraft;
an observed in-world chunk build remains the next validation stage.

The classes that drive Minecraft (`MinecraftExecutionPort`, `MinecraftDepotPort`, and
`SupervisorRuntimeController`) can't run in a unit test, so their decisions live in small classes
that can: for example `ExecutionMode`, `ExecutionTickGate`, `TillTargetCheck`, `ControlAvailability`,
`TakeoffEnvironment`, and `WithdrawalTermination`. Put new decisions there too. Tests share four
fixtures: `SupervisorFakes` (fake ports for the core supervisor), `VanillaFlight` (one tick of
vanilla flight, including landing switching flight off), `WheatFarmModel` (the bundled farm's solid
blocks at any chunk-aligned origin), and `ChunkWindow` (the chunks a client has received). Give new
tests coordinates relative to an arbitrary origin rather than a real world's.

## Documentation

- [`docs/operating.md`](docs/operating.md): running a build, settings, recovery and completion,
  and a detailed reference of how the mod behaves.
- [`docs/profile.md`](docs/profile.md): the dedicated automation profile: installing it,
  building another schematic, updating the mod, and offline recovery tools.
- [`docs/agent-supervision.md`](docs/agent-supervision.md): the optional agent runner and its
  MCP connection.
- [`companion/README.md`](companion/README.md): the desktop monitor and the local protocol.
- [`schematics/README.md`](schematics/README.md): the bundled farm's measurements and build plan.
- [`docs/design/`](docs/design): design specs and performance notes.
- [`docs/releasing.md`](docs/releasing.md): building release files and what to settle before
  publishing.
- [`CHANGELOG.md`](CHANGELOG.md).

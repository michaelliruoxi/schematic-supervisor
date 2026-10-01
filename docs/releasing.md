# Releasing

The project ships two files: the mod jar and the Windows monitor, `SchematicSupervisor.exe`. This
page describes how to build them and what to settle before the first public release.

## Versions

| Part | Where the version lives | Current |
| --- | --- | --- |
| Mod | `mod_version` in `gradle.properties` (copied into `fabric.mod.json` at build time) | 0.2.0 |
| Monitor | `version` in `companion/pyproject.toml`, `__version__` in `companion/supervisor_companion/__init__.py`, and both version fields in `companion/version_info.txt` | 0.4.0 |

Change the monitor's three places together. Record each release in [`CHANGELOG.md`](../CHANGELOG.md).

## Build the release files

From the repository root:

```powershell
.\gradlew.bat clean check build
python -m unittest discover -s scripts/tests

Set-Location companion
python -m unittest discover -s tests
python -m pip install ".[build]"
.\build_exe.ps1 -PythonExe python
.\dist\SchematicSupervisor.exe --check-config --config .\config.disabled.example.json
Set-Location ..

Get-FileHash build\libs\schematic-supervisor-*.jar, companion\dist\SchematicSupervisor.exe -Algorithm SHA256
```

The mod is `build\libs\schematic-supervisor-<version>.jar`; the `-sources.jar` beside it is
optional. The monitor is `companion\dist\SchematicSupervisor.exe`. Publish the SHA-256 values with
the files.

`clean` also removes `build\automation-mods`, which the local profile updater reads. Run
`.\gradlew.bat stageAutomationMods` again before updating the dedicated profile.

## Before the first public release

Settled already: the MIT `LICENSE` (also in `fabric.mod.json` and `companion/pyproject.toml`), the
author credit under the handle `michaelliruoxi` (in `LICENSE`, `fabric.mod.json`, and
`companion/pyproject.toml`), the repository links in `fabric.mod.json`, the versions above, the
monitor finding the pairing file of the default launcher profile (`%APPDATA%\.minecraft`), shop
purchases off by default with configurable routes (`shop.json`), `/fix` hoe repair off by default
(`autoRepairHoes`), any full block without properties in a schematic, the bundled schematic (cleared
for redistribution), the agent runner (published as an optional part), and a public history that
starts from one commit by `michaelliruoxi` with GitHub's no-reply email, with made-up coordinates
in the tests.

These still need a decision or a change:

- **Mod icon.** `fabric.mod.json` has no `icon`.
- **Scope.** State on the mod page what is supported: farmland, wheat, and full blocks without
  properties in one enabled region; flight required; materials from registered chests; shop
  purchases only for dirt, glowstone, and birch planks, through routes described in `shop.json`.
  The dirt route finds its buttons by their words, so it works only with shops whose menus say
  `Blocks`, `Dirt`, and `Buy Stacks`.
- **Rules.** Many servers forbid automation mods, and mod sites have content rules for them.
  Check the target site's rules before uploading.
- **Dependencies.** Fabric API, Litematica, and MaLiLib are on the usual mod sites; Baritone API
  Fabric 1.15.0 comes from Baritone's GitHub releases. Link each one, with the exact versions from
  the README.
- **Windows warnings.** The exe is unsigned, so SmartScreen warns on first launch, and one-file
  PyInstaller builds are sometimes flagged by antivirus software. Code signing avoids both;
  otherwise publish the checksums and explain the warning.

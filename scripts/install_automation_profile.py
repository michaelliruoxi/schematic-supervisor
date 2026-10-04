"""Install a separate launcher profile from the verified local mod bundle.

Every input is checked before the profile changes; a failure while writing puts back what was changed.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import tempfile
import zipfile
from datetime import datetime, timezone
from pathlib import Path
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[1]
PROFILE_ID = "schematic-supervisor"
VERSION_ID = "fabric-loader-0.19.3-1.21.8"
PROFILE_URL = "https://meta.fabricmc.net/v2/versions/loader/1.21.8/0.19.3/profile/json"


def ensure_game_closed() -> None:
    if os.name != "nt":
        return
    command = (
        "$ErrorActionPreference='Stop'; "
        "Get-CimInstance Win32_Process | Where-Object { "
        "$_.Name -in @('MinecraftLauncher.exe','Minecraft.exe') -or ("
        "$_.Name -in @('java.exe','javaw.exe') -and ("
        "$_.CommandLine -like '*net.minecraft.client.main.Main*' -or "
        "$_.CommandLine -like '*net.fabricmc.loader.impl.launch.knot.KnotClient*'))"
        "} | Select-Object -ExpandProperty ProcessId"
    )
    result = subprocess.run(["powershell.exe", "-NoProfile", "-Command", command],
                            capture_output=True, text=True, timeout=30,
                            creationflags=subprocess.CREATE_NO_WINDOW)
    if result.returncode:
        raise ValueError("Could not verify whether Minecraft is closed; no installation was performed.")
    if result.stdout.strip():
        raise ValueError("Close Minecraft and its launcher before updating the automation profile.")


def atomic_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=path.parent,
                                     suffix=".tmp", delete=False) as output:
        temporary = Path(output.name)
        json.dump(value, output, indent=2, ensure_ascii=False)
        output.write("\n")
    try:
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def atomic_bytes(path: Path, data: bytes) -> None:
    with tempfile.NamedTemporaryFile(dir=path.parent, suffix=".tmp", delete=False) as output:
        temporary = Path(output.name)
        output.write(data)
    try:
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def saved_settings(path: Path, description: str, default: dict) -> dict:
    """A settings object saved as JSON, or the default when there is no file."""
    if not path.exists():
        return default
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except ValueError as error:
        raise ValueError(f"{description} in {path} are not valid JSON: {error}") from error
    if not isinstance(value, dict):
        raise ValueError(f"{description} must be an object.")
    return value


class Undo:
    """The previous bytes of every file an installation changes, put back newest first if it fails."""

    def __init__(self) -> None:
        self._saved: list[tuple[Path, bytes | None]] = []

    def save(self, path: Path) -> None:
        self._saved.append((path, path.read_bytes() if path.exists() else None))

    def restore(self) -> list[Path]:
        """Returns the files that could not be put back."""
        failed = []
        for path, data in reversed(self._saved):
            try:
                if data is None:
                    path.unlink(missing_ok=True)
                else:
                    atomic_bytes(path, data)
            except OSError:
                failed.append(path)
        return failed


def install(minecraft: Path, python_exe: Path, agent_exe: Path,
            schematic: Path | None = None, defer_planting: bool | None = None,
            auto_repair_hoes: bool | None = None,
            discard_surplus_when_storage_full: bool | None = None,
            discard_surplus_directly: bool | None = None,
            glowstone_after_structure: bool | None = None,
            shop_purchases: bool | None = None) -> dict[str, object]:
    source_schematic = (ROOT / "schematics" / "wheatfarm_v2.litematic" if schematic is None else schematic).expanduser()
    if source_schematic.suffix.lower() != ".litematic":
        raise ValueError("Schematic input must use the .litematic suffix.")
    source_name = source_schematic.name
    source_schematic = source_schematic.resolve(strict=True)
    if not source_schematic.is_file():
        raise ValueError("Schematic input must be an existing regular file.")
    # Capture the exact source bytes before any installation writes, including backups.
    schematic_bytes = source_schematic.read_bytes()
    schematic_hash = hashlib.sha256(schematic_bytes).hexdigest()
    python_exe = python_exe.resolve(strict=True)
    agent_exe = agent_exe.resolve(strict=True)
    ensure_game_closed()
    minecraft = minecraft.resolve(strict=True)
    game = ROOT / "runtime" / "game"
    supervisor_settings_path = game / "config" / "schematic-supervisor" / "settings.json"
    supervisor_settings = saved_settings(supervisor_settings_path, "Supervisor settings", {
        "companionUri": "http://127.0.0.1:8766", "controlPort": 8765,
        "placementBlocksPerTick": 20000, "verificationBlocksPerTick": 20000,
        "interactionCooldownTicks": 4, "pathGoalRadius": 3, "minimumFood": 1,
    })
    if defer_planting is not None and type(defer_planting) is not bool:
        raise ValueError("Deferred planting selection must be a boolean.")
    if type(supervisor_settings.get("deferPlanting", False)) is not bool:
        raise ValueError("Saved deferPlanting setting must be a boolean.")
    supervisor_settings["deferPlanting"] = (supervisor_settings.get("deferPlanting", False)
                                            if defer_planting is None else defer_planting)
    if glowstone_after_structure is not None and type(glowstone_after_structure) is not bool:
        raise ValueError("Glowstone ordering selection must be a boolean.")
    if type(supervisor_settings.get("glowstoneAfterStructure", False)) is not bool:
        raise ValueError("Saved glowstoneAfterStructure setting must be a boolean.")
    supervisor_settings["glowstoneAfterStructure"] = (
        supervisor_settings.get("glowstoneAfterStructure", False)
        if glowstone_after_structure is None else glowstone_after_structure)
    if auto_repair_hoes is not None and type(auto_repair_hoes) is not bool:
        raise ValueError("Automatic hoe repair selection must be a boolean.")
    if type(supervisor_settings.get("autoRepairHoes", False)) is not bool:
        raise ValueError("Saved autoRepairHoes setting must be a boolean.")
    supervisor_settings["autoRepairHoes"] = (supervisor_settings.get("autoRepairHoes", False)
                                            if auto_repair_hoes is None else auto_repair_hoes)
    if discard_surplus_when_storage_full is not None and type(discard_surplus_when_storage_full) is not bool:
        raise ValueError("Surplus disposal selection must be a boolean.")
    if type(supervisor_settings.get("discardSurplusWhenStorageFull", False)) is not bool:
        raise ValueError("Saved discardSurplusWhenStorageFull setting must be a boolean.")
    supervisor_settings["discardSurplusWhenStorageFull"] = (
        supervisor_settings.get("discardSurplusWhenStorageFull", False)
        if discard_surplus_when_storage_full is None else discard_surplus_when_storage_full)
    if discard_surplus_directly is not None and type(discard_surplus_directly) is not bool:
        raise ValueError("Direct surplus disposal selection must be a boolean.")
    if type(supervisor_settings.get("discardSurplusDirectly", False)) is not bool:
        raise ValueError("Saved discardSurplusDirectly setting must be a boolean.")
    supervisor_settings["discardSurplusDirectly"] = (
        supervisor_settings.get("discardSurplusDirectly", False)
        if discard_surplus_directly is None else discard_surplus_directly)
    if shop_purchases is not None and type(shop_purchases) is not bool:
        raise ValueError("Shop purchase selection must be a boolean.")
    # shop.json keeps any captured layout; only its enabled flag is set here, and only when asked.
    shop_settings_path = game / "config" / "schematic-supervisor" / "shop.json"
    shop_settings = saved_settings(shop_settings_path, "Shop settings", {})
    runner_settings_path = ROOT / "companion" / "agent.local.json"
    runner_settings = {
        "model": "gpt-6-astra",
        "model_policy": "on-error",
        "reasoning_effort": "medium",
        **saved_settings(runner_settings_path, "Runner settings", {}),
        "python_exe": str(python_exe),
        "agent_executable": str(agent_exe),
        "token_file": str(game / "config" / "schematic-supervisor" / "protocol-token.txt"),
        "game_directory": str(game),
    }
    schematic_destination = game / "schematics" / source_name
    if schematic_destination.exists() and not schematic_destination.is_file():
        raise ValueError("The schematic destination exists but is not a regular file.")
    bundle = ROOT / "build" / "automation-mods"
    mods = sorted(bundle.glob("*.jar"))
    if len(mods) != 5:
        raise ValueError("Expected five verified jars; run the stageAutomationMods build task first.")
    required = ("schematic-supervisor-", "fabric-api-", "malilib-", "litematica-", "baritone-api-fabric-")
    if any(sum(path.name.startswith(prefix) for path in mods) != 1 for prefix in required):
        raise ValueError("Staged mod bundle has unexpected contents.")
    managed_ids = set()
    for path in mods:
        with zipfile.ZipFile(path) as archive:
            managed_ids.add(json.loads(archive.read("fabric.mod.json"))["id"])
    launcher_path = minecraft / "launcher_profiles.json"
    original = launcher_path.read_bytes()
    launcher = json.loads(original)
    profiles = launcher.get("profiles")
    if not isinstance(profiles, dict):
        raise ValueError("Launcher profiles are invalid.")
    previous = profiles.get(PROFILE_ID)
    if previous and Path(previous.get("gameDir", "")).resolve() != game.resolve():
        raise ValueError("An unrelated launcher profile already uses the automation profile ID.")
    version_path = minecraft / "versions" / VERSION_ID / f"{VERSION_ID}.json"
    if version_path.exists():
        version = json.loads(version_path.read_text(encoding="utf-8"))
    else:
        with urlopen(PROFILE_URL, timeout=30) as response:
            raw = response.read(1_048_577)
        if len(raw) > 1_048_576:
            raise ValueError("Loader profile exceeded the size limit.")
        version = json.loads(raw)
    if (version.get("id") != VERSION_ID or version.get("inheritsFrom") != "1.21.8"
            or version.get("mainClass") != "net.fabricmc.loader.impl.launch.knot.KnotClient"):
        raise ValueError("Loader profile does not match the pinned game and loader.")
    # Identify every installed jar before writing anything, so an unreadable one cannot stop the
    # installation after the old supervisor has already been moved away.
    target_mods = game / "mods"
    staged_names = {path.name for path in mods}
    retired = []
    for installed in sorted(target_mods.glob("*.jar")):
        try:
            with zipfile.ZipFile(installed) as archive:
                installed_id = json.loads(archive.read("fabric.mod.json"))["id"]
        except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile):
            raise ValueError(f"Cannot identify installed mod {installed.name}; inspect it before updating.")
        if installed_id in managed_ids and installed.name not in staged_names:
            # The exact path is inside this project's dedicated runtime.
            if installed.resolve().parent != target_mods.resolve():
                raise ValueError("Installed mod resolved outside the automation directory.")
            retired.append(installed)
    # Build a new game directory; existing game mods and worlds stay in place.
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    backup = ROOT / "runtime" / "installation-backups" / stamp
    if retired and backup.resolve().parent.parent != (ROOT / "runtime").resolve():
        raise ValueError("Backup directory resolved outside the automation runtime.")
    backup.mkdir(parents=True)
    shutil.copy2(launcher_path, backup / "launcher_profiles.json")
    ensure_game_closed()
    if launcher_path.read_bytes() != original:
        raise ValueError("Launcher profiles changed during preparation; close the launcher and rerun.")
    # A failure from here on puts back every file already changed, so installation.json keeps
    # describing the jars that are actually installed.
    undo = Undo()
    try:
        target_mods.mkdir(parents=True, exist_ok=True)
        for installed in retired:
            undo.save(installed)
            installed.rename(backup / installed.name)
        for path in mods:
            destination = target_mods / path.name
            if destination.exists() and destination.read_bytes() != path.read_bytes():
                shutil.copy2(destination, backup / path.name)
            undo.save(destination)
            shutil.copy2(path, destination)
        if supervisor_settings_path.exists():
            shutil.copy2(supervisor_settings_path, backup / "supervisor-settings.json")
        undo.save(supervisor_settings_path)
        atomic_json(supervisor_settings_path, supervisor_settings)
        if shop_purchases is not None:
            if shop_settings_path.exists():
                shutil.copy2(shop_settings_path, backup / "shop.json")
            undo.save(shop_settings_path)
            atomic_json(shop_settings_path, shop_settings | {"enabled": shop_purchases})
        for name in ("options.txt", "servers.dat"):
            source = minecraft / name
            if source.is_file() and not (game / name).exists():
                undo.save(game / name)
                shutil.copy2(source, game / name)
        schematic_dir = game / "schematics"
        schematic_dir.mkdir(exist_ok=True)
        same_schematic_file = schematic_destination.exists() and source_schematic.samefile(schematic_destination)
        copied_schematic = False
        schematic_backup = None
        if not same_schematic_file and (
                not schematic_destination.exists() or schematic_destination.read_bytes() != schematic_bytes):
            if schematic_destination.exists():
                schematic_backup = backup / "schematics" / source_name
                schematic_backup.parent.mkdir(parents=True)
                shutil.copy2(schematic_destination, schematic_backup)
            undo.save(schematic_destination)
            temporary_schematic = None
            try:
                with tempfile.NamedTemporaryFile(mode="wb", dir=schematic_dir, suffix=".tmp", delete=False) as output:
                    temporary_schematic = Path(output.name)
                    output.write(schematic_bytes)
                    output.flush()
                    os.fsync(output.fileno())
                shutil.copystat(source_schematic, temporary_schematic)
                os.replace(temporary_schematic, schematic_destination)
            finally:
                if temporary_schematic is not None:
                    temporary_schematic.unlink(missing_ok=True)
            copied_schematic = True
        if not version_path.exists():
            undo.save(version_path)
            atomic_json(version_path, version)
        now = datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")
        profiles[PROFILE_ID] = {
            **(previous or {}), "name": "Schematic Supervisor", "type": "custom",
            "gameDir": str(game), "lastVersionId": VERSION_ID, "icon": "Crafting_Table",
            "created": (previous or {}).get("created", now),
            "javaArgs": "-Xmx4G -XX:+UseG1GC",
        }
        if launcher_path.read_bytes() != original:
            raise ValueError("Launcher profiles changed during preparation; close the launcher and rerun.")
        undo.save(launcher_path)
        atomic_json(launcher_path, launcher)
        undo.save(runner_settings_path)
        atomic_json(runner_settings_path, runner_settings)
        report = {"profile": "Schematic Supervisor", "version": VERSION_ID,
                  "game_directory": str(game), "backup_directory": str(backup),
                  "schematic": {"source_name": source_name, "sha256": schematic_hash,
                                "installed_path": str(schematic_destination), "copied": copied_schematic,
                                "backup_path": None if schematic_backup is None else str(schematic_backup)},
                  "mods": [{"name": path.name,
                            "sha256": hashlib.sha256(path.read_bytes()).hexdigest()} for path in mods]}
        installation_path = ROOT / "runtime" / "installation.json"
        undo.save(installation_path)
        atomic_json(installation_path, report)
    except BaseException as error:
        failed = undo.restore()
        if failed and isinstance(error, Exception):
            raise ValueError(f"{error}; the previous profile could not be fully restored "
                             f"({', '.join(map(str, failed))}). Its backups are in {backup}.") from error
        raise
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--minecraft-dir", type=Path, required=True)
    parser.add_argument("--python-exe", type=Path, required=True)
    parser.add_argument("--agent-executable", type=Path, required=True)
    parser.add_argument("--schematic", type=Path,
                        help="Existing .litematic file to install; defaults to the bundled farm schematic.")
    parser.add_argument("--defer-planting", action=argparse.BooleanOptionalAction, default=None,
                        help="Build and till without planting; preserves the existing mode when omitted.")
    parser.add_argument("--glowstone-after-structure", action=argparse.BooleanOptionalAction, default=None,
                        help="Finish all structural layers before Glowstone; preserves the saved mode when omitted.")
    parser.add_argument("--auto-repair-hoes", action=argparse.BooleanOptionalAction, default=None,
                        help="Repair worn hoes with /fix on a server that supports it; opt-in for new profiles.")
    parser.add_argument("--discard-surplus-when-storage-full", action=argparse.BooleanOptionalAction, default=None,
                        help="Allow disposal of plain moss, pumpkin/melon seeds and jack-o'-lanterns only after registered storage fills; opt-in for new profiles.")
    parser.add_argument("--shop-purchases", action=argparse.BooleanOptionalAction, default=None,
                        help="Buy missing Dirt, Glowstone and Birch Planks through the shop route in shop.json; "
                             "off for new profiles, and preserved when omitted.")
    parser.add_argument("--discard-surplus-directly", action=argparse.BooleanOptionalAction, default=None,
                        help="Discard approved plain clearing pickups during refill without depositing them or checking chest capacity; preserves the saved setting when omitted.")
    args = parser.parse_args()
    try:
        print(json.dumps(install(args.minecraft_dir, args.python_exe, args.agent_executable,
                                 args.schematic, args.defer_planting, args.auto_repair_hoes,
                                 args.discard_surplus_when_storage_full, args.discard_surplus_directly,
                                 args.glowstone_after_structure, args.shop_purchases), indent=2))
    except (OSError, ValueError) as error:
        print(f"Installation failed: {error}")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

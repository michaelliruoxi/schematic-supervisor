"""Replace the tested supervisor jar in the workspace automation profile while it is stopped.

Example: python scripts/update_automation_mod.py --artifact build/automation-mods/schematic-supervisor-0.2.0.jar
         --expected-sha256 HASH --test-report data/validation.json
A staged jar with a new version in its filename replaces the installed supervisor jar under the new name.
A profile whose installed supervisor predates 0.2.0 gets a shop.json that keeps its shop purchases on;
newer profiles keep their own choice, which is off unless the installer was told otherwise.
The JSON report must bind sha256 to java_tests > 0 and failures/errors/skipped == 0.
Its optional integrated_features object declares known implementation features using
JSON booleans. These are source/test attestations, never live verification or settings.
This command does not launch the game or verify any in-world behavior.
"""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
from typing import Callable
import zipfile

ROOT = Path(__file__).resolve().parents[1]
STATE_NAMES = frozenset({"checkpoint.json", "settings.json", "run-context.json", "depots.json",
                         "material-purchase.json", "moss-deposit.json", "temporary-support.json",
                         "hoe-repair.json", "surplus-disposal.json"})
MAX_JSON = 8 * 1024 * 1024
# Mods before 0.2.0 always bought from the captured shop; newer ones buy only when shop.json says so.
SHOP_SETTINGS = "shop.json"
SHOP_KEPT_ON = {"enabled": True}
SHOP_SETTINGS_VERSION = (0, 2, 0)
INTEGRATED_FEATURES = frozenset({
    "temporary_supports_integrated", "obstruction_diagnostics", "passive_shop_history",
    "loose_item_clearing_fix", "moss_pickup_reselection", "moss_deposit_receipts",
    "material_shop_receipts", "server_cursor_observation", "inventory_equipment_telemetry",
    "bounded_automatic_dirt", "depot_first_dirt", "background_building", "automatic_hoe_repair",
})


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def json_object(data: bytes) -> dict:
    if len(data) > MAX_JSON:
        raise ValueError("JSON input exceeds the size limit.")
    value = json.loads(data)
    if not isinstance(value, dict):
        raise ValueError("Expected a JSON object.")
    return value


def read_object(path: Path) -> dict:
    with path.open("rb") as source:
        return json_object(source.read(MAX_JSON + 1))


def integrated_features(validation: dict) -> dict[str, bool]:
    features = validation.get("integrated_features", {})
    if (not isinstance(features, dict) or not features.keys() <= INTEGRATED_FEATURES
            or any(type(value) is not bool for value in features.values())):
        raise ValueError("integrated_features must map only known implementation features to booleans.")
    return dict(features)


def contained(root: Path, path: Path) -> Path:
    path = path.absolute()
    if not path.is_relative_to(root) or not path.resolve().is_relative_to(root):
        raise ValueError("Update paths must remain inside the workspace.")
    # Reject junctions and symlinks, even those resolving elsewhere inside the workspace.
    for component in (path, *path.parents):
        if component == root:
            break
        if component.is_symlink() or (hasattr(component, "is_junction") and component.is_junction()):
            raise ValueError("Update paths cannot contain symbolic links or junctions.")
    return path


def atomic_bytes(path: Path, data: bytes) -> None:
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=path.parent, suffix=".tmp", delete=False) as output:
            temporary = Path(output.name)
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def atomic_json(path: Path, value: dict) -> None:
    atomic_bytes(path, (json.dumps(value, indent=2, ensure_ascii=False) + "\n").encode("utf-8"))


def ensure_processes_absent() -> None:
    if os.name != "nt":
        raise ValueError("Minecraft process verification is supported only on Windows.")
    # Only classifications leave PowerShell; never expose process command lines or credentials.
    command = r"""
$ErrorActionPreference='Stop'
$items = @(Get-CimInstance Win32_Process)
$unknown = @($items | Where-Object {
    $_.Name -in @('java.exe','javaw.exe','python.exe','pythonw.exe') -and
    [string]::IsNullOrWhiteSpace($_.CommandLine)
})
$games = @($items | Where-Object {
    $_.Name -in @('java.exe','javaw.exe') -and
    $_.CommandLine -match 'net\.minecraft\.client\.main\.Main|net\.fabricmc\.loader\..*KnotClient|net\.minecraft\.launchwrapper\.Launch|org\.multimc\.EntryPoint|(?:^|\s)@'
})
$runners = @($items | Where-Object {
    $_.Name -in @('python.exe','pythonw.exe') -and
    $_.CommandLine -match 'agent_launcher\.py"?\s+"?run"?(?:\s|$)|(?:^|\s)"?-m"?\s+"?supervisor_companion\.agent_runner"?(?:\s|$)'
})
@{verified=($items.Count -gt 0 -and $unknown.Count -eq 0); game_count=$games.Count; runner_count=$runners.Count} | ConvertTo-Json -Compress
"""
    try:
        result = subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command],
                                capture_output=True, text=True, timeout=30,
                                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        facts = json.loads(result.stdout)
    except (OSError, subprocess.SubprocessError, ValueError) as error:
        raise ValueError("Could not verify stopped game and runner; update refused.") from error
    if (result.returncode != 0 or not isinstance(facts, dict) or facts.get("verified") is not True
            or type(facts.get("game_count")) is not int or type(facts.get("runner_count")) is not int
            or facts["game_count"] < 0 or facts["runner_count"] < 0):
        raise ValueError("Could not verify stopped game and runner; update refused.")
    if facts["game_count"] or facts["runner_count"]:
        raise ValueError("Stop Minecraft and the supervision runner before updating. The launcher may stay open.")


def state_snapshot(root: Path, directory: Path) -> dict[str, bytes]:
    result = {}
    for path in sorted(directory.rglob("*")):
        contained(root, path)
        if path.name in STATE_NAMES:
            if not path.is_file() or path.stat().st_size > MAX_JSON:
                raise ValueError("Saved build state must be a bounded regular file.")
            result[path.relative_to(directory).as_posix()] = path.read_bytes()
    return result


def mod_metadata(path: Path | io.BytesIO) -> dict:
    try:
        with zipfile.ZipFile(path) as archive:
            info = archive.getinfo("fabric.mod.json")
            if info.file_size > MAX_JSON:
                raise ValueError("Mod metadata exceeds the size limit.")
            metadata = json.loads(archive.read(info))
    except (OSError, KeyError, zipfile.BadZipFile, ValueError) as error:
        raise ValueError("Cannot identify a local mod jar; update refused.") from error
    if not isinstance(metadata, dict):
        raise ValueError("Cannot identify a local mod jar; update refused.")
    return metadata


def supervisor_id(path: Path | io.BytesIO) -> bool:
    return mod_metadata(path).get("id") == "schematic_supervisor"


def predates_shop_settings(jar: bytes) -> bool:
    """Whether a jar declares a version before 0.2.0, which bought without shop.json.

    A missing or unreadable version counts as newer, so purchases stay off.
    """
    version = mod_metadata(io.BytesIO(jar)).get("version")
    match = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)(?:[-+].*)?", version) if isinstance(version, str) else None
    return match is not None and tuple(int(part) for part in match.groups()) < SHOP_SETTINGS_VERSION


def update(workspace: Path, artifact: Path, expected_sha256: str, test_report: Path,
           process_check: Callable[[], None] = ensure_processes_absent) -> dict:
    root = workspace.resolve(strict=True)
    runtime = contained(root, root / "runtime")
    staged = contained(root, root / "build" / "automation-mods")
    artifact = contained(root, artifact if artifact.is_absolute() else root / artifact)
    report_path = contained(root, test_report if test_report.is_absolute() else root / test_report)
    mods = contained(root, runtime / "game" / "mods")
    state = contained(root, runtime / "game" / "config" / "schematic-supervisor")
    installation_path = contained(root, runtime / "installation.json")
    if artifact.parent != staged or artifact.suffix != ".jar" or not artifact.is_file():
        raise ValueError("Artifact must be a staged jar under build/automation-mods.")
    if not re.fullmatch(r"[0-9a-fA-F]{64}", expected_sha256):
        raise ValueError("An explicit SHA256 is required.")
    expected = expected_sha256.lower()
    artifact_bytes = artifact.read_bytes()
    with report_path.open("rb") as source:
        report_bytes = source.read(MAX_JSON + 1)
    validation = json_object(report_bytes)
    if (sha256(artifact_bytes) != expected or not isinstance(validation.get("sha256"), str)
            or validation["sha256"].lower() != expected
            or type(validation.get("java_tests")) is not int or validation["java_tests"] < 1
            or any(type(validation.get(key)) is not int or validation[key] != 0
                   for key in ("failures", "errors", "skipped"))):
        raise ValueError("Artifact hash and a complete passing test report must agree.")
    features = integrated_features(validation)
    if not supervisor_id(io.BytesIO(artifact_bytes)):
        raise ValueError("Staged artifact is not the supervisor mod.")
    if not mods.is_dir() or not state.is_dir():
        raise ValueError("Install the separate automation profile before updating its mod.")
    installed = [path for path in mods.glob("*.jar") if supervisor_id(contained(root, path))]
    if len(installed) != 1:
        raise ValueError("Expected exactly one installed supervisor jar.")
    current = installed[0]
    # A new version in the staged filename replaces the installed jar under the new name.
    destination = contained(root, mods / artifact.name)
    renamed = destination != current
    if renamed and destination.exists():
        raise ValueError("Another file already uses the staged filename; inspect the mods folder.")
    previous_jar = current.read_bytes()
    keep_shop_purchases = predates_shop_settings(previous_jar)
    with installation_path.open("rb") as source:
        previous_metadata = source.read(MAX_JSON + 1)
    metadata = json_object(previous_metadata)
    entries = metadata.get("mods")
    if not isinstance(entries, list) or any(not isinstance(entry, dict) for entry in entries):
        raise ValueError("Installation metadata has no valid mod inventory.")
    matches = [entry for entry in entries if entry.get("name") == current.name]
    if (len(matches) != 1 or not isinstance(matches[0].get("sha256"), str)
            or matches[0]["sha256"].lower() != sha256(previous_jar)):
        raise ValueError("Installed supervisor hash does not match installation metadata.")
    history = metadata.get("mod_update_history", [])
    if not isinstance(history, list):
        raise ValueError("Installation update history must be a list.")
    process_check()
    lock = contained(root, runtime / ".mod-update.lock")
    with lock.open("x", encoding="utf-8") as output:
        output.write(str(os.getpid()))
    backup = None
    changed = False
    metadata_changed = False
    try:
        process_check()
        saved = state_snapshot(root, state)
        stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
        backup = contained(root, runtime / "installation-backups" / ("mod-update-" + stamp))
        backup.mkdir(parents=True)
        atomic_bytes(backup / current.name, previous_jar)
        atomic_bytes(backup / "installation.json", previous_metadata)
        atomic_bytes(backup / "validation.json", report_bytes)
        for name, data in saved.items():
            target = backup / "state" / name
            target.parent.mkdir(parents=True, exist_ok=True)
            atomic_bytes(target, data)
        entry = {"status": "prepared", "sha256": expected, "previous_sha256": sha256(previous_jar),
                 "installed_path": str(destination.relative_to(root)), "backup_directory": str(backup),
                 **({"previous_installed_path": str(current.relative_to(root))} if renamed else {}),
                 "validation_report": str(report_path.relative_to(root)), "validation_sha256": sha256(report_bytes),
                 "java_tests": validation["java_tests"], "failures": 0, "errors": 0, "skipped": 0,
                 "updated_at": datetime.now(timezone.utc).isoformat(), "update_live_verified": False,
                 "state_sha256": {name: sha256(data) for name, data in saved.items()},
                 "checkpoint_sha256": sha256(saved["checkpoint.json"]) if "checkpoint.json" in saved else None,
                 **features}
        atomic_json(backup / "update.json", entry)
        process_check()
        if (state_snapshot(root, state) != saved or current.read_bytes() != previous_jar
                or (renamed and destination.exists())
                or installation_path.read_bytes() != previous_metadata
                or artifact.read_bytes() != artifact_bytes or report_path.read_bytes() != report_bytes):
            raise ValueError("An update input changed during preparation; update refused.")
        atomic_bytes(destination, artifact_bytes)
        changed = True
        if renamed:
            # Two copies of one mod would stop the game from starting.
            current.unlink()
        process_check()
        if (destination.read_bytes() != artifact_bytes or (renamed and current.exists())
                or state_snapshot(root, state) != saved):
            raise ValueError("Jar or saved build state changed during replacement; inspect the backup.")
        entry.update(status="installed", checkpoint_unchanged=True)
        # Only a profile that bought before shop.json existed keeps buying. Older jars ignore
        # shop.json, so it stays even if a later step rolls the jar back.
        shop_settings = contained(root, state / SHOP_SETTINGS)
        if keep_shop_purchases and not shop_settings.exists():
            atomic_json(shop_settings, SHOP_KEPT_ON)
            entry["shop_settings_created"] = True
        # Preserve the complete previous feature and verification evidence in history.
        if "last_mod_update" in metadata:
            history = [*history, metadata["last_mod_update"]]
        metadata["mod_update_history"] = history
        metadata["last_mod_update"] = entry
        matches[0]["sha256"] = expected
        matches[0]["name"] = destination.name
        if installation_path.read_bytes() != previous_metadata:
            raise ValueError("Installation metadata changed before commit; update refused.")
        atomic_json(installation_path, metadata)
        metadata_changed = True
        process_check()
        if (state_snapshot(root, state) != saved or destination.read_bytes() != artifact_bytes
                or read_object(installation_path) != metadata):
            raise ValueError("Update verification changed before completion; inspect the backup.")
        atomic_json(backup / "update.json", entry)
        return entry
    except Exception:
        # Roll back only our exact writes while the game and runner remain absent.
        rolled_back = not changed
        if changed:
            try:
                process_check()
                metadata_matches = (read_object(installation_path) == metadata if metadata_changed
                                    else installation_path.read_bytes() == previous_metadata)
                if destination.read_bytes() == artifact_bytes and metadata_matches:
                    if renamed:
                        atomic_bytes(current, previous_jar)
                        destination.unlink()
                    else:
                        atomic_bytes(destination, previous_jar)
                    if metadata_changed:
                        atomic_bytes(installation_path, previous_metadata)
                    rolled_back = True
            except (OSError, ValueError):
                rolled_back = False
        if backup is not None:
            try:
                atomic_json(backup / "outcome.json", {"status": "rolled_back" if rolled_back else "inspection_required",
                                                     "update_live_verified": False})
            except OSError:
                pass
        raise
    finally:
        lock.unlink(missing_ok=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifact", type=Path, required=True)
    parser.add_argument("--expected-sha256", required=True)
    parser.add_argument("--test-report", type=Path, required=True)
    args = parser.parse_args()
    try:
        print(json.dumps(update(ROOT, args.artifact, args.expected_sha256, args.test_report), indent=2))
    except (OSError, ValueError) as error:
        print(f"Update failed: {error}")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

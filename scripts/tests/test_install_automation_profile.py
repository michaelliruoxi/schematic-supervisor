"""Regression checks for isolated profile installation and managed jar updates."""

import importlib.util
import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

MODULE_PATH = Path(__file__).resolve().parents[1] / "install_automation_profile.py"
SPEC = importlib.util.spec_from_file_location("profile_installer", MODULE_PATH)
installer = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(installer)


def mod(path, mod_id):
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("fabric.mod.json", json.dumps({"id": mod_id}))


def prepared_installation(root):
    minecraft = root / "original"
    minecraft.mkdir()
    (minecraft / "launcher_profiles.json").write_text(json.dumps({"profiles": {}}))
    version_dir = minecraft / "versions" / installer.VERSION_ID
    version_dir.mkdir(parents=True)
    (version_dir / f"{installer.VERSION_ID}.json").write_text(json.dumps({
        "id": installer.VERSION_ID, "inheritsFrom": "1.21.8",
        "mainClass": "net.fabricmc.loader.impl.launch.knot.KnotClient"}))
    for name, identity in (("schematic-supervisor-0.2.jar", "schematic_supervisor"),
                           ("fabric-api-1.jar", "fabric-api"), ("malilib-1.jar", "malilib"),
                           ("litematica-1.jar", "litematica"), ("baritone-api-fabric-1.jar", "baritone")):
        mod(root / "build" / "automation-mods" / name, identity)
    (root / "schematics").mkdir()
    (root / "schematics" / "wheatfarm_v2.litematic").write_bytes(b"bundled-schematic")
    executable = root / "agent.exe"
    executable.touch()
    return minecraft, executable


def stage_new_supervisor(root):
    """Stage a renamed supervisor jar, as after a version bump."""
    bundle = root / "build" / "automation-mods"
    (bundle / "schematic-supervisor-0.2.jar").unlink()
    mod(bundle / "schematic-supervisor-0.3.jar", "schematic_supervisor")


def files(root, skip=None):
    """Every file under root by relative path, except under the skipped folder."""
    result = {}
    for path in root.rglob("*"):
        relative = path.relative_to(root).as_posix()
        if path.is_file() and not (skip and relative.startswith(skip + "/")):
            result[relative] = path.read_bytes()
    return result


@unittest.skipUnless(os.name == "nt", "The installer process guard uses Windows PowerShell.")
class ProcessGuardTests(unittest.TestCase):
    def run_with_process_inventory(self, processes, *, inspection_failed=False, action=None):
        """Execute the production predicate while replacing only its process inventory."""
        real_run = installer.subprocess.run
        fixture = json.dumps(processes).replace("'", "''")
        inventory = ("throw 'Fixture process inspection failed'" if inspection_failed else
                     f"foreach ($process in (ConvertFrom-Json -InputObject '{fixture}')) {{ $process }}")
        prefix = (
            "function Get-CimInstance { param([string]$ClassName) "
            "if ($ClassName -ne 'Win32_Process') { throw 'Unexpected process query' }; "
            + inventory + " }; "
        )

        def run_fixture(arguments, **kwargs):
            self.assertEqual(arguments[-2], "-Command")
            result = real_run([*arguments[:-1], prefix + arguments[-1]], **kwargs)
            if not inspection_failed:
                self.assertEqual(result.returncode, 0, result.stderr)
            return result

        with patch.object(installer.subprocess, "run", side_effect=run_fixture):
            (installer.ensure_game_closed if action is None else action)()

    def test_current_launcher_alone_blocks_installation(self):
        with self.assertRaisesRegex(ValueError, "Close Minecraft and its launcher"):
            self.run_with_process_inventory([
                {"Name": "Minecraft.exe", "ProcessId": 101, "CommandLine": None}])

    def test_legacy_launcher_alone_blocks_installation(self):
        with self.assertRaisesRegex(ValueError, "Close Minecraft and its launcher"):
            self.run_with_process_inventory([
                {"Name": "MinecraftLauncher.exe", "ProcessId": 102, "CommandLine": None}])

    def test_running_fabric_game_blocks_installation(self):
        with self.assertRaisesRegex(ValueError, "Close Minecraft and its launcher"):
            self.run_with_process_inventory([
                {"Name": "javaw.exe", "ProcessId": 103,
                 "CommandLine": "javaw.exe net.fabricmc.loader.impl.launch.knot.KnotClient"}])

    def test_unrelated_java_process_is_allowed(self):
        self.run_with_process_inventory([
            {"Name": "java.exe", "ProcessId": 104, "CommandLine": "java.exe -jar unrelated.jar"}])

    def test_empty_process_inventory_is_allowed(self):
        self.run_with_process_inventory([])

    def test_failed_process_inspection_refuses_installation(self):
        with self.assertRaisesRegex(ValueError, "Could not verify whether Minecraft is closed"):
            self.run_with_process_inventory([], inspection_failed=True)

    def test_current_launcher_refuses_installation_before_any_writes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            minecraft, executable = prepared_installation(root)
            before_files = {str(path.relative_to(root)): path.read_bytes()
                            for path in root.rglob("*") if path.is_file()}
            before_directories = {str(path.relative_to(root))
                                  for path in root.rglob("*") if path.is_dir()}
            with patch.object(installer, "ROOT", root):
                with self.assertRaisesRegex(ValueError, "Close Minecraft and its launcher"):
                    self.run_with_process_inventory(
                        [{"Name": "Minecraft.exe", "ProcessId": 105, "CommandLine": None}],
                        action=lambda: installer.install(minecraft, executable, executable))
            self.assertEqual(before_files, {str(path.relative_to(root)): path.read_bytes()
                                            for path in root.rglob("*") if path.is_file()})
            self.assertEqual(before_directories, {str(path.relative_to(root))
                                                  for path in root.rglob("*") if path.is_dir()})


class ProfileInstallerTests(unittest.TestCase):
    def test_structure_first_ordering_is_preserved_and_does_not_change_other_settings(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            minecraft, executable = prepared_installation(root)
            path = root / "runtime/game/config/schematic-supervisor/settings.json"
            with patch.object(installer, "ROOT", root), patch.object(installer, "ensure_game_closed"):
                installer.install(minecraft, executable, executable)
                original = json.loads(path.read_text())
                self.assertIs(original["glowstoneAfterStructure"], False)
                installer.install(minecraft, executable, executable, glowstone_after_structure=True)
                saved = original | {"glowstoneAfterStructure": True}
                self.assertEqual(json.loads(path.read_text()), saved)
                installer.install(minecraft, executable, executable)
                self.assertEqual(json.loads(path.read_text()), saved)
                with self.assertRaises(ValueError):
                    installer.install(minecraft, executable, executable, glowstone_after_structure="true")
                self.assertEqual(json.loads(path.read_text()), saved)

    def test_shop_purchases_are_opt_in_preserved_and_keep_a_captured_layout(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            minecraft, executable = prepared_installation(root)
            path = root / "runtime/game/config/schematic-supervisor/shop.json"
            with patch.object(installer, "ROOT", root), patch.object(installer, "ensure_game_closed"):
                installer.install(minecraft, executable, executable)
                self.assertFalse(path.exists(), "a new profile buys nothing until asked")
                installer.install(minecraft, executable, executable, shop_purchases=True)
                self.assertEqual({"enabled": True}, json.loads(path.read_text()))
                path.write_text('{"enabled": true, "command": "market"}', encoding="utf-8")
                installer.install(minecraft, executable, executable)
                self.assertEqual({"enabled": True, "command": "market"}, json.loads(path.read_text()))
                installer.install(minecraft, executable, executable, shop_purchases=False)
                self.assertEqual({"enabled": False, "command": "market"}, json.loads(path.read_text()))
                before = path.read_bytes()
                with self.assertRaisesRegex(ValueError, "Shop purchase selection must be a boolean"):
                    installer.install(minecraft, executable, executable, shop_purchases="yes")
                self.assertEqual(path.read_bytes(), before)

    def test_direct_disposal_is_independent_preserved_and_revocable(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            minecraft, executable = prepared_installation(root)
            path = root / "runtime/game/config/schematic-supervisor/settings.json"
            with patch.object(installer, "ROOT", root), patch.object(installer, "ensure_game_closed"):
                installer.install(minecraft, executable, executable)
                self.assertIs(json.loads(path.read_text())["discardSurplusDirectly"], False)
                installer.install(minecraft, executable, executable, discard_surplus_directly=True)
                saved = json.loads(path.read_text())
                self.assertIs(saved["discardSurplusDirectly"], True)
                self.assertIs(saved["discardSurplusWhenStorageFull"], False)
                installer.install(minecraft, executable, executable)
                self.assertEqual(json.loads(path.read_text()), saved)
                installer.install(minecraft, executable, executable, discard_surplus_directly=False)
                self.assertEqual(json.loads(path.read_text()), saved | {"discardSurplusDirectly": False})
                before = path.read_bytes()
                with self.assertRaises(ValueError):
                    installer.install(minecraft, executable, executable, discard_surplus_directly="true")
                self.assertEqual(path.read_bytes(), before)

    def test_surplus_disposal_is_opt_in_preserved_and_revocable(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            minecraft, executable = prepared_installation(root)
            path = root / "runtime/game/config/schematic-supervisor/settings.json"
            with patch.object(installer, "ROOT", root), patch.object(installer, "ensure_game_closed"):
                installer.install(minecraft, executable, executable)
                self.assertIs(json.loads(path.read_text())["discardSurplusWhenStorageFull"], False)
                installer.install(minecraft, executable, executable, discard_surplus_when_storage_full=True)
                saved = json.loads(path.read_text())
                self.assertIs(saved["discardSurplusWhenStorageFull"], True)
                installer.install(minecraft, executable, executable)
                self.assertEqual(json.loads(path.read_text()), saved)
                installer.install(minecraft, executable, executable, discard_surplus_when_storage_full=False)
                self.assertEqual(json.loads(path.read_text()), saved | {"discardSurplusWhenStorageFull": False})
                before = path.read_bytes()
                with self.assertRaisesRegex(ValueError, "Surplus disposal selection must be a boolean"):
                    installer.install(minecraft, executable, executable, discard_surplus_when_storage_full="true")
                self.assertEqual(path.read_bytes(), before)

    def test_hoe_repair_is_opt_in_preserved_and_explicitly_configurable(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            minecraft, executable = prepared_installation(root)
            path = root / "runtime/game/config/schematic-supervisor/settings.json"
            with patch.object(installer, "ROOT", root), patch.object(installer, "ensure_game_closed"):
                installer.install(minecraft, executable, executable)
                self.assertIs(json.loads(path.read_text())["autoRepairHoes"], False)
                installer.install(minecraft, executable, executable, auto_repair_hoes=True,
                                  defer_planting=True)
                saved = json.loads(path.read_text())
                self.assertIs(saved["autoRepairHoes"], True)
                self.assertIs(saved["deferPlanting"], True)
                installer.install(minecraft, executable, executable)
                self.assertEqual(json.loads(path.read_text()), saved)
                installer.install(minecraft, executable, executable, auto_repair_hoes=False)
                self.assertEqual(json.loads(path.read_text()), saved | {"autoRepairHoes": False})

    def test_planting_mode_defaults_false_preserves_existing_and_allows_explicit_change(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            minecraft, executable = prepared_installation(root)
            path = root / "runtime" / "game" / "config" / "schematic-supervisor" / "settings.json"
            with patch.object(installer, "ROOT", root), patch.object(installer, "ensure_game_closed"):
                installer.install(minecraft, executable, executable)
                saved = json.loads(path.read_text())
                self.assertIs(saved["deferPlanting"], False)
                saved.update({"deferPlanting": True, "minimumFood": 0, "customPreference": 42})
                path.write_text(json.dumps(saved))
                installer.install(minecraft, executable, executable)
                self.assertEqual(json.loads(path.read_text()), saved)
                installer.install(minecraft, executable, executable, defer_planting=False)
                self.assertEqual(json.loads(path.read_text()), saved | {"deferPlanting": False})

    def test_new_profile_preserves_existing_and_upgrades_old_managed_jar(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            minecraft = root / "original"
            minecraft.mkdir()
            existing = {"name": "Existing", "gameDir": str(minecraft)}
            (minecraft / "launcher_profiles.json").write_text(json.dumps({"profiles": {"existing": existing}}))
            version_dir = minecraft / "versions" / installer.VERSION_ID
            version_dir.mkdir(parents=True)
            (version_dir / f"{installer.VERSION_ID}.json").write_text(json.dumps({
                "id": installer.VERSION_ID, "inheritsFrom": "1.21.8",
                "mainClass": "net.fabricmc.loader.impl.launch.knot.KnotClient"}))
            for name, identity in (("schematic-supervisor-0.2.jar", "schematic_supervisor"),
                                   ("fabric-api-1.jar", "fabric-api"), ("malilib-1.jar", "malilib"),
                                   ("litematica-1.jar", "litematica"), ("baritone-api-fabric-1.jar", "baritone")):
                mod(root / "build" / "automation-mods" / name, identity)
            target = root / "runtime" / "game" / "mods"
            old = target / "renamed-old-supervisor.jar"
            mod(old, "schematic_supervisor")
            mod(target / "unrelated.jar", "unrelated")
            (root / "schematics").mkdir()
            (root / "schematics" / "wheatfarm_v2.litematic").write_bytes(b"test-schematic")
            fake_exe = root / "agent.exe"
            fake_exe.touch()
            with patch.object(installer, "ROOT", root), patch.object(installer, "ensure_game_closed"):
                report = installer.install(minecraft, fake_exe, fake_exe)
                installer.install(minecraft, fake_exe, fake_exe)
            self.assertFalse(old.exists())
            self.assertTrue((Path(report["backup_directory"]) / old.name).exists())
            self.assertTrue((target / "unrelated.jar").exists())
            self.assertEqual(len(list(target.glob("*.jar"))), 6)
            profiles = json.loads((minecraft / "launcher_profiles.json").read_text())["profiles"]
            self.assertEqual(profiles["existing"], existing)
            self.assertEqual(profiles[installer.PROFILE_ID]["gameDir"], str(root / "runtime" / "game"))
            self.assertEqual(report["schematic"]["source_name"], "wheatfarm_v2.litematic")

    def test_installs_distinct_schematic_and_records_exact_source_digest(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            minecraft, executable = prepared_installation(root)
            source = root / "workshop.litematic"
            source.write_bytes(b"a-distinct-build")
            with patch.object(installer, "ROOT", root), patch.object(installer, "ensure_game_closed"):
                report = installer.install(minecraft, executable, executable, source)
            destination = root / "runtime" / "game" / "schematics" / source.name
            self.assertEqual(destination.read_bytes(), source.read_bytes())
            self.assertFalse((destination.parent / "wheatfarm_v2.litematic").exists())
            self.assertEqual(report["schematic"]["source_name"], source.name)
            self.assertEqual(report["schematic"]["sha256"], hashlib.sha256(source.read_bytes()).hexdigest())
            self.assertTrue(report["schematic"]["copied"])
            persisted = json.loads((root / "runtime" / "installation.json").read_text())
            self.assertEqual(persisted["schematic"], report["schematic"])
            settings = json.loads((root / "companion" / "agent.local.json").read_text())
            self.assertEqual(settings["model"], "gpt-6-astra")
            self.assertEqual(settings["model_policy"], "on-error")

    def test_different_same_name_schematic_is_backed_up_before_replacement(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            minecraft, executable = prepared_installation(root)
            source = root / "tower.litematic"
            source.write_bytes(b"new-tower")
            destination = root / "runtime" / "game" / "schematics" / source.name
            destination.parent.mkdir(parents=True)
            destination.write_bytes(b"previous-tower")
            with patch.object(installer, "ROOT", root), patch.object(installer, "ensure_game_closed"):
                report = installer.install(minecraft, executable, executable, source)
                repeated = installer.install(minecraft, executable, executable, source)
            self.assertEqual(Path(report["schematic"]["backup_path"]).read_bytes(), b"previous-tower")
            self.assertEqual(destination.read_bytes(), b"new-tower")
            self.assertEqual(source.read_bytes(), b"new-tower")
            self.assertFalse(repeated["schematic"]["copied"])
            self.assertIsNone(repeated["schematic"]["backup_path"])

    def test_existing_profile_schematic_is_never_overwritten_onto_itself(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            minecraft, executable = prepared_installation(root)
            source = root / "runtime" / "game" / "schematics" / "existing.litematic"
            source.parent.mkdir(parents=True)
            source.write_bytes(b"existing-build")
            original_mtime = source.stat().st_mtime_ns
            with patch.object(installer, "ROOT", root), patch.object(installer, "ensure_game_closed"), \
                    patch.object(installer.os, "replace", wraps=installer.os.replace) as replace:
                report = installer.install(minecraft, executable, executable, source)
            self.assertFalse(report["schematic"]["copied"])
            self.assertIsNone(report["schematic"]["backup_path"])
            self.assertEqual(source.read_bytes(), b"existing-build")
            self.assertEqual(source.stat().st_mtime_ns, original_mtime)
            self.assertTrue(all(Path(call.args[1]).resolve() != source.resolve() for call in replace.call_args_list))

    def test_missing_wrong_suffix_and_directory_inputs_fail_before_any_writes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            minecraft, executable = prepared_installation(root)
            wrong_suffix = root / "not-a-schematic.txt"
            wrong_suffix.write_bytes(b"wrong-input")
            directory = root / "folder.litematic"
            directory.mkdir()
            before = {str(path.relative_to(root)): path.read_bytes() for path in root.rglob("*") if path.is_file()}
            before_directories = {str(path.relative_to(root)) for path in root.rglob("*") if path.is_dir()}
            for source in (root / "missing.litematic", wrong_suffix, directory):
                with self.subTest(source=source), patch.object(installer, "ROOT", root), \
                        patch.object(installer, "ensure_game_closed") as closed:
                    with self.assertRaises((OSError, ValueError)):
                        installer.install(minecraft, executable, executable, source)
                    closed.assert_not_called()
                self.assertEqual(before, {str(path.relative_to(root)): path.read_bytes()
                                          for path in root.rglob("*") if path.is_file()})
                self.assertEqual(before_directories, {str(path.relative_to(root))
                                                     for path in root.rglob("*") if path.is_dir()})

    def test_explicit_existing_model_settings_are_preserved(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            minecraft, executable = prepared_installation(root)
            settings_path = root / "companion" / "agent.local.json"
            settings_path.parent.mkdir()
            settings_path.write_text(json.dumps({"model": "gpt-6-astra", "model_policy": "on-error",
                                                 "reasoning_effort": "high", "custom_setting": True}))
            with patch.object(installer, "ROOT", root), patch.object(installer, "ensure_game_closed"):
                installer.install(minecraft, executable, executable)
            settings = json.loads(settings_path.read_text())
            self.assertEqual(settings["reasoning_effort"], "high")
            self.assertTrue(settings["custom_setting"])
            self.assertEqual(settings["model"], "gpt-6-astra")
            self.assertEqual(settings["model_policy"], "on-error")

    def test_invalid_runner_settings_fail_before_the_profile_changes(self):
        for content in ("{not json", "[]", '"text"'):
            with self.subTest(content=content), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                minecraft, executable = prepared_installation(root)
                with patch.object(installer, "ROOT", root), patch.object(installer, "ensure_game_closed"):
                    installer.install(minecraft, executable, executable)
                    stage_new_supervisor(root)
                    (root / "companion" / "agent.local.json").write_text(content, encoding="utf-8")
                    before = files(root)
                    with self.assertRaisesRegex(ValueError, "Runner settings"):
                        installer.install(minecraft, executable, executable)
                self.assertEqual(before, files(root))

    def test_an_unreadable_installed_jar_fails_before_the_old_supervisor_moves(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            minecraft, executable = prepared_installation(root)
            with patch.object(installer, "ROOT", root), patch.object(installer, "ensure_game_closed"):
                installer.install(minecraft, executable, executable)
                stage_new_supervisor(root)
                (root / "runtime" / "game" / "mods" / "zz-damaged.jar").write_bytes(b"not a jar")
                before = files(root)
                with self.assertRaisesRegex(ValueError, "Cannot identify installed mod zz-damaged.jar"):
                    installer.install(minecraft, executable, executable)
            self.assertEqual(before, files(root))
            self.assertEqual(1, len(list((root / "runtime" / "installation-backups").iterdir())))

    def test_a_failed_write_puts_back_the_previous_profile(self):
        def failing_metadata(path, value):
            if path.name == "installation.json":
                raise OSError("disk full")
            original(path, value)

        original = installer.atomic_json
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            minecraft, executable = prepared_installation(root)
            with patch.object(installer, "ROOT", root), patch.object(installer, "ensure_game_closed"):
                fresh = files(root)
                with patch.object(installer, "atomic_json", side_effect=failing_metadata), \
                        self.assertRaisesRegex(OSError, "disk full"):
                    installer.install(minecraft, executable, executable, shop_purchases=True)
                self.assertEqual(fresh, files(root, skip="runtime/installation-backups"))
                installer.install(minecraft, executable, executable)
                stage_new_supervisor(root)
                installed = files(root, skip="runtime/installation-backups")
                with patch.object(installer, "atomic_json", side_effect=failing_metadata), \
                        self.assertRaisesRegex(OSError, "disk full"):
                    installer.install(minecraft, executable, executable, defer_planting=True)
                self.assertEqual(installed, files(root, skip="runtime/installation-backups"))
                recorded = json.loads((root / "runtime" / "installation.json").read_text())
                for entry in recorded["mods"]:
                    jar = root / "runtime" / "game" / "mods" / entry["name"]
                    self.assertEqual(entry["sha256"], hashlib.sha256(jar.read_bytes()).hexdigest())

    def test_a_failed_restore_names_what_it_left_behind(self):
        def failing_metadata(path, value):
            if path.name == "installation.json":
                raise OSError("disk full")
            original(path, value)

        original = installer.atomic_json
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            minecraft, executable = prepared_installation(root)
            with patch.object(installer, "ROOT", root), patch.object(installer, "ensure_game_closed"):
                installer.install(minecraft, executable, executable)
                stage_new_supervisor(root)
                with patch.object(installer, "atomic_json", side_effect=failing_metadata), \
                        patch.object(installer, "atomic_bytes", side_effect=OSError("still full")), \
                        self.assertRaisesRegex(ValueError, "disk full; the previous profile could not be fully "
                                                           "restored .*launcher_profiles.json.*Its backups are in"):
                    installer.install(minecraft, executable, executable)


if __name__ == "__main__":
    unittest.main()

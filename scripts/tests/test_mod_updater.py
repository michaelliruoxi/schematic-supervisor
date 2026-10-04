from __future__ import annotations

import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile


MODULE_PATH = Path(__file__).resolve().parents[2] / "scripts" / "update_automation_mod.py"
SPEC = importlib.util.spec_from_file_location("automation_mod_updater", MODULE_PATH)
updater = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(updater)


@unittest.skipUnless(os.name == "nt", "The updater process guard uses Windows PowerShell.")
class ProcessGuardTests(unittest.TestCase):
    def run_with_process_inventory(self, processes):
        """Execute the production classifier using only fixture process records."""
        real_run = updater.subprocess.run
        fixture = json.dumps(processes).replace("'", "''")
        prefix = (
            "function Get-CimInstance { param([string]$ClassName) "
            "if ($ClassName -ne 'Win32_Process') { throw 'Unexpected process query' }; "
            f"foreach ($process in (ConvertFrom-Json -InputObject '{fixture}')) {{ $process }}"
            " }; "
        )

        def run_fixture(arguments, **kwargs):
            self.assertEqual(arguments[-2], "-Command")
            result = real_run([*arguments[:-1], prefix + arguments[-1]], **kwargs)
            self.assertEqual(result.returncode, 0, result.stderr)
            return result

        with patch.object(updater.subprocess, "run", side_effect=run_fixture):
            updater.ensure_processes_absent()

    def test_quoted_and_unquoted_launcher_run_commands_block_update(self):
        for command in (
                r'"C:\runtime\python.exe" "C:\project\companion\agent_launcher.py" "run" "--workspace" "C:\project"',
                r'C:\runtime\python.exe C:\project\companion\agent_launcher.py run --workspace C:\project'):
            with self.subTest(command=command):
                with self.assertRaisesRegex(ValueError, "Stop Minecraft and the supervision runner"):
                    self.run_with_process_inventory([
                        {"Name": "python.exe", "ProcessId": 201, "CommandLine": command}])

    def test_quoted_and_unquoted_module_tokens_block_update(self):
        for module in ('-m supervisor_companion.agent_runner',
                       '"-m" "supervisor_companion.agent_runner"',
                       '-m "supervisor_companion.agent_runner"',
                       '"-m" supervisor_companion.agent_runner'):
            with self.subTest(module=module):
                with self.assertRaisesRegex(ValueError, "Stop Minecraft and the supervision runner"):
                    self.run_with_process_inventory([
                        {"Name": "pythonw.exe", "ProcessId": 202,
                         "CommandLine": f'"C:\\runtime\\pythonw.exe" {module} --workspace C:\\project'}])

    def test_launcher_serve_command_is_allowed(self):
        self.run_with_process_inventory([
            {"Name": "python.exe", "ProcessId": 203,
             "CommandLine": r'"C:\runtime\python.exe" "C:\project\companion\agent_launcher.py" "serve"'}])

    def test_unrelated_python_process_is_allowed(self):
        self.run_with_process_inventory([
            {"Name": "python.exe", "ProcessId": 204,
             "CommandLine": r'"C:\runtime\python.exe" "C:\project\unrelated.py" "run"'}])

    def test_launcher_process_is_allowed(self):
        self.run_with_process_inventory([
            {"Name": "Minecraft.exe", "ProcessId": 205, "CommandLine": None},
            {"Name": "MinecraftLauncher.exe", "ProcessId": 206, "CommandLine": None}])

    def test_unknown_python_command_line_refuses_verification(self):
        with self.assertRaisesRegex(ValueError, "Could not verify stopped game and runner"):
            self.run_with_process_inventory([
                {"Name": "python.exe", "ProcessId": 207, "CommandLine": None}])

    def test_empty_inventory_refuses_verification(self):
        with self.assertRaisesRegex(ValueError, "Could not verify stopped game and runner"):
            self.run_with_process_inventory([])


class ModUpdaterTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.artifact = self.root / "build/automation-mods/schematic-supervisor-0.1.0.jar"
        self.installed = self.root / "runtime/game/mods" / self.artifact.name
        self.state = self.root / "runtime/game/config/schematic-supervisor"
        self.state.mkdir(parents=True)
        self.metadata = self.root / "runtime/installation.json"
        self.report = self.root / "validation.json"
        self.make_jar(self.artifact, "new")
        self.make_jar(self.installed, "old")
        self.other = self.installed.parent / "other.jar"
        self.make_jar(self.other, "other", "other_mod")
        self.old_jar = self.installed.read_bytes()
        self.expected = updater.sha256(self.artifact.read_bytes())
        self.prior_update = {"sha256": "prior", "background_building": True,
                             "temporary_supports_integrated": True,
                             "material_shop_live_verified": True, "custom_feature": "retained in history"}
        self.write_json(self.metadata, {"profile": "Schematic Supervisor", "custom_setting": 5,
                                       "game_directory": str(self.root / "runtime/game"),
                                       "mods": [{"name": self.installed.name,
                                                 "sha256": updater.sha256(self.old_jar)},
                                                {"name": self.other.name, "sha256": "unmodified"}],
                                       "last_mod_update": self.prior_update})
        self.write_json(self.report, {"sha256": self.expected, "java_tests": 722,
                                     "failures": 0, "errors": 0, "skipped": 0})
        for name in updater.STATE_NAMES:
            (self.state / name).write_bytes(b'{"state":"preserved"}\r\n')
        self.nested = self.state / "builds/another-build/checkpoint.json"
        self.nested.parent.mkdir(parents=True)
        self.nested.write_bytes(b'{"separate":"build"}\n')
        (self.nested.parent / "hoe-repair.json").write_bytes(
            b'{"pending":true,"command":"fix","attempt":"awaiting-receipt"}\n')
        (self.nested.parent / "surplus-disposal.json").write_bytes(
            b'{"pending":true,"phase":"awaiting-receipt","item":"minecraft:melon_seeds"}\n')
        (self.state / "protocol-token.txt").write_text("secret-must-not-be-copied", encoding="utf-8")
        self.old_metadata = self.metadata.read_bytes()

    @staticmethod
    def write_json(path, value):
        path.write_text(json.dumps(value), encoding="utf-8")

    @staticmethod
    def make_jar(path, marker, mod_id="schematic_supervisor", version=None):
        path.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("fabric.mod.json", json.dumps(
                {"id": mod_id, "version": marker if version is None else version}))
            archive.writestr("test-content", marker)

    def install_version(self, version):
        """Replace the installed fixture jar with one declaring this version, recorded in the metadata."""
        self.make_jar(self.installed, "old", version=version)
        self.old_jar = self.installed.read_bytes()
        metadata = updater.read_object(self.metadata)
        metadata["mods"][0]["sha256"] = updater.sha256(self.old_jar)
        self.write_json(self.metadata, metadata)
        self.old_metadata = self.metadata.read_bytes()

    def update(self, **options):
        return updater.update(self.root, self.artifact, self.expected, self.report,
                              options.get("process_check", lambda: None))

    def assert_unchanged(self):
        self.assertEqual(self.old_jar, self.installed.read_bytes())
        self.assertEqual(self.old_metadata, self.metadata.read_bytes())

    def test_update_backs_up_exact_saved_state_and_preserves_history_without_live_claim(self):
        before = updater.state_snapshot(self.root, self.state)
        other_bytes = self.other.read_bytes()
        checks = []
        result = self.update(process_check=lambda: checks.append(True))
        backup = Path(result["backup_directory"])
        self.assertEqual(self.installed.read_bytes(), self.artifact.read_bytes())
        self.assertEqual(other_bytes, self.other.read_bytes())
        self.assertEqual(before, updater.state_snapshot(self.root, self.state))
        self.assertIn("builds/another-build/hoe-repair.json", before)
        self.assertIn("builds/another-build/surplus-disposal.json", before)
        self.assertEqual(self.old_jar, (backup / self.installed.name).read_bytes())
        self.assertEqual(self.old_metadata, (backup / "installation.json").read_bytes())
        for name, data in before.items():
            self.assertEqual(data, (backup / "state" / name).read_bytes())
        self.assertFalse(list(backup.rglob("*token*")))
        self.assertFalse(any(b"secret-must-not-be-copied" in p.read_bytes()
                             for p in backup.rglob("*") if p.is_file()))
        metadata = updater.read_object(self.metadata)
        self.assertEqual(5, metadata["custom_setting"])
        self.assertEqual([self.prior_update], metadata["mod_update_history"])
        self.assertFalse(metadata["last_mod_update"]["update_live_verified"])
        self.assertNotIn("material_shop_live_verified", metadata["last_mod_update"])
        self.assertNotIn("temporary_supports_integrated", metadata["last_mod_update"])
        self.assertEqual(updater.sha256(before["checkpoint.json"]), result["checkpoint_sha256"])
        self.assertTrue(result["checkpoint_unchanged"])
        self.assertGreaterEqual(len(checks), 4)
        self.assertFalse((self.root / "runtime/.mod-update.lock").exists())

    def test_hash_bound_feature_flags_remain_compatible_with_manifest_evidence(self):
        with patch.object(sys, "path", [str(MODULE_PATH.parent), *sys.path]):
            capture = importlib.import_module("capture_build_manifest")
        for supported in (True, False):
            with self.subTest(supported=supported):
                report = updater.read_object(self.report)
                report["integrated_features"] = {"temporary_supports_integrated": supported,
                                                  "background_building": True, "material_shop_receipts": False,
                                                  "automatic_hoe_repair": True}
                self.write_json(self.report, report)
                entry = self.update()
                self.assertEqual(supported, entry["temporary_supports_integrated"])
                self.assertTrue(entry["background_building"])
                self.assertTrue(entry["automatic_hoe_repair"])
                self.assertFalse(entry["material_shop_receipts"])
                self.assertFalse(entry["update_live_verified"])
                self.assertNotIn("material_shop_live_verified", entry)
                evidence = capture._support_evidence(self.metadata, self.root / "runtime/game",
                                                      {"sha256": self.expected}, self.installed.name)
                self.assertEqual("available" if supported else "unavailable", evidence["status"])
                self.assertEqual("hash_matched_installation_report", evidence["basis"])
                self.assertEqual("not_assessed_by_manifest", evidence["live_verification"])

    def test_omitted_feature_map_does_not_inherit_old_integration_claim(self):
        with patch.object(sys, "path", [str(MODULE_PATH.parent), *sys.path]):
            capture = importlib.import_module("capture_build_manifest")
        self.update()
        metadata = updater.read_object(self.metadata)
        self.assertTrue(metadata["mod_update_history"][0]["temporary_supports_integrated"])
        self.assertNotIn("temporary_supports_integrated", metadata["last_mod_update"])
        evidence = capture._support_evidence(self.metadata, self.root / "runtime/game",
                                              {"sha256": self.expected}, self.installed.name)
        self.assertEqual("unknown", evidence["status"])
        self.assertEqual("integration_claim_missing_or_invalid", evidence["reason"])

    def test_invalid_feature_maps_and_undeclared_or_live_flags_refuse_before_writing(self):
        invalid = (None, [], "background_building", True, 1,
                   {"temporary_supports_integrated": 1}, {"temporary_supports_integrated": "true"},
                   {"background_building": None}, {"unknown_feature": True},
                   {"update_live_verified": True}, {"material_shop_live_verified": False},
                   {"planting_deferred": True}, {"checkpoint_unchanged": True})
        for features in invalid:
            with self.subTest(features=features):
                report = updater.read_object(self.report)
                report["integrated_features"] = features
                self.write_json(self.report, report)
                with self.assertRaisesRegex(ValueError, "integrated_features"):
                    self.update()
                self.assert_unchanged()
                self.assertFalse((self.root / "runtime/installation-backups").exists())

    def test_wrong_hash_failed_or_unbound_report_refuses_before_writing(self):
        for field, value in (("sha256", "0" * 64), ("sha256", None), ("java_tests", 0),
                             ("java_tests", True), ("failures", 1), ("errors", 1), ("skipped", 1)):
            with self.subTest(field=field, value=value):
                report = {"sha256": self.expected, "java_tests": 1, "failures": 0, "errors": 0, "skipped": 0}
                report[field] = value
                self.write_json(self.report, report)
                with self.assertRaises(ValueError):
                    self.update()
                self.assert_unchanged()
                self.assertFalse((self.root / "runtime/installation-backups").exists())

    def test_only_staged_supervisor_jar_can_be_used(self):
        other_location = self.root / self.artifact.name
        other_location.write_bytes(self.artifact.read_bytes())
        with self.assertRaisesRegex(ValueError, "staged jar"):
            updater.update(self.root, other_location, self.expected, self.report, lambda: None)
        self.make_jar(self.artifact, "wrong", "unrelated_mod")
        self.expected = updater.sha256(self.artifact.read_bytes())
        self.write_json(self.report, {"sha256": self.expected, "java_tests": 1,
                                     "failures": 0, "errors": 0, "skipped": 0})
        with self.assertRaisesRegex(ValueError, "not the supervisor"):
            self.update()
        self.assert_unchanged()

    def test_unknown_or_duplicate_installed_mod_refuses(self):
        duplicate = self.installed.parent / "duplicate.jar"
        self.make_jar(duplicate, "old")
        with self.assertRaisesRegex(ValueError, "exactly one"):
            self.update()
        duplicate.write_bytes(b"not a jar")
        with self.assertRaisesRegex(ValueError, "Cannot identify"):
            self.update()
        self.assert_unchanged()

    def test_existing_update_lock_refuses_and_is_not_removed(self):
        lock = self.root / "runtime/.mod-update.lock"
        lock.write_text("existing process", encoding="utf-8")
        with self.assertRaises(FileExistsError):
            self.update()
        self.assertEqual("existing process", lock.read_text())
        self.assert_unchanged()

    def test_failed_process_verification_refuses_before_backup(self):
        with self.assertRaisesRegex(ValueError, "running"):
            self.update(process_check=lambda: (_ for _ in ()).throw(ValueError("game running")))
        self.assert_unchanged()
        self.assertFalse((self.root / "runtime/installation-backups").exists())

    def test_changed_checkpoint_during_preparation_refuses_without_overwriting_it(self):
        calls = 0

        def check():
            nonlocal calls
            calls += 1
            if calls == 3:
                self.nested.write_bytes(b"new authoritative checkpoint")

        with self.assertRaisesRegex(ValueError, "changed during preparation"):
            self.update(process_check=check)
        self.assert_unchanged()
        self.assertEqual(b"new authoritative checkpoint", self.nested.read_bytes())

    def test_metadata_write_failure_rolls_jar_back(self):
        original = updater.atomic_json

        def fail_metadata(path, data):
            if path == self.metadata:
                raise OSError("disk full")
            original(path, data)

        with patch.object(updater, "atomic_json", side_effect=fail_metadata):
            with self.assertRaisesRegex(OSError, "disk full"):
                self.update()
        self.assert_unchanged()
        outcome = next((self.root / "runtime/installation-backups").glob("*/outcome.json"))
        self.assertEqual("rolled_back", updater.read_object(outcome)["status"])

    def renamed_artifact(self):
        """A staged jar whose filename carries a new version, as after a version bump."""
        renamed = self.artifact.with_name("schematic-supervisor-0.2.0.jar")
        self.artifact.rename(renamed)
        self.artifact = renamed
        return renamed

    def test_a_new_version_replaces_the_installed_jar_under_its_new_name(self):
        self.renamed_artifact()
        result = self.update()
        new_path = self.installed.with_name(self.artifact.name)
        self.assertFalse(self.installed.exists(), "two copies of the mod would stop the game")
        self.assertEqual(self.artifact.read_bytes(), new_path.read_bytes())
        self.assertEqual(self.old_jar, (Path(result["backup_directory"]) / self.installed.name).read_bytes())
        self.assertEqual(str(self.installed.relative_to(self.root)), result["previous_installed_path"])
        names = [entry["name"] for entry in updater.read_object(self.metadata)["mods"]]
        self.assertIn(new_path.name, names)
        self.assertNotIn(self.installed.name, names)

    def test_a_failed_rename_restores_the_old_jar_and_removes_the_new_one(self):
        self.renamed_artifact()
        original = updater.atomic_json

        def fail_metadata(path, data):
            if path == self.metadata:
                raise OSError("disk full")
            original(path, data)

        with patch.object(updater, "atomic_json", side_effect=fail_metadata):
            with self.assertRaisesRegex(OSError, "disk full"):
                self.update()
        self.assert_unchanged()
        self.assertFalse(self.installed.with_name(self.artifact.name).exists())

    def test_a_staged_name_taken_by_another_file_refuses(self):
        self.renamed_artifact()
        self.make_jar(self.installed.with_name(self.artifact.name), "other", "other_mod")
        with self.assertRaisesRegex(ValueError, "already uses the staged filename"):
            self.update()
        self.assert_unchanged()

    def test_an_older_profile_keeps_its_shop_purchases_and_an_existing_choice_is_kept(self):
        self.install_version("0.1.0")
        result = self.update()
        shop = self.state / "shop.json"
        self.assertEqual({"enabled": True}, updater.read_object(shop))
        self.assertTrue(result["shop_settings_created"])
        self.assertTrue(updater.read_object(self.metadata)["last_mod_update"]["shop_settings_created"])
        shop.write_text('{"enabled": false}', encoding="utf-8")
        self.make_jar(self.artifact, "newer")
        self.expected = updater.sha256(self.artifact.read_bytes())
        self.write_json(self.report, {"sha256": self.expected, "java_tests": 1,
                                     "failures": 0, "errors": 0, "skipped": 0})
        second = self.update()
        self.assertEqual({"enabled": False}, updater.read_object(shop))
        self.assertNotIn("shop_settings_created", second)

    def test_a_profile_from_0_2_0_or_with_an_unreadable_version_keeps_purchases_off(self):
        shop = self.state / "shop.json"
        for version in ("0.2.0", "0.2.0+build.7", "0.10.0", "1.0.0", "old", "", "v0.1.0", 7, [0, 1, 0]):
            with self.subTest(version=version):
                self.install_version(version)
                result = self.update()
                self.assertFalse(shop.exists(), "a profile that installed opted out must stay opted out")
                self.assertNotIn("shop_settings_created", result)
                self.assertNotIn("shop_settings_created", updater.read_object(self.metadata)["last_mod_update"])

    def test_post_commit_checkpoint_change_rolls_back_only_updater_files(self):
        calls = 0

        def check():
            nonlocal calls
            calls += 1
            if calls == 5:
                self.nested.write_bytes(b"external checkpoint change")

        with self.assertRaisesRegex(ValueError, "verification changed"):
            self.update(process_check=check)
        self.assert_unchanged()
        self.assertEqual(b"external checkpoint change", self.nested.read_bytes())

    def test_game_start_during_replace_does_not_rollback_a_potentially_loaded_jar(self):
        calls = 0

        def check():
            nonlocal calls
            calls += 1
            if calls >= 4:
                raise ValueError("game running")

        with self.assertRaisesRegex(ValueError, "game running"):
            self.update(process_check=check)
        self.assertEqual(self.artifact.read_bytes(), self.installed.read_bytes())
        self.assertEqual(self.old_metadata, self.metadata.read_bytes())
        outcome = next((self.root / "runtime/installation-backups").glob("*/outcome.json"))
        self.assertEqual("inspection_required", updater.read_object(outcome)["status"])
        self.assertFalse(updater.read_object(outcome)["update_live_verified"])

    def test_changed_metadata_is_preserved_and_requires_inspection(self):
        calls = 0

        def check():
            nonlocal calls
            calls += 1
            if calls == 5:
                metadata = updater.read_object(self.metadata)
                metadata["external_change"] = "keep"
                self.write_json(self.metadata, metadata)

        with self.assertRaisesRegex(ValueError, "verification changed"):
            self.update(process_check=check)
        self.assertEqual("keep", updater.read_object(self.metadata)["external_change"])
        self.assertEqual(self.artifact.read_bytes(), self.installed.read_bytes())
        outcome = next((self.root / "runtime/installation-backups").glob("*/outcome.json"))
        self.assertEqual("inspection_required", updater.read_object(outcome)["status"])

    def test_report_outside_workspace_and_installed_hash_mismatch_refuse(self):
        with tempfile.TemporaryDirectory() as outside:
            report = Path(outside) / "validation.json"
            report.write_bytes(self.report.read_bytes())
            with self.assertRaisesRegex(ValueError, "inside the workspace"):
                updater.update(self.root, self.artifact, self.expected, report, lambda: None)
        metadata = updater.read_object(self.metadata)
        metadata["mods"][0]["sha256"] = "f" * 64
        self.write_json(self.metadata, metadata)
        with self.assertRaisesRegex(ValueError, "does not match"):
            self.update()
        self.assertEqual(self.old_jar, self.installed.read_bytes())

    def test_second_update_keeps_prior_update_history(self):
        first = self.update()
        second = self.update()
        self.assertNotEqual(first["backup_directory"], second["backup_directory"])
        self.assertEqual([self.prior_update, first], updater.read_object(self.metadata)["mod_update_history"])

    def test_process_check_accepts_verified_empty_snapshot_and_does_not_reject_launcher(self):
        result = subprocess.CompletedProcess([], 0, '{"verified":true,"game_count":0,"runner_count":0}', "")
        with patch.object(updater.os, "name", "nt"), patch.object(updater.subprocess, "run", return_value=result) as run:
            updater.ensure_processes_absent()
        self.assertNotIn("MinecraftLauncher.exe", run.call_args.args[0][-1])
        self.assertTrue(run.call_args.kwargs["capture_output"])

    def test_process_check_refuses_running_or_unverifiable_snapshot(self):
        for facts in ({"verified": True, "game_count": 1, "runner_count": 0},
                      {"verified": True, "game_count": 0, "runner_count": 1},
                      {"verified": False, "game_count": 0, "runner_count": 0},
                      {"verified": True, "game_count": False, "runner_count": 0}, {}):
            with self.subTest(facts=facts):
                result = subprocess.CompletedProcess([], 0, json.dumps(facts), "")
                with patch.object(updater.os, "name", "nt"), patch.object(updater.subprocess, "run", return_value=result):
                    with self.assertRaises(ValueError):
                        updater.ensure_processes_absent()

    def test_process_check_timeout_or_failure_is_not_interpreted_as_absence(self):
        for failure in (subprocess.TimeoutExpired("powershell", 30), OSError("cannot enumerate")):
            with patch.object(updater.os, "name", "nt"), patch.object(updater.subprocess, "run", side_effect=failure):
                with self.assertRaisesRegex(ValueError, "Could not verify"):
                    updater.ensure_processes_absent()


if __name__ == "__main__":
    unittest.main()

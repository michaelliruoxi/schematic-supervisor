from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile


SCRIPTS = Path(__file__).resolve().parents[1]
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import capture_build_manifest as capture  # noqa: E402


class BuildManifestTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.game = self.root / "account-secret" / "game"
        (self.game / "mods").mkdir(parents=True)
        self.source = self.game / "schematics" / "workshop.litematic"
        self.source.parent.mkdir()
        self.source.write_bytes(b"exact-source-snapshot")
        self.state_path = self.root / "private-world-dimension.json"
        self.state = {
            "server": "server-secret.invalid", "world_file": "private-world.dat",
            "placements": {"selected": 1, "placements": [
                {"enabled": False, "schematic": "not-the-selected-file.litematic"},
                {"schematic": str(self.source), "name": "account-secret", "hash_code": "hash-secret",
                 "origin": [17, -63, -29], "rotation": "CLOCKWISE_90", "mirror": "FRONT_BACK", "enabled": True,
                 "ui_state": {"token": "token-secret"},
                 "placements": [
                     {"name": "structure", "placement": {
                         "pos": [-4, 3, 11], "rotation": "COUNTERCLOCKWISE_90", "mirror": "LEFT_RIGHT",
                         "enabled": True, "hash_code": "hash-secret", "server": "server-secret.invalid"}},
                     {"name": "optional", "placement": {
                         "pos": [0, -2, 4], "rotation": "CLOCKWISE_180", "mirror": "NONE", "enabled": False}},
                 ]},
            ]},
        }
        self.write_state()
        self.settings_path = self.game / "config" / "schematic-supervisor" / "settings.json"
        self.settings_path.parent.mkdir(parents=True)
        self.settings = {"placementBlocksPerTick": 20_000, "verificationBlocksPerTick": 10_000,
                         "interactionCooldownTicks": 4, "pathGoalRadius": 3, "minimumFood": 0,
                         "token": "token-secret", "account": "account-secret",
                         "companionUri": "http://server-secret.invalid:8766", "controlPort": 8765}
        self.settings_path.write_text(json.dumps(self.settings))
        self.mod_paths = {}
        for identity in capture.MANAGED_MOD_IDS:
            path = self.game / "mods" / f"{identity}.jar"
            self.write_mod(path, identity)
            self.mod_paths[identity] = path
        self.output = self.root / "reports" / "manifest.json"
        self.installation_path = self.game.parent / "installation.json"

    def write_state(self) -> None:
        self.state_path.write_text(json.dumps(self.state), encoding="utf-8")

    def installation(self, integrated: object = True) -> dict:
        mod = self.mod_paths["schematic_supervisor"]
        digest = hashlib.sha256(mod.read_bytes()).hexdigest()
        return {"game_directory": str(self.game), "account": "account-secret", "token": "token-secret",
                "mods": [{"name": mod.name, "sha256": digest}],
                "last_mod_update": {"sha256": digest.upper(), "temporary_supports_integrated": integrated,
                                    "live_upper_support_verified": True, "server": "server-secret.invalid"}}

    def write_installation(self, report: dict) -> None:
        self.installation_path.write_text(json.dumps(report), encoding="utf-8")

    @staticmethod
    def write_mod(path: Path, identity: str, version: str = "1.2.3+build.4") -> None:
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("fabric.mod.json", json.dumps({
                "id": identity, "version": version, "authors": ["account-secret"],
                "custom": {"token": "token-secret", "server": "server-secret.invalid"}}))
            archive.writestr("content.bin", identity.encode())

    def test_selected_index_exact_transforms_and_managed_hashes_are_captured(self) -> None:
        manifest = capture.capture_manifest(self.state_path, self.game, self.output)
        self.assertEqual(json.loads(self.output.read_text()), manifest)
        self.assertEqual(manifest["source"], {"name": "workshop.litematic",
                         "sha256": hashlib.sha256(self.source.read_bytes()).hexdigest()})
        selected = manifest["placement"]
        self.assertEqual(selected["selected_index"], 1)
        self.assertEqual(selected["origin"], [17, -63, -29])
        self.assertEqual(selected["rotation"], "CLOCKWISE_90")
        self.assertEqual(selected["mirror"], "FRONT_BACK")
        self.assertEqual(selected["sub_regions"], [
            {"name": "structure", "pos": [-4, 3, 11], "rotation": "COUNTERCLOCKWISE_90",
             "mirror": "LEFT_RIGHT", "enabled": True},
            {"name": "optional", "pos": [0, -2, 4], "rotation": "CLOCKWISE_180",
             "mirror": "NONE", "enabled": False},
        ])
        self.assertEqual(len(manifest["mods"]), 5)
        for entry in manifest["mods"]:
            self.assertEqual(entry["version"], "1.2.3+build.4")
            self.assertEqual(entry["sha256"], hashlib.sha256(self.mod_paths[entry["id"]].read_bytes()).hexdigest())
        self.assertEqual(manifest["layer_policy"], "layers-v2")
        self.assertEqual(manifest["resource_bounds"], {
            "maximum_volume_cells": 2_000_000, "maximum_non_air_blocks": 1_000_000, "maximum_live_chunks": 1024})
        self.assertFalse(manifest["snapshot"]["source_contents_decoded"])
        self.assertIn("Save", manifest["snapshot"]["freshness"])
        self.assertEqual(list(self.output.parent.iterdir()), [self.output])

    def test_secret_paths_addresses_accounts_tokens_and_ui_fields_are_not_exported(self) -> None:
        self.write_installation(self.installation())
        manifest = capture.capture_manifest(self.state_path, self.game, self.output)
        encoded = self.output.read_text()
        for secret in ("token-secret", "account-secret", "server-secret.invalid", "private-world",
                       "hash-secret", "hash_code", "companionUri", "controlPort", "ui_state", str(self.root)):
            with self.subTest(secret=secret):
                self.assertNotIn(secret, encoded)
        self.assertEqual(manifest["supervisor_settings"]["values"], {
            key: self.settings[key] for key in capture.SETTING_LIMITS})
        self.assertEqual(manifest["supervisor_settings"]["unsaved_fields"],
                         ["autoRepairHoes", "deferPlanting", "discardSurplusDirectly", "discardSurplusWhenStorageFull", "glowstoneAfterStructure"])

    def test_support_claim_requires_matching_profile_and_both_installed_jar_hashes(self) -> None:
        for integrated in (True, False):
            with self.subTest(integrated=integrated):
                self.write_installation(self.installation(integrated))
                manifest, inputs = capture.build_manifest(self.state_path, self.game)
                capabilities = manifest["capabilities"]
                self.assertEqual(manifest["schema_version"], 1)
                self.assertIs(capabilities["temporary_support_execution_available"], integrated)
                self.assertIn(self.installation_path.resolve(), inputs)
                evidence = capabilities["temporary_support_execution_evidence"]
                self.assertEqual(evidence["status"], "available" if integrated else "unavailable")
                self.assertEqual(evidence["basis"], "hash_matched_installation_report")
                self.assertEqual(evidence["live_verification"], "not_assessed_by_manifest")
                self.assertNotIn("live_upper_support_verified", json.dumps(manifest))
        for changed in ("game_directory", "update_hash", "mod_hash", "mod_name", "duplicate_mod"):
            report = self.installation()
            if changed == "game_directory":
                report["game_directory"] = str(self.root)
            elif changed == "update_hash":
                report["last_mod_update"]["sha256"] = "0" * 64
            elif changed == "mod_hash":
                report["mods"][0]["sha256"] = "0" * 64
            elif changed == "mod_name":
                report["mods"][0]["name"] = "different.jar"
            else:
                report["mods"].append(report["mods"][0].copy())
            self.write_installation(report)
            with self.subTest(changed=changed):
                capabilities = capture.build_manifest(self.state_path, self.game)[0]["capabilities"]
                self.assertIsNone(capabilities["temporary_support_execution_available"])
                self.assertEqual(capabilities["temporary_support_execution_evidence"]["status"], "unknown")

    def test_missing_invalid_or_non_boolean_evidence_is_unknown_without_blocking_capture(self) -> None:
        capabilities = capture.build_manifest(self.state_path, self.game)[0]["capabilities"]
        self.assertIsNone(capabilities["temporary_support_execution_available"])
        self.assertEqual(capabilities["temporary_support_execution_evidence"]["reason"], "installation_report_missing")
        for integrated in (None, "true", 1, [], {}):
            self.write_installation(self.installation(integrated))
            with self.subTest(integrated=integrated):
                capabilities = capture.build_manifest(self.state_path, self.game)[0]["capabilities"]
                self.assertIsNone(capabilities["temporary_support_execution_available"])
        for encoded in ("invalid JSON", "[]", "{}", "x" * (capture.MAX_JSON_BYTES + 1)):
            self.installation_path.write_text(encoded)
            capabilities = capture.build_manifest(self.state_path, self.game)[0]["capabilities"]
            self.assertIsNone(capabilities["temporary_support_execution_available"])
            self.assertEqual(capabilities["temporary_support_execution_evidence"]["reason"],
                             "installation_report_unreadable_or_invalid")

    def test_explicit_installation_report_and_input_overwrite_protection(self) -> None:
        report_path = self.root / "custom-evidence.json"
        report_path.write_text(json.dumps(self.installation()))
        manifest = capture.capture_manifest(self.state_path, self.game, self.output, report_path)
        self.assertIs(manifest["capabilities"]["temporary_support_execution_available"], True)
        before = report_path.read_bytes()
        with self.assertRaisesRegex(ValueError, "overwrite an input"):
            capture.capture_manifest(self.state_path, self.game, report_path, report_path)
        self.assertEqual(report_path.read_bytes(), before)
        with self.assertRaisesRegex(ValueError, "overwrite an input"):
            capture.capture_manifest(self.state_path, self.game, self.installation_path)
        self.assertFalse(self.installation_path.exists())

    def test_captures_explicit_planting_mode_and_rejects_non_boolean_setting(self) -> None:
        for mode in (True, False):
            self.settings_path.write_text(json.dumps(self.settings | {"deferPlanting": mode}))
            manifest = capture.capture_manifest(self.state_path, self.game, self.output)
            self.assertIs(manifest["supervisor_settings"]["values"]["deferPlanting"], mode)
            self.assertEqual(manifest["layer_policy"], "layers-v2-deferred-planting" if mode else "layers-v2")
            self.assertEqual(manifest["supervisor_settings"]["unsaved_fields"],
                             ["autoRepairHoes", "discardSurplusDirectly", "discardSurplusWhenStorageFull", "glowstoneAfterStructure"])
        self.settings_path.write_text(json.dumps(self.settings | {"deferPlanting": "true"}))
        with self.assertRaises(ValueError):
            capture.build_manifest(self.state_path, self.game)

    def test_structure_first_policy_keeps_planting_selection_and_validates_boolean(self) -> None:
        for deferred in (False, True):
            self.settings_path.write_text(json.dumps(self.settings | {
                "deferPlanting": deferred, "glowstoneAfterStructure": True}))
            manifest = capture.capture_manifest(self.state_path, self.game, self.output)
            self.assertEqual(manifest["layer_policy"], "layers-v2-structure-first"
                             + ("-deferred-planting" if deferred else ""))
            self.assertIs(manifest["supervisor_settings"]["values"]["glowstoneAfterStructure"], True)
        self.settings_path.write_text(json.dumps(self.settings | {"glowstoneAfterStructure": "true"}))
        with self.assertRaises(ValueError):
            capture.build_manifest(self.state_path, self.game)

    def test_relative_schematic_path_is_resolved_from_game_directory(self) -> None:
        self.state["placements"]["placements"][1]["schematic"] = "schematics/workshop.litematic"
        self.write_state()
        manifest = capture.capture_manifest(self.state_path, self.game, self.output)
        self.assertEqual(manifest["source"]["name"], self.source.name)
        self.assertEqual(manifest["source"]["sha256"], hashlib.sha256(self.source.read_bytes()).hexdigest())

    def test_explicit_hoe_repair_setting_is_preserved_and_requires_boolean(self) -> None:
        for enabled in (True, False):
            self.settings_path.write_text(json.dumps(self.settings | {"autoRepairHoes": enabled}))
            manifest = capture.capture_manifest(self.state_path, self.game, self.output)
            self.assertIs(manifest["supervisor_settings"]["values"]["autoRepairHoes"], enabled)
        for invalid in ("true", 1, None):
            self.settings_path.write_text(json.dumps(self.settings | {"autoRepairHoes": invalid}))
            with self.assertRaisesRegex(ValueError, "autoRepairHoes must be a boolean"):
                capture.build_manifest(self.state_path, self.game)

    def test_surplus_disposal_authorization_is_explicit_and_boolean(self) -> None:
        for setting in ("discardSurplusWhenStorageFull", "discardSurplusDirectly"):
            for enabled in (True, False):
                self.settings_path.write_text(json.dumps(self.settings | {setting: enabled}))
                manifest = capture.capture_manifest(self.state_path, self.game, self.output)
                self.assertIs(manifest["supervisor_settings"]["values"][setting], enabled)
            for invalid in ("true", 1, None):
                self.settings_path.write_text(json.dumps(self.settings | {setting: invalid}))
                with self.assertRaisesRegex(ValueError, setting + " must be a boolean"):
                    capture.build_manifest(self.state_path, self.game)

    def test_invalid_selection_disabled_and_invalid_transforms_do_not_create_output(self) -> None:
        valid = copy.deepcopy(self.state)
        for selected in (-1, 2, True, None):
            with self.subTest(selected=selected):
                self.state = copy.deepcopy(valid)
                self.state["placements"]["selected"] = selected
                self.write_state()
                with self.assertRaisesRegex(ValueError, "Selected placement index"):
                    capture.capture_manifest(self.state_path, self.game, self.output)
                self.assertFalse(self.output.parent.exists())
        for field, value, message in (("enabled", False, "disabled"),
                                      ("origin", [1, True, 3], "integer coordinates"),
                                      ("rotation", "INVALID", "rotation"),
                                      ("mirror", [], "mirror")):
            with self.subTest(field=field):
                self.state = copy.deepcopy(valid)
                self.state["placements"]["placements"][1][field] = value
                self.write_state()
                with self.assertRaisesRegex(ValueError, message):
                    capture.capture_manifest(self.state_path, self.game, self.output)
                self.assertFalse(self.output.parent.exists())
        for enabled in (False, True):
            self.state = copy.deepcopy(valid)
            regions = self.state["placements"]["placements"][1]["placements"]
            regions[0]["placement"]["enabled"] = enabled
            regions[1]["placement"]["enabled"] = enabled
            self.write_state()
            with self.assertRaisesRegex(ValueError, "exactly one enabled sub-region"):
                capture.capture_manifest(self.state_path, self.game, self.output)
            self.assertFalse(self.output.parent.exists())

    def test_missing_input_or_source_and_invalid_json_do_not_create_output(self) -> None:
        with self.assertRaises((OSError, ValueError)):
            capture.capture_manifest(self.root / "missing.json", self.game, self.output)
        with self.assertRaises((OSError, ValueError)):
            capture.capture_manifest(self.state_path, self.root / "missing-game", self.output)
        self.source.unlink()
        with self.assertRaisesRegex(ValueError, "Referenced schematic"):
            capture.capture_manifest(self.state_path, self.game, self.output)
        self.state_path.write_text("{invalid")
        with self.assertRaisesRegex(ValueError, "valid JSON"):
            capture.capture_manifest(self.state_path, self.game, self.output)
        self.assertFalse(self.output.parent.exists())

    def test_missing_duplicate_and_invalid_mods_are_rejected_before_output(self) -> None:
        removed = self.mod_paths["baritone"]
        contents = removed.read_bytes()
        removed.unlink()
        with self.assertRaisesRegex(ValueError, "all five"):
            capture.capture_manifest(self.state_path, self.game, self.output)
        removed.write_bytes(contents)
        duplicate = self.game / "mods" / "another-baritone.jar"
        self.write_mod(duplicate, "baritone")
        with self.assertRaisesRegex(ValueError, "Duplicate managed mod"):
            capture.capture_manifest(self.state_path, self.game, self.output)
        duplicate.write_bytes(b"not-a-jar")
        with self.assertRaisesRegex(ValueError, "invalid Fabric metadata"):
            capture.capture_manifest(self.state_path, self.game, self.output)
        self.assertFalse(self.output.parent.exists())

    def test_unsaved_settings_are_explicit_and_invalid_whitelisted_values_are_rejected(self) -> None:
        self.settings["minimumFood"] = "token-secret"
        self.settings_path.write_text(json.dumps(self.settings))
        with self.assertRaisesRegex(ValueError, "supported integer range"):
            capture.capture_manifest(self.state_path, self.game, self.output)
        self.assertFalse(self.output.parent.exists())
        self.settings_path.unlink()
        manifest = capture.capture_manifest(self.state_path, self.game, self.output)
        self.assertEqual(manifest["supervisor_settings"], {
            "saved_file_present": False, "values": {},
            "unsaved_fields": sorted(capture.SETTING_LIMITS.keys() | capture.BOOLEAN_SETTINGS)})
        self.assertFalse(self.settings_path.exists())

    def test_inputs_cannot_be_overwritten_and_atomic_failure_preserves_previous_output(self) -> None:
        for input_path in (self.source, self.state_path, self.settings_path, self.mod_paths["baritone"]):
            before = input_path.read_bytes()
            with self.subTest(input_path=input_path), self.assertRaisesRegex(ValueError, "overwrite an input"):
                capture.capture_manifest(self.state_path, self.game, input_path)
            self.assertEqual(input_path.read_bytes(), before)
        self.output.parent.mkdir()
        self.output.write_bytes(b"previous-manifest")
        with patch.object(capture.os, "replace", side_effect=OSError("replacement failed")):
            with self.assertRaisesRegex(OSError, "replacement failed"):
                capture.capture_manifest(self.state_path, self.game, self.output)
        self.assertEqual(self.output.read_bytes(), b"previous-manifest")
        self.assertEqual(list(self.output.parent.iterdir()), [self.output])


if __name__ == "__main__":
    unittest.main()

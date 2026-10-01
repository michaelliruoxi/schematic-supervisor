from __future__ import annotations

import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from supervisor_companion.ui_state import (
    UiState,
    candidate_paths,
    default_compact_position,
    is_visible,
    load_ui_state,
    save_ui_state,
)

OLDER, NEWER = 1_700_000_000, 1_700_000_100


def write_state(path: Path, text: str, modified: int) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")
    os.utime(path, (modified, modified))


class UiStateTests(unittest.TestCase):
    def test_round_trip(self):
        with tempfile.TemporaryDirectory() as temporary:
            paths = [Path(temporary) / "monitor-state.json"]
            state = UiState(mode="full", compact_position=(100, 40), full_geometry=(960, 720, 50, 60),
                            pinned=False, full_on_top=True, sound=True)
            self.assertEqual(save_ui_state(paths, state), paths[0])
            self.assertEqual(load_ui_state(paths), state)

    def test_missing_or_invalid_files_give_defaults(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "monitor-state.json"
            self.assertEqual(load_ui_state([path]), UiState())
            path.write_text("{not json", encoding="utf-8")
            self.assertEqual(load_ui_state([path]), UiState())

    def test_wrong_types_fall_back_field_by_field(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "monitor-state.json"
            path.write_text(json.dumps({"mode": "huge", "compact_position": [1, "2"],
                                        "full_geometry": [100, 100, 0, 0], "pinned": "yes",
                                        "sound": True}), encoding="utf-8")
            self.assertEqual(load_ui_state([path]), UiState(sound=True))

    def test_saving_falls_back_when_the_first_folder_is_unusable(self):
        with tempfile.TemporaryDirectory() as temporary:
            blocked = Path(temporary) / "blocked"
            blocked.write_text("a file where a folder should be", encoding="utf-8")
            paths = [blocked / "monitor-state.json", Path(temporary) / "fallback" / "monitor-state.json"]
            self.assertEqual(save_ui_state(paths, UiState(sound=True)), paths[1])
            self.assertTrue(load_ui_state(paths).sound)

    def test_visibility_and_default_position(self):
        screens = [(0, 0, 1920, 1040), (1920, 0, 3840, 1040)]
        self.assertTrue(is_visible((1900, 100), (320, 210), screens))
        self.assertFalse(is_visible((5000, 100), (320, 210), screens))
        self.assertFalse(is_visible((1900, 1020), (320, 210), screens))
        self.assertEqual(default_compact_position((0, 0, 1920, 1040), (320, 210)), (1584, 16))

    def test_candidate_paths_try_the_application_folder_then_the_user_folder(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with patch.dict(os.environ, {"LOCALAPPDATA": str(root / "local")}):
                paths = candidate_paths(root / "app")
            self.assertEqual(paths, [root / "app" / "monitor-state.json",
                                     root / "local" / "SchematicSupervisor" / "monitor-state.json"])

    def test_a_deeply_nested_file_gives_defaults(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "monitor-state.json"
            path.write_text("[" * 100_000, encoding="utf-8")
            self.assertEqual(load_ui_state([path]), UiState())

    def test_the_newest_state_file_loads_whichever_folder_holds_it(self):
        with tempfile.TemporaryDirectory() as temporary:
            paths = [Path(temporary) / "app" / "monitor-state.json",
                     Path(temporary) / "local" / "monitor-state.json"]
            for first_time, second_time, expected in ((OLDER, NEWER, UiState(sound=True)),
                                                      (NEWER, OLDER, UiState(mode="full"))):
                with self.subTest(first=first_time, second=second_time):
                    write_state(paths[0], json.dumps({"mode": "full"}), first_time)
                    write_state(paths[1], json.dumps({"sound": True}), second_time)
                    self.assertEqual(load_ui_state(paths), expected)

    def test_files_saved_at_the_same_time_keep_the_candidate_order(self):
        with tempfile.TemporaryDirectory() as temporary:
            paths = [Path(temporary) / "app" / "monitor-state.json",
                     Path(temporary) / "local" / "monitor-state.json"]
            write_state(paths[0], json.dumps({"mode": "full"}), OLDER)
            write_state(paths[1], json.dumps({"sound": True}), OLDER)
            self.assertEqual(load_ui_state(paths), UiState(mode="full"))

    def test_a_newer_file_that_does_not_parse_is_skipped(self):
        with tempfile.TemporaryDirectory() as temporary:
            paths = [Path(temporary) / "app" / "monitor-state.json",
                     Path(temporary) / "local" / "monitor-state.json"]
            write_state(paths[0], json.dumps({"mode": "full"}), OLDER)
            write_state(paths[1], "{not json", NEWER)
            self.assertEqual(load_ui_state(paths), UiState(mode="full"))

    def test_a_file_saved_with_a_byte_order_mark_loads(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "monitor-state.json"
            path.write_text(json.dumps({"mode": "full", "sound": True}), encoding="utf-8-sig")
            self.assertEqual(path.read_bytes()[:3], b"\xef\xbb\xbf")
            self.assertEqual(load_ui_state([path]), UiState(mode="full", sound=True))


if __name__ == "__main__":
    unittest.main()

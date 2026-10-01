from __future__ import annotations

import contextlib
from datetime import datetime, timezone
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import MagicMock, patch

from supervisor_companion.desktop import _show_error, discover_token_file, main
from http_fixture import RecordingEndpoint


class DesktopTests(unittest.TestCase):
    def test_discovery_finds_profile_above_distribution_folder(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            binary = root / "companion/dist"
            binary.mkdir(parents=True)
            token = root / "runtime/game/config/schematic-supervisor/protocol-token.txt"
            token.parent.mkdir(parents=True)
            token.write_text("local-test-token", encoding="ascii")
            self.assertEqual(discover_token_file(binary), token)

    def test_a_downloaded_monitor_finds_the_default_launcher_profile(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            downloads = root / "Downloads"
            downloads.mkdir()
            token = root / "AppData/.minecraft/config/schematic-supervisor/protocol-token.txt"
            token.parent.mkdir(parents=True)
            token.write_text("local-test-token", encoding="ascii")
            with patch.dict("os.environ", {"APPDATA": str(root / "AppData")}):
                self.assertEqual(discover_token_file(downloads), token)
                # A dedicated profile beside the monitor still wins.
                dedicated = downloads / "runtime/game/config/schematic-supervisor/protocol-token.txt"
                dedicated.parent.mkdir(parents=True)
                dedicated.write_text("dedicated", encoding="ascii")
                self.assertEqual(discover_token_file(downloads), dedicated)
            with patch.dict("os.environ", {}, clear=True):
                self.assertEqual(discover_token_file(root / "Downloads"), dedicated)

    def test_missing_pairing_tracks_expected_profile_for_later_launch(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            binary = root / "companion/dist"
            binary.mkdir(parents=True)
            (root / "runtime/game").mkdir(parents=True)
            self.assertEqual(discover_token_file(binary), root / "runtime/game/config/schematic-supervisor/protocol-token.txt")

    def test_diagnostic_observes_without_any_controls_or_gui(self):
        observation = {
            "protocol_version": 1, "run_id": "test-run", "state": "PAUSED",
            "ready": True, "allowed_actions": ["RESUME", "PAUSE", "STOP"],
            "last_control": {"sequence": 4},
            "updated_at": datetime.now(timezone.utc).isoformat(),
        }
        with tempfile.TemporaryDirectory() as temporary, RecordingEndpoint(lambda _: (200, observation)) as endpoint:
            output = Path(temporary) / "connection.json"
            with patch("supervisor_companion.desktop.discover_token_file", return_value=Path(temporary) / "missing.txt"):
                result = main(["--mod-url", endpoint.base_url, "--check-connection", "--output", str(output)])
            self.assertEqual(result, 0)
            body = json.loads(output.read_text(encoding="utf-8"))
            self.assertTrue(body["fresh"])
            self.assertEqual(body["observation"]["state"], "PAUSED")
            self.assertEqual([(r["method"], r["path"]) for r in endpoint.requests],
                             [("GET", "/v1/observation"), ("GET", "/v1/progress")])
            self.assertIn("progress", body)

    def test_diagnostic_rejects_remote_endpoint_without_contacting_it(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "error.json"
            result = main(["--mod-url", "https://example.com", "--check-connection", "--output", str(output)])
            self.assertEqual(result, 2)
            self.assertFalse(json.loads(output.read_text())["ok"])

    def test_check_mode_reports_a_bad_setting_once(self):
        errors = io.StringIO()
        with contextlib.redirect_stderr(errors):
            result = main(["--check-config", "--mod-url", "https://example.com"])
        self.assertEqual(result, 2)
        self.assertEqual(errors.getvalue().count("Could not open the monitor"), 1, errors.getvalue())

    # The tests below replace the Windows integrations, the error dialog, and logging: no real mutex, no Tk.

    def test_check_modes_never_take_the_monitor_lock(self):
        observation = {
            "protocol_version": 1, "run_id": "test-run", "state": "PAUSED",
            "ready": True, "allowed_actions": ["RESUME", "PAUSE", "STOP"],
            "last_control": {"sequence": 4},
            "updated_at": datetime.now(timezone.utc).isoformat(),
        }
        with tempfile.TemporaryDirectory() as temporary, RecordingEndpoint(lambda _: (200, observation)) as endpoint, \
                patch("supervisor_companion.desktop.win32") as win32:
            missing = str(Path(temporary) / "missing.txt")
            checked = main(["--check-config", "--token-file", missing,
                            "--output", str(Path(temporary) / "summary.json")])
            connected = main(["--check-connection", "--mod-url", endpoint.base_url, "--token-file", missing,
                              "--output", str(Path(temporary) / "connection.json")])
        self.assertEqual((checked, connected), (0, 0))
        win32.SingleInstance.assert_not_called()
        win32.enable_dpi_awareness.assert_not_called()

    def test_a_second_monitor_reports_already_open_without_logging(self):
        order = []
        with tempfile.TemporaryDirectory() as temporary, \
                patch("supervisor_companion.desktop.win32") as win32, \
                patch("supervisor_companion.desktop._show_error",
                      side_effect=lambda message, gui: order.append(("dialog", message, gui))), \
                patch("supervisor_companion.desktop.configure_monitor_logging") as configure_logging:
            instance = win32.SingleInstance.return_value
            instance.acquired = False
            instance.release.side_effect = lambda: order.append("release")
            result = main(["--token-file", str(Path(temporary) / "missing.txt")])
        self.assertEqual(result, 0)
        # The lock is let go before the modal dialog; the second release during cleanup does nothing.
        self.assertEqual(order[:2], ["release", ("dialog", "The monitor is already open.", True)])
        configure_logging.assert_not_called()

    def test_the_monitor_lock_is_released_when_a_later_startup_step_fails(self):
        with tempfile.TemporaryDirectory() as temporary, \
                patch("supervisor_companion.desktop.win32") as win32, \
                patch("supervisor_companion.desktop._show_error") as show_error, \
                patch("supervisor_companion.desktop.configure_monitor_logging", side_effect=OSError("disk full")):
            win32.SingleInstance.return_value.acquired = True
            result = main(["--token-file", str(Path(temporary) / "missing.txt")])
        self.assertEqual(result, 2)
        win32.SingleInstance.return_value.release.assert_called_once_with()
        show_error.assert_called_once_with("Could not open the monitor: disk full", gui=True)

    def test_only_the_error_dialog_turns_on_dpi_awareness_before_creating_tk(self):
        order = []
        errors = io.StringIO()
        with patch("supervisor_companion.desktop.win32") as win32, patch("tkinter.Tk") as tk_root, \
                patch("tkinter.messagebox.showerror") as dialog, contextlib.redirect_stderr(errors):
            win32.enable_dpi_awareness.side_effect = lambda: order.append("dpi")
            tk_root.side_effect = lambda: order.append("tk") or MagicMock()
            _show_error("Printed only.", gui=False)
            self.assertEqual(order, [])
            _show_error("Shown in a dialog.", gui=True)
        self.assertEqual(order, ["dpi", "tk"])
        dialog.assert_called_once()
        self.assertEqual(errors.getvalue().splitlines(), ["Printed only.", "Shown in a dialog."])


if __name__ == "__main__":
    unittest.main()

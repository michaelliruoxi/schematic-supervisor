from __future__ import annotations

from pathlib import Path
import tempfile
from tkinter import TclError
import unittest
from unittest.mock import Mock, call, patch

from log_capture import capture
from supervisor_companion.alerts import Alert
from supervisor_companion.monitor_ui import MonitorApp
from supervisor_companion.ui_state import UiState, load_ui_state, save_ui_state
from test_view_model import snapshot

SCREEN = (0, 0, 1920, 1040)


class MonitorAppTestCase(unittest.TestCase):
    def setUp(self):
        self.logger, self.records = capture("test.monitor_ui")

    def open_app(self, state: UiState, service: Mock | None = None,
                 notifier: Mock | None = None) -> tuple[MonitorApp, Mock, Path]:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        path = Path(temporary.name) / "monitor-state.json"
        save_ui_state([path], state)
        root = Mock()
        root.winfo_fpixels.return_value = 96.0
        win32 = Mock(**{"apps_use_light_theme.return_value": True, "screen_work_areas.return_value": [SCREEN],
                        "primary_work_area.return_value": SCREEN})
        # The window shell is real; widgets, styles, and the refresh loop are stand-ins, so no window opens.
        # They stay in place for the whole test, so layout switches don't read the real screens either.
        stand_ins = patch.multiple("supervisor_companion.monitor_ui", tk=Mock(TclError=TclError), CardFrame=Mock(),
                                   FullFrame=Mock(), apply_styles=Mock(), SafeTicker=Mock(), win32=win32)
        stand_ins.start()
        self.addCleanup(stand_ins.stop)
        app = MonitorApp(root, service or Mock(), token_file=None, state_paths=[path], logger=self.logger,
                         log_directory=Path(temporary.name), notifier=notifier)
        return app, root, path


class SessionEndTests(MonitorAppTestCase):
    """Windows ending the session sends WM_SAVE_YOURSELF and may never close the window first."""

    @staticmethod
    def end_session(root: Mock, path: Path) -> None:
        """Run the handler the window registered, as Tk does for WM_SAVE_YOURSELF."""
        # Without the startup file, whatever is read back afterwards was written by this handler.
        path.unlink()
        handlers = {registered.args[0]: registered.args[1] for registered in root.protocol.call_args_list}
        handlers["WM_SAVE_YOURSELF"]()

    def test_a_dragged_card_position_is_saved_and_the_window_stays_open(self):
        app, root, path = self.open_app(UiState(mode="compact", compact_position=(1584, 16), sound=True))
        root.state.return_value = "normal"
        root.geometry.return_value = "320x210+1500+40"
        self.end_session(root, path)
        self.assertEqual(load_ui_state([path]), UiState(mode="compact", compact_position=(1500, 40), sound=True))
        root.destroy.assert_not_called()
        app.ticker.stop.assert_not_called()
        # Closing still works afterwards if the session end is cancelled.
        self.assertIn(call("WM_DELETE_WINDOW", app.close), root.protocol.call_args_list)

    def test_a_maximized_window_stays_maximized_and_keeps_its_last_normal_size(self):
        _app, root, path = self.open_app(UiState(mode="full", full_geometry=(960, 720, 100, 80)))
        root.state.return_value = "zoomed"
        root.geometry.return_value = "1920x1009+0+0"
        self.end_session(root, path)
        self.assertEqual(load_ui_state([path]), UiState(mode="full", full_geometry=(960, 720, 100, 80)))
        self.assertNotIn(call("normal"), root.state.call_args_list)
        root.destroy.assert_not_called()

    def test_the_settings_are_saved_when_the_placement_cannot_be_read(self):
        _app, root, path = self.open_app(UiState(compact_position=(1584, 16), pinned=False))
        root.state.return_value = "normal"
        root.geometry.side_effect = TclError("the window is going away")
        self.end_session(root, path)
        self.assertEqual(load_ui_state([path]), UiState(compact_position=(1584, 16), pinned=False))
        self.assertTrue(any("placement" in record.getMessage() for record in self.records))


class StagePreviewTests(MonitorAppTestCase):
    """The full window's timeline reports the stage under the pointer, and None when the pointer leaves."""

    CURRENT = "Stage 2 of 3 · Lights · Y -62 · 50%"
    FIRST = "Stage 1 of 3 · Structure · Y -63 · 100%"
    LAST = "Stage 3 of 3 · Till · Y -63 · 0%"

    def open_full(self) -> tuple[MonitorApp, Mock]:
        app, root, _path = self.open_app(UiState(mode="full"), Mock(**{"snapshot.return_value": snapshot()}))
        return app, root

    @staticmethod
    def shown(app: MonitorApp):
        return app.full.render.call_args.args[0]

    def test_the_map_shows_the_hovered_stage_and_then_the_current_one_again(self):
        app, _root = self.open_full()
        app.preview_stage(2)
        self.assertEqual(self.shown(app).stage_caption, self.LAST)
        self.assertTrue(self.shown(app).following_current)
        renders = app.full.render.call_count
        app.preview_stage(2)
        self.assertEqual(app.full.render.call_count, renders, "each pointer move over the same stage redrew")
        app.preview_stage(None)
        self.assertEqual(self.shown(app).stage_caption, self.CURRENT)

    def test_leaving_the_timeline_returns_to_the_clicked_stage(self):
        app, _root = self.open_full()
        app.preview_stage(0)
        app.select_stage(0)
        app.preview_stage(2)
        self.assertEqual((self.shown(app).stage_caption, self.shown(app).selected_stage), (self.LAST, 0))
        app.preview_stage(None)
        self.assertEqual(self.shown(app).stage_caption, self.FIRST)
        self.assertFalse(self.shown(app).following_current)

    def test_a_preview_ends_with_the_layout(self):
        app, root = self.open_full()
        root.geometry.return_value = "960x720+100+80"
        app.preview_stage(2)
        app.compact()
        app.expand()
        self.assertEqual(self.shown(app).stage_caption, self.CURRENT)



class PhoneNotificationTests(MonitorAppTestCase):
    """An alert also goes to the phone when notifications are configured."""

    def test_a_pause_sends_its_cause_and_hint(self):
        notifier = Mock(enabled=True, **{"notify.return_value": True})
        service = Mock()
        app, _root, _path = self.open_app(UiState(mode="compact"), service, notifier)
        app._observation = {"last_error": "Manual movement input interrupted flight construction"}
        app._alert(Alert("paused", "The build paused."))
        title, body = notifier.notify.call_args.args
        self.assertEqual(title, "Schematic Supervisor: The build paused.")
        self.assertIn("Manual movement input interrupted flight construction", body)
        self.assertIn("Hint: A movement key was pressed during flight.", body)
        service.note_event.assert_any_call("Phone notification sent")

    def test_without_a_notifier_alerts_stay_on_this_computer(self):
        service = Mock()
        app, _root, _path = self.open_app(UiState(mode="compact"), service)
        app._alert(Alert("paused", "The build paused."))
        self.assertNotIn(call("Phone notification sent"), service.note_event.call_args_list)

if __name__ == "__main__":
    unittest.main()

from __future__ import annotations

import unittest
from unittest.mock import patch
import uuid

from supervisor_companion import win32


@unittest.skipUnless(win32.IS_WINDOWS, "Windows only")
class WindowsIntegrationTests(unittest.TestCase):
    def test_single_instance_detects_a_second_copy(self):
        name = f"Local\\SchematicSupervisorTest-{uuid.uuid4().hex}"
        first = win32.SingleInstance(name)
        self.addCleanup(first.release)
        second = win32.SingleInstance(name)
        self.addCleanup(second.release)
        self.assertTrue(first.acquired)
        self.assertFalse(second.acquired)

    def test_calls_without_a_window_are_harmless(self):
        win32.flash_window(0)
        win32.use_dark_title_bar(0)
        self.assertEqual(len(win32.primary_work_area()), 4)

    def test_a_failed_beep_is_ignored(self):
        import winsound

        # winsound raises RuntimeError when the system cannot play the sound (no audio device).
        with patch("winsound.MessageBeep", side_effect=RuntimeError("Failed to play sound")) as message_beep:
            win32.beep()
        message_beep.assert_called_once_with(winsound.MB_ICONEXCLAMATION)

    def test_screen_work_areas_include_the_primary_work_area(self):
        self.assertIn(win32.primary_work_area(), win32.screen_work_areas())

    def test_theme_follows_the_registry_value(self):
        self.assertFalse(win32.apps_use_light_theme(lambda: 0))
        self.assertTrue(win32.apps_use_light_theme(lambda: 1))

        def missing():
            raise OSError("key not found")

        self.assertTrue(win32.apps_use_light_theme(missing))


if __name__ == "__main__":
    unittest.main()

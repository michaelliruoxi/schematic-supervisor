from __future__ import annotations

import unittest

from supervisor_companion.ui_theme import DARK, LIGHT, share_color, status_color, tone_colors


class ThemeTests(unittest.TestCase):
    def test_share_color_blends_between_the_two_ends(self):
        self.assertEqual(share_color(LIGHT, 0.0), LIGHT.share_low)
        self.assertEqual(share_color(LIGHT, 1.0), LIGHT.share_high)
        self.assertEqual(share_color(LIGHT, 2.0), LIGHT.share_high)
        self.assertNotIn(share_color(DARK, 0.5), (DARK.share_low, DARK.share_high))

    def test_unknown_tones_and_statuses_fall_back_to_neutral(self):
        self.assertEqual(tone_colors(DARK, "offline"), (DARK.idle, DARK.idle_bg))
        self.assertEqual(tone_colors(LIGHT, "ok"), (LIGHT.ok, LIGHT.ok_bg))
        self.assertEqual(status_color(DARK, "todo"), DARK.segment_todo)
        self.assertEqual(status_color(DARK, "mystery"), DARK.segment_todo)
        self.assertEqual(status_color(LIGHT, "partial"), LIGHT.segment_partial)


if __name__ == "__main__":
    unittest.main()

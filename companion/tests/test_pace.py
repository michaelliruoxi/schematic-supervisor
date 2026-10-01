from __future__ import annotations

import unittest

from supervisor_companion.pace import PaceTracker, format_remaining

IDENTITY = ("sha256:plan", "layers-v1")


class PaceTests(unittest.TestCase):
    def test_rate_appears_after_three_active_minutes(self):
        tracker = PaceTracker()
        for second in range(0, 180):
            tracker.observe(float(second), "BUILDING", IDENTITY, second * 2)
        self.assertIsNone(tracker.pace(1000))
        tracker.observe(180.0, "BUILDING", IDENTITY, 360)
        pace = tracker.pace(1200)
        self.assertAlmostEqual(pace.per_minute, 120.0)
        self.assertAlmostEqual(pace.remaining_seconds, 600.0)

    def test_rate_uses_only_the_last_ten_active_minutes(self):
        tracker = PaceTracker()
        done = 0
        for second in range(0, 1201):
            if second > 0:
                done += 1 if second <= 600 else 2
            tracker.observe(float(second), "BUILDING", IDENTITY, done)
        self.assertAlmostEqual(tracker.pace(None).per_minute, 120.0)

    def test_paused_time_does_not_slow_the_rate(self):
        tracker = PaceTracker()
        for second in range(0, 91):
            tracker.observe(float(second), "BUILDING", IDENTITY, second)
        for second in range(91, 3600):
            tracker.observe(float(second), "PAUSED", IDENTITY, 90)
        for second in range(3600, 3691):
            tracker.observe(float(second), "BUILDING", IDENTITY, 90 + (second - 3600))
        self.assertAlmostEqual(tracker.pace(None).per_minute, 60.0)

    def test_pieces_a_build_check_marks_done_are_not_build_speed(self):
        tracker = PaceTracker(minimum_seconds=10)
        for second in range(11):
            tracker.observe(float(second), "BUILDING", IDENTITY, second)
        self.assertAlmostEqual(tracker.pace(None).per_minute, 60.0)
        # Stop, then Start: the check finds 5,000 more actions already built.
        tracker.observe(11.0, "STOPPED", IDENTITY, 10)
        tracker.observe(12.0, "CHECKING", IDENTITY, 10)
        self.assertIsNone(tracker.pace(None))
        for second in range(13, 24):
            tracker.observe(float(second), "BUILDING", IDENTITY, 5_000 + second)
        self.assertAlmostEqual(tracker.pace(None).per_minute, 60.0)

    def test_reconnecting_does_not_count_unobserved_work_as_instant_progress(self):
        tracker = PaceTracker()
        for second in range(181):
            tracker.observe(float(second), "BUILDING", IDENTITY, second)
        self.assertAlmostEqual(tracker.pace(600).per_minute, 60.0)

        # The mod keeps building for an hour while the monitor cannot read it.
        tracker.observe(181.0, None, IDENTITY, 180)
        self.assertIsNone(tracker.pace(600))
        tracker.observe(3780.0, "BUILDING", IDENTITY, 3780)
        self.assertIsNone(tracker.pace(600))
        for second in range(3781, 3961):
            tracker.observe(float(second), "BUILDING", IDENTITY, second)
        pace = tracker.pace(600)
        self.assertAlmostEqual(pace.per_minute, 60.0)
        self.assertAlmostEqual(pace.remaining_seconds, 600.0)

    def test_missing_progress_count_discards_the_previous_rate(self):
        tracker = PaceTracker(minimum_seconds=10)
        for second in range(11):
            tracker.observe(float(second), "BUILDING", IDENTITY, second)
        self.assertIsNotNone(tracker.pace(100))
        tracker.observe(11.0, "BUILDING", IDENTITY, None)
        self.assertIsNone(tracker.pace(100))
        tracker.observe(100.0, "BUILDING", IDENTITY, 100)
        self.assertIsNone(tracker.pace(100))

    def test_new_plan_or_lower_count_starts_over(self):
        tracker = PaceTracker(minimum_seconds=10)
        for second in range(0, 11):
            tracker.observe(float(second), "BUILDING", IDENTITY, second)
        self.assertIsNotNone(tracker.pace(None))
        other = ("sha256:other", "layers-v1")
        tracker.observe(11.0, "BUILDING", other, 11)
        self.assertIsNone(tracker.pace(None))
        for second in range(12, 23):
            tracker.observe(float(second), "BUILDING", other, second)
        self.assertIsNotNone(tracker.pace(None))
        tracker.observe(23.0, "BUILDING", other, 0)
        self.assertIsNone(tracker.pace(None))

    def test_no_progress_gives_zero_rate_and_no_time_remaining(self):
        tracker = PaceTracker(minimum_seconds=10)
        for second in range(0, 11):
            tracker.observe(float(second), "BUILDING", IDENTITY, 50)
        pace = tracker.pace(100)
        self.assertEqual(pace.per_minute, 0.0)
        self.assertIsNone(pace.remaining_seconds)

    def test_remaining_time_is_formatted_for_people(self):
        self.assertEqual(format_remaining(20), "under a minute")
        self.assertEqual(format_remaining(45 * 60), "45 m")
        self.assertEqual(format_remaining(6 * 3600 + 20 * 60), "6 h 20 m")
        self.assertEqual(format_remaining(2 * 86400 + 3 * 3600), "2 d 3 h")


if __name__ == "__main__":
    unittest.main()

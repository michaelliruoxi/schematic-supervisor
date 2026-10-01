from __future__ import annotations

import unittest

from log_capture import capture
from supervisor_companion.ticker import SafeTicker


class SafeTickerTests(unittest.TestCase):
    def test_failing_render_is_logged_flagged_and_still_rescheduled(self):
        logger, records = capture("test.ticker")
        scheduled = []
        changes = []
        outcomes = [RuntimeError("bad data"), RuntimeError("bad data"), None]

        def render():
            outcome = outcomes.pop(0)
            if outcome is not None:
                raise outcome

        def schedule(delay, callback):
            scheduled.append((delay, callback))
            return len(scheduled)

        ticker = SafeTicker(render, schedule, interval_ms=250, logger=logger, on_failure_change=changes.append)
        ticker.tick()
        self.assertTrue(ticker.failed)
        ticker.tick()
        ticker.tick()
        self.assertEqual([delay for delay, _ in scheduled], [250, 250, 250])
        self.assertEqual(changes, [True, False])
        self.assertEqual(len(records), 1, "one failure site is logged once per minute")
        self.assertFalse(ticker.failed)

    def test_stop_cancels_the_pending_tick_and_ends_the_loop(self):
        logger, _ = capture("test.ticker.stop")
        cancelled = []
        scheduled = []
        ticker = SafeTicker(lambda: None, lambda delay, callback: scheduled.append(callback) or "handle-1",
                            interval_ms=250, logger=logger, cancel=cancelled.append)
        ticker.tick()
        ticker.stop()
        ticker.tick()
        self.assertEqual(cancelled, ["handle-1"])
        self.assertEqual(len(scheduled), 1)


if __name__ == "__main__":
    unittest.main()

from __future__ import annotations

import unittest

from supervisor_companion.alerts import AttentionTracker


class AttentionTrackerTests(unittest.TestCase):
    def setUp(self):
        self.now = [0.0]
        self.tracker = AttentionTracker(clock=lambda: self.now[0])

    def update(self, *, online=True, state="BUILDING", action=None, request=None, own=()):
        return self.tracker.update(online=online, state=state, last_action=action, last_request_id=request,
                                   own_request_ids=own)

    def test_first_snapshot_never_alerts(self):
        self.assertIsNone(self.update(state="PAUSED"))

    def test_entering_paused_error_or_done_alerts_once(self):
        self.update()
        self.assertEqual(self.update(state="PAUSED").kind, "paused")
        self.assertIsNone(self.update(state="PAUSED"))
        self.assertEqual(self.update(state="ERROR").kind, "error")
        self.assertEqual(self.update(state="DONE").kind, "done")

    def test_own_pause_does_not_alert(self):
        self.update()
        self.assertIsNone(self.update(state="PAUSED", action="PAUSE", request="monitor-1", own=["monitor-1"]))

    def test_pause_after_own_resume_alerts(self):
        self.update(state="PAUSED")
        self.assertIsNone(self.update(action="RESUME", request="monitor-2", own=["monitor-2"]))
        alert = self.update(state="PAUSED", action="RESUME", request="monitor-2", own=["monitor-2"])
        self.assertEqual(alert.kind, "paused")

    def test_pause_from_elsewhere_alerts_even_after_own_requests(self):
        self.update()
        self.assertEqual(self.update(state="PAUSED", action="PAUSE", request=None, own=["monitor-1"]).kind, "paused")

    def test_stuck_does_not_alert(self):
        self.update()
        self.assertIsNone(self.update(state="STUCK"))

    def test_connection_loss_during_work_alerts_once_after_fifteen_seconds(self):
        self.update()
        self.assertIsNone(self.update(online=False, state=None))
        self.now[0] = 14.9
        self.assertIsNone(self.update(online=False, state=None))
        self.now[0] = 15.0
        self.assertEqual(self.update(online=False, state=None).kind, "connection")
        self.now[0] = 60.0
        self.assertIsNone(self.update(online=False, state=None))
        self.assertIsNone(self.update(state="BUILDING"))

    def test_connection_loss_during_the_start_build_check_alerts(self):
        self.update(state="CHECKING")
        self.update(online=False, state=None)
        self.now[0] = 15.0
        self.assertEqual(self.update(online=False, state=None).kind, "connection")

    def test_connection_loss_while_idle_does_not_alert(self):
        self.update(state="IDLE")
        self.update(online=False, state=None)
        self.now[0] = 60.0
        self.assertIsNone(self.update(online=False, state=None))

    def test_starting_offline_then_connecting_sets_the_baseline_quietly(self):
        self.assertIsNone(self.update(online=False, state=None))
        self.now[0] = 30.0
        self.assertIsNone(self.update(online=False, state=None))
        self.assertIsNone(self.update(state="PAUSED"))


if __name__ == "__main__":
    unittest.main()

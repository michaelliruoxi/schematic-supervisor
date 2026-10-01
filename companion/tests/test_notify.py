from __future__ import annotations

import unittest
from unittest.mock import Mock

from log_capture import capture
from supervisor_companion.alerts import Alert
from supervisor_companion.config import AppConfig, ConfigurationError, NotifyConfig
from supervisor_companion.http_json import HttpJsonError
from supervisor_companion.notify import MAX_BODY_CHARACTERS, PhoneNotifier, alert_text

NTFY = "https://ntfy.sh/a-long-private-topic"
DISCORD = "https://discord.com/api/webhooks/123/abc"


class NotifyConfigTests(unittest.TestCase):
    def test_notifications_are_off_by_default(self):
        self.assertFalse(AppConfig.from_mapping({}).notify.enabled)

    def test_https_addresses_are_accepted_and_hidden_from_the_summary(self):
        config = AppConfig.from_mapping({"notify": {"ntfy_url": NTFY, "discord_webhook_url": DISCORD}})
        self.assertTrue(config.notify.enabled)
        self.assertEqual(config.notify.ntfy_url, NTFY)
        summary = config.safe_summary()["notify"]
        self.assertEqual(summary["ntfy_url"], "(set)")
        self.assertEqual(summary["discord_webhook_url"], "(set)")
        self.assertEqual(AppConfig.from_mapping({}).safe_summary()["notify"]["ntfy_url"], "")

    def test_plain_http_off_this_computer_queries_and_unknown_keys_are_rejected(self):
        with self.assertRaisesRegex(ConfigurationError, "HTTPS"):
            NotifyConfig.from_mapping({"ntfy_url": "http://ntfy.sh/topic"})
        with self.assertRaisesRegex(ConfigurationError, "query"):
            NotifyConfig.from_mapping({"discord_webhook_url": DISCORD + "?wait=true"})
        with self.assertRaisesRegex(ConfigurationError, "unknown keys"):
            NotifyConfig.from_mapping({"pushover": "x"})
        with self.assertRaises(ConfigurationError):
            NotifyConfig.from_mapping({"ntfy_url": NTFY, "minimum_interval_seconds": -1})
        # A self-hosted ntfy on this computer may use plain HTTP.
        self.assertEqual(NotifyConfig.from_mapping({"ntfy_url": "http://127.0.0.1:8080/topic"}).ntfy_url,
                         "http://127.0.0.1:8080/topic")


class AlertTextTests(unittest.TestCase):
    def test_a_pause_carries_its_cause_and_hint(self):
        title, body = alert_text(Alert("paused", "The build paused."), {
            "last_error": "Registered depots cannot satisfy the exact material shortage:\n {wheat_seeds=64}"})
        self.assertEqual(title, "Schematic Supervisor: The build paused.")
        self.assertEqual(body.splitlines(), [
            "The build paused.",
            "Registered depots cannot satisfy the exact material shortage: {wheat_seeds=64}",
            "Hint: Put the missing items in a registered chest, then press Scan depots.",
        ])

    def test_other_alerts_keep_only_their_message(self):
        for alert in (Alert("done", "The build is finished."),
                      Alert("connection", "Lost contact with Minecraft during the build.")):
            title, body = alert_text(alert, {"last_error": "an older error"})
            self.assertEqual(body, alert.message)
            self.assertTrue(title.endswith(alert.message))

    def test_long_errors_are_cut(self):
        _title, body = alert_text(Alert("paused", "The build paused."), {"last_error": "x" * 5_000})
        self.assertEqual(len(body), MAX_BODY_CHARACTERS)
        self.assertTrue(body.endswith("…"))


class PhoneNotifierTests(unittest.TestCase):
    def setUp(self):
        self.logger, self.records = capture("test.notify")
        self.now = 1_000.0
        self.text = Mock(return_value=200)
        self.json = Mock()

    def notifier(self, **settings) -> PhoneNotifier:
        config = NotifyConfig(**settings)
        # Delivery runs inline here; the monitor runs it on a daemon thread.
        return PhoneNotifier(config, logger=self.logger, clock=lambda: self.now, start=lambda work: work(),
                             send_text=self.text, send_json=self.json)

    def test_ntfy_and_discord_receive_the_same_alert(self):
        notifier = self.notifier(ntfy_url=NTFY, discord_webhook_url=DISCORD)
        self.assertTrue(notifier.notify("Schematic Supervisor: The build paused.", "The build paused.\nWhy"))
        self.text.assert_called_once_with(NTFY, "The build paused.\nWhy", timeout_seconds=5.0, headers={
            "Title": "Schematic Supervisor: The build paused.", "Priority": "high", "Tags": "construction"})
        self.json.assert_called_once_with(DISCORD, {
            "content": "**Schematic Supervisor: The build paused.**\nThe build paused.\nWhy"}, timeout_seconds=5.0)

    def test_alerts_within_the_minimum_interval_are_skipped(self):
        notifier = self.notifier(ntfy_url=NTFY, minimum_interval_seconds=60)
        self.assertTrue(notifier.notify("t", "first"))
        self.now += 59
        self.assertFalse(notifier.notify("t", "second"))
        self.now += 1
        self.assertTrue(notifier.notify("t", "third"))
        self.assertEqual([call.args[1] for call in self.text.call_args_list], ["first", "third"])

    def test_a_disabled_notifier_sends_nothing(self):
        notifier = self.notifier()
        self.assertFalse(notifier.enabled)
        self.assertFalse(notifier.notify("t", "b"))
        self.text.assert_not_called()
        self.json.assert_not_called()

    def test_delivery_failures_are_logged_and_never_raised(self):
        self.text.side_effect = HttpJsonError("HTTP endpoint is unavailable")
        self.json.side_effect = RuntimeError("unexpected")
        notifier = self.notifier(ntfy_url=NTFY, discord_webhook_url=DISCORD)
        self.assertTrue(notifier.notify("t", "b"))
        messages = [record.getMessage() for record in self.records]
        self.assertIn("ntfy notification failed: HTTP endpoint is unavailable", messages)
        self.assertIn("Discord notification failed", messages)

    def test_titles_stay_ascii_for_the_ntfy_header(self):
        notifier = self.notifier(ntfy_url=NTFY)
        notifier.notify("Schematic Supervisor: café", "body")
        self.assertEqual(self.text.call_args.kwargs["headers"]["Title"], "Schematic Supervisor: caf?")


if __name__ == "__main__":
    unittest.main()

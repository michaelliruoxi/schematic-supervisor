"""Optional phone notifications for monitor alerts, sent off the interface thread."""

from __future__ import annotations

import logging
import threading
import time
from typing import Any, Callable, Mapping

from .alerts import Alert
from .config import NotifyConfig
from .hints import hint_for
from .http_json import HttpJsonError, post_json, post_text

TITLE = "Schematic Supervisor"
MAX_BODY_CHARACTERS = 1_500


def alert_text(alert: Alert, observation: Mapping[str, Any]) -> tuple[str, str]:
    """The notification title and body for an alert, from the latest observation."""

    lines = [alert.message]
    error = observation.get("last_error")
    if alert.kind in {"paused", "error"} and isinstance(error, str) and error.strip():
        lines.append(" ".join(error.split()))
        hint = hint_for(error)
        if hint:
            lines.append("Hint: " + hint)
    body = "\n".join(lines)
    if len(body) > MAX_BODY_CHARACTERS:
        body = body[: MAX_BODY_CHARACTERS - 1] + "…"
    return f"{TITLE}: {alert.message}", body


class PhoneNotifier:
    """Posts alerts to ntfy and/or a Discord webhook. Delivery never blocks or raises into the caller."""

    def __init__(
        self,
        config: NotifyConfig,
        *,
        logger: logging.Logger | None = None,
        clock: Callable[[], float] = time.monotonic,
        start: Callable[[Callable[[], None]], None] | None = None,
        send_text: Callable[..., int] = post_text,
        send_json: Callable[..., Any] = post_json,
    ) -> None:
        self.config = config
        self._logger = logger or logging.getLogger(__name__)
        self._clock = clock
        self._start = start or (lambda work: threading.Thread(
            target=work, name="phone-notifier", daemon=True).start())
        self._send_text = send_text
        self._send_json = send_json
        self._lock = threading.Lock()
        self._last_sent: float | None = None

    @property
    def enabled(self) -> bool:
        return self.config.enabled

    def notify(self, title: str, body: str) -> bool:
        """Queues one notification; returns False when disabled or within the minimum interval."""

        if not self.enabled:
            return False
        now = self._clock()
        with self._lock:
            if self._last_sent is not None and now - self._last_sent < self.config.minimum_interval_seconds:
                self._logger.info("Phone notification skipped; one was sent less than %.0f s ago",
                                  self.config.minimum_interval_seconds)
                return False
            self._last_sent = now
        self._start(lambda: self._deliver(title, body))
        return True

    def _deliver(self, title: str, body: str) -> None:
        timeout = self.config.timeout_seconds
        if self.config.ntfy_url:
            try:
                # ntfy reads these headers; HTTP headers must stay ASCII.
                self._send_text(self.config.ntfy_url, body, timeout_seconds=timeout,
                                headers={"Title": title.encode("ascii", "replace").decode("ascii"),
                                         "Priority": "high", "Tags": "construction"})
            except HttpJsonError as error:
                self._logger.warning("ntfy notification failed: %s", error)
            except Exception:
                self._logger.exception("ntfy notification failed")
        if self.config.discord_webhook_url:
            try:
                content = f"**{title}**\n{body}"
                self._send_json(self.config.discord_webhook_url, {"content": content[:1_900]},
                                timeout_seconds=timeout)
            except HttpJsonError as error:
                self._logger.warning("Discord notification failed: %s", error)
            except Exception:
                self._logger.exception("Discord notification failed")

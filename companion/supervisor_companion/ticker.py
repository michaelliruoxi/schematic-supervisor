"""Refresh loop that survives render failures."""

from __future__ import annotations

import logging
from typing import Any, Callable

from .logging_setup import ThrottledExceptionLog


class SafeTicker:
    """Runs `render` every `interval_ms` through `schedule`; a failing render never stops the loop."""

    def __init__(self, render: Callable[[], None], schedule: Callable[[int, Callable[[], None]], Any], *,
                 interval_ms: int, logger: logging.Logger,
                 on_failure_change: Callable[[bool], None] = lambda failed: None,
                 cancel: Callable[[Any], None] | None = None,
                 errors: ThrottledExceptionLog | None = None) -> None:
        self._render = render
        self._schedule = schedule
        self._interval_ms = interval_ms
        self._on_failure_change = on_failure_change
        self._cancel = cancel
        self._errors = errors or ThrottledExceptionLog(logger)
        self._handle: Any = None
        self._stopped = False
        self._failed = False

    @property
    def failed(self) -> bool:
        return self._failed

    def tick(self) -> None:
        if self._stopped:
            return
        try:
            self._render()
        except Exception as error:
            self._errors.exception("Display refresh failed", error)
            self._set_failed(True)
        else:
            self._set_failed(False)
        finally:
            if not self._stopped:
                self._handle = self._schedule(self._interval_ms, self.tick)

    def stop(self) -> None:
        self._stopped = True
        if self._handle is not None and self._cancel is not None:
            try:
                self._cancel(self._handle)
            except Exception:
                pass
        self._handle = None

    def _set_failed(self, failed: bool) -> None:
        if failed == self._failed:
            return
        self._failed = failed
        try:
            self._on_failure_change(failed)
        except Exception as error:
            # The error banner itself must not stop the refresh loop.
            self._errors.exception("Display error banner failed", error)

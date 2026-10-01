from __future__ import annotations

import logging
import os
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch

from log_capture import capture
from supervisor_companion.config import AppConfig
from supervisor_companion.logging_setup import (
    ThrottledExceptionLog,
    configure_monitor_logging,
    install_exception_hooks,
    tk_callback_error_handler,
)


def close_handlers(logger: logging.Logger) -> None:
    for handler in list(logger.handlers):
        handler.close()
        logger.removeHandler(handler)


class MonitorLoggingTests(unittest.TestCase):
    def test_log_goes_next_to_the_application_and_hides_the_pairing_token(self):
        with tempfile.TemporaryDirectory() as temporary:
            application = Path(temporary)
            logger, directory = configure_monitor_logging(
                AppConfig.from_mapping({}), application, lambda: "pairing-secret-value")
            logger.info("Using pairing-secret-value with X-Supervisor-Token: header-secret-value")
            try:
                raise RuntimeError("failed with pairing-secret-value")
            except RuntimeError:
                logger.exception("Request failed")
            close_handlers(logger)
            text = (directory / "monitor.log").read_text(encoding="utf-8")
        self.assertEqual(directory, application / "logs")
        self.assertNotIn("pairing-secret-value", text)
        self.assertNotIn("header-secret-value", text)
        self.assertIn("[redacted]", text)
        self.assertIn("RuntimeError", text)

    def test_falls_back_to_the_user_folder_when_the_log_folder_cannot_be_created(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            blocked = root / "blocked"
            blocked.write_text("a file where the log folder should be", encoding="utf-8")
            config = AppConfig.from_mapping({"logging": {"directory": str(blocked / "logs")}})
            with patch.dict(os.environ, {"LOCALAPPDATA": str(root / "local")}):
                logger, directory = configure_monitor_logging(config, root, lambda: None)
            close_handlers(logger)
            self.assertEqual(directory, root / "local" / "SchematicSupervisor" / "logs")
            self.assertTrue((directory / "monitor.log").is_file())

    def test_exception_hooks_send_tracebacks_to_the_log(self):
        logger, records = capture("test.hooks")
        self.addCleanup(setattr, sys, "excepthook", sys.excepthook)
        self.addCleanup(setattr, threading, "excepthook", threading.excepthook)
        install_exception_hooks(logger)
        try:
            raise ValueError("unexpected")
        except ValueError:
            sys.excepthook(*sys.exc_info())
        worker = threading.Thread(target=lambda: 1 / 0, name="worker")
        worker.start()
        worker.join()
        self.assertEqual(records[0].levelno, logging.CRITICAL)
        self.assertIs(records[0].exc_info[0], ValueError)
        self.assertIn("worker", records[1].getMessage())
        self.assertIs(records[1].exc_info[0], ZeroDivisionError)

    def test_throttled_log_repeats_one_failure_site_once_per_interval(self):
        logger, records = capture("test.throttled")
        now = [0.0]
        log = ThrottledExceptionLog(logger, interval_seconds=60, clock=lambda: now[0])

        def fail():
            raise RuntimeError("same failure")

        for moment in (0.0, 10.0, 30.0, 61.0):
            now[0] = moment
            try:
                fail()
            except RuntimeError as error:
                log.exception("Refresh failed", error)
        self.assertEqual(len(records), 2)
        self.assertIs(records[0].exc_info[0], RuntimeError)

    def test_throttled_log_keeps_callers_of_one_shared_helper_apart(self):
        logger, records = capture("test.throttled.callers")
        log = ThrottledExceptionLog(logger, interval_seconds=60, clock=lambda: 0.0)

        def shared_helper(number):
            raise RuntimeError(f"shared failure {number}")

        def first_caller(number):
            shared_helper(number)

        def second_caller(number):
            shared_helper(number)

        # The third failure repeats the first call site; different text and log messages don't make it new.
        for number, caller in enumerate((first_caller, second_caller, first_caller)):
            try:
                caller(number)
            except RuntimeError as error:
                log.exception(f"Refresh {number} failed", error)
        self.assertEqual([record.getMessage() for record in records], ["Refresh 0 failed", "Refresh 1 failed"])
        self.assertIn("in first_caller", logging.Formatter().formatException(records[0].exc_info))
        self.assertIn("in second_caller", logging.Formatter().formatException(records[1].exc_info))

    def test_window_callback_errors_are_throttled_and_keep_their_traceback(self):
        logger, records = capture("test.window")
        now = [0.0]
        report = tk_callback_error_handler(ThrottledExceptionLog(logger, interval_seconds=60, clock=lambda: now[0]))

        def press():
            raise KeyError("missing widget")

        for moment in (0.0, 5.0):
            now[0] = moment
            try:
                press()
            except KeyError:
                # Tk passes sys.exc_info() to report_callback_exception.
                report(*sys.exc_info())
        self.assertEqual(len(records), 1)
        self.assertEqual(records[0].getMessage(), "Unhandled error in the window")
        self.assertIs(records[0].exc_info[0], KeyError)
        self.assertIn("in press", logging.Formatter().formatException(records[0].exc_info))


if __name__ == "__main__":
    unittest.main()

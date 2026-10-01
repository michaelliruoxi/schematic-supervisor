"""Rotating application logs, token redaction, and crash capture."""

from __future__ import annotations

import logging
from logging.handlers import RotatingFileHandler
import os
from pathlib import Path
import re
import sys
import threading
import time
import traceback
from types import TracebackType
from typing import Callable

from .config import AppConfig

LOGGER_NAME = "schematic_supervisor.companion"
MONITOR_LOGGER_NAME = "schematic_supervisor.monitor"
MONITOR_LOG_FILENAME = "monitor.log"
REDACTED = "[redacted]"


def configure_logging(config: AppConfig) -> logging.Logger:
    directory = config.log_directory_path()
    directory.mkdir(parents=True, exist_ok=True)
    log_path = directory / config.logging.filename
    logger = logging.getLogger(LOGGER_NAME)
    logger.setLevel(getattr(logging, config.logging.level))
    logger.propagate = False
    for handler in list(logger.handlers):
        handler.close()
        logger.removeHandler(handler)
    handler = RotatingFileHandler(
        Path(log_path),
        maxBytes=config.logging.max_bytes,
        backupCount=config.logging.backup_count,
        encoding="utf-8",
    )
    handler.setFormatter(
        logging.Formatter(
            "%(asctime)s %(levelname)s %(name)s %(message)s",
            datefmt="%Y-%m-%dT%H:%M:%S",
        )
    )
    logger.addHandler(handler)
    return logger


class RedactingFilter(logging.Filter):
    """Masks the pairing token and any X-Supervisor-Token value in messages and tracebacks."""

    _HEADER = re.compile(r"(X-Supervisor-Token[\"']?\s*[:=]\s*[\"']?)([^\s\"',;}]+)", re.IGNORECASE)

    def __init__(self, secret: Callable[[], str | None]) -> None:
        super().__init__()
        self._secret = secret

    def filter(self, record: logging.LogRecord) -> bool:
        try:
            message = record.getMessage()
        except Exception:
            message = str(record.msg)
        record.msg = self._clean(message)
        record.args = None
        if record.exc_info and not record.exc_text:
            record.exc_text = logging.Formatter().formatException(record.exc_info)
        if record.exc_text:
            record.exc_text = self._clean(record.exc_text)
        return True

    def _clean(self, text: str) -> str:
        try:
            secret = self._secret()
        except Exception:
            secret = None
        if secret:
            text = text.replace(secret, REDACTED)
        return self._HEADER.sub(lambda match: match.group(1) + REDACTED, text)


def fallback_directory() -> Path:
    base = os.environ.get("LOCALAPPDATA")
    return (Path(base) if base else Path.home()) / "SchematicSupervisor"


def configure_monitor_logging(config: AppConfig, application_directory: Path,
                              secret: Callable[[], str | None]) -> tuple[logging.Logger, Path]:
    """Log to <exe folder>/<logging.directory>/monitor.log, or to the per-user folder if that fails."""
    configured = Path(config.logging.directory)
    preferred = configured if configured.is_absolute() else application_directory / configured
    logger = logging.getLogger(MONITOR_LOGGER_NAME)
    logger.setLevel(getattr(logging, config.logging.level))
    logger.propagate = False
    for handler in list(logger.handlers):
        handler.close()
        logger.removeHandler(handler)
    failure: OSError | None = None
    for directory in (preferred, fallback_directory() / "logs"):
        try:
            directory.mkdir(parents=True, exist_ok=True)
            handler = RotatingFileHandler(directory / MONITOR_LOG_FILENAME, maxBytes=config.logging.max_bytes,
                                          backupCount=config.logging.backup_count, encoding="utf-8")
        except OSError as error:
            failure = error
            continue
        handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s [%(threadName)s] %(message)s",
                                               datefmt="%Y-%m-%dT%H:%M:%S"))
        handler.addFilter(RedactingFilter(secret))
        logger.addHandler(handler)
        return logger, directory
    raise OSError(f"No writable log folder was found ({failure}).")


def install_exception_hooks(logger: logging.Logger) -> None:
    """Send tracebacks that a windowed exe would otherwise lose to the log."""

    def unhandled(exc_type, exc_value, exc_traceback) -> None:
        if issubclass(exc_type, KeyboardInterrupt):
            sys.__excepthook__(exc_type, exc_value, exc_traceback)
            return
        logger.critical("Unhandled exception", exc_info=(exc_type, exc_value, exc_traceback))

    def unhandled_thread(arguments: threading.ExceptHookArgs) -> None:
        if arguments.exc_type is SystemExit:
            return
        name = arguments.thread.name if arguments.thread is not None else "unknown"
        logger.error("Unhandled exception in thread %s", name,
                     exc_info=(arguments.exc_type, arguments.exc_value, arguments.exc_traceback))

    sys.excepthook = unhandled
    threading.excepthook = unhandled_thread


class ThrottledExceptionLog:
    """Logs a failure with its traceback at most once per interval for each distinct failure site.

    A site is the exception type and the whole traceback, so callers that fail inside one shared helper stay
    apart. Messages are not part of it: their text can change from one repeat to the next."""

    def __init__(self, logger: logging.Logger, *, interval_seconds: float = 60.0,
                 clock: Callable[[], float] = time.monotonic) -> None:
        self._logger = logger
        self._interval = interval_seconds
        self._clock = clock
        self._last: dict[tuple[type[BaseException], tuple[tuple[str, int | None], ...]], float] = {}

    def exception(self, message: str, error: BaseException) -> bool:
        frames = traceback.extract_tb(error.__traceback__)
        site = (type(error), tuple((frame.filename, frame.lineno) for frame in frames))
        now = self._clock()
        last = self._last.get(site)
        if last is not None and now - last < self._interval:
            return False
        self._last[site] = now
        self._logger.error(message, exc_info=(type(error), error, error.__traceback__))
        return True


def tk_callback_error_handler(
    errors: ThrottledExceptionLog,
) -> Callable[[type[BaseException], BaseException, TracebackType | None], None]:
    """Build a Tk `report_callback_exception` handler that logs a repeating failure once per interval."""

    def report(exc_type, exc_value, exc_traceback) -> None:
        # Tk passes sys.exc_info(), so exc_value already carries the traceback.
        errors.exception("Unhandled error in the window", exc_value)

    return report

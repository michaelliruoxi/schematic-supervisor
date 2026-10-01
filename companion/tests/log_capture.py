"""Collect log records in tests."""

from __future__ import annotations

import logging


class _Collector(logging.Handler):
    def __init__(self, records: list[logging.LogRecord]) -> None:
        super().__init__()
        self.records = records

    def emit(self, record: logging.LogRecord) -> None:
        self.records.append(record)


def capture(name: str) -> tuple[logging.Logger, list[logging.LogRecord]]:
    records: list[logging.LogRecord] = []
    logger = logging.getLogger(name)
    for handler in list(logger.handlers):
        logger.removeHandler(handler)
    logger.addHandler(_Collector(records))
    logger.setLevel(logging.DEBUG)
    logger.propagate = False
    return logger, records

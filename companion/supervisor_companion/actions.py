"""Allowlisted actions shared by control and diagnosis code."""

from __future__ import annotations

from enum import Enum


class ControlAction(str, Enum):
    """Commands an operator may send to the deterministic mod."""

    START = "START"
    PAUSE = "PAUSE"
    RESUME = "RESUME"
    STOP = "STOP"


class RecoveryAction(str, Enum):
    """The complete set of recovery advice the AI is allowed to return."""

    WAIT = "WAIT"
    REPATH = "REPATH"
    RESTOCK = "RESTOCK"
    RETRY_CHUNK = "RETRY_CHUNK"
    RETURN_TO_SAFE_POSITION = "RETURN_TO_SAFE_POSITION"
    PAUSE_AND_ALERT = "PAUSE_AND_ALERT"


ALLOWED_RECOVERY_ACTIONS: tuple[str, ...] = tuple(
    action.value for action in RecoveryAction
)

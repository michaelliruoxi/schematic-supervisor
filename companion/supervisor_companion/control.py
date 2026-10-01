"""Localhost control client for the deterministic Fabric mod."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Mapping
from uuid import uuid4

from .actions import ControlAction
from .config import validate_http_url
from .http_json import HttpJsonError, post_json


class ControlError(RuntimeError):
    """Raised when a control cannot be delivered or is rejected."""


@dataclass(frozen=True)
class ControlResult:
    action: ControlAction
    request_id: str
    accepted: bool
    message: str
    mod_state: str | None = None


class ModControlClient:
    """Send one of four fixed operator controls to the local mod."""

    def __init__(
        self,
        base_url: str,
        *,
        timeout_seconds: float = 3.0,
        token: str | None = None,
    ) -> None:
        safe_base_url = validate_http_url(
            base_url,
            "mod base URL",
            require_loopback=True,
        )
        self._url = f"{safe_base_url}/v1/control"
        self._timeout_seconds = timeout_seconds
        self._headers = (
            {} if token is None else {"X-Supervisor-Token": token}
        )

    def send(
        self,
        action: ControlAction,
        *,
        request_id: str | None = None,
    ) -> ControlResult:
        if not isinstance(action, ControlAction):
            raise TypeError("action must be a ControlAction")
        actual_request_id = request_id or str(uuid4())
        payload = {
            "action": action.value,
            "request_id": actual_request_id,
            "sent_at": datetime.now(timezone.utc).isoformat(
                timespec="milliseconds"
            ),
        }
        try:
            response = post_json(
                self._url,
                payload,
                timeout_seconds=self._timeout_seconds,
                headers=self._headers,
            )
        except HttpJsonError as error:
            raise ControlError(f"{action.value} could not reach the mod") from error
        body: Mapping[str, Any]
        if isinstance(response.body, Mapping):
            body = response.body
        else:
            raise ControlError("mod control response was not an object")
        if "accepted" not in body:
            raise ControlError(
                "mod control response did not explicitly acknowledge the request"
            )
        accepted = body["accepted"]
        if not isinstance(accepted, bool):
            raise ControlError("mod control response has invalid accepted value")
        raw_message = body.get(
            "message",
            "Control accepted." if accepted else "Control rejected.",
        )
        if not isinstance(raw_message, str):
            raise ControlError("mod control response has invalid message")
        raw_state = body.get("state")
        if raw_state is not None and not isinstance(raw_state, str):
            raise ControlError("mod control response has invalid state")
        result = ControlResult(
            action=action,
            request_id=actual_request_id,
            accepted=accepted,
            message=raw_message.strip()[:1_000],
            mod_state=raw_state,
        )
        if not accepted:
            raise ControlError(
                result.message or f"{action.value} was rejected by the mod"
            )
        return result

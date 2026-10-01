"""Thread-safe in-memory state for the UI and localhost server."""

from __future__ import annotations

from dataclasses import dataclass
from threading import RLock
from typing import Any

from .models import DiagnosisDecision, Incident, StatusSnapshot


@dataclass(frozen=True)
class AppState:
    status: StatusSnapshot
    latest_incident: Incident | None
    latest_decision: DiagnosisDecision | None
    local_error: str | None

    @property
    def displayed_error(self) -> str | None:
        return self.local_error or self.status.last_error

    def to_dict(self) -> dict[str, Any]:
        decision = self.latest_decision
        incident = self.latest_incident
        return {
            "status": self.status.to_dict(),
            "latest_incident_id": (
                None if incident is None else incident.incident_id
            ),
            "latest_decision": (
                None
                if decision is None or incident is None
                else decision.to_dict(incident.incident_id)
            ),
            "local_error": self.local_error,
        }


class StatusStore:
    """A lock-protected store; no database or on-disk state is used."""

    def __init__(self) -> None:
        self._lock = RLock()
        self._status = StatusSnapshot()
        self._latest_incident: Incident | None = None
        self._latest_decision: DiagnosisDecision | None = None
        self._local_error: str | None = None

    def update_status(self, status: StatusSnapshot) -> None:
        with self._lock:
            self._status = status

    def record_incident(self, incident: Incident) -> None:
        with self._lock:
            self._latest_incident = incident
            self._latest_decision = None
            if incident.status is not None:
                self._status = incident.status

    def record_decision(self, decision: DiagnosisDecision) -> None:
        with self._lock:
            self._latest_decision = decision

    def set_local_error(self, message: str | None) -> None:
        with self._lock:
            self._local_error = None if message is None else message[:4_096]

    def snapshot(self) -> AppState:
        with self._lock:
            return AppState(
                status=self._status,
                latest_incident=self._latest_incident,
                latest_decision=self._latest_decision,
                local_error=self._local_error,
            )

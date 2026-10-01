"""Desktop companion for the schematic build supervisor."""

from .actions import ControlAction, RecoveryAction
from .models import DiagnosisDecision, Incident, StatusSnapshot

__all__ = [
    "ControlAction",
    "DiagnosisDecision",
    "Incident",
    "RecoveryAction",
    "StatusSnapshot",
]

__version__ = "0.4.0"

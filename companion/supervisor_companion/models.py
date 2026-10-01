"""Validated data models for the companion's localhost protocol."""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
import math
from types import MappingProxyType
from typing import Any, Mapping, Sequence

from .actions import RecoveryAction

MAX_TEXT_LENGTH = 4_096
MAX_COLLECTION_ITEMS = 256
MAX_NESTING_DEPTH = 6


class PayloadError(ValueError):
    """Raised when a localhost protocol payload is invalid."""


def utc_now_text() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


def _required_text(
    value: Any,
    name: str,
    *,
    max_length: int = MAX_TEXT_LENGTH,
) -> str:
    if not isinstance(value, str):
        raise PayloadError(f"{name} must be a string")
    normalized = value.strip()
    if not normalized:
        raise PayloadError(f"{name} must not be empty")
    if len(normalized) > max_length:
        raise PayloadError(f"{name} exceeds {max_length} characters")
    return normalized


def _optional_text(
    value: Any,
    name: str,
    *,
    max_length: int = MAX_TEXT_LENGTH,
) -> str | None:
    if value is None:
        return None
    return _required_text(value, name, max_length=max_length)


def _integer(
    value: Any,
    name: str,
    *,
    minimum: int | None = None,
    maximum: int | None = None,
) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        raise PayloadError(f"{name} must be an integer")
    if minimum is not None and value < minimum:
        raise PayloadError(f"{name} must be at least {minimum}")
    if maximum is not None and value > maximum:
        raise PayloadError(f"{name} must be at most {maximum}")
    return value


def sanitize_json_value(
    value: Any,
    name: str = "value",
    *,
    depth: int = 0,
) -> Any:
    """Copy a JSON-shaped value while enforcing conservative size bounds."""

    if depth > MAX_NESTING_DEPTH:
        raise PayloadError(f"{name} is nested too deeply")
    if value is None or isinstance(value, (bool, int)):
        return value
    if isinstance(value, float):
        if not math.isfinite(value):
            raise PayloadError(f"{name} must be a finite number")
        return value
    if isinstance(value, str):
        if len(value) > MAX_TEXT_LENGTH:
            raise PayloadError(f"{name} exceeds {MAX_TEXT_LENGTH} characters")
        return value
    if isinstance(value, Mapping):
        if len(value) > MAX_COLLECTION_ITEMS:
            raise PayloadError(f"{name} has too many entries")
        copied: dict[str, Any] = {}
        for key, item in value.items():
            if not isinstance(key, str) or not key:
                raise PayloadError(f"{name} keys must be non-empty strings")
            if len(key) > 128:
                raise PayloadError(f"{name} contains an overlong key")
            copied[key] = sanitize_json_value(
                item,
                f"{name}.{key}",
                depth=depth + 1,
            )
        return copied
    if isinstance(value, Sequence) and not isinstance(
        value, (str, bytes, bytearray)
    ):
        if len(value) > MAX_COLLECTION_ITEMS:
            raise PayloadError(f"{name} has too many items")
        return [
            sanitize_json_value(item, f"{name}[{index}]", depth=depth + 1)
            for index, item in enumerate(value)
        ]
    raise PayloadError(f"{name} must contain only JSON-compatible values")


@dataclass(frozen=True)
class ChunkStatus:
    """Current chunk identity and progress."""

    index: int
    total: int
    x: int | None = None
    z: int | None = None

    @classmethod
    def from_mapping(cls, value: Any) -> "ChunkStatus | None":
        if value is None:
            return None
        if not isinstance(value, Mapping):
            raise PayloadError("current_chunk must be an object or null")
        index = _integer(value.get("index"), "current_chunk.index", minimum=1)
        total = _integer(value.get("total"), "current_chunk.total", minimum=1)
        if index > total:
            raise PayloadError("current_chunk.index must not exceed total")
        x = value.get("x")
        z = value.get("z")
        return cls(
            index=index,
            total=total,
            x=None if x is None else _integer(x, "current_chunk.x"),
            z=None if z is None else _integer(z, "current_chunk.z"),
        )

    def to_dict(self) -> dict[str, int | None]:
        return {
            "index": self.index,
            "total": self.total,
            "x": self.x,
            "z": self.z,
        }


@dataclass(frozen=True)
class LayerStatus:
    """Current stage and horizontal layer in the deterministic build schedule."""

    order: str
    stage: str
    index: int
    total: int
    y: int | None
    chunk_index: int
    chunk_total: int

    @classmethod
    def from_mapping(cls, value: Any) -> "LayerStatus | None":
        if value is None:
            return None
        if not isinstance(value, Mapping):
            raise PayloadError("current_layer must be an object or null")
        order = _required_text(value.get("order"), "current_layer.order", max_length=32)
        stage = _required_text(value.get("stage"), "current_layer.stage", max_length=32)
        index = _integer(value.get("index"), "current_layer.index", minimum=0)
        total = _integer(value.get("total"), "current_layer.total", minimum=0)
        chunk_index = _integer(value.get("chunk_index"), "current_layer.chunk_index", minimum=0)
        chunk_total = _integer(value.get("chunk_total"), "current_layer.chunk_total", minimum=0)
        if index > total or chunk_index > chunk_total:
            raise PayloadError("current_layer indices must not exceed their totals")
        y = value.get("y")
        return cls(
            order=order,
            stage=stage,
            index=index,
            total=total,
            y=None if y is None else _integer(y, "current_layer.y"),
            chunk_index=chunk_index,
            chunk_total=chunk_total,
        )

    def to_dict(self) -> dict[str, str | int | None]:
        return {
            "order": self.order,
            "stage": self.stage,
            "index": self.index,
            "total": self.total,
            "y": self.y,
            "chunk_index": self.chunk_index,
            "chunk_total": self.chunk_total,
        }


@dataclass(frozen=True)
class MaterialStatus:
    """Available, required, and missing counts for one tracked material."""

    available: int
    required: int | None = None
    missing: int = 0

    @classmethod
    def from_value(cls, value: Any, name: str) -> "MaterialStatus":
        if isinstance(value, bool):
            raise PayloadError(f"materials.{name} must be an integer or object")
        if isinstance(value, int):
            return cls(
                available=_integer(
                    value, f"materials.{name}", minimum=0, maximum=2_147_483_647
                )
            )
        if not isinstance(value, Mapping):
            raise PayloadError(f"materials.{name} must be an integer or object")
        available = _integer(
            value.get("available"),
            f"materials.{name}.available",
            minimum=0,
            maximum=2_147_483_647,
        )
        required_raw = value.get("required")
        required = (
            None
            if required_raw is None
            else _integer(
                required_raw,
                f"materials.{name}.required",
                minimum=0,
                maximum=2_147_483_647,
            )
        )
        missing_raw = value.get("missing")
        if missing_raw is None:
            missing = max(0, required - available) if required is not None else 0
        else:
            missing = _integer(
                missing_raw,
                f"materials.{name}.missing",
                minimum=0,
                maximum=2_147_483_647,
            )
        return cls(available=available, required=required, missing=missing)

    def to_dict(self) -> dict[str, int | None]:
        return {
            "available": self.available,
            "required": self.required,
            "missing": self.missing,
        }


@dataclass(frozen=True)
class StatusSnapshot:
    """The latest deterministic builder state reported by the mod."""

    current_chunk: ChunkStatus | None = None
    phase: str = "STOPPED"
    materials: Mapping[str, MaterialStatus] = field(
        default_factory=lambda: MappingProxyType({})
    )
    baritone_status: str = "Unavailable"
    last_error: str | None = None
    updated_at: str = field(default_factory=utc_now_text)
    current_layer: LayerStatus | None = None
    planting_deferred: bool = False
    deferred_seed_cells: int = 0

    @classmethod
    def from_mapping(cls, value: Any) -> "StatusSnapshot":
        if not isinstance(value, Mapping):
            raise PayloadError("status must be an object")
        materials_raw = value.get("materials", {})
        if not isinstance(materials_raw, Mapping):
            raise PayloadError("materials must be an object")
        if len(materials_raw) > 64:
            raise PayloadError("materials has too many entries")
        materials: dict[str, MaterialStatus] = {}
        for raw_name, raw_status in materials_raw.items():
            name = _required_text(raw_name, "material name", max_length=64)
            materials[name] = MaterialStatus.from_value(raw_status, name)
        updated_at = value.get("updated_at")
        planting_deferred = value.get("planting_deferred", False)
        if type(planting_deferred) is not bool:
            raise PayloadError("planting_deferred must be a boolean")
        deferred_seed_cells = _integer(value.get("deferred_seed_cells", 0),
                                       "deferred_seed_cells", minimum=0)
        if deferred_seed_cells > 1_000_000 or (deferred_seed_cells and not planting_deferred):
            raise PayloadError("deferred_seed_cells must fit the active deferred planting scope")
        return cls(
            current_chunk=ChunkStatus.from_mapping(value.get("current_chunk")),
            current_layer=LayerStatus.from_mapping(value.get("current_layer")),
            planting_deferred=planting_deferred,
            deferred_seed_cells=deferred_seed_cells,
            phase=_required_text(
                value.get("phase", "STOPPED"), "phase", max_length=128
            ),
            materials=MappingProxyType(materials),
            baritone_status=_required_text(
                value.get("baritone_status", "Unavailable"),
                "baritone_status",
                max_length=512,
            ),
            last_error=_optional_text(value.get("last_error"), "last_error"),
            updated_at=(
                utc_now_text()
                if updated_at is None
                else _required_text(updated_at, "updated_at", max_length=128)
            ),
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "current_chunk": (
                None if self.current_chunk is None else self.current_chunk.to_dict()
            ),
            "current_layer": (
                None if self.current_layer is None else self.current_layer.to_dict()
            ),
            "phase": self.phase,
            "planting_deferred": self.planting_deferred,
            "deferred_seed_cells": self.deferred_seed_cells,
            "materials": {
                name: status.to_dict() for name, status in self.materials.items()
            },
            "baritone_status": self.baritone_status,
            "last_error": self.last_error,
            "updated_at": self.updated_at,
        }


@dataclass(frozen=True)
class Incident:
    """A failed deterministic recovery attempt submitted for diagnosis."""

    incident_id: str
    category: str
    summary: str
    recovery_exhausted: bool = False
    status: StatusSnapshot | None = None
    attempted_recovery: tuple[str, ...] = ()
    details: Mapping[str, Any] = field(
        default_factory=lambda: MappingProxyType({})
    )
    created_at: str = field(default_factory=utc_now_text)

    @classmethod
    def from_mapping(cls, value: Any) -> "Incident":
        if not isinstance(value, Mapping):
            raise PayloadError("incident must be an object")
        attempts_raw = value.get("attempted_recovery", [])
        if not isinstance(attempts_raw, Sequence) or isinstance(
            attempts_raw, (str, bytes, bytearray)
        ):
            raise PayloadError("attempted_recovery must be an array")
        if len(attempts_raw) > 32:
            raise PayloadError("attempted_recovery has too many items")
        attempts = tuple(
            _required_text(
                attempt,
                f"attempted_recovery[{index}]",
                max_length=128,
            )
            for index, attempt in enumerate(attempts_raw)
        )
        details_raw = sanitize_json_value(value.get("details", {}), "details")
        if not isinstance(details_raw, dict):
            raise PayloadError("details must be an object")
        status_raw = value.get("status")
        recovery_exhausted = value.get("recovery_exhausted", False)
        if not isinstance(recovery_exhausted, bool):
            raise PayloadError("recovery_exhausted must be a boolean")
        created_at = value.get("created_at")
        return cls(
            incident_id=_required_text(
                value.get("incident_id"), "incident_id", max_length=128
            ),
            category=_required_text(
                value.get("category"), "category", max_length=128
            ),
            summary=_required_text(value.get("summary"), "summary"),
            recovery_exhausted=recovery_exhausted,
            status=(
                None
                if status_raw is None
                else StatusSnapshot.from_mapping(status_raw)
            ),
            attempted_recovery=attempts,
            details=MappingProxyType(details_raw),
            created_at=(
                utc_now_text()
                if created_at is None
                else _required_text(created_at, "created_at", max_length=128)
            ),
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "incident_id": self.incident_id,
            "category": self.category,
            "summary": self.summary,
            "recovery_exhausted": self.recovery_exhausted,
            "status": None if self.status is None else self.status.to_dict(),
            "attempted_recovery": list(self.attempted_recovery),
            "details": dict(self.details),
            "created_at": self.created_at,
        }


@dataclass(frozen=True)
class DiagnosisDecision:
    """A validated recovery decision safe to return to the mod."""

    action: RecoveryAction
    reason: str
    source: str
    used_fallback: bool = False

    @classmethod
    def from_provider_mapping(
        cls,
        value: Any,
        *,
        source: str,
    ) -> "DiagnosisDecision":
        if not isinstance(value, Mapping):
            raise PayloadError("diagnosis response must be an object")
        raw_action = value.get("action")
        if not isinstance(raw_action, str):
            raise PayloadError("diagnosis action must be a string")
        try:
            action = RecoveryAction(raw_action.strip().upper())
        except ValueError as error:
            raise PayloadError("diagnosis action is not allowlisted") from error
        # Provider prose is deliberately not returned to the mod or UI. Even
        # with an allowlisted action, free text could contain coordinates,
        # commands, or other out-of-policy instructions.
        reason = {
            RecoveryAction.WAIT: (
                "Use the fixed wait interval, then resume deterministic checks."
            ),
            RecoveryAction.REPATH: (
                "Cancel and restart only the current deterministic path."
            ),
            RecoveryAction.RESTOCK: (
                "Use the registered depot restocking routine."
            ),
            RecoveryAction.RETRY_CHUNK: (
                "Restart the deterministic workflow for the current chunk."
            ),
            RecoveryAction.RETURN_TO_SAFE_POSITION: (
                "Use the mod's recorded safe-position recovery routine."
            ),
            RecoveryAction.PAUSE_AND_ALERT: (
                "Pause construction and request operator review."
            ),
        }[action]
        return cls(action=action, reason=reason, source=source)

    @classmethod
    def pause_fallback(cls, reason: str) -> "DiagnosisDecision":
        safe_reason = reason.strip()[:1_000] or "Diagnosis was unavailable."
        return cls(
            action=RecoveryAction.PAUSE_AND_ALERT,
            reason=safe_reason,
            source="safe_fallback",
            used_fallback=True,
        )

    def to_dict(self, incident_id: str) -> dict[str, Any]:
        return {
            "incident_id": incident_id,
            "action": self.action.value,
            "reason": self.reason,
            "source": self.source,
            "used_fallback": self.used_fallback,
        }

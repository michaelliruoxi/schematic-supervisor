"""Validation and derived facts for the mod's /v1/progress payload."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

MAX_STAGES = 4096
MAX_CHUNKS = 1024
MAX_MATERIALS = 64
# The mod's numbers are Java ints and longs; anything larger could only overflow the display.
JAVA_LONG_MIN, JAVA_LONG_MAX = -2 ** 63, 2 ** 63 - 1
DONE, CURRENT, TO_DO, NO_WORK = "D", "C", "-", "."
_ALPHABET = frozenset("DC-.")
_FINAL_STAGES = frozenset({"DONE", "VERIFY"})


class ProgressError(ValueError):
    """The payload is not a valid progress report."""


@dataclass(frozen=True)
class Layout:
    origin_x: int
    origin_z: int
    columns: int
    rows: int

    @property
    def chunk_count(self) -> int:
        return self.columns * self.rows

    def chunk_coordinates(self, index: int) -> tuple[int, int]:
        return self.origin_x + index % self.columns, self.origin_z + index // self.columns


@dataclass(frozen=True)
class Stage:
    kind: str
    y: int
    actions: int
    done: int
    chunks: str | None

    @property
    def fraction(self) -> float:
        return 1.0 if self.actions == 0 else self.done / self.actions


@dataclass(frozen=True)
class MaterialCount:
    """Items of one material in the schedule, and in its finished pieces."""

    name: str
    planned: int
    done: int

    @property
    def remaining(self) -> int:
        return self.planned - self.done


@dataclass(frozen=True)
class Progress:
    revision: int
    plan_id: str
    schedule_id: str
    layout: Layout
    total_actions: int
    done_actions: int
    current_stage: int | None
    stages: tuple[Stage, ...]
    chunk_detail_truncated: bool
    # None from mods that don't count materials in their progress.
    materials: tuple[MaterialCount, ...] | None = None

    @property
    def fraction(self) -> float:
        return 1.0 if self.total_actions == 0 else self.done_actions / self.total_actions

    @property
    def remaining_actions(self) -> int:
        return self.total_actions - self.done_actions

    def stage_status(self, index: int) -> str:
        """"current", "done", "partial", or "todo" for a zero-based stage index."""
        if self.current_stage is not None and index == self.current_stage - 1:
            return "current"
        stage = self.stages[index]
        if stage.done >= stage.actions:
            return "done"
        return "partial" if stage.done > 0 else "todo"


@dataclass(frozen=True)
class Unavailable:
    revision: int
    reason: str


@dataclass(frozen=True)
class Approximate:
    """Rough progress from the observation, for mods without /v1/progress."""

    fraction: float
    stage: int
    stages: int


def parse_progress(value: Any) -> Progress | Unavailable:
    data = _object(value, "progress")
    if _integer(data.get("protocol_version"), "protocol_version", 0) != 1:
        raise ProgressError("Unsupported progress protocol version.")
    available = data.get("available")
    if not isinstance(available, bool):
        raise ProgressError("available must be a boolean.")
    revision = _integer(data.get("revision"), "revision", 0)
    if not available:
        return Unavailable(revision, _text(data.get("reason"), "reason", 1000))
    layout_data = _object(data.get("layout"), "layout")
    layout = Layout(_integer(layout_data.get("origin_x"), "layout.origin_x"),
                    _integer(layout_data.get("origin_z"), "layout.origin_z"),
                    _integer(layout_data.get("columns"), "layout.columns", 1),
                    _integer(layout_data.get("rows"), "layout.rows", 1))
    if layout.chunk_count > MAX_CHUNKS:
        raise ProgressError("layout has too many chunks.")
    raw_stages = data.get("stages")
    if not isinstance(raw_stages, list) or len(raw_stages) > MAX_STAGES:
        raise ProgressError("stages must be a list of at most 4096 entries.")
    stages = tuple(_stage(item, layout.chunk_count) for item in raw_stages)
    totals = _object(data.get("totals"), "totals")
    total_actions = _integer(totals.get("actions"), "totals.actions", 0)
    done_actions = _integer(totals.get("done"), "totals.done", 0)
    if _integer(totals.get("stages"), "totals.stages", 0) != len(stages):
        raise ProgressError("totals.stages does not match the stage list.")
    if (total_actions != sum(stage.actions for stage in stages)
            or done_actions != sum(stage.done for stage in stages)):
        raise ProgressError("totals do not match the stages.")
    current = totals.get("current_stage")
    if current is not None:
        current = _integer(current, "totals.current_stage", 1)
        if current > len(stages):
            raise ProgressError("current_stage is outside the stage list.")
    truncated = data.get("chunk_detail_truncated")
    if not isinstance(truncated, bool):
        raise ProgressError("chunk_detail_truncated must be a boolean.")
    missing = {index for index, stage in enumerate(stages) if stage.chunks is None}
    if missing and (not truncated or (current is not None and current - 1 in missing)):
        raise ProgressError("chunk detail is missing.")
    materials = _materials(data["materials"]) if "materials" in data else None
    return Progress(revision, _text(data.get("plan_id"), "plan_id", 256),
                    _text(data.get("schedule_id"), "schedule_id", 256), layout,
                    total_actions, done_actions, current, stages, truncated, materials)


def chunk_shares(progress: Progress) -> list[float | None] | None:
    """Share of each chunk's stages that are finished; None for chunks without work.

    Returns None when some stages lack chunk detail.
    """
    if any(stage.chunks is None for stage in progress.stages):
        return None
    count = progress.layout.chunk_count
    work = [0] * count
    done = [0] * count
    for stage in progress.stages:
        for index, letter in enumerate(stage.chunks or ""):
            if letter != NO_WORK:
                work[index] += 1
                if letter == DONE:
                    done[index] += 1
    return [None if work[index] == 0 else done[index] / work[index] for index in range(count)]


def approximate_from_layer(layer: Any) -> Approximate | None:
    if not isinstance(layer, dict):
        return None
    values = (layer.get("index"), layer.get("total"), layer.get("chunk_index"), layer.get("chunk_total"))
    if not all(isinstance(value, int) and not isinstance(value, bool) for value in values):
        return None
    index, total, chunk_index, chunk_total = values
    stage = layer.get("stage")
    if not isinstance(stage, str) or not 1 <= total <= MAX_STAGES or not 1 <= index <= total:
        return None
    if chunk_total > 0 and 1 <= chunk_index <= chunk_total:
        within = (chunk_index - 1) / chunk_total
    else:
        within = 1.0 if stage in _FINAL_STAGES else 0.0
    return Approximate(min(1.0, (index - 1 + within) / total), index, total)


def _stage(value: Any, chunk_count: int) -> Stage:
    data = _object(value, "stage")
    actions = _integer(data.get("actions"), "stage.actions", 0)
    done = _integer(data.get("done"), "stage.done", 0)
    if done > actions:
        raise ProgressError("stage.done exceeds stage.actions.")
    chunks = data.get("chunks")
    if chunks is not None and (not isinstance(chunks, str) or len(chunks) != chunk_count
                               or not set(chunks) <= _ALPHABET):
        raise ProgressError("stage.chunks is invalid.")
    return Stage(_text(data.get("kind"), "stage.kind", 32), _integer(data.get("y"), "stage.y"),
                 actions, done, chunks)


def _materials(value: Any) -> tuple[MaterialCount, ...]:
    data = _object(value, "materials")
    if len(data) > MAX_MATERIALS:
        raise ProgressError(f"materials must have at most {MAX_MATERIALS} entries.")
    counts = []
    for name, item in data.items():
        item = _object(item, "material")
        planned = _integer(item.get("planned"), "material.planned", 0)
        done = _integer(item.get("done"), "material.done", 0)
        if done > planned:
            raise ProgressError("material.done exceeds material.planned.")
        counts.append(MaterialCount(_text(name, "material name", 64), planned, done))
    return tuple(counts)


def _object(value: Any, name: str) -> dict:
    if not isinstance(value, dict):
        raise ProgressError(f"{name} must be an object.")
    return value


def _integer(value: Any, name: str, minimum: int | None = None) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or (minimum is not None and value < minimum):
        bound = "" if minimum is None else f" of at least {minimum}"
        raise ProgressError(f"{name} must be an integer{bound}.")
    if not JAVA_LONG_MIN <= value <= JAVA_LONG_MAX:
        raise ProgressError(f"{name} is outside the 64-bit integer range.")
    return value


def _text(value: Any, name: str, maximum: int) -> str:
    if not isinstance(value, str) or not value.strip() or len(value) > maximum:
        raise ProgressError(f"{name} must be text of 1 to {maximum} characters.")
    return value

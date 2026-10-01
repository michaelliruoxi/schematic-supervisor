"""Remembered window layout and settings for the monitor."""

from __future__ import annotations

from dataclasses import asdict, dataclass
import json
import os
from pathlib import Path
from typing import Any, Sequence

from .logging_setup import fallback_directory

STATE_FILENAME = "monitor-state.json"
_LIMIT = 100_000


@dataclass
class UiState:
    mode: str = "compact"
    compact_position: tuple[int, int] | None = None
    full_geometry: tuple[int, int, int, int] | None = None
    pinned: bool = True
    full_on_top: bool = False
    sound: bool = False


def candidate_paths(application_directory: Path) -> list[Path]:
    return [application_directory / STATE_FILENAME, fallback_directory() / STATE_FILENAME]


def load_ui_state(paths: Sequence[Path]) -> UiState:
    """Load the newest candidate that parses, so a stale file cannot hide a later fallback save.

    Ties keep the candidate order. Missing, unreadable or invalid files are skipped."""
    newest: tuple[int, Any] | None = None
    for path in paths:
        try:
            modified = path.stat().st_mtime_ns
            raw = json.loads(path.read_text(encoding="utf-8-sig"))
        except (OSError, ValueError, RecursionError):
            continue
        if newest is None or modified > newest[0]:
            newest = (modified, raw)
    return UiState() if newest is None else _from_mapping(newest[1])


def save_ui_state(paths: Sequence[Path], state: UiState) -> Path | None:
    encoded = json.dumps(asdict(state), indent=2) + "\n"
    for path in paths:
        temporary = path.with_name(path.name + ".tmp")
        try:
            path.parent.mkdir(parents=True, exist_ok=True)
            temporary.write_text(encoded, encoding="utf-8")
            os.replace(temporary, path)
            return path
        except OSError:
            continue
    return None


def is_visible(position: tuple[int, int], size: tuple[int, int],
               screens: Sequence[tuple[int, int, int, int]], *, minimum: int = 40) -> bool:
    x, y = position
    width, height = size
    for left, top, right, bottom in screens:
        overlap_width = min(x + width, right) - max(x, left)
        overlap_height = min(y + height, bottom) - max(y, top)
        if overlap_width >= minimum and overlap_height >= minimum:
            return True
    return False


def default_compact_position(work_area: tuple[int, int, int, int], size: tuple[int, int], *,
                             margin: int = 16) -> tuple[int, int]:
    left, top, right, _bottom = work_area
    width, _height = size
    return max(left, right - width - margin), top + margin


def _from_mapping(raw: Any) -> UiState:
    if not isinstance(raw, dict):
        return UiState()
    geometry = _integers(raw.get("full_geometry"), 4)
    if geometry is not None and (geometry[0] < 200 or geometry[1] < 200):
        geometry = None
    return UiState(
        mode=raw["mode"] if raw.get("mode") in ("compact", "full") else "compact",
        compact_position=_integers(raw.get("compact_position"), 2),
        full_geometry=geometry,
        pinned=_flag(raw.get("pinned"), True),
        full_on_top=_flag(raw.get("full_on_top"), False),
        sound=_flag(raw.get("sound"), False),
    )


def _integers(value: Any, count: int) -> tuple | None:
    if (not isinstance(value, list) or len(value) != count
            or not all(isinstance(item, int) and not isinstance(item, bool) and abs(item) <= _LIMIT
                       for item in value)):
        return None
    return tuple(value)


def _flag(value: Any, default: bool) -> bool:
    return value if isinstance(value, bool) else default

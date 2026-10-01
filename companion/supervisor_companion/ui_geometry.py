"""Pixel layout for the stage timeline, chunk map, tooltips, and window placement.

Pure functions; ui_canvas and monitor_ui apply the result.
"""

from __future__ import annotations

from typing import Sequence

_PRIORITY = {"current": 3, "todo": 2, "partial": 1, "done": 0}


def timeline_runs(statuses: Sequence[str], width: int) -> list[tuple[int, int, str]]:
    """Filled spans (x0, x1 exclusive, status) for a bar `width` pixels wide.

    Segments get a 1 px gap when each is at least 4 px wide. With more stages than pixels, each
    pixel column shows its most important status: current, then to do, partly done, done.
    """
    count = len(statuses)
    if count == 0 or width <= 0:
        return []
    if count <= width:
        gap = 1 if width // count >= 4 else 0
        runs = []
        for index, status in enumerate(statuses):
            x0 = index * width // count
            x1 = (index + 1) * width // count - gap
            if x1 > x0:
                runs.append((x0, x1, status))
        return runs
    runs: list[tuple[int, int, str]] = []
    for x in range(width):
        status = statuses[_column_stage(statuses, x, width)]
        if runs and runs[-1][2] == status and runs[-1][1] == x:
            runs[-1] = (runs[-1][0], x + 1, status)
        else:
            runs.append((x, x + 1, status))
    return runs


def _column_stage(statuses: Sequence[str], column: int, width: int) -> int:
    """With more stages than pixels, the stage whose status colours pixel `column`.

    The column stands for stages column * count // width up to (column + 1) * count // width and shows
    the first of them with the most important status.
    """
    count = len(statuses)
    first = column * count // width
    last = max(first + 1, (column + 1) * count // width)
    return max(range(first, last), key=lambda stage: _PRIORITY.get(statuses[stage], 0))


def timeline_index(x: float, width: int, count: int) -> int | None:
    """The stage that `timeline_runs` draws at `x`, or None off the bar.

    With more stages than pixels, a column stands for several stages and this gives the first of them.
    """
    if count <= 0 or width <= 0 or not 0 <= x < width:
        return None
    column = int(x)
    if count <= width:
        # Stage i starts at i * width // count, so a column belongs to the last stage starting at or before it.
        return ((column + 1) * count - 1) // width
    return column * count // width


def timeline_pick(x: float, width: int, statuses: Sequence[str]) -> int | None:
    """The stage to hover or click at `x`, or None off the bar.

    Like `timeline_index`, except that a column standing for several stages gives the stage whose
    status colours it. Pass the width the bar was drawn with.
    """
    index = timeline_index(x, width, len(statuses))
    if index is None or len(statuses) <= width:
        return index
    return _column_stage(statuses, int(x), width)


def label_spans(runs: Sequence[tuple[str, int]], width: int) -> list[tuple[int, int, str]]:
    total = sum(count for _label, count in runs)
    if total <= 0 or width <= 0:
        return []
    spans = []
    start = 0
    for label, count in runs:
        x0 = start * width // total
        start += count
        spans.append((x0, start * width // total, label))
    return spans


def map_cell_size(columns: int, rows: int, width: int, height: int, *, gap: int = 2,
                  maximum: int = 28, minimum: int = 2) -> int:
    if columns <= 0 or rows <= 0:
        return 0
    fit = min((width + gap) // columns, (height + gap) // rows) - gap
    return max(minimum, min(maximum, fit))


def map_cell_origin(index: int, columns: int, cell: int, gap: int = 2) -> tuple[int, int]:
    if columns <= 0:
        return 0, 0
    return (index % columns) * (cell + gap), (index // columns) * (cell + gap)


def map_index(x: float, y: float, columns: int, rows: int, cell: int, gap: int = 2) -> int | None:
    pitch = cell + gap
    if pitch <= 0 or cell <= 0 or x < 0 or y < 0:
        return None
    column, row = int(x // pitch), int(y // pitch)
    if column >= columns or row >= rows or x - column * pitch >= cell or y - row * pitch >= cell:
        return None
    return row * columns + column


def work_area_at(x: int, y: int, areas: Sequence[tuple[int, int, int, int]],
                 fallback: tuple[int, int, int, int]) -> tuple[int, int, int, int]:
    """The work area (left, top, right, bottom; right and bottom exclusive) that holds the point.

    A point in none of them, such as one over a taskbar, gets the nearest. `fallback` stands in when there
    are no areas: off Windows, or when the monitors can't be read.
    """
    if not areas:
        return fallback
    return min(areas, key=lambda area: _distance_squared(x, y, area))


def _distance_squared(x: int, y: int, area: tuple[int, int, int, int]) -> int:
    left, top, right, bottom = area
    dx = max(left - x, 0, x - (right - 1))
    dy = max(top - y, 0, y - (bottom - 1))
    return dx * dx + dy * dy


def tooltip_origin(pointer_x: int, pointer_y: int, width: int, height: int,
                   work_area: tuple[int, int, int, int]) -> tuple[int, int]:
    """Top-left corner for a `width` x `height` tooltip next to the pointer, inside `work_area`.

    The tooltip goes below and right of the pointer, moves left so it ends at the work area's right edge,
    and goes above the pointer when there is no room below, so it doesn't cover the pointer. A tooltip
    larger than the work area starts at its left or top edge.
    """
    left, top, right, bottom = work_area
    # 14 px right of and 18 px below the pointer clear the arrow cursor; nothing of it is above the pointer.
    x = max(left, min(pointer_x + 14, right - width))
    y = pointer_y + 18
    if y + height > bottom:
        y = pointer_y - 6 - height
    return x, max(top, min(y, bottom - height))


def fit_window(size: tuple[int, int], position: tuple[int, int] | None, work_area: tuple[int, int, int, int],
               frame: tuple[int, int, int, int], *,
               minimum: tuple[int, int] = (0, 0)) -> tuple[int, int, int, int]:
    """Client size and frame position (width, height, x, y) that keep a window inside `work_area`.

    `frame` is the border and title bar around the client area (left, top, right, bottom), and `position`
    is where the frame's top-left corner goes, or None to centre the window. The client grows to `minimum`,
    then shrinks until the framed window fits, and the window moves just far enough to lie inside the work
    area. A work area too small even for the frame gets a 1 px client at its top-left corner.
    """
    left, top, right, bottom = work_area
    frame_left, frame_top, frame_right, frame_bottom = frame
    width = max(1, min(max(size[0], minimum[0]), right - left - frame_left - frame_right))
    height = max(1, min(max(size[1], minimum[1]), bottom - top - frame_top - frame_bottom))
    outer_width, outer_height = frame_left + width + frame_right, frame_top + height + frame_bottom
    if position is None:
        position = (left + (right - left - outer_width) // 2, top + (bottom - top - outer_height) // 2)
    return (width, height, max(left, min(position[0], right - outer_width)),
            max(top, min(position[1], bottom - outer_height)))

"""Canvas widgets for the stage timeline and chunk map, plus tooltips and text fitting."""

from __future__ import annotations

import tkinter as tk
from tkinter import font as tkfont
from tkinter import ttk
from typing import Callable, Sequence

from . import win32
from .ui_geometry import (label_spans, map_cell_origin, map_cell_size, map_index, timeline_pick, timeline_runs,
                          tooltip_origin, work_area_at)
from .ui_theme import FONT, Palette, share_color, status_color
from .view_model import Cell, Segment

MAP_GAP = 2
MAP_TOP = 16
# Tooltip lines wrap at 270 pt: 360 px at 100% scaling, about 60 characters of the 9 pt text at any scaling.
TOOLTIP_WRAP = "270p"


def _strip_height(widget: tk.Misc, minimum: int) -> int:
    """Room for a line of the canvases' 8 pt text, which grows with display scaling, and at least `minimum`."""
    return max(minimum, tkfont.Font(root=widget, font=(FONT, 8)).metrics("linespace") + 3)


class Tooltip:
    """Shows text next to the pointer; the text can depend on the pointer position."""

    def __init__(self, widget: tk.Misc, text_at: Callable[[int, int], str | None], palette: Palette) -> None:
        self._widget = widget
        self._text_at = text_at
        self._palette = palette
        self._window: tk.Toplevel | None = None
        self._label: tk.Label | None = None
        self._areas: list[tuple[int, int, int, int]] = []
        widget.bind("<Motion>", self._move, add="+")
        widget.bind("<Leave>", lambda _event: self.hide(), add="+")
        widget.bind("<ButtonPress>", lambda _event: self.hide(), add="+")

    def _move(self, event: tk.Event) -> None:
        text = self._text_at(event.x, event.y)
        if not text:
            self.hide()
            return
        if self._window is None:
            self._window = tk.Toplevel(self._widget)
            self._window.wm_overrideredirect(True)
            self._window.attributes("-topmost", True)
            self._label = tk.Label(self._window, background=self._palette.surface, foreground=self._palette.text,
                                   relief="solid", borderwidth=1, padx=6, pady=3, font=(FONT, 9),
                                   justify="left", wraplength=TOOLTIP_WRAP)
            self._label.pack()
            # Read once per showing: monitors don't change while the pointer moves over one widget.
            self._areas = win32.screen_work_areas()
        assert self._label is not None
        if self._label.cget("text") != text:
            self._label.configure(text=text)
        # The window is exactly the label, whose requested size is up to date as soon as its text is set.
        screen = (0, 0, self._widget.winfo_screenwidth(), self._widget.winfo_screenheight())
        area = work_area_at(event.x_root, event.y_root, self._areas, screen)
        x, y = tooltip_origin(event.x_root, event.y_root, self._label.winfo_reqwidth(),
                              self._label.winfo_reqheight(), area)
        self._window.geometry(f"+{x}+{y}")

    def hide(self) -> None:
        if self._window is not None:
            self._window.destroy()
            self._window = None
            self._label = None


def fit_text(text: str, widget: ttk.Label, width: int) -> str:
    """Shorten `text` with an ellipsis so it fits in `width` pixels in the widget's font."""
    if width <= 20 or not text:
        return text
    try:
        style = str(widget.cget("style")) or "TLabel"
        font = tkfont.Font(root=widget, font=ttk.Style(widget).lookup(style, "font") or "TkDefaultFont")
    except tk.TclError:
        return text
    if font.measure(text) <= width:
        return text
    low, high = 0, len(text)
    while low < high:
        middle = (low + high + 1) // 2
        if font.measure(text[:middle] + "…") <= width:
            low = middle
        else:
            high = middle - 1
    return text[:low].rstrip() + "…"


class TimelineCanvas(tk.Canvas):
    BAR = 14
    LABELS = 16

    def __init__(self, master: tk.Misc, palette: Palette, *,
                 on_select: Callable[[int], None] | None = None,
                 on_hover: Callable[[int | None], None] | None = None) -> None:
        labels = _strip_height(master, self.LABELS)
        super().__init__(master, height=self.BAR + labels, highlightthickness=0,
                         background=palette.background, cursor="hand2" if on_select else "")
        self._labels_height = labels
        self._palette = palette
        self._on_select = on_select
        self._segments: tuple[Segment, ...] = ()
        self._statuses: tuple[str, ...] = ()
        self._labels: tuple[tuple[str, int], ...] = ()
        self._selected: int | None = None
        # Hover and click hit-test against the width of the last drawing, so they match what is on screen.
        self._width = 0
        self.bind("<Configure>", lambda _event: self._draw())
        if on_select is not None:
            self.bind("<Button-1>", self._click)
        if on_hover is not None:
            # Every move reports the stage a click there would select, or None; the receiver ignores repeats.
            self.bind("<Motion>", lambda event: on_hover(self._hover_at(event.x, event.y)), add="+")
            self.bind("<Leave>", lambda _event: on_hover(None), add="+")
        self._tooltip = Tooltip(self, self._tooltip_at, palette)

    def show(self, segments: Sequence[Segment], labels: Sequence[tuple[str, int]],
             selected: int | None = None) -> None:
        view = (tuple(segments), tuple(labels), selected)
        if view == (self._segments, self._labels, self._selected):
            return
        self._segments, self._labels, self._selected = view
        self._statuses = tuple(segment.status for segment in self._segments)
        self._draw()

    def _draw(self) -> None:
        self.delete("all")
        width = self.winfo_width()
        self._width = width if width > 1 else 0
        if not self._width:
            return
        for x0, x1, status in timeline_runs(self._statuses, width):
            self.create_rectangle(x0, 0, x1, self.BAR, fill=status_color(self._palette, status), width=0)
        count = len(self._segments)
        if self._selected is not None and 0 <= self._selected < count:
            x0 = self._selected * width // count
            x1 = max(x0 + 3, (self._selected + 1) * width // count)
            self.create_rectangle(x0, 0, x1 - 1, self.BAR - 1, outline=self._palette.text, width=1)
        for x0, _x1, label in label_spans(self._labels, width):
            self.create_line(x0, self.BAR + 2, x0, self.BAR + self._labels_height, fill=self._palette.border)
            self.create_text(x0 + 4, self.BAR + 2, text=label, anchor="nw", fill=self._palette.muted,
                             font=(FONT, 8))

    def _tooltip_at(self, x: int, y: int) -> str | None:
        if y > self.BAR:
            return None
        index = timeline_pick(x, self._width, self._statuses)
        return None if index is None else self._segments[index].tooltip

    def _hover_at(self, x: int, y: int) -> int | None:
        # While a button is held, moves outside the canvas still arrive here.
        if not 0 <= y < self.winfo_height():
            return None
        return timeline_pick(x, self._width, self._statuses)

    def _click(self, event: tk.Event) -> None:
        # This <Button-1> binding is more specific than the tooltip's <ButtonPress>, which Tk then skips.
        self._tooltip.hide()
        index = timeline_pick(event.x, self._width, self._statuses)
        if index is not None and self._on_select is not None:
            self._on_select(index)


class ChunkMapCanvas(tk.Canvas):
    def __init__(self, master: tk.Misc, palette: Palette) -> None:
        super().__init__(master, highlightthickness=0, background=palette.background, width=280, height=260)
        self._top = _strip_height(master, MAP_TOP)
        self._palette = palette
        self._view: tuple[int, int, tuple[Cell, ...], str] | None = None
        self._cell = 0
        self.bind("<Configure>", lambda _event: self._draw())
        Tooltip(self, self._tooltip_at, palette)

    def show(self, columns: int, rows: int, cells: Sequence[Cell], message: str) -> None:
        view = (columns, rows, tuple(cells), message)
        if view == self._view:
            return
        self._view = view
        self._draw()

    def _draw(self) -> None:
        self.delete("all")
        # Hover hit-tests only cells this drawing shows.
        self._cell = 0
        width, height = self.winfo_width(), self.winfo_height()
        if self._view is None or width <= 1:
            return
        columns, rows, cells, message = self._view
        if message or not cells:
            self.create_text(width // 2, height // 2, text=message or "No chunk data.", fill=self._palette.muted,
                             width=max(80, width - 20), justify="center", font=(FONT, 10))
            return
        self._cell = map_cell_size(columns, rows, width, height - self._top, gap=MAP_GAP)
        self.create_text(0, 0, text="N ↑", anchor="nw", fill=self._palette.muted, font=(FONT, 8))
        for index, cell in enumerate(cells):
            x, y = map_cell_origin(index, columns, self._cell, MAP_GAP)
            y += self._top
            if cell.status == "none":
                self.create_rectangle(x, y, x + self._cell - 1, y + self._cell - 1,
                                      outline=self._palette.border, width=1)
            else:
                fill = (share_color(self._palette, cell.share) if cell.status == "share" and cell.share is not None
                        else status_color(self._palette, cell.status))
                self.create_rectangle(x, y, x + self._cell, y + self._cell, fill=fill, width=0)

    def _tooltip_at(self, x: int, y: int) -> str | None:
        if self._view is None or not self._view[2] or self._cell <= 0:
            return None
        columns, rows, cells, _message = self._view
        index = map_index(x, y - self._top, columns, rows, self._cell, MAP_GAP)
        return None if index is None or index >= len(cells) else cells[index].tooltip

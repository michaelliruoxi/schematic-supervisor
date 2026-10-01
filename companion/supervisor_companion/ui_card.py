"""The compact always-on-top card."""

from __future__ import annotations

import tkinter as tk
from tkinter import ttk
from typing import Callable

from .ui_canvas import TimelineCanvas, Tooltip, fit_text
from .ui_theme import Palette
from .view_model import CardView

LINE_STYLES = {"muted": "Muted.TLabel", "warn": "Warn.TLabel", "error": "Error.TLabel"}
# At least 8 characters wide. The theme's default minimum of 11 makes Pause, Scan depots, and Expand
# 315 px wide at 100% scaling, more than the card's 296 px row, which cut off Expand.
BUTTON_WIDTH = -8
# The card's 12 px side padding plus the line label's 2 px border and padding, on both sides.
LINE_INSET = 28


class CardFrame(ttk.Frame):
    def __init__(self, master: tk.Misc, palette: Palette, *, on_control: Callable[[str], None],
                 on_expand: Callable[[int | None], None], on_pin: Callable[[], None]) -> None:
        super().__init__(master, padding=(12, 10, 12, 8))
        self._on_control = on_control
        self._toggle_action: str | None = None
        self._line_full = ""
        self._reasons = {"toggle": "", "scan": ""}
        self._rendered: tuple[CardView, bool] | None = None
        self.columnconfigure(0, weight=1)

        top = ttk.Frame(self)
        top.grid(row=0, column=0, sticky="ew")
        top.columnconfigure(1, weight=1)
        self.state_label = ttk.Label(top, text="Connecting…", style="Offline.Pill.TLabel")
        self.state_label.grid(row=0, column=0, sticky="w")
        self.status_label = ttk.Label(top, style="Muted.TLabel")
        self.status_label.grid(row=0, column=1, sticky="e", padx=(8, 6))
        self.pin_button = ttk.Button(top, text="Unpin", style="Small.TButton", command=on_pin, width=6)
        self.pin_button.grid(row=0, column=2, sticky="e")

        middle = ttk.Frame(self)
        middle.grid(row=1, column=0, sticky="ew", pady=(6, 0))
        middle.columnconfigure(1, weight=1)
        self.percent_label = ttk.Label(middle, text="—", style="Big.TLabel")
        self.percent_label.grid(row=0, column=0, sticky="w")
        self.suffix_label = ttk.Label(middle, style="Muted.TLabel")
        self.suffix_label.grid(row=0, column=1, sticky="sw", padx=(6, 0), pady=(0, 5))
        self.verify_label = ttk.Label(middle, style="Muted.TLabel")
        self.verify_label.grid(row=0, column=2, sticky="se", pady=(0, 5))

        self.timeline = TimelineCanvas(self, palette, on_select=on_expand)
        self.timeline.grid(row=2, column=0, sticky="ew", pady=(6, 0))
        self.line_label = ttk.Label(self, style="Muted.TLabel")
        self.line_label.grid(row=3, column=0, sticky="ew", pady=(4, 6))
        Tooltip(self.line_label, lambda _x, _y: (self._line_full
                                                 if self.line_label.cget("text") != self._line_full else None),
                palette)

        buttons = ttk.Frame(self)
        buttons.grid(row=4, column=0, sticky="ew")
        buttons.columnconfigure(2, weight=1)
        self.toggle_button = ttk.Button(buttons, text="Pause", command=self._toggle, width=BUTTON_WIDTH)
        self.toggle_button.grid(row=0, column=0, sticky="w")
        self.scan_button = ttk.Button(buttons, text="Scan depots", command=lambda: on_control("SCAN_DEPOTS"),
                                      width=BUTTON_WIDTH)
        self.scan_button.grid(row=0, column=1, sticky="w", padx=(6, 0))
        ttk.Button(buttons, text="Expand", command=lambda: on_expand(None), width=BUTTON_WIDTH).grid(
            row=0, column=3, sticky="e")
        Tooltip(self.toggle_button, lambda _x, _y: self._reasons["toggle"] or None, palette)
        Tooltip(self.scan_button, lambda _x, _y: self._reasons["scan"] or None, palette)
        self.bind("<Configure>", lambda _event: self._fit_line(), add="+")

    def render(self, view: CardView, *, pinned: bool) -> None:
        if (view, pinned) == self._rendered:
            return
        self._rendered = (view, pinned)
        self.state_label.configure(text=view.state_label, style=f"{view.tone.title()}.Pill.TLabel")
        self.status_label.configure(text=view.status_text,
                                    style="Warn.TLabel" if view.status_tone == "warn" else "Muted.TLabel")
        self.pin_button.configure(text="Unpin" if pinned else "Pin")
        self.percent_label.configure(text=view.percent_text,
                                     style="BigMuted.TLabel" if view.stale else "Big.TLabel")
        # The percentage's approximation sign already conveys this; leave room for "last known".
        self.suffix_label.configure(text=view.percent_suffix.replace(" (approximate)", ""))
        self.verify_label.configure(text=view.verify_text)
        self.timeline.show(view.segments, view.kind_labels)
        self._line_full = view.line_text
        self.line_label.configure(style=LINE_STYLES.get(view.line_tone, "Muted.TLabel"))
        self._fit_line()
        if view.toggle is None:
            self.toggle_button.grid_remove()
            self._toggle_action = None
            self._reasons["toggle"] = ""
        else:
            self.toggle_button.grid()
            self._toggle_action = view.toggle.action
            self.toggle_button.configure(text=view.toggle.label,
                                         state="normal" if view.toggle.enabled else "disabled")
            self._reasons["toggle"] = view.toggle.reason
        self.scan_button.configure(state="normal" if view.scan.enabled else "disabled")
        self._reasons["scan"] = view.scan.reason

    def _fit_line(self) -> None:
        self.line_label.configure(text=fit_text(self._line_full, self.line_label, self.winfo_width() - LINE_INSET))

    def _toggle(self) -> None:
        if self._toggle_action is not None:
            self._on_control(self._toggle_action)

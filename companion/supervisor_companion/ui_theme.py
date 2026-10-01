"""Colors and ttk styles for the monitor, in light and dark variants."""

from __future__ import annotations

from dataclasses import dataclass
import tkinter as tk
from tkinter import ttk

TONES = ("ok", "warn", "error", "done", "idle", "offline")
FONT = "Segoe UI"


@dataclass(frozen=True)
class Palette:
    background: str
    surface: str
    text: str
    muted: str
    border: str
    ok: str
    warn: str
    error: str
    done: str
    idle: str
    ok_bg: str
    warn_bg: str
    error_bg: str
    done_bg: str
    idle_bg: str
    segment_done: str
    segment_partial: str
    segment_current: str
    segment_todo: str
    share_low: str
    share_high: str


LIGHT = Palette(background="#f7f8fa", surface="#ffffff", text="#202834", muted="#5f6b7a", border="#d5dae1",
                ok="#1f7a45", warn="#8a5310", error="#b42318", done="#1f5fa8", idle="#5f6b7a",
                ok_bg="#e3f4ea", warn_bg="#fbefdc", error_bg="#fde8e6", done_bg="#e3eefb", idle_bg="#eceff3",
                segment_done="#2e9e5b", segment_partial="#9fd8b4", segment_current="#2f6fdb",
                segment_todo="#d5dae1", share_low="#e3f4ea", share_high="#1f7a45")
DARK = Palette(background="#1d2126", surface="#262b31", text="#e6e9ee", muted="#9aa4b1", border="#3f4650",
               ok="#6fd39a", warn="#f0b45a", error="#f28b82", done="#8ab4f8", idle="#a3adb9",
               ok_bg="#1f3a2b", warn_bg="#3d2f17", error_bg="#43201d", done_bg="#1d2f4a", idle_bg="#30363e",
               segment_done="#4fbf7f", segment_partial="#2e5f45", segment_current="#6ea0f7",
               segment_todo="#3f4650", share_low="#23392c", share_high="#6fd39a")


def tone_colors(palette: Palette, tone: str) -> tuple[str, str]:
    """Foreground and background for a state label."""
    return {
        "ok": (palette.ok, palette.ok_bg), "warn": (palette.warn, palette.warn_bg),
        "error": (palette.error, palette.error_bg), "done": (palette.done, palette.done_bg),
    }.get(tone, (palette.idle, palette.idle_bg))


def status_color(palette: Palette, status: str) -> str:
    return {"done": palette.segment_done, "partial": palette.segment_partial,
            "current": palette.segment_current}.get(status, palette.segment_todo)


def share_color(palette: Palette, share: float) -> str:
    """Blend from the low-share color to the high-share color."""
    share = min(1.0, max(0.0, share))
    low = [int(palette.share_low[index:index + 2], 16) for index in (1, 3, 5)]
    high = [int(palette.share_high[index:index + 2], 16) for index in (1, 3, 5)]
    return "#" + "".join(f"{round(start + (end - start) * share):02x}" for start, end in zip(low, high))


def apply_styles(root: tk.Misc, palette: Palette) -> ttk.Style:
    style = ttk.Style(root)
    if "clam" in style.theme_names():
        style.theme_use("clam")
    root.configure(background=palette.background)
    # Classic widgets need an option default; themed labels inherit their own ttk styles.
    for widget_class in ("Label", "Menu"):
        root.option_add(f"*{widget_class}.Font", f"{{{FONT}}} 10")
    style.configure(".", font=(FONT, 10), background=palette.background, foreground=palette.text)
    style.configure("TFrame", background=palette.background)
    style.configure("TLabel", background=palette.background, foreground=palette.text)
    style.configure("Muted.TLabel", foreground=palette.muted)
    style.configure("Warn.TLabel", foreground=palette.warn)
    style.configure("Error.TLabel", foreground=palette.error)
    style.configure("Section.TLabel", font=(FONT, 10, "bold"))
    style.configure("Big.TLabel", font=(FONT, 20, "bold"))
    style.configure("BigMuted.TLabel", font=(FONT, 20, "bold"), foreground=palette.muted)
    for tone in TONES:
        foreground, background = tone_colors(palette, tone)
        style.configure(f"{tone.title()}.Pill.TLabel", foreground=foreground, background=background,
                        padding=(8, 2), font=(FONT, 9, "bold"))
    style.configure("TButton", padding=(10, 4), background=palette.surface, foreground=palette.text,
                    bordercolor=palette.border, lightcolor=palette.surface, darkcolor=palette.surface)
    style.map("TButton", background=[("disabled", palette.background), ("active", palette.border)],
              foreground=[("disabled", palette.muted)])
    style.configure("Small.TButton", padding=(6, 1))
    style.configure("TMenubutton", background=palette.surface, foreground=palette.text)
    style.configure("TRadiobutton", background=palette.background, foreground=palette.text)
    style.map("TRadiobutton", background=[("active", palette.background)])
    style.configure("TNotebook", background=palette.background, borderwidth=0)
    style.configure("TNotebook.Tab", padding=(14, 6), background=palette.background, foreground=palette.text)
    style.map("TNotebook.Tab", background=[("selected", palette.surface)])
    style.configure("Treeview", background=palette.surface, fieldbackground=palette.surface,
                    foreground=palette.text, rowheight=26, bordercolor=palette.border)
    style.configure("Treeview.Heading", font=(FONT, 10, "bold"), background=palette.background,
                    foreground=palette.text)
    return style

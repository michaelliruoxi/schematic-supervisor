"""Native desktop window: a compact always-on-top card and an expanded full view."""

from __future__ import annotations

from datetime import datetime, timezone
import logging
import os
from pathlib import Path
import re
import tkinter as tk
from tkinter import messagebox
from typing import Any, Mapping, Protocol, Sequence

from . import __version__, win32
from .alerts import Alert, AttentionTracker
from .logging_setup import ThrottledExceptionLog, tk_callback_error_handler
from .notify import PhoneNotifier, alert_text
from .ticker import SafeTicker
from .ui_card import CardFrame
from .ui_full import FullFrame
from .ui_geometry import fit_window, work_area_at
from .ui_state import default_compact_position, is_visible, load_ui_state, save_ui_state
from .ui_theme import DARK, LIGHT, apply_styles
from .view_model import build_card, build_full

COMPACT_SIZE = (320, 210)
FULL_SIZE = (960, 720)
FULL_MINIMUM = (720, 560)
# The Windows 10/11 frame around a window's client area at 100% scaling, measured: 8 px borders and a 31 px title
# bar. Tk sizes the client area and places the frame, so the full window keeps room for the frame too.
WINDOW_FRAME = (8, 31, 8, 8)
STOP_CONFIRMATION = ("Stop ends this run. Placed blocks are kept: the next Start checks what is already "
                     "built and continues from the first unfinished piece.\n\nStop now?")
BANNER_TEXT = "Display error; details are in the log"
_GEOMETRY = re.compile(r"^(\d+)x(\d+)\+(-?\d+)\+(-?\d+)$")


class MonitorSource(Protocol):
    def refresh(self) -> None: ...
    def send_control(self, action: str, *, observation: Mapping[str, Any] | None = None) -> bool: ...
    def snapshot(self) -> dict[str, Any]: ...
    def note_event(self, message: str) -> None: ...


class MonitorApp:
    """Owns the Tk root: switches layouts, refreshes views, raises alerts, remembers placement."""

    def __init__(self, root: tk.Tk, service: MonitorSource, *, token_file: Path | None,
                 state_paths: Sequence[Path], logger: logging.Logger, log_directory: Path,
                 notifier: PhoneNotifier | None = None) -> None:
        self.root = root
        self.service = service
        self.notifier = notifier
        self.token_file = token_file
        self.logger = logger
        self.log_directory = log_directory
        self._state_paths = list(state_paths)
        self.state = load_ui_state(self._state_paths)
        self.palette = LIGHT if win32.apps_use_light_theme() else DARK
        apply_styles(root, self.palette)
        self._scale = max(1.0, root.winfo_fpixels("1i") / 96.0)
        # Render failures and Tk callback errors share one throttle, so a repeating error is logged once a minute.
        self._errors = ThrottledExceptionLog(logger)
        self.attention = AttentionTracker()
        self.selected_stage: int | None = None
        # The stage under the pointer on the full window's timeline; the map shows it until the pointer leaves.
        self.previewed_stage: int | None = None
        self.map_mode = "stage"
        self._observation: Mapping[str, Any] = {}
        self._closed = False
        self.card = CardFrame(root, self.palette, on_control=self.control, on_expand=self.expand,
                              on_pin=self.toggle_pin)
        self.full = FullFrame(root, self.palette, on_control=self.control, on_compact=self.compact,
                              on_select_stage=self.select_stage, on_preview_stage=self.preview_stage,
                              on_follow_current=self.follow_current, on_map_mode=self.set_map_mode,
                              on_refresh=service.refresh)
        self._build_menu()
        self.banner = tk.Label(root, text=BANNER_TEXT, background=self.palette.error_bg,
                               foreground=self.palette.error)
        root.protocol("WM_DELETE_WINDOW", self.close)
        # Tk sends this when Windows signs out or shuts down, which can end the process without a close.
        root.protocol("WM_SAVE_YOURSELF", self.save_for_session_end)
        root.bind("<F5>", lambda _event: service.refresh())
        root.report_callback_exception = tk_callback_error_handler(self._errors)
        # This also shows the window and gives it the dark title bar in dark mode.
        self._apply_mode(self.state.mode, remember=False)
        # The ticker's callbacks are the only ones the app schedules; close() cancels them through ticker.stop().
        self.ticker = SafeTicker(self._refresh, lambda delay, callback: root.after(delay, callback),
                                 interval_ms=250, logger=logger, on_failure_change=self._show_banner,
                                 cancel=root.after_cancel, errors=self._errors)
        self.ticker.tick()

    # Layout

    def expand(self, stage: int | None = None) -> None:
        if stage is not None:
            self.selected_stage = stage
        if self.state.mode != "full":
            self._apply_mode("full")
        self._refresh_now()

    def compact(self) -> None:
        if self.state.mode != "compact":
            self._apply_mode("compact")
        self._refresh_now()

    def _apply_mode(self, mode: str, *, remember: bool = True) -> None:
        # Tk ignores wm geometry while the window is maximized, and the geometry to remember is the normal one.
        self._restore_if_maximized()
        if remember:
            self._remember_geometry()
        self.state.mode = mode
        # A hidden timeline gets no pointer events, so a preview could otherwise outlast the layout.
        self.previewed_stage = None
        self.card.pack_forget()
        self.full.pack_forget()
        screens = win32.screen_work_areas() or [win32.primary_work_area()]
        if mode == "compact":
            width, height = self._scaled(COMPACT_SIZE)
            position = self.state.compact_position
            if position is None or not is_visible(position, (width, height), screens):
                position = default_compact_position(win32.primary_work_area(), (width, height))
            self.root.minsize(width, height)
            self.root.geometry(f"{width}x{height}+{position[0]}+{position[1]}")
            self.card.pack(fill="both", expand=True)
            resizable, on_top = False, self.state.pinned
        else:
            saved = self.state.full_geometry
            frame = tuple(round(side * self._scale) for side in WINDOW_FRAME)
            minimum = self._scaled(FULL_MINIMUM)
            if saved is not None and is_visible((saved[2], saved[3]), (saved[0], saved[1]), screens):
                # It stays on the monitor it was on, inside that monitor's work area.
                area = work_area_at(saved[2] + saved[0] // 2, saved[3] + saved[1] // 2, screens,
                                    win32.primary_work_area())
                geometry = fit_window((saved[0], saved[1]), (saved[2], saved[3]), area, frame, minimum=minimum)
            else:
                geometry = fit_window(self._scaled(FULL_SIZE), None, win32.primary_work_area(), frame,
                                      minimum=minimum)
            # On a work area smaller than the minimum size, the minimum must not push the window past it.
            self.root.minsize(min(minimum[0], geometry[0]), min(minimum[1], geometry[1]))
            self.root.geometry(f"{geometry[0]}x{geometry[1]}+{geometry[2]}+{geometry[3]}")
            self.full.pack(fill="both", expand=True)
            resizable, on_top = True, self.state.full_on_top
        # Move first: on Windows, changing resizable or topmost on a shown window drops a pending move.
        self.root.update_idletasks()
        self.root.resizable(resizable, resizable)
        self.root.attributes("-topmost", on_top)
        # Changing resizable rebuilds the frame window on Windows, and a new frame starts with a light title bar.
        self._apply_title_bar()
        if remember:
            self._save_state()

    def _restore_if_maximized(self) -> None:
        if self.root.state() == "zoomed":
            self.root.state("normal")
            self.root.update_idletasks()

    def _apply_title_bar(self) -> None:
        if self.palette is DARK:
            win32.use_dark_title_bar(self._hwnd())

    def _remember_geometry(self) -> None:
        self.root.update_idletasks()
        match = _GEOMETRY.match(self.root.geometry())
        if match is None:
            return
        width, height, x, y = (int(value) for value in match.groups())
        if self.state.mode == "compact":
            self.state.compact_position = (x, y)
        else:
            self.state.full_geometry = (width, height, x, y)

    def _scaled(self, size: tuple[int, int]) -> tuple[int, int]:
        return round(size[0] * self._scale), round(size[1] * self._scale)

    def _save_state(self) -> None:
        if save_ui_state(self._state_paths, self.state) is None:
            self.logger.warning("Window settings could not be saved")

    # Actions

    def toggle_pin(self) -> None:
        self.state.pinned = not self.state.pinned
        if self.state.mode == "compact":
            self.root.attributes("-topmost", self.state.pinned)
        self._save_state()
        self._refresh_now()

    def select_stage(self, index: int) -> None:
        self.selected_stage = index
        self._refresh_now()

    def preview_stage(self, index: int | None) -> None:
        """Show the hovered stage's chunks, or with None, the selected or current stage's again."""
        if index == self.previewed_stage:
            return
        self.previewed_stage = index
        self._refresh_now()

    def follow_current(self) -> None:
        self.selected_stage = None
        self._refresh_now()

    def set_map_mode(self, mode: str) -> None:
        self.map_mode = "all" if mode == "all" else "stage"
        self._refresh_now()

    def control(self, action: str) -> None:
        if action == "STOP" and not messagebox.askyesno("Stop the build?", STOP_CONFIRMATION, parent=self.root):
            return
        self.service.send_control(action, observation=dict(self._observation))
        self._refresh_now()

    # Refresh and alerts

    def _refresh_now(self) -> None:
        try:
            self._refresh()
        except Exception as error:
            self._errors.exception("Display refresh failed", error)

    def _refresh(self) -> None:
        if self._closed:
            return
        snapshot = self.service.snapshot()
        observation = snapshot.get("observation")
        self._observation = observation if isinstance(observation, dict) else {}
        now = datetime.now(timezone.utc)
        card = build_card(snapshot, now=now)
        if self.root.title() != card.title:
            self.root.title(card.title)
        state = self._observation.get("state")
        control = self._observation.get("last_control")
        alert = self.attention.update(
            online=snapshot.get("fresh") is True,
            state=state if isinstance(state, str) else None,
            last_action=control.get("action") if isinstance(control, dict) else None,
            last_request_id=control.get("request_id") if isinstance(control, dict) else None,
            own_request_ids=snapshot.get("own_request_ids") or ())
        if alert is not None:
            self._alert(alert)
        if self.state.mode == "compact":
            self.card.render(card, pinned=self.state.pinned)
        else:
            self.full.render(build_full(snapshot, now=now, selected_stage=self.selected_stage,
                                        map_mode=self.map_mode, preview_stage=self.previewed_stage), snapshot)

    def _alert(self, alert: Alert) -> None:
        self.logger.info("Alert: %s", alert.message)
        self.service.note_event(f"Alert · {alert.message}")
        win32.flash_window(self._hwnd())
        if self.state.sound:
            win32.beep()
        if self.notifier is not None and self.notifier.enabled:
            title, body = alert_text(alert, self._observation)
            if self.notifier.notify(title, body):
                self.service.note_event("Phone notification sent")

    def _hwnd(self) -> int:
        return win32.toplevel_handle(self.root.winfo_id())

    def _show_banner(self, failed: bool) -> None:
        if failed:
            # Packed ahead of the layout, which could otherwise take all of a window too small for both.
            self.banner.pack(side="bottom", fill="x", before=self._layout())
        else:
            self.banner.pack_forget()

    def _layout(self) -> CardFrame | FullFrame:
        return self.card if self.state.mode == "compact" else self.full

    # Menu

    def _build_menu(self) -> None:
        menu = tk.Menu(self.full.menu_button, tearoff=False)
        self._sound = tk.BooleanVar(master=self.root, value=self.state.sound)
        self._on_top = tk.BooleanVar(master=self.root, value=self.state.full_on_top)
        menu.add_command(label="Open log folder", command=self._open_logs)
        menu.add_checkbutton(label="Sound on attention", variable=self._sound, command=self._toggle_sound)
        menu.add_checkbutton(label="Keep full window on top", variable=self._on_top, command=self._toggle_on_top)
        menu.add_command(label="Reset card position", command=self._reset_card_position)
        menu.add_separator()
        menu.add_command(label="About", command=self._about)
        self.full.menu_button.configure(menu=menu)

    def _open_logs(self) -> None:
        try:
            os.startfile(str(self.log_directory))  # type: ignore[attr-defined]
        except (AttributeError, OSError) as error:
            self.logger.warning("Could not open the log folder: %s", error)
            messagebox.showinfo("Log folder", f"Logs are in {self.log_directory}", parent=self.root)

    def _toggle_sound(self) -> None:
        self.state.sound = bool(self._sound.get())
        self._save_state()

    def _toggle_on_top(self) -> None:
        self.state.full_on_top = bool(self._on_top.get())
        if self.state.mode == "full":
            self.root.attributes("-topmost", self.state.full_on_top)
        self._save_state()

    def _reset_card_position(self) -> None:
        self.state.compact_position = None
        self._save_state()

    def _about(self) -> None:
        snapshot = self.service.snapshot()
        observation = snapshot.get("observation") if isinstance(snapshot.get("observation"), dict) else {}
        if self.token_file is None:
            pairing = "Pairing file: not set"
        else:
            found = "found" if self.token_file.is_file() else "missing"
            pairing = f"Pairing file: {self.token_file} ({found})"
        messagebox.showinfo("About Schematic Supervisor", "\n".join((
            f"Monitor version {__version__}", "Mod protocol version 1",
            f"Plan: {observation.get('plan_id') or 'none loaded'}", pairing,
            f"Logs: {self.log_directory}")), parent=self.root)

    # Lifecycle

    def save_for_session_end(self) -> None:
        """Remember the placement and settings, and leave the window open: the session end can be cancelled."""
        if self._closed:
            return
        self.logger.info("Windows is ending the session; saving the window settings")
        try:
            # A maximized window keeps its last remembered normal size; restoring it here would show.
            if self.root.state() != "zoomed":
                self._remember_geometry()
        except tk.TclError as error:
            self.logger.warning("The window placement couldn't be read at the session end: %s", error)
        self._save_state()

    def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        try:
            self._restore_if_maximized()
            self._remember_geometry()
        except tk.TclError as error:
            # The other settings are saved all the same, with the last placement the window remembered.
            self.logger.warning("The window placement couldn't be read on closing: %s", error)
        self._save_state()
        self.ticker.stop()
        self.root.destroy()


def run_window(service: MonitorSource, *, token_file: Path | None, state_paths: Sequence[Path],
               logger: logging.Logger, log_directory: Path, notifier: PhoneNotifier | None = None) -> None:
    root = tk.Tk()
    try:
        MonitorApp(root, service, token_file=token_file, state_paths=state_paths, logger=logger,
                   log_directory=log_directory, notifier=notifier)
    except BaseException:
        # The caller reports the failure; a half-built window would sit behind its message.
        root.destroy()
        raise
    root.mainloop()

"""The expanded window: header, attention, project map, controls, and detail tabs."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
import json
import math
from pathlib import Path
import tkinter as tk
from tkinter import filedialog, messagebox, ttk
from typing import Any, Callable, Mapping

from .progress import Progress
from .ui_canvas import ChunkMapCanvas, TimelineCanvas, Tooltip
from .ui_theme import FONT, Palette
from .view_model import AttentionItem, FullView, build_check, problem_text

ATTENTION_STYLES = {"error": "Error.TLabel", "warn": "Warn.TLabel", "muted": "Muted.TLabel"}
CONTROL_ACTIONS = (("START", "Start"), ("PAUSE", "Pause"), ("RESUME", "Continue"), ("STOP", "Stop"),
                   ("SCAN_DEPOTS", "Scan depots"))
PACE_TOOLTIP = "Blocks placed, tilled, or planted per minute."
PLACED_NOTE = "Placed and Remaining count finished chunks, like the % built."
LEDGER_NOTE = ("Used and Remaining are ledger counts since the last Start. "
               "Update the mod to count placed materials.")


def _mapping(value: Any) -> Mapping[str, Any]:
    return value if isinstance(value, dict) else {}


def _text(value: Any, fallback: str = "—") -> str:
    if value is None or value == "":
        return fallback
    if isinstance(value, bool):
        return "Yes" if value else "No"
    return str(value)


def _number(value: Any) -> str:
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        try:
            if math.isfinite(value):
                return f"{value:,.0f}" if float(value).is_integer() else f"{value:,.1f}"
        except (OverflowError, ValueError):  # an integer too large to show as a float
            pass
    return "—"


def _total(values: Mapping[str, Any]) -> int | float | None:
    try:
        return sum(value for value in values.values()
                   if isinstance(value, (int, float)) and not isinstance(value, bool))
    except (OverflowError, ValueError):
        return None


def _name(value: Any) -> str:
    return _text(value).replace("minecraft:", "").replace("_", " ").capitalize()


def _position(value: Any) -> str:
    values = _mapping(value)
    if not all(axis in values for axis in ("x", "y", "z")):
        return "—"
    return " / ".join(_number(values[axis]) for axis in ("x", "y", "z"))


def _time(value: Any) -> str:
    if not value:
        return "—"
    try:
        return datetime.fromisoformat(str(value).replace("Z", "+00:00")).astimezone().strftime("%H:%M:%S")
    except (TypeError, ValueError, OverflowError):
        return str(value)


def safe_observation(value: Any) -> Any:
    """Keep exported telemetry independent of connection credentials."""
    if isinstance(value, dict):
        return {str(key): safe_observation(item) for key, item in value.items()
                if str(key) == "control_token_configured"
                or not any(word in str(key).lower() for word in ("token", "password", "secret", "authorization"))}
    if isinstance(value, list):
        return [safe_observation(item) for item in value]
    return value


@dataclass(frozen=True)
class MaterialTable:
    """The Materials tab. Each row is (material ID, cell texts, short of it); rows are sorted by ID."""

    placed: bool
    rows: tuple[tuple[str, tuple[str, ...], bool], ...]
    note: str


def material_table(snapshot: Mapping[str, Any]) -> MaterialTable:
    """On hand and Shortfall come from the observation. Planned, Placed, and Remaining come from the progress:
    items in finished pieces, counted like the % built. Older mods count only what the run used since the last
    Start (the material ledger), so the table shows that as Used."""
    observation = _mapping(snapshot.get("observation"))
    stock = _mapping(observation.get("materials"))
    ledger = _mapping(observation.get("material_ledger"))
    progress = snapshot.get("progress")
    counts = progress.materials if isinstance(progress, Progress) else None
    placed_counts = counts is not None or not (isinstance(progress, Progress)
                                               or snapshot.get("progress_status") == "unsupported")
    if counts is not None:
        planned = {count.name: count.planned for count in counts}
        placed = {count.name: count.done for count in counts}
        remaining = {count.name: count.remaining for count in counts}
    elif placed_counts:
        # No progress to count with yet: the plan's totals are known, what is placed is not.
        planned, placed, remaining = _mapping(ledger.get("planned")), {}, {}
    else:
        planned, placed, remaining = (_mapping(ledger.get(key)) for key in ("planned", "consumed", "remaining_plan"))
    rows = []
    for material in sorted(key for key in set(stock) | set(planned) | set(placed) | set(remaining)
                           if isinstance(key, str) and key):
        held = _mapping(stock.get(material))
        used = placed.get(material)
        if used is None and not placed_counts:
            used = 0  # The ledger lists only materials this run has used.
        missing = held.get("missing")
        rows.append((material, (_name(material), _number(held.get("available", 0)), _number(held.get("missing", 0)),
                                _number(planned.get(material)), _number(used), _number(remaining.get(material))),
                     isinstance(missing, (int, float)) and not isinstance(missing, bool) and missing > 0))
    return MaterialTable(placed_counts, tuple(rows), PLACED_NOTE if placed_counts else LEDGER_NOTE)


def status_details(observation: Any, progress: Any = None) -> str:
    """Everything the old Overview showed, as plain text: the mod's messages, place in the plan, execution,
    movement, operations, recovery, verification, world, material use, player, and inventory. Material use
    counts placed items from the progress when the mod sends them, else the run's material ledger."""
    observation = _mapping(observation)
    if not observation:
        return "No observation received yet."
    lines = []
    if observation.get("last_message"):
        lines.append("Last message: " + str(observation["last_message"]))
    if observation.get("last_error"):
        lines.append("Last error: " + str(observation["last_error"]))
    blockers = observation.get("blockers")
    if isinstance(blockers, list):
        lines.extend("Blocker: " + str(blocker) for blocker in blockers if blocker not in (None, ""))
    check = build_check(observation)
    if check.get("summary"):
        finished = f" (finished {_time(check['finished_at'])})" if check.get("finished_at") else ""
        lines.append(f"{check['summary']}{finished}")
        problems = check.get("problems") if isinstance(check.get("problems"), list) else []
        for problem in problems:
            text = problem_text(problem)
            if text:
                lines.append("  Needs attention: " + text)
    if observation.get("planting_deferred"):
        lines.append(f"Planting deferred: {_number(observation.get('deferred_seed_cells'))} seed cells.")
    if observation.get("phase"):
        lines.append("Phase: " + _name(observation["phase"]))
    layer = _mapping(observation.get("current_layer"))
    if layer:
        lines.append(f"Layer: {_number(layer.get('index'))} of {_number(layer.get('total'))} · "
                     f"Y {_number(layer.get('y'))}")
    chunk = _mapping(observation.get("current_chunk"))
    if chunk:
        lines.append(f"Chunk: {_number(chunk.get('index'))} of {_number(chunk.get('total'))}")
    execution = _mapping(observation.get("execution"))
    if execution:
        lines.append("Execution: " + _name(execution.get("mode")))
        if execution.get("detail"):
            lines.append(str(execution["detail"]))
        if execution.get("target"):
            target = _mapping(execution["target"])
            lines.append("Target: " + _position(target) + " · " + _text(target.get("expected_block")))
        for key, label in (("error", "Execution error"), ("flight_status", "Flight")):
            if execution.get(key):
                lines.append(f"{label}: {execution[key]}")
    if observation.get("baritone_status"):
        lines.append("Movement: " + str(observation["baritone_status"]))
    for key, label in (("shop", "Dirt shop"), ("material_shop", "Material shop"),
                       ("moss_deposit", "Moss deposit"), ("depots", "Depots")):
        operation = _mapping(observation.get(key))
        # A tuple compares with ==, so an unhashable value can't raise here.
        depot_active = key == "depots" and (operation.get("active_depot_id") or operation.get("queued_depot_ids")
                                            or operation.get("operation") not in (None, "", "IDLE", "NONE"))
        if operation and (operation.get("active") or operation.get("pending") or operation.get("blocked")
                          or operation.get("error") or depot_active):
            lines.append(label + ": " + _name(operation.get("stage") or operation.get("state")) + " · "
                         + _text(operation.get("detail") or operation.get("waiting_reason")
                                 or operation.get("error"), ""))
    lines.append("Recovery: " + _name(observation.get("recovery_stage")))
    verification = "Verification: " + _name(observation.get("verification_stage"))
    passes = _number(observation.get("stable_verification_passes"))
    if passes != "—":
        verification += f" · {passes} stable passes"
    lines.append(verification)
    world = "Connected" if observation.get("world_connected") is True else "Disconnected"
    if observation.get("world_connected") is True and observation.get("context_matches") is False:
        world += " · context mismatch"
    lines.append("World: " + world)
    counts = progress.materials if isinstance(progress, Progress) else None
    ledger = _mapping(observation.get("material_ledger"))
    planned = _mapping(ledger.get("planned"))
    if counts:
        lines.append(f"Materials placed: {_number(sum(count.done for count in counts))} / "
                     f"{_number(sum(count.planned for count in counts))} planned")
    elif planned:
        lines.append(f"Material use: {_number(_total(_mapping(ledger.get('consumed'))))} / "
                     f"{_number(_total(planned))} planned")
    player = _mapping(observation.get("player"))
    if player:
        lines.append(f"Player XYZ: {_position(player)} · Health: {_number(player.get('health'))} · "
                     f"Hunger: {_number(player.get('hunger'))}" + (" · Flying" if player.get("flying") else ""))
    else:
        lines.append("Player telemetry unavailable")
    inventory = _mapping(observation.get("inventory"))
    if inventory.get("available"):
        lines.append(f"Empty inventory slots: {_number(inventory.get('empty_main_slots'))} · "
                     f"Selected hotbar slot: {_number(inventory.get('selected_hotbar_slot'))}")
    else:
        lines.append(_text(inventory.get("error"), "Inventory telemetry unavailable"))
    return "\n".join(lines)


class FullFrame(ttk.Frame):
    def __init__(self, master: tk.Misc, palette: Palette, *, on_control: Callable[[str], None],
                 on_compact: Callable[[], None], on_select_stage: Callable[[int], None],
                 on_preview_stage: Callable[[int | None], None], on_follow_current: Callable[[], None],
                 on_map_mode: Callable[[str], None], on_refresh: Callable[[], None]) -> None:
        super().__init__(master, padding=(18, 12, 18, 10))
        self._palette = palette
        self._reasons: dict[str, str] = {}
        self._attention_rendered: tuple[AttentionItem, ...] | None = None
        self._rendered_tabs: tuple[str, MaterialTable] | None = None
        self._observation: Mapping[str, Any] = {}
        self.columnconfigure(0, weight=1)
        self.rowconfigure(4, weight=3)
        self.rowconfigure(7, weight=2)
        self._build_header(on_compact)
        self.attention_frame = ttk.Frame(self)
        self.attention_frame.grid(row=3, column=0, sticky="ew", pady=(6, 0))
        self.attention_frame.columnconfigure(0, weight=1)
        self._build_project(on_select_stage, on_preview_stage, on_follow_current, on_map_mode)
        self._build_controls(on_control, on_refresh)
        self._build_tabs()
        # Attention lines are built before the frame has its final width, and the window can be resized.
        self.bind("<Configure>", lambda _event: self._wrap_attention(), add="+")

    def _build_header(self, on_compact: Callable[[], None]) -> None:
        header = ttk.Frame(self)
        header.grid(row=0, column=0, sticky="ew")
        header.columnconfigure(4, weight=1)
        self.state_label = ttk.Label(header, text="Connecting…", style="Offline.Pill.TLabel")
        self.state_label.grid(row=0, column=0, sticky="w")
        self.percent_label = ttk.Label(header, text="—", style="Big.TLabel")
        self.percent_label.grid(row=0, column=1, sticky="w", padx=(12, 0))
        self.suffix_label = ttk.Label(header, style="Muted.TLabel")
        self.suffix_label.grid(row=0, column=2, sticky="sw", padx=(6, 0), pady=(0, 5))
        self.verify_label = ttk.Label(header, style="Muted.TLabel")
        self.verify_label.grid(row=0, column=3, sticky="sw", padx=(12, 0), pady=(0, 5))
        self.status_label = ttk.Label(header, style="Muted.TLabel")
        self.status_label.grid(row=0, column=4, sticky="se", pady=(0, 5))
        ttk.Button(header, text="Compact", command=on_compact).grid(row=0, column=5, sticky="e", padx=(12, 0))
        self.pace_label = ttk.Label(self, style="Muted.TLabel")
        self.pace_label.grid(row=1, column=0, sticky="w", pady=(4, 0))
        Tooltip(self.pace_label, lambda _x, _y: PACE_TOOLTIP if str(self.pace_label.cget("text")) else None,
                self._palette)
        self.control_source_label = ttk.Label(self, style="Muted.TLabel")
        self.control_source_label.grid(row=2, column=0, sticky="w")

    def _build_project(self, on_select_stage: Callable[[int], None],
                       on_preview_stage: Callable[[int | None], None], on_follow_current: Callable[[], None],
                       on_map_mode: Callable[[str], None]) -> None:
        project = ttk.Frame(self)
        project.grid(row=4, column=0, sticky="nsew", pady=(10, 0))
        project.columnconfigure(0, weight=3)
        project.columnconfigure(1, weight=2)
        project.rowconfigure(0, weight=1)
        left = ttk.Frame(project)
        left.grid(row=0, column=0, sticky="new", padx=(0, 18))
        left.columnconfigure(0, weight=1)
        ttk.Label(left, text="Stage timeline", style="Section.TLabel").grid(row=0, column=0, sticky="w")
        self.timeline = TimelineCanvas(left, self._palette, on_select=on_select_stage, on_hover=on_preview_stage)
        self.timeline.grid(row=1, column=0, sticky="ew", pady=(6, 0))
        caption_row = ttk.Frame(left)
        caption_row.grid(row=2, column=0, sticky="ew", pady=(6, 0))
        caption_row.columnconfigure(0, weight=1)
        self.caption_label = ttk.Label(caption_row, style="Muted.TLabel")
        self.caption_label.grid(row=0, column=0, sticky="w")
        self.follow_button = ttk.Button(caption_row, text="Back to current", style="Small.TButton",
                                        command=on_follow_current)
        self.follow_button.grid(row=0, column=1, sticky="e")
        ttk.Label(left, text="Hover a stage to preview its chunks. Click to keep it.", style="Muted.TLabel").grid(
            row=3, column=0, sticky="w", pady=(8, 0))
        right = ttk.Frame(project)
        right.grid(row=0, column=1, sticky="nsew")
        right.columnconfigure(0, weight=1)
        right.rowconfigure(1, weight=1)
        mode_row = ttk.Frame(right)
        mode_row.grid(row=0, column=0, sticky="ew")
        ttk.Label(mode_row, text="Chunk map", style="Section.TLabel").pack(side="left")
        self.map_mode = tk.StringVar(master=self, value="stage")
        ttk.Radiobutton(mode_row, text="All stages", value="all", variable=self.map_mode,
                        command=lambda: on_map_mode(self.map_mode.get())).pack(side="right")
        ttk.Radiobutton(mode_row, text="This stage", value="stage", variable=self.map_mode,
                        command=lambda: on_map_mode(self.map_mode.get())).pack(side="right", padx=(0, 8))
        self.map = ChunkMapCanvas(right, self._palette)
        self.map.grid(row=1, column=0, sticky="nsew", pady=(6, 0))
        ttk.Label(right, text="Green done · Blue being built · Grey to do · Outline no work",
                  style="Muted.TLabel").grid(row=2, column=0, sticky="w", pady=(4, 0))

    def _build_controls(self, on_control: Callable[[str], None], on_refresh: Callable[[], None]) -> None:
        controls = ttk.Frame(self)
        controls.grid(row=5, column=0, sticky="ew", pady=(10, 0))
        controls.columnconfigure(len(CONTROL_ACTIONS), weight=1)
        self.buttons: dict[str, ttk.Button] = {}
        for column, (action, label) in enumerate(CONTROL_ACTIONS):
            button = ttk.Button(controls, text=label, width=0,
                                command=lambda choice=action: on_control(choice))
            button.grid(row=0, column=column, padx=(0, 6), sticky="w")
            Tooltip(button, lambda _x, _y, choice=action: self._reasons.get(choice) or None, self._palette)
            self.buttons[action] = button
        ttk.Button(controls, text="Refresh", width=0, command=on_refresh).grid(
            row=0, column=len(CONTROL_ACTIONS) + 1, sticky="e")
        self.menu_button = ttk.Menubutton(controls, text="More", width=0)
        self.menu_button.grid(row=0, column=len(CONTROL_ACTIONS) + 2, sticky="e", padx=(6, 0))
        self.result_label = ttk.Label(self, style="Muted.TLabel")
        self.result_label.grid(row=6, column=0, sticky="ew", pady=(6, 0))

    def _build_tabs(self) -> None:
        tabs = ttk.Notebook(self)
        tabs.grid(row=7, column=0, sticky="nsew", pady=(10, 0))
        details, materials, activity, raw = (ttk.Frame(tabs, padding=10) for _ in range(4))
        for frame, title in ((details, "Status details"), (materials, "Materials"), (activity, "Activity"),
                             (raw, "Raw data")):
            tabs.add(frame, text=title)
        self.details_text = self._textbox(details, row=0)
        self._build_materials(materials)
        self.events_text = self._textbox(activity, row=0)
        ttk.Button(raw, text="Save snapshot…", command=self._export).grid(row=0, column=0, sticky="e", pady=(0, 6))
        self.raw_text = self._textbox(raw, row=1, monospace=True)

    def _textbox(self, parent: ttk.Frame, *, row: int, monospace: bool = False) -> tk.Text:
        parent.columnconfigure(0, weight=1)
        parent.rowconfigure(row, weight=1)
        frame = ttk.Frame(parent)
        frame.grid(row=row, column=0, sticky="nsew")
        frame.columnconfigure(0, weight=1)
        frame.rowconfigure(0, weight=1)
        widget = tk.Text(frame, wrap="word", height=8, width=1, relief="solid", borderwidth=1,
                         highlightthickness=0, background=self._palette.surface, foreground=self._palette.text,
                         insertbackground=self._palette.text, padx=10, pady=8,
                         font=("Consolas", 9) if monospace else (FONT, 10), state="disabled")
        widget.grid(row=0, column=0, sticky="nsew")
        scrollbar = ttk.Scrollbar(frame, orient="vertical", command=widget.yview)
        scrollbar.grid(row=0, column=1, sticky="ns")
        widget.configure(yscrollcommand=scrollbar.set)
        return widget

    def _build_materials(self, parent: ttk.Frame) -> None:
        parent.columnconfigure(0, weight=1)
        parent.rowconfigure(0, weight=1)
        columns = ("material", "available", "missing", "planned", "placed", "remaining")
        self.material_tree = ttk.Treeview(parent, columns=columns, show="headings", selectmode="browse", height=6)
        for key, title in zip(columns, ("Material", "On hand", "Shortfall", "Planned", "Placed", "Remaining")):
            self.material_tree.heading(key, text=title)
            self.material_tree.column(key, width=180 if key == "material" else 85, minwidth=70,
                                      anchor="w" if key == "material" else "e", stretch=True)
        self.material_tree.grid(row=0, column=0, sticky="nsew")
        scrollbar = ttk.Scrollbar(parent, orient="vertical", command=self.material_tree.yview)
        scrollbar.grid(row=0, column=1, sticky="ns")
        self.material_tree.configure(yscrollcommand=scrollbar.set)
        self.material_tree.tag_configure("missing", foreground=self._palette.warn)
        self.material_note = ttk.Label(parent, text=PLACED_NOTE, style="Muted.TLabel")
        self.material_note.grid(row=1, column=0, sticky="w", pady=(6, 0))

    def render(self, view: FullView, snapshot: Mapping[str, Any]) -> None:
        card = view.card
        self.state_label.configure(text=card.state_label, style=f"{card.tone.title()}.Pill.TLabel")
        self.percent_label.configure(text=card.percent_text,
                                     style="BigMuted.TLabel" if card.stale else "Big.TLabel")
        self.suffix_label.configure(text=card.percent_suffix)
        self.verify_label.configure(text=card.verify_text)
        self.status_label.configure(text=card.status_text,
                                    style="Warn.TLabel" if card.status_tone == "warn" else "Muted.TLabel")
        self.pace_label.configure(text=view.pace_text)
        self.control_source_label.configure(text=view.last_control_text)
        self._render_attention(view.attention)
        self.timeline.show(card.segments, card.kind_labels,
                           selected=None if view.following_current else view.selected_stage)
        self.caption_label.configure(text=view.stage_caption)
        if view.following_current:
            self.follow_button.grid_remove()
        else:
            self.follow_button.grid()
        if self.map_mode.get() != view.map_mode:
            self.map_mode.set(view.map_mode)
        self.map.show(view.map_columns, view.map_rows, view.cells, view.map_message)
        for button in view.buttons:
            self.buttons[button.action].configure(state="normal" if button.enabled else "disabled")
            self._reasons[button.action] = button.reason
        self.result_label.configure(text=view.control_message)
        self._render_tabs(snapshot)

    def _attention_wrap(self) -> int:
        return max(400, self.winfo_width() - 60)

    def _render_attention(self, items: tuple[AttentionItem, ...]) -> None:
        if items == self._attention_rendered:
            return
        self._attention_rendered = items
        for child in self.attention_frame.winfo_children():
            child.destroy()
        if not items:
            self.attention_frame.grid_remove()
            return
        self.attention_frame.grid()
        wrap = self._attention_wrap()
        for row, item in enumerate(items):
            ttk.Label(self.attention_frame, text=item.text, style=ATTENTION_STYLES.get(item.tone, "Muted.TLabel"),
                      wraplength=wrap, justify="left").grid(row=row * 2, column=0, sticky="w")
            if item.hint:
                ttk.Label(self.attention_frame, text="→ " + item.hint, wraplength=wrap, justify="left").grid(
                    row=row * 2 + 1, column=0, sticky="w", padx=(14, 0), pady=(0, 4))

    def _wrap_attention(self) -> None:
        wrap = self._attention_wrap()
        for child in self.attention_frame.winfo_children():
            child.configure(wraplength=wrap)

    def _render_tabs(self, snapshot: Mapping[str, Any]) -> None:
        observation = _mapping(snapshot.get("observation"))
        self._observation = observation
        # Progress can arrive apart from the observation, and the material counts come from it.
        table = material_table(snapshot)
        rendered = (json.dumps(observation, sort_keys=True, ensure_ascii=False, default=str), table)
        if rendered != self._rendered_tabs:
            self._rendered_tabs = rendered
            self._set_text(self.details_text, status_details(observation, snapshot.get("progress")))
            self._render_materials(table)
            self._set_text(self.raw_text, json.dumps(safe_observation(observation), indent=2, ensure_ascii=False,
                                                     default=str) if observation else "No observation received yet.")
        events = snapshot.get("events") if isinstance(snapshot.get("events"), list) else []
        text = "\n".join(f"{_time(event.get('time'))}  {_text(event.get('message'), '')}"
                         for event in events if isinstance(event, dict))
        self._set_text(self.events_text, text or "Waiting for activity…", follow=True)

    def _render_materials(self, table: MaterialTable) -> None:
        self.material_tree.heading("placed", text="Placed" if table.placed else "Used")
        self.material_note.configure(text=table.note)
        existing = set(self.material_tree.get_children())
        for material, values, short in table.rows:
            tags = ("missing",) if short else ()
            if material in existing:
                self.material_tree.item(material, values=values, tags=tags)
                existing.discard(material)
            else:
                self.material_tree.insert("", "end", iid=material, values=values, tags=tags)
        for material in existing:
            self.material_tree.delete(material)

    @staticmethod
    def _set_text(widget: tk.Text, text: str, *, follow: bool = False) -> None:
        if widget.get("1.0", "end-1c") == text:
            return
        view = widget.yview()
        widget.configure(state="normal")
        widget.delete("1.0", "end")
        widget.insert("1.0", text)
        widget.configure(state="disabled")
        if follow and view[1] >= 0.99:
            widget.see("end")
        else:
            widget.yview_moveto(view[0])

    def _export(self) -> None:
        if not self._observation:
            messagebox.showinfo("Nothing to save", "No observation has been received yet.", parent=self)
            return
        path = filedialog.asksaveasfilename(
            parent=self, title="Save supervisor snapshot", defaultextension=".json",
            initialfile="supervisor-snapshot-" + datetime.now().strftime("%Y%m%d-%H%M%S") + ".json",
            filetypes=[("JSON snapshot", "*.json")])
        if not path:
            return
        try:
            Path(path).write_text(json.dumps(safe_observation(dict(self._observation)), indent=2,
                                             ensure_ascii=False) + "\n", encoding="utf-8")
        except OSError as error:
            messagebox.showerror("Snapshot couldn't be saved", str(error), parent=self)

"""Basic Tk status window; importing this module does not create a window."""

from __future__ import annotations

import threading
import tkinter as tk
from queue import Empty, Queue
from tkinter import ttk

from .actions import ControlAction
from .app import CompanionRuntime
from .control import ControlError, ControlResult


class CompanionWindow:
    """Small operator window showing only MVP state and controls."""

    def __init__(self, root: tk.Tk, runtime: CompanionRuntime) -> None:
        self._root = root
        self._runtime = runtime
        self._closing = False
        self._buttons: dict[ControlAction, ttk.Button] = {}
        self._pending_controls: set[ControlAction] = set()
        self._control_results: Queue[
            tuple[ControlAction, ControlResult | None, str | None]
        ] = Queue()
        self._layer = tk.StringVar(value="Not reported")
        self._chunk = tk.StringVar(value="Not reported")
        self._phase = tk.StringVar(value="STOPPED")
        self._baritone = tk.StringVar(value="Unavailable")
        self._last_error = tk.StringVar(value="None")
        self._control_result = tk.StringVar(value="Ready")
        self._build()
        self._root.protocol("WM_DELETE_WINDOW", self._close)
        self._poll()

    def _build(self) -> None:
        self._root.title("Schematic Supervisor")
        self._root.minsize(650, 470)
        frame = ttk.Frame(self._root, padding=14)
        frame.grid(row=0, column=0, sticky="nsew")
        self._root.columnconfigure(0, weight=1)
        self._root.rowconfigure(0, weight=1)
        frame.columnconfigure(1, weight=1)
        frame.rowconfigure(6, weight=1)

        rows = (
            ("Current layer", self._layer),
            ("Chunk", self._chunk),
            ("Phase", self._phase),
            ("Baritone", self._baritone),
            ("Last error", self._last_error),
        )
        for row, (label, variable) in enumerate(rows):
            ttk.Label(frame, text=f"{label}:").grid(
                row=row,
                column=0,
                padx=(0, 12),
                pady=4,
                sticky="nw",
            )
            ttk.Label(
                frame,
                textvariable=variable,
                wraplength=500,
            ).grid(row=row, column=1, pady=4, sticky="nw")

        ttk.Label(frame, text="Materials:").grid(
            row=5,
            column=0,
            columnspan=2,
            pady=(12, 4),
            sticky="w",
        )
        self._materials = ttk.Treeview(
            frame,
            columns=("available", "required", "missing"),
            show="tree headings",
            height=8,
        )
        self._materials.heading("#0", text="Material")
        self._materials.heading("available", text="Available")
        self._materials.heading("required", text="Required")
        self._materials.heading("missing", text="Missing")
        self._materials.column("#0", width=180, stretch=True)
        for column in ("available", "required", "missing"):
            self._materials.column(column, width=100, anchor="e")
        self._materials.grid(
            row=6,
            column=0,
            columnspan=2,
            sticky="nsew",
        )

        controls = ttk.Frame(frame)
        controls.grid(
            row=7,
            column=0,
            columnspan=2,
            pady=(14, 6),
            sticky="ew",
        )
        for column, action in enumerate(ControlAction):
            button = ttk.Button(
                controls,
                text=action.value.title(),
                command=lambda selected=action: self._send_control(selected),
            )
            button.grid(row=0, column=column, padx=(0, 8))
            self._buttons[action] = button
        ttk.Label(
            frame,
            textvariable=self._control_result,
            wraplength=610,
        ).grid(row=8, column=0, columnspan=2, sticky="w")

    def _poll(self) -> None:
        if self._closing:
            return
        self._drain_control_results()
        state = self._runtime.store.snapshot()
        status = state.status
        layer = status.current_layer
        if layer is None:
            self._layer.set("Not reported")
        elif layer.y is None:
            self._layer.set(f"{layer.stage.title()} (all {layer.total} layer steps processed)")
        else:
            self._layer.set(
                f"{layer.stage.title()} at Y={layer.y} "
                f"(step {layer.index} of {layer.total})"
            )
        if layer is not None and layer.chunk_total > 0:
            self._chunk.set(f"{layer.chunk_index} of {layer.chunk_total} in this layer")
        elif status.current_chunk is None:
            self._chunk.set("Not reported")
        else:
            chunk = status.current_chunk
            coordinates = (
                ""
                if chunk.x is None or chunk.z is None
                else f" at ({chunk.x}, {chunk.z})"
            )
            self._chunk.set(
                f"Schematic chunk {chunk.index} of {chunk.total}{coordinates}"
            )
        self._phase.set(status.phase + (" — planting deferred" if status.planting_deferred else ""))
        self._baritone.set(status.baritone_status)
        self._last_error.set(state.displayed_error or "None")
        existing = set(self._materials.get_children())
        wanted = set(status.materials)
        for removed in existing - wanted:
            self._materials.delete(removed)
        for name, material in status.materials.items():
            values = (
                material.available,
                "—" if material.required is None else material.required,
                material.missing,
            )
            if self._materials.exists(name):
                self._materials.item(name, text=name, values=values)
            else:
                self._materials.insert(
                    "",
                    "end",
                    iid=name,
                    text=name,
                    values=values,
                )
        self._root.after(self._runtime.config.ui.poll_interval_ms, self._poll)

    def _send_control(self, action: ControlAction) -> None:
        if action in self._pending_controls:
            return
        self._pending_controls.add(action)
        self._refresh_control_buttons()
        self._control_result.set(f"Sending {action.value.title()}…")

        def deliver() -> None:
            try:
                result = self._runtime.controller.send(action)
            except ControlError as error:
                self._control_results.put((action, None, str(error)))
            except Exception:
                self._runtime.logger.exception(
                    "Unexpected operator control failure"
                )
                self._control_results.put(
                    (action, None, "Unexpected control failure.")
                )
            else:
                self._control_results.put((action, result, None))

        threading.Thread(
            target=deliver,
            name=f"control-{action.value.lower()}",
            daemon=True,
        ).start()

    def _drain_control_results(self) -> None:
        while True:
            try:
                action, result, error = self._control_results.get_nowait()
            except Empty:
                return
            self._control_finished(action, result, error)

    def _control_finished(
        self,
        action: ControlAction,
        result: ControlResult | None,
        error: str | None,
    ) -> None:
        if self._closing:
            return
        self._pending_controls.discard(action)
        self._refresh_control_buttons()
        if error is not None:
            self._control_result.set(error)
        elif result is not None:
            state = f" State: {result.mod_state}." if result.mod_state else ""
            self._control_result.set(f"{result.message}{state}")

    def _refresh_control_buttons(self) -> None:
        any_pending = bool(self._pending_controls)
        for action, button in self._buttons.items():
            disabled = action in self._pending_controls or (
                any_pending
                and action in {ControlAction.START, ControlAction.RESUME}
            )
            button.state(["disabled"] if disabled else ["!disabled"])

    def _close(self) -> None:
        self._closing = True
        self._root.destroy()


def run_window(runtime: CompanionRuntime) -> None:
    """Create and run the Tk window. This is the only GUI entry point."""

    root = tk.Tk()
    CompanionWindow(root, runtime)
    root.mainloop()

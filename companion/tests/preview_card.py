"""Manual preview of the compact card with sample data. Not part of the automated suite.

Run from the companion folder:
    python tests/preview_card.py
    python tests/preview_card.py --paused --dark
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
from pathlib import Path
import sys
import tkinter as tk

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from supervisor_companion.progress import parse_progress  # noqa: E402
from supervisor_companion.ui_card import CardFrame  # noqa: E402
from supervisor_companion.ui_theme import DARK, LIGHT, apply_styles  # noqa: E402
from supervisor_companion.view_model import build_card  # noqa: E402
from test_progress import progress_payload  # noqa: E402


def sample_snapshot(paused: bool) -> dict:
    now = datetime.now(timezone.utc)
    observation = {
        "state": "PAUSED" if paused else "BUILDING", "run_id": "preview", "plan_id": "sha256:preview",
        "allowed_actions": ["RESUME", "STOP", "SCAN_DEPOTS"] if paused else ["PAUSE", "STOP"],
        "blockers": [], "stable_verification_passes": 0, "control_token_configured": True,
        "last_error": ("Registered depots cannot satisfy the exact material shortage: {hoe=1} Missing: {hoe=1}"
                       if paused else None),
        "last_progress_at": (now - timedelta(seconds=4)).isoformat(),
        "current_layer": {"order": "LAYERS", "stage": "LIGHTING", "index": 2, "total": 3, "y": -62,
                          "chunk_index": 2, "chunk_total": 2},
        "last_control": {"sequence": 1, "action": "RESUME", "request_id": None},
    }
    return {"connection": "online", "detail": None, "message": "Connected.", "fresh": True, "paired": True,
            "observation": observation, "pending_actions": [], "control_message": "", "events": [],
            "state_since": (now - timedelta(minutes=12)).isoformat(), "own_request_ids": [],
            "progress_status": "ok", "progress": parse_progress(progress_payload()), "pace": None}


def main() -> None:
    palette = DARK if "--dark" in sys.argv else LIGHT
    root = tk.Tk()
    apply_styles(root, palette)
    card = CardFrame(root, palette, on_control=lambda action: print("control", action),
                     on_expand=lambda stage: print("expand", stage), on_pin=lambda: print("pin"))
    card.pack(fill="both", expand=True)
    root.geometry("320x210")
    root.attributes("-topmost", True)
    root.update_idletasks()
    card.render(build_card(sample_snapshot("--paused" in sys.argv)), pinned=True)
    root.mainloop()


if __name__ == "__main__":
    main()

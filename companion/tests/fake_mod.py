"""Manual test server that imitates the mod's loopback API. Not part of the automated suite.

Run from the companion folder, in two terminals:
    python tests/fake_mod.py --port 8799 --token-file "$env:TEMP\\fake-token.txt"
    python launcher.py --mod-url http://127.0.0.1:8799 --token-file "$env:TEMP\\fake-token.txt"

Then type commands into the fake mod's terminal: pause (as if paused in game), error, blocker,
clear, done, restart (the revision counter starts over), quit. Add --legacy to imitate an older
mod without /v1/progress.
"""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import secrets
import sys
import threading
import time

STAGES = ([("STRUCTURE", y) for y in range(-63, -45)] + [("LIGHTING", y) for y in range(-62, -46, 2)]
          + [("TILL", y) for y in range(-47, -64, -4)])
CHUNKS = 49
ACTIONS_PER_PIECE = 100


class FakeMod:
    def __init__(self, seconds_per_piece: float, legacy: bool) -> None:
        self.lock = threading.Lock()
        self.legacy = legacy
        self.seconds_per_piece = seconds_per_piece
        self.state = "PAUSED"
        self.cursor = 0
        self.revision = 1
        self.sequence = 0
        self.last_action: str | None = None
        self.last_request: str | None = None
        self.last_error: str | None = None
        self.blockers: list[str] = []
        self.last_progress: str | None = None

    @property
    def pieces(self) -> int:
        return len(STAGES) * CHUNKS

    def run(self) -> None:
        while True:
            time.sleep(self.seconds_per_piece)
            with self.lock:
                if self.state == "BUILDING" and self.cursor < self.pieces:
                    self.cursor += 1
                    self.revision += 1
                    self.last_progress = datetime.now(timezone.utc).isoformat()
                    if self.cursor == self.pieces:
                        self.state = "DONE"

    def observation(self) -> dict:
        with self.lock:
            stage_index = min(self.cursor // CHUNKS, len(STAGES) - 1)
            kind, y = STAGES[stage_index]
            finished = self.cursor >= self.pieces
            allowed = ["STOP"]
            if self.state in ("BUILDING", "PAUSED"):
                allowed.append("PAUSE")
            if self.state == "PAUSED" and not self.blockers:
                allowed += ["RESUME", "SCAN_DEPOTS"]
            if self.state in ("STOPPED", "IDLE"):
                allowed.append("START")
            body = {
                "protocol_version": 1, "run_id": "fake-run", "state": self.state,
                "updated_at": datetime.now(timezone.utc).isoformat(), "world_connected": True,
                "context_matches": True, "control_token_configured": True, "ready": not self.blockers,
                "blockers": list(self.blockers), "allowed_actions": allowed, "plan_id": "sha256:fake",
                "loading_progress": None, "last_error": self.last_error, "last_message": "Fake mod.",
                "baritone_status": "Idle",
                "last_control": {"sequence": self.sequence, "action": self.last_action,
                                 "request_id": self.last_request},
                "phase": "ORDINARY_BLOCKS", "recovery_stage": "NONE", "verification_stage": "CHUNK",
                "stable_verification_passes": 2 if self.state == "DONE" else 0,
                "current_layer": {"order": "LAYERS", "stage": "DONE" if finished else kind,
                                  "index": stage_index + 1, "total": len(STAGES),
                                  "y": None if finished else y,
                                  "chunk_index": 0 if finished else self.cursor % CHUNKS + 1,
                                  "chunk_total": 0 if finished else CHUNKS},
                "materials": {},
                "material_ledger": {"planned": {}, "consumed": {}, "withdrawn": {}, "remaining_plan": {}},
            }
            if not self.legacy:
                body["progress_revision"] = self.revision
                body["last_progress_at"] = self.last_progress
            return body

    def progress(self) -> dict:
        with self.lock:
            stages = []
            for index, (kind, y) in enumerate(STAGES):
                letters = []
                for chunk in range(CHUNKS):
                    piece = index * CHUNKS + chunk
                    letters.append("D" if piece < self.cursor else "C" if piece == self.cursor else "-")
                stages.append({"kind": kind, "y": y, "actions": CHUNKS * ACTIONS_PER_PIECE,
                               "done": letters.count("D") * ACTIONS_PER_PIECE, "chunks": "".join(letters)})
            materials: dict[str, dict[str, int]] = {}
            for stage in stages:
                # Tilling uses no items.
                name = {"STRUCTURE": "dirt", "LIGHTING": "glowstone"}.get(stage["kind"])
                if name:
                    counts = materials.setdefault(name, {"planned": 0, "done": 0})
                    counts["planned"] += stage["actions"]
                    counts["done"] += stage["done"]
            return {"protocol_version": 1, "available": True, "revision": self.revision,
                    "plan_id": "sha256:fake", "schedule_id": "layers-v1-structure-first",
                    "layout": {"origin_x": 512, "origin_z": -1686, "columns": 7, "rows": 7},
                    "totals": {"actions": len(STAGES) * CHUNKS * ACTIONS_PER_PIECE,
                               "done": sum(stage["done"] for stage in stages), "stages": len(STAGES),
                               "current_stage": self.cursor // CHUNKS + 1 if self.cursor < self.pieces else None},
                    "materials": materials, "stages": stages, "chunk_detail_truncated": False}

    def control(self, body: dict) -> dict:
        action = body.get("action")
        with self.lock:
            if action in ("START", "RESUME") and (self.state not in ("PAUSED", "STOPPED", "IDLE") or self.blockers):
                return {"accepted": False, "message": "Not allowed now.", "state": self.state}
            self.sequence += 1
            self.last_action = action
            self.last_request = body.get("request_id")
            if action == "START":
                self.cursor, self.state, self.last_error = 0, "BUILDING", None
                self.revision += 1
            elif action == "RESUME":
                self.state, self.last_error = "BUILDING", None
            elif action == "PAUSE" and self.state != "DONE":
                self.state = "PAUSED"
            elif action == "STOP":
                self.state = "STOPPED"
            return {"accepted": True, "message": f"{str(action).title()} applied.", "state": self.state}

    def command(self, text: str) -> None:
        with self.lock:
            if text == "pause":
                self.sequence += 1
                self.state, self.last_action, self.last_request = "PAUSED", "PAUSE", None
            elif text == "error":
                self.state = "ERROR"
                self.last_error = ("Depot withdrawal failed: real chest stock does not cover the "
                                   "allocation at depot-014")
            elif text == "blocker":
                self.state = "PAUSED"
                self.blockers = ["Join the target world before starting or resuming."]
            elif text == "clear":
                self.blockers = []
                self.last_error = None
                if self.state == "ERROR":
                    self.state = "PAUSED"
            elif text == "done":
                self.cursor, self.state = self.pieces, "DONE"
                self.revision += 1
            elif text == "restart":
                self.revision = 1


def serve(mod: FakeMod, port: int, token: str) -> ThreadingHTTPServer:
    class Handler(BaseHTTPRequestHandler):
        def _reply(self, status: int, body: dict) -> None:
            encoded = json.dumps(body).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(encoded)))
            self.end_headers()
            self.wfile.write(encoded)

        def _authorized(self) -> bool:
            if self.headers.get("X-Supervisor-Token") == token:
                return True
            self._reply(401, {"accepted": False, "message": "Unauthorized."})
            return False

        def do_GET(self) -> None:
            if self.path == "/v1/observation":
                if self._authorized():
                    self._reply(200, mod.observation())
            elif self.path == "/v1/progress" and not mod.legacy:
                if self._authorized():
                    self._reply(200, mod.progress())
            else:
                self._reply(404, {"accepted": False, "message": "Not found."})

        def do_POST(self) -> None:
            if self.path != "/v1/control":
                self._reply(404, {"accepted": False, "message": "Not found."})
                return
            if not self._authorized():
                return
            length = int(self.headers.get("Content-Length", "0"))
            self._reply(200, mod.control(json.loads(self.rfile.read(length) or b"{}")))

        def log_message(self, *_args) -> None:
            return

    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


def main() -> None:
    parser = argparse.ArgumentParser(description="Imitate the Schematic Supervisor mod for manual monitor tests.")
    parser.add_argument("--port", type=int, default=8799)
    parser.add_argument("--token-file", type=Path, required=True)
    parser.add_argument("--seconds-per-piece", type=float, default=0.5)
    parser.add_argument("--legacy", action="store_true", help="Imitate a mod without /v1/progress.")
    arguments = parser.parse_args()
    token = secrets.token_urlsafe(24)
    arguments.token_file.write_text(token, encoding="ascii")
    mod = FakeMod(arguments.seconds_per_piece, arguments.legacy)
    threading.Thread(target=mod.run, daemon=True).start()
    server = serve(mod, arguments.port, token)
    print(f"Fake mod on http://127.0.0.1:{arguments.port}; pairing token in {arguments.token_file}")
    print("Commands: pause, error, blocker, clear, done, restart, quit")
    for line in sys.stdin:
        text = line.strip().lower()
        if text == "quit":
            break
        mod.command(text)
    server.shutdown()


if __name__ == "__main__":
    main()

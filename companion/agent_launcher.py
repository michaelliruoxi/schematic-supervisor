"""Absolute-path entry point for the local agent connection and runner."""

from __future__ import annotations

import sys


def main() -> int:
    if len(sys.argv) < 2 or sys.argv[1] not in {"serve", "run"}:
        print("Usage: agent_launcher.py serve|run [options]", file=sys.stderr)
        return 2
    mode = sys.argv[1]
    if mode == "serve":
        from supervisor_companion.agent_bridge import main as entry_point
    else:
        from supervisor_companion.agent_runner import main as entry_point
    return entry_point(sys.argv[2:])


if __name__ == "__main__":
    raise SystemExit(main())

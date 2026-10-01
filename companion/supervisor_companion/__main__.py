"""Command-line entry point for the desktop companion."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from .app import build_runtime
from .config import ConfigurationError, load_config


def _default_config_path() -> Path | None:
    if getattr(sys, "frozen", False):
        directory = Path(sys.executable).resolve().parent
    else:
        directory = Path.cwd()
    candidate = directory / "config.json"
    return candidate if candidate.is_file() else None


def build_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Run the Schematic Supervisor desktop companion."
    )
    parser.add_argument(
        "--config",
        type=Path,
        help="JSON configuration path; defaults to ./config.json when present.",
    )
    parser.add_argument(
        "--token-file",
        type=Path,
        help="Local pairing file created by the mod on first launch.",
    )
    parser.add_argument(
        "--check-config",
        action="store_true",
        help="Validate configuration and exit without network or GUI startup.",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    arguments = build_argument_parser().parse_args(argv)
    path = arguments.config or _default_config_path()
    try:
        config = load_config(path)
        if arguments.check_config:
            print(json.dumps(config.safe_summary(), indent=2, sort_keys=True))
            return 0
        protocol_token = None
        if arguments.token_file is not None:
            from .agent_bridge import AgentBridge, EndpointError

            try:
                protocol_token = AgentBridge(config, token_file=arguments.token_file).mod_token
            except EndpointError as error:
                raise ConfigurationError(str(error)) from error
            if protocol_token is None:
                raise ConfigurationError("Pairing file is missing; launch the mod first.")
        runtime = build_runtime(config, protocol_token=protocol_token)
    except ConfigurationError as error:
        print(f"Configuration error: {error}", file=sys.stderr)
        return 2
    except OSError as error:
        print(f"Startup error: {error}", file=sys.stderr)
        return 3

    runtime.start()
    try:
        from .ui import run_window

        run_window(runtime)
    except KeyboardInterrupt:
        runtime.logger.info("Interrupted by operator")
    except Exception:
        runtime.logger.exception("Desktop window failed")
        return 4
    finally:
        runtime.stop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

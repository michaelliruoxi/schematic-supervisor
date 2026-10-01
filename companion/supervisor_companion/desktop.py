"""Direct desktop monitor entry point, including read-only connection diagnostics."""

from __future__ import annotations

import argparse
from dataclasses import replace
import json
import logging
import os
from pathlib import Path
import platform
import sys

from . import __version__, win32
from .agent_bridge import AgentBridge
from .config import ConfigurationError, load_config, validate_http_url
from .logging_setup import MONITOR_LOGGER_NAME, configure_monitor_logging, install_exception_hooks
from .ui_state import candidate_paths


def application_directory() -> Path:
    if getattr(sys, "frozen", False):
        return Path(sys.executable).resolve().parent
    return Path(__file__).resolve().parents[1]


def default_launcher_token_file() -> Path | None:
    """The pairing file in the default launcher's game folder, %APPDATA%\\.minecraft."""
    appdata = os.environ.get("APPDATA")
    if not appdata:
        return None
    return Path(appdata) / ".minecraft" / "config" / "schematic-supervisor" / "protocol-token.txt"


def discover_token_file(directory: Path | None = None) -> Path:
    """Find the dedicated profile relative to the app, independent of working directory, and then the
    default launcher's game folder."""
    base = (directory or application_directory()).resolve()
    roots = [base, *list(base.parents)[:3]]
    for root in roots:
        for relative in (
            Path("protocol-token.txt"),
            Path("runtime/game/config/schematic-supervisor/protocol-token.txt"),
            Path("config/schematic-supervisor/protocol-token.txt"),
        ):
            candidate = root / relative
            if candidate.is_file():
                return candidate
    default_launcher = default_launcher_token_file()
    if default_launcher is not None and default_launcher.is_file():
        return default_launcher
    # Keep the expected location even before the first game launch. The bridge
    # reads the file again on every request, so pairing recovers automatically.
    for root in roots:
        if (root / "runtime/game").is_dir():
            return root / "runtime/game/config/schematic-supervisor/protocol-token.txt"
    return base / "protocol-token.txt"


def build_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Monitor and control Schematic Supervisor.")
    parser.add_argument("--config", type=Path, help="Optional JSON configuration.")
    parser.add_argument("--token-file", type=Path, help="Pairing file; discovered automatically in the dedicated profile.")
    parser.add_argument("--mod-url", help="Optional loopback mod address (default http://127.0.0.1:8765).")
    parser.add_argument("--poll-interval", type=float, default=1.0, help="Seconds between observations (0.25 to 30).")
    parser.add_argument("--check-config", action="store_true", help="Validate settings without opening a window.")
    parser.add_argument("--check-connection", action="store_true", help="Read the live mod once without opening a window or sending controls.")
    parser.add_argument("--output", type=Path, help="Write diagnostic JSON to this file (for either check mode).")
    return parser


def _emit(value: dict, output: Path | None) -> None:
    encoded = json.dumps(value, indent=2, ensure_ascii=False, allow_nan=False)
    if output is not None:
        output.write_text(encoded + "\n", encoding="utf-8")
    elif sys.stdout is not None:
        print(encoded)


def _show_error(message: str, *, gui: bool) -> None:
    if sys.stderr is not None:
        print(message, file=sys.stderr)
    if gui:
        try:
            # Errors can arrive before main() turns this on, and it must be on before the Tk root exists.
            win32.enable_dpi_awareness()
            import tkinter as tk
            from tkinter import messagebox
            root = tk.Tk()
            root.withdraw()
            messagebox.showerror("Schematic Supervisor", message, parent=root)
            root.destroy()
        except Exception:
            pass


def _token_reader(bridge: AgentBridge):
    def read() -> str | None:
        try:
            return bridge.mod_token
        except Exception:
            return None
    return read


def _log_session_header(logger: logging.Logger, config, token_file: Path, config_path: Path | None,
                        log_directory: Path) -> None:
    logger.info("Monitor %s starting (Python %s, %s)", __version__, platform.python_version(),
                "packaged" if getattr(sys, "frozen", False) else "source")
    logger.info("Mod %s; pairing file %s (%s); configuration %s; logs %s", config.mod.base_url, token_file,
                "found" if token_file.is_file() else "missing", config_path or "built-in defaults", log_directory)


def main(argv: list[str] | None = None) -> int:
    arguments = build_argument_parser().parse_args(argv)
    diagnostic = arguments.check_config or arguments.check_connection
    service = None
    instance = None
    logger = logging.getLogger(MONITOR_LOGGER_NAME)
    logging_configured = False
    try:
        if not 0.25 <= arguments.poll_interval <= 30:
            raise ConfigurationError("Poll interval must be between 0.25 and 30 seconds.")
        if arguments.output is not None and not diagnostic:
            raise ConfigurationError("--output requires --check-config or --check-connection.")
        path = arguments.config
        if path is None:
            candidate = application_directory() / "config.json"
            path = candidate if candidate.is_file() else None
        config = load_config(path)
        if arguments.mod_url:
            url = validate_http_url(arguments.mod_url, "mod URL", require_loopback=True)
            config = replace(config, mod=replace(config.mod, base_url=url))
        token_file = arguments.token_file or discover_token_file()
        if arguments.check_config:
            _emit(config.safe_summary(), arguments.output)
            return 0
        bridge = AgentBridge(config, token_file=token_file)
        if arguments.check_connection:
            result = bridge.observe()
            result["progress"] = bridge.progress()
            _emit(result, arguments.output)
            return 0 if result["ok"] else 1
        win32.enable_dpi_awareness()
        instance = win32.SingleInstance()
        if not instance.acquired:
            # Before the modal dialog, so this copy doesn't keep the mutex alive after the open monitor closes.
            instance.release()
            _show_error("The monitor is already open.", gui=True)
            return 0
        logger, log_directory = configure_monitor_logging(config, application_directory(), _token_reader(bridge))
        logging_configured = True
        install_exception_hooks(logger)
        _log_session_header(logger, config, token_file, path, log_directory)
        from .monitor import MonitorService
        from .monitor_ui import run_window
        from .notify import PhoneNotifier
        service = MonitorService(bridge, poll_interval_seconds=arguments.poll_interval, logger=logger)
        service.start()
        notifier = PhoneNotifier(config.notify, logger=logger) if config.notify.enabled else None
        if notifier is not None:
            logger.info("Phone notifications are on (%s)", ", ".join(
                name for name, url in (("ntfy", config.notify.ntfy_url),
                                       ("Discord", config.notify.discord_webhook_url)) if url))
        run_window(service, token_file=token_file, state_paths=candidate_paths(application_directory()),
                   logger=logger, log_directory=log_directory, notifier=notifier)
        return 0
    except (ConfigurationError, OSError) as error:
        message = f"Could not open the monitor: {error}"
        if logging_configured:
            logger.error(message)
        if diagnostic and arguments.output:
            try:
                _emit({"ok": False, "message": message}, arguments.output)
            except OSError:
                pass
        _show_error(message, gui=not diagnostic)
        return 2
    except KeyboardInterrupt:
        return 0
    except Exception:
        logger.exception("The monitor could not start")
        detail = ("Details are in the log." if logging_configured
                  else "Check the executable and local configuration, then reopen it.")
        _show_error(f"The monitor could not start. {detail}", gui=not diagnostic)
        return 4
    finally:
        if service is not None:
            service.stop()
        if instance is not None:
            instance.release()


if __name__ == "__main__":
    raise SystemExit(main())

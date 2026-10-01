"""Configuration loading and validation for the desktop companion."""

from __future__ import annotations

import ipaddress
import json
import os
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Mapping
from urllib.parse import urlparse


class ConfigurationError(ValueError):
    """Raised when configuration is unsafe or invalid."""


def _object(value: Any, name: str) -> Mapping[str, Any]:
    if value is None:
        return {}
    if not isinstance(value, Mapping):
        raise ConfigurationError(f"{name} must be an object")
    return value


def _known_keys(value: Mapping[str, Any], allowed: set[str], name: str) -> None:
    unknown = sorted(set(value) - allowed)
    if unknown:
        raise ConfigurationError(f"{name} has unknown keys: {', '.join(unknown)}")


def _string(value: Any, name: str, *, allow_empty: bool = False) -> str:
    if not isinstance(value, str):
        raise ConfigurationError(f"{name} must be a string")
    text = value.strip()
    if not allow_empty and not text:
        raise ConfigurationError(f"{name} must not be empty")
    return text


def _integer(
    value: Any,
    name: str,
    *,
    minimum: int,
    maximum: int,
) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        raise ConfigurationError(f"{name} must be an integer")
    if not minimum <= value <= maximum:
        raise ConfigurationError(
            f"{name} must be between {minimum} and {maximum}"
        )
    return value


def _number(
    value: Any,
    name: str,
    *,
    minimum: float,
    maximum: float,
) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ConfigurationError(f"{name} must be a number")
    result = float(value)
    if not minimum <= result <= maximum:
        raise ConfigurationError(
            f"{name} must be between {minimum} and {maximum}"
        )
    return result


def is_loopback_host(host: str) -> bool:
    normalized = host.strip().lower().rstrip(".")
    if normalized == "localhost":
        return True
    try:
        return ipaddress.ip_address(normalized).is_loopback
    except ValueError:
        return False


def validate_http_url(
    value: str,
    name: str,
    *,
    require_loopback: bool,
    require_https_off_loopback: bool = False,
) -> str:
    try:
        parsed = urlparse(value)
        hostname = parsed.hostname
        # Accessing port performs urllib's range and syntax validation.
        parsed.port
    except ValueError as error:
        raise ConfigurationError(f"{name} is not a valid URL") from error
    if parsed.scheme not in {"http", "https"} or not hostname:
        raise ConfigurationError(f"{name} must be an http(s) URL")
    if parsed.username or parsed.password:
        raise ConfigurationError(f"{name} must not contain credentials")
    if parsed.query or parsed.fragment:
        raise ConfigurationError(f"{name} must not contain a query or fragment")
    if require_loopback and not is_loopback_host(hostname):
        raise ConfigurationError(f"{name} must use a loopback host")
    if (
        require_https_off_loopback
        and parsed.scheme != "https"
        and not is_loopback_host(hostname)
    ):
        raise ConfigurationError(
            f"{name} must use HTTPS when it is not loopback"
        )
    return value.rstrip("/")


@dataclass(frozen=True)
class ServerConfig:
    host: str = "127.0.0.1"
    port: int = 8766
    max_request_bytes: int = 262_144
    token_env: str = ""

    @classmethod
    def from_mapping(cls, value: Any) -> "ServerConfig":
        data = _object(value, "server")
        _known_keys(
            data,
            {"host", "port", "max_request_bytes", "token_env"},
            "server",
        )
        host = _string(data.get("host", cls.host), "server.host")
        if not is_loopback_host(host):
            raise ConfigurationError("server.host must be a loopback host")
        if host.strip().lower().rstrip(".") != "localhost":
            try:
                bind_address = ipaddress.ip_address(host)
            except ValueError as error:
                raise ConfigurationError(
                    "server.host must be localhost or IPv4 loopback"
                ) from error
            if bind_address.version != 4:
                raise ConfigurationError(
                    "server.host must be localhost or IPv4 loopback"
                )
        return cls(
            host=host,
            port=_integer(
                data.get("port", cls.port),
                "server.port",
                minimum=1,
                maximum=65_535,
            ),
            max_request_bytes=_integer(
                data.get("max_request_bytes", cls.max_request_bytes),
                "server.max_request_bytes",
                minimum=1_024,
                maximum=4_194_304,
            ),
            token_env=_string(
                data.get("token_env", cls.token_env),
                "server.token_env",
                allow_empty=True,
            ),
        )

    def resolve_token(self) -> str | None:
        if not self.token_env:
            return None
        value = os.environ.get(self.token_env)
        if not value:
            raise ConfigurationError(
                f"environment variable {self.token_env!r} is required"
            )
        return value


@dataclass(frozen=True)
class ModConfig:
    base_url: str = "http://127.0.0.1:8765"
    timeout_seconds: float = 3.0
    token_env: str = ""

    @classmethod
    def from_mapping(cls, value: Any) -> "ModConfig":
        data = _object(value, "mod")
        _known_keys(data, {"base_url", "timeout_seconds", "token_env"}, "mod")
        base_url = validate_http_url(
            _string(data.get("base_url", cls.base_url), "mod.base_url"),
            "mod.base_url",
            require_loopback=True,
        )
        return cls(
            base_url=base_url,
            timeout_seconds=_number(
                data.get("timeout_seconds", cls.timeout_seconds),
                "mod.timeout_seconds",
                minimum=0.1,
                maximum=30.0,
            ),
            token_env=_string(
                data.get("token_env", cls.token_env),
                "mod.token_env",
                allow_empty=True,
            ),
        )

    def resolve_token(self) -> str | None:
        if not self.token_env:
            return None
        value = os.environ.get(self.token_env)
        if not value:
            raise ConfigurationError(
                f"environment variable {self.token_env!r} is required"
            )
        return value


@dataclass(frozen=True)
class AIConfig:
    provider: str = "disabled"
    endpoint: str = ""
    model: str = ""
    timeout_seconds: float = 10.0
    api_key_env: str = ""

    @classmethod
    def from_mapping(cls, value: Any) -> "AIConfig":
        data = _object(value, "ai")
        _known_keys(
            data,
            {"provider", "endpoint", "model", "timeout_seconds", "api_key_env"},
            "ai",
        )
        provider = _string(
            data.get("provider", cls.provider), "ai.provider"
        ).lower()
        if provider not in {"disabled", "http", "ollama"}:
            raise ConfigurationError(
                "ai.provider must be disabled, http, or ollama"
            )
        endpoint = _string(
            data.get("endpoint", cls.endpoint),
            "ai.endpoint",
            allow_empty=True,
        )
        model = _string(
            data.get("model", cls.model), "ai.model", allow_empty=True
        )
        api_key_env = _string(
            data.get("api_key_env", cls.api_key_env),
            "ai.api_key_env",
            allow_empty=True,
        )
        if provider == "http":
            if not endpoint:
                raise ConfigurationError("ai.endpoint is required for http")
            endpoint = validate_http_url(
                endpoint,
                "ai.endpoint",
                require_loopback=False,
                require_https_off_loopback=True,
            )
        elif provider == "ollama":
            if not endpoint:
                endpoint = "http://127.0.0.1:11434/api/chat"
            endpoint = validate_http_url(
                endpoint, "ai.endpoint", require_loopback=True
            )
            if not model:
                raise ConfigurationError("ai.model is required for ollama")
            if api_key_env:
                raise ConfigurationError(
                    "ai.api_key_env is supported only by the http provider"
                )
        elif endpoint:
            raise ConfigurationError(
                "ai.endpoint must be empty when provider is disabled"
            )
        elif api_key_env:
            raise ConfigurationError(
                "ai.api_key_env is supported only by the http provider"
            )
        return cls(
            provider=provider,
            endpoint=endpoint,
            model=model,
            timeout_seconds=_number(
                data.get("timeout_seconds", cls.timeout_seconds),
                "ai.timeout_seconds",
                minimum=0.1,
                maximum=60.0,
            ),
            api_key_env=api_key_env,
        )

    def resolve_api_key(self) -> str | None:
        if not self.api_key_env:
            return None
        value = os.environ.get(self.api_key_env)
        if not value:
            raise ConfigurationError(
                f"environment variable {self.api_key_env!r} is required"
            )
        return value


@dataclass(frozen=True)
class LogConfig:
    directory: str = "logs"
    filename: str = "companion.log"
    level: str = "INFO"
    max_bytes: int = 1_048_576
    backup_count: int = 4

    @classmethod
    def from_mapping(cls, value: Any) -> "LogConfig":
        data = _object(value, "logging")
        _known_keys(
            data,
            {"directory", "filename", "level", "max_bytes", "backup_count"},
            "logging",
        )
        level = _string(data.get("level", cls.level), "logging.level").upper()
        if level not in {"DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL"}:
            raise ConfigurationError("logging.level is invalid")
        filename = _string(
            data.get("filename", cls.filename), "logging.filename"
        )
        if Path(filename).name != filename:
            raise ConfigurationError("logging.filename must be a file name")
        return cls(
            directory=_string(
                data.get("directory", cls.directory), "logging.directory"
            ),
            filename=filename,
            level=level,
            max_bytes=_integer(
                data.get("max_bytes", cls.max_bytes),
                "logging.max_bytes",
                minimum=1_024,
                maximum=104_857_600,
            ),
            backup_count=_integer(
                data.get("backup_count", cls.backup_count),
                "logging.backup_count",
                minimum=1,
                maximum=20,
            ),
        )


@dataclass(frozen=True)
class UIConfig:
    poll_interval_ms: int = 250

    @classmethod
    def from_mapping(cls, value: Any) -> "UIConfig":
        data = _object(value, "ui")
        _known_keys(data, {"poll_interval_ms"}, "ui")
        return cls(
            poll_interval_ms=_integer(
                data.get("poll_interval_ms", cls.poll_interval_ms),
                "ui.poll_interval_ms",
                minimum=100,
                maximum=5_000,
            )
        )


@dataclass(frozen=True)
class NotifyConfig:
    """Optional phone notifications for monitor alerts. Both addresses work like passwords."""

    ntfy_url: str = ""
    discord_webhook_url: str = ""
    timeout_seconds: float = 5.0
    minimum_interval_seconds: float = 60.0

    @classmethod
    def from_mapping(cls, value: Any) -> "NotifyConfig":
        data = _object(value, "notify")
        _known_keys(
            data,
            {"ntfy_url", "discord_webhook_url", "timeout_seconds", "minimum_interval_seconds"},
            "notify",
        )
        urls = {}
        for key in ("ntfy_url", "discord_webhook_url"):
            url = _string(data.get(key, ""), f"notify.{key}", allow_empty=True)
            if url:
                url = validate_http_url(
                    url, f"notify.{key}", require_loopback=False, require_https_off_loopback=True
                )
            urls[key] = url
        return cls(
            ntfy_url=urls["ntfy_url"],
            discord_webhook_url=urls["discord_webhook_url"],
            timeout_seconds=_number(
                data.get("timeout_seconds", cls.timeout_seconds),
                "notify.timeout_seconds",
                minimum=1.0,
                maximum=30.0,
            ),
            minimum_interval_seconds=_number(
                data.get("minimum_interval_seconds", cls.minimum_interval_seconds),
                "notify.minimum_interval_seconds",
                minimum=0.0,
                maximum=86_400.0,
            ),
        )

    @property
    def enabled(self) -> bool:
        return bool(self.ntfy_url or self.discord_webhook_url)


@dataclass(frozen=True)
class AppConfig:
    server: ServerConfig = field(default_factory=ServerConfig)
    mod: ModConfig = field(default_factory=ModConfig)
    ai: AIConfig = field(default_factory=AIConfig)
    logging: LogConfig = field(default_factory=LogConfig)
    ui: UIConfig = field(default_factory=UIConfig)
    notify: NotifyConfig = field(default_factory=NotifyConfig)
    config_directory: Path = field(
        default_factory=lambda: Path.cwd(), repr=False, compare=False
    )

    @classmethod
    def from_mapping(
        cls,
        value: Any,
        *,
        config_directory: Path | None = None,
    ) -> "AppConfig":
        data = _object(value, "configuration")
        _known_keys(data, {"server", "mod", "ai", "logging", "ui", "notify"}, "configuration")
        return cls(
            server=ServerConfig.from_mapping(data.get("server")),
            mod=ModConfig.from_mapping(data.get("mod")),
            ai=AIConfig.from_mapping(data.get("ai")),
            logging=LogConfig.from_mapping(data.get("logging")),
            ui=UIConfig.from_mapping(data.get("ui")),
            notify=NotifyConfig.from_mapping(data.get("notify")),
            config_directory=(
                Path.cwd()
                if config_directory is None
                else config_directory.resolve()
            ),
        )

    def log_directory_path(self) -> Path:
        directory = Path(self.logging.directory)
        if not directory.is_absolute():
            directory = self.config_directory / directory
        return directory.resolve()

    def safe_summary(self) -> dict[str, Any]:
        result = asdict(self)
        result.pop("config_directory", None)
        # A notification address lets anyone post to (or, for ntfy, read) the channel.
        for key in ("ntfy_url", "discord_webhook_url"):
            if result["notify"][key]:
                result["notify"][key] = "(set)"
        return result


def load_config(path: str | Path | None) -> AppConfig:
    """Load a JSON config, or safe built-in defaults when no path is supplied."""

    if path is None:
        return AppConfig.from_mapping({})
    config_path = Path(path).expanduser().resolve()
    try:
        raw = config_path.read_text(encoding="utf-8")
    except OSError as error:
        raise ConfigurationError(
            f"could not read configuration: {config_path}"
        ) from error
    try:
        value = json.loads(raw)
    except json.JSONDecodeError as error:
        raise ConfigurationError(
            f"configuration is not valid JSON: line {error.lineno}, "
            f"column {error.colno}"
        ) from error
    return AppConfig.from_mapping(value, config_directory=config_path.parent)

from __future__ import annotations

import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from supervisor_companion.config import (
    AIConfig,
    AppConfig,
    ConfigurationError,
    ModConfig,
    ServerConfig,
    is_loopback_host,
    load_config,
)


class ConfigurationTests(unittest.TestCase):
    def test_defaults_are_loopback_and_ai_disabled(self) -> None:
        config = AppConfig.from_mapping({})
        self.assertEqual(config.server.host, "127.0.0.1")
        self.assertEqual(config.mod.base_url, "http://127.0.0.1:8765")
        self.assertEqual(config.ai.provider, "disabled")

    def test_recognizes_ipv4_ipv6_and_localhost_loopback(self) -> None:
        self.assertTrue(is_loopback_host("127.0.0.1"))
        self.assertTrue(is_loopback_host("::1"))
        self.assertTrue(is_loopback_host("localhost"))
        self.assertFalse(is_loopback_host("0.0.0.0"))
        self.assertFalse(is_loopback_host("example.com"))

    def test_rejects_non_loopback_server_bind(self) -> None:
        with self.assertRaisesRegex(ConfigurationError, "loopback"):
            ServerConfig.from_mapping({"host": "0.0.0.0"})

    def test_rejects_unsupported_ipv6_server_bind(self) -> None:
        with self.assertRaisesRegex(ConfigurationError, "IPv4"):
            ServerConfig.from_mapping({"host": "::1"})

    def test_rejects_remote_mod_address(self) -> None:
        with self.assertRaisesRegex(ConfigurationError, "loopback"):
            ModConfig.from_mapping({"base_url": "https://example.com"})

    def test_rejects_credentials_in_url(self) -> None:
        with self.assertRaisesRegex(ConfigurationError, "credentials"):
            ModConfig.from_mapping(
                {"base_url": "http://name:secret@127.0.0.1:8765"}
            )

    def test_rejects_malformed_ipv6_and_out_of_range_port(self) -> None:
        with self.assertRaises(ConfigurationError):
            ModConfig.from_mapping({"base_url": "http://[::1"})
        with self.assertRaises(ConfigurationError):
            ModConfig.from_mapping(
                {"base_url": "http://127.0.0.1:99999"}
            )

    def test_http_provider_allows_configured_remote_endpoint(self) -> None:
        config = AIConfig.from_mapping(
            {
                "provider": "http",
                "endpoint": "https://diagnosis.example/api",
            }
        )
        self.assertEqual(config.provider, "http")

    def test_remote_http_provider_requires_tls(self) -> None:
        with self.assertRaisesRegex(ConfigurationError, "HTTPS"):
            AIConfig.from_mapping(
                {
                    "provider": "http",
                    "endpoint": "http://diagnosis.example/api",
                }
            )

    def test_loopback_http_provider_may_use_plain_http(self) -> None:
        config = AIConfig.from_mapping(
            {
                "provider": "http",
                "endpoint": "http://127.0.0.1:9000/diagnose",
            }
        )
        self.assertEqual(config.provider, "http")

    def test_ollama_requires_loopback_and_model(self) -> None:
        with self.assertRaisesRegex(ConfigurationError, "loopback"):
            AIConfig.from_mapping(
                {
                    "provider": "ollama",
                    "endpoint": "http://example.com/api/chat",
                    "model": "model",
                }
            )
        with self.assertRaisesRegex(ConfigurationError, "model"):
            AIConfig.from_mapping({"provider": "ollama"})

    def test_ollama_uses_default_endpoint(self) -> None:
        config = AIConfig.from_mapping(
            {"provider": "ollama", "model": "model"}
        )
        self.assertEqual(
            config.endpoint, "http://127.0.0.1:11434/api/chat"
        )

    def test_disabled_provider_rejects_endpoint(self) -> None:
        with self.assertRaisesRegex(ConfigurationError, "must be empty"):
            AIConfig.from_mapping(
                {
                    "provider": "disabled",
                    "endpoint": "http://127.0.0.1:9999",
                }
            )

    def test_unknown_keys_are_rejected(self) -> None:
        with self.assertRaisesRegex(ConfigurationError, "unknown keys"):
            AppConfig.from_mapping({"mystery": True})

    def test_environment_secret_is_resolved_without_storing_value(self) -> None:
        config = AIConfig.from_mapping(
            {
                "provider": "http",
                "endpoint": "https://diagnosis.example/api",
                "api_key_env": "TEST_DIAGNOSIS_KEY",
            }
        )
        with patch.dict(os.environ, {"TEST_DIAGNOSIS_KEY": "secret-value"}):
            self.assertEqual(config.resolve_api_key(), "secret-value")
        self.assertNotIn("secret-value", repr(config))

    def test_missing_environment_secret_is_an_error(self) -> None:
        config = ServerConfig.from_mapping(
            {"token_env": "MISSING_TEST_TOKEN"}
        )
        with patch.dict(os.environ, {}, clear=True):
            with self.assertRaisesRegex(ConfigurationError, "required"):
                config.resolve_token()

    def test_relative_log_directory_resolves_from_config(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            config = AppConfig.from_mapping(
                {"logging": {"directory": "relative-logs"}},
                config_directory=Path(directory),
            )
            self.assertEqual(
                config.log_directory_path(),
                (Path(directory) / "relative-logs").resolve(),
            )

    def test_load_config_reports_invalid_json_location(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "config.json"
            path.write_text('{"server":', encoding="utf-8")
            with self.assertRaisesRegex(ConfigurationError, "line 1"):
                load_config(path)

    def test_safe_summary_is_json_serializable_and_has_no_secret_values(self) -> None:
        config = AppConfig.from_mapping({})
        encoded = json.dumps(config.safe_summary())
        self.assertIn('"provider": "disabled"', encoded)
        self.assertNotIn("config_directory", encoded)

    def test_checked_in_examples_validate(self) -> None:
        project = Path(__file__).resolve().parents[1]
        for name in (
            "config.disabled.example.json",
            "config.ollama.example.json",
            "config.http.example.json",
        ):
            with self.subTest(name=name):
                config = load_config(project / name)
                self.assertIn(config.ai.provider, {"disabled", "ollama", "http"})


if __name__ == "__main__":
    unittest.main()

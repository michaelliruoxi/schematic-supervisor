"""Small bounded JSON-over-HTTP helper built on the standard library."""

from __future__ import annotations

import json
from dataclasses import dataclass
from http.client import HTTPException
from typing import Any, Mapping
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, OpenerDirector, ProxyHandler, Request, build_opener

from .config import is_loopback_host

MAX_RESPONSE_BYTES = 262_144


class HttpJsonError(RuntimeError):
    """Raised for transport, HTTP, or response-decoding failures."""


@dataclass(frozen=True)
class HttpJsonResponse:
    status: int
    body: Any


class _RejectRedirects(HTTPRedirectHandler):
    """Keep a validated endpoint from redirecting across its trust boundary."""

    def redirect_request(
        self,
        req: Request,
        fp: Any,
        code: int,
        msg: str,
        headers: Mapping[str, str],
        newurl: str,
    ) -> None:
        return None


def _opener(url: str) -> OpenerDirector:
    """A per-call opener, so process-global urllib state cannot change its policy.

    Redirects are refused. A loopback endpoint is on this computer, so an
    environment or system proxy must never receive its requests or tokens;
    remote endpoints, such as phone alerts, keep the user's proxy.
    """
    hostname = urlsplit(url).hostname
    if hostname is not None and is_loopback_host(hostname):
        return build_opener(ProxyHandler({}), _RejectRedirects())
    return build_opener(_RejectRedirects())


def post_json(
    url: str,
    payload: Mapping[str, Any],
    *,
    timeout_seconds: float,
    headers: Mapping[str, str] | None = None,
    max_response_bytes: int = MAX_RESPONSE_BYTES,
) -> HttpJsonResponse:
    try:
        body = json.dumps(
            payload,
            ensure_ascii=False,
            allow_nan=False,
            separators=(",", ":"),
        ).encode("utf-8")
    except (TypeError, ValueError) as error:
        raise HttpJsonError("request payload is not valid JSON") from error
    request_headers = {
        "Accept": "application/json",
        "Content-Type": "application/json; charset=utf-8",
        "User-Agent": "SchematicSupervisorCompanion/0.1",
    }
    if headers:
        request_headers.update(headers)
    request = Request(
        url,
        data=body,
        headers=request_headers,
        method="POST",
    )
    try:
        with _opener(url).open(request, timeout=timeout_seconds) as response:
            raw = response.read(max_response_bytes + 1)
            if len(raw) > max_response_bytes:
                raise HttpJsonError("HTTP response exceeded the size limit")
            if not raw:
                parsed: Any = {}
            else:
                try:
                    parsed = json.loads(raw.decode("utf-8"))
                except (UnicodeDecodeError, json.JSONDecodeError) as error:
                    raise HttpJsonError(
                        "HTTP response was not valid UTF-8 JSON"
                    ) from error
            return HttpJsonResponse(status=response.status, body=parsed)
    except HTTPError as error:
        try:
            detail = error.read(2_048).decode("utf-8", errors="replace").strip()
        except (OSError, HTTPException):
            detail = ""
        suffix = f": {detail}" if detail else ""
        raise HttpJsonError(f"HTTP request failed with {error.code}{suffix}") from error
    except URLError as error:
        raise HttpJsonError("HTTP endpoint is unavailable") from error
    except TimeoutError as error:
        raise HttpJsonError("HTTP endpoint timed out") from error
    except HTTPException as error:
        raise HttpJsonError("HTTP endpoint returned an invalid response") from error
    except OSError as error:
        raise HttpJsonError("HTTP request failed") from error


def post_text(
    url: str,
    text: str,
    *,
    timeout_seconds: float,
    headers: Mapping[str, str] | None = None,
) -> int:
    """POST a UTF-8 text body and return the HTTP status; the response body is discarded."""

    request_headers = {
        "Content-Type": "text/plain; charset=utf-8",
        "User-Agent": "SchematicSupervisorCompanion/0.1",
    }
    if headers:
        request_headers.update(headers)
    request = Request(url, data=text.encode("utf-8"), headers=request_headers, method="POST")
    try:
        with _opener(url).open(request, timeout=timeout_seconds) as response:
            response.read(MAX_RESPONSE_BYTES)
            return response.status
    except HTTPError as error:
        raise HttpJsonError(f"HTTP request failed with {error.code}") from error
    except URLError as error:
        raise HttpJsonError("HTTP endpoint is unavailable") from error
    except TimeoutError as error:
        raise HttpJsonError("HTTP endpoint timed out") from error
    except HTTPException as error:
        raise HttpJsonError("HTTP endpoint returned an invalid response") from error
    except OSError as error:
        raise HttpJsonError("HTTP request failed") from error

# SPDX-License-Identifier: Apache-2.0
"""The single HTTP path of the SDK.

Base URL + ``/api/v1``, Authorization with one refresh on 401, ``Idempotency-Key`` on every mutation (R41),
problem+json decoding (RFC 9457) and jittered retries of safe calls (GET, or a mutation with a key; R28).
"""
from __future__ import annotations

import json
import random
import socket
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass, field
from datetime import date, datetime, timezone
from decimal import Decimal
from typing import Any, Mapping

from .auth import NoAuth, TokenProvider
from .errors import GoneError, Problem, SangamApiError, StaleLeaseError, TransportError
from .log import SangamLogger, noop_logger

API_PREFIX = "/api/v1"
SDK_VERSION = "0.1.0"


@dataclass(frozen=True)
class RetryPolicy:
    """Exponential back-off with jitter. ``max_attempts`` counts the first attempt (1 = no retry)."""

    max_attempts: int = 4
    initial_delay: float = 0.2
    max_delay: float = 5.0
    multiplier: float = 2.0

    def delay(self, retry: int) -> float:
        cap = min(self.initial_delay * (self.multiplier ** max(0, retry - 1)), self.max_delay)
        if cap <= 0:
            return 0.0
        return random.uniform(cap / 2, cap)

    @staticmethod
    def none() -> "RetryPolicy":
        return RetryPolicy(1, 0.0, 0.0, 1.0)


@dataclass
class ApiRequest:
    method: str
    path: str
    query: Mapping[str, Any] | None = None
    body: Any = None
    raw_body: bytes | None = None
    raw_content_type: str | None = None
    idempotency_key: str | None = None
    timeout: float | None = None
    retry: bool = True
    headers: dict[str, str] = field(default_factory=dict)

    @property
    def is_mutation(self) -> bool:
        return self.method not in ("GET", "HEAD")


@dataclass
class ApiResponse:
    status: int
    headers: Mapping[str, str]
    body: Any

    def header(self, name: str) -> str | None:
        for k, v in self.headers.items():
            if k.lower() == name.lower():
                return v
        return None

    @property
    def replayed(self) -> bool:
        """``Idempotent-Replayed: true``: the server answered from its idempotency store (R41)."""
        return (self.header("Idempotent-Replayed") or "").strip().lower() == "true"


def _json_default(o: Any) -> Any:
    if isinstance(o, Decimal):
        # R33: money is never a binary float; a Decimal is always a TypedValue with a string value
        return {"type": "Decimal", "value": str(o)}
    if isinstance(o, datetime):
        dt = o if o.tzinfo is not None else o.replace(tzinfo=timezone.utc)
        return dt.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
    if isinstance(o, date):
        return o.isoformat()
    if hasattr(o, "to_dict"):
        return o.to_dict()
    raise TypeError(f"cannot serialise {type(o).__name__}")


def dumps(value: Any) -> bytes:
    return json.dumps(value, default=_json_default, separators=(",", ":")).encode("utf-8")


def seg(s: str) -> str:
    """Percent-encodes one path segment."""
    if s is None:
        raise ValueError("path parameter must not be None")
    return urllib.parse.quote(str(s), safe="")


class HttpTransport:
    def __init__(self, base_url: str, token_provider: TokenProvider | None = None,
                 retry_policy: RetryPolicy | None = None, request_timeout: float = 30.0,
                 logger: SangamLogger | None = None, user_agent: str | None = None,
                 default_headers: Mapping[str, str] | None = None) -> None:
        b = base_url.rstrip("/")
        if b.endswith(API_PREFIX):
            b = b[: -len(API_PREFIX)]
        self.base = b
        self.tokens: TokenProvider = token_provider or NoAuth()
        self.retry_policy = retry_policy or RetryPolicy()
        self.request_timeout = request_timeout
        self.log = logger or noop_logger()
        self.user_agent = user_agent or f"sangam-sdk-python/{SDK_VERSION}"
        #: sent on every request unless the request overrides them (e.g. X-Sangam-Seat, X-Sangam-Acting-For-Seat)
        self.default_headers: dict[str, str] = {k: v for k, v in (default_headers or {}).items() if v is not None}
        self._opener = urllib.request.build_opener(_NoRedirect())

    def execute(self, req: ApiRequest) -> ApiResponse:
        key = req.idempotency_key
        if key is None and req.is_mutation:
            key = str(uuid.uuid4())
        safe = (not req.is_mutation) or key is not None
        max_attempts = self.retry_policy.max_attempts if (req.retry and safe) else 1
        refreshed = False
        attempt = 0
        while True:
            attempt += 1
            try:
                status, headers, body = self._send_once(req, key)
            except (urllib.error.URLError, socket.timeout, TimeoutError, ConnectionError, OSError) as e:
                if attempt < max_attempts:
                    d = self.retry_policy.delay(attempt)
                    self.log.debug("http.retry", f"retrying after transport error: {e}",
                                   {"method": req.method, "path": req.path, "attempt": attempt, "delayMs": int(d * 1000)})
                    time.sleep(d)
                    continue
                raise TransportError(f"{req.method} {req.path} failed: {e}") from e
            if status == 401 and not refreshed:
                refreshed = True
                self.tokens.invalidate()
                attempt -= 1  # an auth refresh does not consume a retry
                continue
            if 200 <= status < 300:
                return ApiResponse(status, headers, _decode(body))
            err = self._to_error(req, status, body)
            if err.retryable and attempt < max_attempts:
                d = _retry_after(headers)
                if d is None:
                    d = self.retry_policy.delay(attempt)
                self.log.debug("http.retry", f"retrying after HTTP {status}",
                               {"method": req.method, "path": req.path, "attempt": attempt, "delayMs": int(d * 1000)})
                time.sleep(d)
                continue
            raise err

    def _url(self, req: ApiRequest) -> str:
        path = req.path if req.path.startswith("/") else "/" + req.path
        url = self.base + API_PREFIX + path
        if req.query:
            pairs: list[tuple[str, str]] = []
            for k, v in req.query.items():
                if v is None:
                    continue
                if isinstance(v, (list, tuple, set)):
                    pairs.extend((k, _q(i)) for i in v)
                else:
                    pairs.append((k, _q(v)))
            if pairs:
                url += "?" + urllib.parse.urlencode(pairs)
        return url

    def _send_once(self, req: ApiRequest, key: str | None) -> tuple[int, dict[str, str], bytes]:
        headers = {"Accept": "application/json, application/problem+json", "User-Agent": self.user_agent}
        auth = self.tokens.authorization()
        if auth:
            headers["Authorization"] = auth
        if key is not None:
            headers["Idempotency-Key"] = key
        headers.update(self.default_headers)
        headers.update(req.headers)
        data: bytes | None
        if req.raw_body is not None:
            headers["Content-Type"] = req.raw_content_type or "application/octet-stream"
            data = req.raw_body
        elif req.body is not None:
            headers["Content-Type"] = "application/json"
            data = dumps(req.body)
        elif req.is_mutation:
            data = b""  # no body (e.g. claim/unclaim); Content-Length: 0
        else:
            data = None
        r = urllib.request.Request(self._url(req), data=data, headers=headers, method=req.method)
        timeout = req.timeout if req.timeout is not None else self.request_timeout
        try:
            with self._opener.open(r, timeout=timeout) as resp:
                return resp.status, dict(resp.headers.items()), resp.read()
        except urllib.error.HTTPError as e:
            try:
                body = e.read()
            finally:
                e.close()
            return e.code, dict(e.headers.items()) if e.headers else {}, body

    def _to_error(self, req: ApiRequest, status: int, body: bytes) -> SangamApiError:
        problem: Problem | None = None
        if body:
            try:
                parsed = json.loads(body.decode("utf-8"))
                if isinstance(parsed, dict):
                    problem = Problem.from_dict(parsed)
            except ValueError:
                pass
        if problem is None:
            problem = Problem(status=status, title=f"HTTP {status}",
                              detail=body.decode("utf-8", "replace") if body else None)
        if problem.status is None:
            problem.status = status
        msg = f"{req.method} {req.path} -> {status}"
        if problem.title:
            msg += " " + problem.title
        if problem.detail:
            msg += ": " + problem.detail
        if status == 409 and problem.is_("stale-lease"):
            return StaleLeaseError(409, problem, msg)
        if status == 410:
            return GoneError(410, problem, msg)
        return SangamApiError(status, problem, msg)


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):  # type: ignore[no-untyped-def]
        return None


def _q(v: Any) -> str:
    if isinstance(v, bool):
        return "true" if v else "false"
    return str(v)


def _decode(body: bytes) -> Any:
    if not body:
        return None
    try:
        return json.loads(body.decode("utf-8"), parse_float=Decimal)
    except ValueError:
        return body


def _retry_after(headers: Mapping[str, str]) -> float | None:
    for k, v in headers.items():
        if k.lower() == "retry-after":
            try:
                return min(60.0, float(v.strip()))
            except ValueError:
                return None
    return None

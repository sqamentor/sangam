# SPDX-License-Identifier: Apache-2.0
"""SDK exceptions and the RFC 9457 problem+json body."""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Mapping

_KNOWN = {"type", "title", "status", "detail", "instance", "code", "traceId"}


@dataclass
class Problem:
    """``application/problem+json`` (RFC 9457). ``code`` is the Sangam machine code (``STALE_LEASE`` ...)."""

    type: str | None = None
    title: str | None = None
    status: int | None = None
    detail: str | None = None
    instance: str | None = None
    code: str | None = None
    trace_id: str | None = None
    extensions: dict[str, Any] = field(default_factory=dict)

    @classmethod
    def from_dict(cls, d: Mapping[str, Any]) -> "Problem":
        return cls(
            type=d.get("type"),
            title=d.get("title"),
            status=d.get("status"),
            detail=d.get("detail"),
            instance=d.get("instance"),
            code=d.get("code"),
            trace_id=d.get("traceId"),
            extensions={k: v for k, v in d.items() if k not in _KNOWN},
        )

    def is_(self, name: str) -> bool:
        """True when ``code`` or the last segment of ``type`` equals ``name`` (case and -/_ insensitive)."""
        n = _norm(name)
        if self.code and _norm(self.code) == n:
            return True
        if self.type:
            t = self.type.rsplit("/", 1)[-1].rsplit("#", 1)[-1]
            return _norm(t) == n
        return False


def _norm(s: str) -> str:
    return s.lower().replace("_", "-")


class SangamError(Exception):
    """Root of all SDK exceptions."""


class SangamApiError(SangamError):
    """A non-2xx answer, carrying the parsed (or synthetic) problem body."""

    def __init__(self, status: int, problem: Problem, message: str) -> None:
        super().__init__(message)
        self.status = status
        self.problem = problem

    @property
    def retryable(self) -> bool:
        """408, 425, 429, 500, 502, 503, 504: the same request (same Idempotency-Key) may be repeated."""
        return self.status in (408, 425, 429, 500, 502, 503, 504)


class StaleLeaseError(SangamApiError):
    """409 ``stale-lease`` (VI.3.2 rule 2, R20): the lockVersion is no longer current; the server changed nothing."""

    @property
    def retryable(self) -> bool:
        return False


class GoneError(SangamApiError):
    """410 ``GONE_INSTANCE_DELETED`` (VI.5, R23): the instance was cancelled/deleted; abandon the work."""

    @property
    def retryable(self) -> bool:
        return False


class TransportError(SangamError):
    """Network failure (connect, reset, timeout) after the configured retries were used up."""


class TokenError(SangamError):
    """No access token could be obtained from the identity provider."""

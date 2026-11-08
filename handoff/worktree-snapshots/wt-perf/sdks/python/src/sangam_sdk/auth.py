# SPDX-License-Identifier: Apache-2.0
"""Token providers for the ``Authorization`` header (ARCHITECTURE section 4: ``Bearer <OIDC JWT>``)."""
from __future__ import annotations

import base64
import json
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Optional, Protocol, runtime_checkable

from .errors import TokenError


@runtime_checkable
class TokenProvider(Protocol):
    """Thread-safe source of the Authorization header."""

    def authorization(self) -> Optional[str]:
        """Full header value (``Bearer ...``), or None for anonymous."""
        ...

    def invalidate(self) -> None:
        """Called after a 401; the next call must not return the cached token."""
        ...


class NoAuth:
    """No authentication (local dev profile without a token)."""

    def authorization(self) -> Optional[str]:
        return None

    def invalidate(self) -> None:
        pass


class StaticTokenProvider:
    """A fixed bearer token (dev profile static token, or a token injected by the platform)."""

    def __init__(self, token: str) -> None:
        if not token:
            raise ValueError("token must not be empty")
        self._token = token

    def authorization(self) -> Optional[str]:
        return "Bearer " + self._token

    def invalidate(self) -> None:
        pass


class BasicAuthProvider:
    """HTTP Basic (Flowable external-client parity, FC-37). For dev/test gateways only; Sangam expects OIDC."""

    def __init__(self, user: str, password: str) -> None:
        raw = f"{user}:{password}".encode("utf-8")
        self._value = "Basic " + base64.b64encode(raw).decode("ascii")

    def authorization(self) -> Optional[str]:
        return self._value

    def invalidate(self) -> None:
        pass


class ClientCredentialsTokenProvider:
    """OAuth 2.0 client-credentials grant (RFC 6749 section 4.4) against an OIDC token endpoint.

    The token is cached and refreshed ``refresh_skew`` seconds before ``expires_in`` ends. Refresh is
    single-flight under a lock so concurrent worker threads do not stampede the IdP.
    """

    def __init__(self, token_endpoint: str, client_id: str, client_secret: str, *, scope: str | None = None,
                 audience: str | None = None, client_auth: str = "basic", refresh_skew: float = 30.0,
                 timeout: float = 10.0) -> None:
        if client_auth not in ("basic", "post"):
            raise ValueError("client_auth must be 'basic' or 'post'")
        self._endpoint = token_endpoint
        self._client_id = client_id
        self._client_secret = client_secret
        self._scope = scope
        self._audience = audience
        self._client_auth = client_auth
        self._skew = refresh_skew
        self._timeout = timeout
        self._lock = threading.Lock()
        self._token: str | None = None
        self._expires_at = 0.0

    def token(self) -> str:
        t = self._token
        if t is not None and time.monotonic() < self._expires_at - self._skew:
            return t
        with self._lock:
            t = self._token
            if t is not None and time.monotonic() < self._expires_at - self._skew:
                return t
            return self._fetch()

    def authorization(self) -> Optional[str]:
        return "Bearer " + self.token()

    def invalidate(self) -> None:
        with self._lock:
            self._token = None
            self._expires_at = 0.0

    def _fetch(self) -> str:
        form = {"grant_type": "client_credentials"}
        if self._scope:
            form["scope"] = self._scope
        if self._audience:
            form["audience"] = self._audience
        headers = {"Content-Type": "application/x-www-form-urlencoded", "Accept": "application/json"}
        if self._client_auth == "basic":
            raw = (urllib.parse.quote(self._client_id, safe="") + ":"
                   + urllib.parse.quote(self._client_secret, safe="")).encode("utf-8")
            headers["Authorization"] = "Basic " + base64.b64encode(raw).decode("ascii")
        else:
            form["client_id"] = self._client_id
            form["client_secret"] = self._client_secret
        data = urllib.parse.urlencode(form).encode("utf-8")
        req = urllib.request.Request(self._endpoint, data=data, headers=headers, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=self._timeout) as resp:
                body = json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            detail = e.read().decode("utf-8", "replace")
            raise TokenError(f"token endpoint returned HTTP {e.code}: {detail}") from e
        except (urllib.error.URLError, OSError, ValueError) as e:
            raise TokenError(f"token request failed: {e}") from e
        token = body.get("access_token")
        if not token:
            raise TokenError("token endpoint response has no access_token")
        expires_in = float(body.get("expires_in", 300))
        self._token = token
        self._expires_at = time.monotonic() + expires_in
        return token

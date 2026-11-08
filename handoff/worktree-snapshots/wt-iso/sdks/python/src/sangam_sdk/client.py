# SPDX-License-Identifier: Apache-2.0
"""Entry point: :class:`SangamClient`, a typed client for ``/api/v1``. Thread-safe; share one instance."""
from __future__ import annotations

from typing import Any, Mapping

from .api import AdminApi, AuditApi, DecisionsApi, OpsApi, ProcessesApi, RepositoryApi, TasksApi, WorkApi
from .auth import StaticTokenProvider, TokenProvider
from .http import ApiRequest, HttpTransport, RetryPolicy
from .log import SangamLogger


class SangamClient:
    """Example::

        client = SangamClient("https://sangam.example.gov.in",
                              token_provider=ClientCredentialsTokenProvider(token_url, "bill-worker", secret),
                              seat="SEAT-DDO-0042")
        pi = client.processes.start("p1_bill", "BILL-2026-0001", {"amount": Decimal("125000.00")})

    ``seat`` / ``acting_for_seat`` become the default ``X-Sangam-Seat`` / ``X-Sangam-Acting-For-Seat`` headers
    (IX.5 rules 1 and 4). The tenant always comes from the token.
    """

    def __init__(self, base_url: str, *, token_provider: TokenProvider | None = None, token: str | None = None,
                 seat: str | None = None, acting_for_seat: str | None = None,
                 retry_policy: RetryPolicy | None = None, request_timeout: float = 30.0,
                 logger: SangamLogger | None = None, user_agent: str | None = None,
                 default_headers: Mapping[str, str] | None = None) -> None:
        if token_provider is None and token is not None:
            token_provider = StaticTokenProvider(token)
        headers = dict(default_headers or {})
        if seat is not None:
            headers["X-Sangam-Seat"] = seat
        if acting_for_seat is not None:
            headers["X-Sangam-Acting-For-Seat"] = acting_for_seat
        self.logger = logger or SangamLogger()
        self.transport = HttpTransport(base_url, token_provider, retry_policy, request_timeout, self.logger,
                                       user_agent, headers)
        self.work = WorkApi(self.transport)
        self.tasks = TasksApi(self.transport)
        self.processes = ProcessesApi(self.transport)
        self.decisions = DecisionsApi(self.transport)
        self.audit = AuditApi(self.transport)
        self.admin = AdminApi(self.transport)
        self.ops = OpsApi(self.transport)
        self.repository = RepositoryApi(self.transport)

    def me(self) -> dict[str, Any]:
        """``GET /me``: the authenticated caller (tenantId, principalId, groups, seatId, permissions, ...)."""
        return self.transport.execute(ApiRequest("GET", "/me")).body or {}

    def set_seat(self, seat: str | None, acting_for_seat: str | None = None) -> None:
        """Changes the default seat context for subsequent calls."""
        for name, value in (("X-Sangam-Seat", seat), ("X-Sangam-Acting-For-Seat", acting_for_seat)):
            if value is None:
                self.transport.default_headers.pop(name, None)
            else:
                self.transport.default_headers[name] = value

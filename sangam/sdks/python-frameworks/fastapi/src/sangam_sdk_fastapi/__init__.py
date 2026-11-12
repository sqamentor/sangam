# SPDX-License-Identifier: Apache-2.0
"""FastAPI / Starlette adapter for the Sangam Python SDK worker (M-027).

Starts a :class:`sangam_sdk.Worker` when the application starts and stops it gracefully (``Worker.stop``: no new
fetches, wait for in-flight handlers, then cancel and unlock) when it shuts down, through the ASGI lifespan
(Starlette ``Lifespan``: a callable ``app -> async context manager``, whose yielded mapping becomes ``request.state``).
Handlers and sagas are registered from the arguments (or from a factory that builds the worker from configuration);
the core SDK gets no dependency on FastAPI.

Usage::

    from fastapi import Depends, FastAPI
    from sangam_sdk import Saga, TopicSubscription
    from sangam_sdk_fastapi import WorkerSettings, get_worker, sangam_lifespan

    app = FastAPI(lifespan=sangam_lifespan(
        lambda app: WorkerSettings.from_env().build_worker(),
        subscriptions=[TopicSubscription("payment", pay, lock_duration_ms=120_000, irreversible=True)],
        sagas=[charge_saga]))

    @app.get("/worker")
    def worker_info(worker=Depends(get_worker)):
        return {"workerId": worker.worker_id, "inFlight": worker.in_flight_count}

An application that already has a lifespan passes it as ``inner=`` (it is entered first and left last), or calls
:func:`install` on an existing app.
"""
from __future__ import annotations

import os
from contextlib import AsyncExitStack, asynccontextmanager
from dataclasses import dataclass
from typing import Any, AsyncIterator, Callable, Iterable, Mapping, Optional, Union

import anyio.to_thread
from starlette.requests import Request

from sangam_sdk import Saga, SangamClient, TopicSubscription, Worker

__version__ = "0.1.0"

#: key of the worker in the lifespan state (``request.state.sangam_worker``) and on ``app.state``
STATE_KEY = "sangam_worker"

WorkerSource = Union[Worker, Callable[[Any], Worker]]
Lifespan = Callable[[Any], Any]


@dataclass(frozen=True)
class WorkerSettings:
    """Worker configuration, e.g. from the environment (:meth:`from_env`)."""

    base_url: str
    token: Optional[str] = None
    worker_id: Optional[str] = None
    max_concurrency: int = 8
    shutdown_timeout: float = 30.0

    @classmethod
    def from_env(cls, prefix: str = "SANGAM_", environ: Optional[Mapping[str, str]] = None) -> "WorkerSettings":
        """``<prefix>URL`` (required), ``<prefix>TOKEN``, ``<prefix>WORKER_ID``, ``<prefix>MAX_CONCURRENCY``,
        ``<prefix>SHUTDOWN_TIMEOUT`` (seconds)."""
        env = os.environ if environ is None else environ
        url = env.get(prefix + "URL")
        if not url:
            raise ValueError(f"{prefix}URL is required")
        conc = env.get(prefix + "MAX_CONCURRENCY")
        timeout = env.get(prefix + "SHUTDOWN_TIMEOUT")
        return cls(url, env.get(prefix + "TOKEN") or None, env.get(prefix + "WORKER_ID") or None,
                   int(conc) if conc else 8, float(timeout) if timeout else 30.0)

    def build_worker(self, **client_options: Any) -> Worker:
        """A worker on a new :class:`~sangam_sdk.SangamClient` (``client_options`` are passed to the client, e.g.
        ``token_provider=``)."""
        if self.token is not None and "token_provider" not in client_options:
            client_options.setdefault("token", self.token)
        client = SangamClient(self.base_url, **client_options)
        return Worker(client, worker_id=self.worker_id, max_concurrency=self.max_concurrency,
                      shutdown_timeout=self.shutdown_timeout)


def sangam_lifespan(worker: WorkerSource, *, subscriptions: Iterable[TopicSubscription] = (),
                    sagas: Iterable[Saga] = (), stop_timeout: Optional[float] = None,
                    inner: Optional[Lifespan] = None, state_key: str = STATE_KEY) -> Callable[[Any], Any]:
    """A lifespan for ``FastAPI(lifespan=...)`` / ``Starlette(lifespan=...)``.

    ``worker`` is a :class:`~sangam_sdk.Worker` or a factory ``app -> Worker`` (called at startup, so configuration is
    read then). ``subscriptions`` and ``sagas`` are registered before the worker starts. On shutdown ``Worker.stop``
    runs in a worker thread (it blocks up to ``stop_timeout``, default the worker's ``shutdown_timeout``), so the
    event loop keeps serving while handlers drain. The worker is put on ``app.state`` and in the lifespan state under
    ``state_key``; ``inner`` (another lifespan) is entered before the worker starts and left after it stopped, and its
    state is merged.
    """
    subs = tuple(subscriptions)
    saga_list = tuple(sagas)

    @asynccontextmanager
    async def lifespan(app: Any) -> AsyncIterator[dict[str, Any]]:
        async with AsyncExitStack() as stack:
            state: dict[str, Any] = {}
            if inner is not None:
                inner_state = await stack.enter_async_context(inner(app))
                if isinstance(inner_state, Mapping):
                    state.update(inner_state)
            w = worker(app) if callable(worker) else worker
            for s in subs:
                w.subscribe(s)
            for saga in saga_list:
                saga.register_on(w)
            app_state = getattr(app, "state", None)
            if app_state is not None:
                setattr(app_state, state_key, w)
            w.start()
            try:
                state[state_key] = w
                yield state
            finally:
                await anyio.to_thread.run_sync(w.stop, stop_timeout)

    return lifespan


def install(app: Any, worker: WorkerSource, **options: Any) -> Any:
    """Adds the worker lifespan to an existing FastAPI / Starlette app, keeping its current lifespan as ``inner``.
    Call before the app starts. Returns ``app``."""
    router = app.router
    router.lifespan_context = sangam_lifespan(worker, inner=router.lifespan_context, **options)
    return app


def get_worker(request: Request) -> Worker:
    """FastAPI dependency (``Depends(get_worker)``): the worker of the running app."""
    w = getattr(request.state, STATE_KEY, None)
    if w is None:
        w = getattr(request.app.state, STATE_KEY, None)
    if w is None:
        raise RuntimeError("the Sangam worker is not running: use sangam_lifespan(...) or install(app, ...)")
    return w


__all__ = ["STATE_KEY", "WorkerSettings", "get_worker", "install", "sangam_lifespan"]

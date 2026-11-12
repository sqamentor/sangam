# SPDX-License-Identifier: Apache-2.0
"""M-027: the FastAPI lifespan adapter, driven directly (``async with lifespan(app)``) so no HTTP client is needed."""
from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager
from types import SimpleNamespace
from typing import Any, AsyncIterator, Optional

import pytest
from fastapi import FastAPI

from sangam_sdk import Saga, SangamClient, SangamLogger, TopicSubscription, Worker
from sangam_sdk_fastapi import STATE_KEY, WorkerSettings, get_worker, install, sangam_lifespan


class FakeWorker:
    def __init__(self) -> None:
        self.events: list[Any] = []
        self.topics: list[str] = []
        self.worker_id = "fake"

    def subscribe(self, sub: TopicSubscription) -> "FakeWorker":
        self.topics.append(sub.topic)
        return self

    def start(self) -> "FakeWorker":
        self.events.append("start")
        return self

    def stop(self, timeout: Optional[float] = None) -> None:
        self.events.append(("stop", timeout))


def run(coro: Any) -> Any:
    return asyncio.run(coro)


def sub(topic: str) -> TopicSubscription:
    return TopicSubscription(topic, lambda ctx: None)


def test_starts_registers_and_stops_with_the_app() -> None:
    fake = FakeWorker()
    saga = Saga.step("charge").action("pay", lambda c: {}).compensation("refund", lambda c: {}).build()
    app = FastAPI(lifespan=sangam_lifespan(fake, subscriptions=[sub("mail")], sagas=[saga],  # type: ignore[arg-type]
                                           stop_timeout=5.0))

    async def go() -> None:
        async with app.router.lifespan_context(app) as state:
            assert state[STATE_KEY] is fake
            assert getattr(app.state, STATE_KEY) is fake
            assert fake.events == ["start"]
            assert fake.topics == ["mail", "pay", "refund"]  # registered before start
        assert fake.events == ["start", ("stop", 5.0)]

    run(go())


def test_factory_is_called_at_startup_with_the_app() -> None:
    seen: list[Any] = []
    fake = FakeWorker()

    def factory(app: Any) -> Any:
        seen.append(app)
        return fake

    app = FastAPI(lifespan=sangam_lifespan(factory))
    assert seen == []

    async def go() -> None:
        async with app.router.lifespan_context(app):
            assert seen == [app]

    run(go())
    assert fake.events == ["start", ("stop", None)]


def test_worker_stops_when_the_app_body_fails() -> None:
    fake = FakeWorker()
    lifespan = sangam_lifespan(fake)  # type: ignore[arg-type]

    async def go() -> None:
        async with lifespan(SimpleNamespace(state=SimpleNamespace())):
            raise RuntimeError("serving failed")

    with pytest.raises(RuntimeError):
        run(go())
    assert fake.events == ["start", ("stop", None)]


def test_inner_lifespan_wraps_the_worker_and_its_state_is_merged() -> None:
    order: list[str] = []
    fake = FakeWorker()

    @asynccontextmanager
    async def inner(app: Any) -> AsyncIterator[dict[str, Any]]:
        order.append("inner-enter")
        yield {"db": "pool"}
        order.append("inner-exit")

    lifespan = sangam_lifespan(fake, inner=inner)  # type: ignore[arg-type]

    async def go() -> None:
        async with lifespan(SimpleNamespace(state=SimpleNamespace())) as state:
            order.append("serving")
            assert state == {"db": "pool", STATE_KEY: fake}

    run(go())
    assert order == ["inner-enter", "serving", "inner-exit"]
    assert fake.events == ["start", ("stop", None)]


def test_install_keeps_the_existing_lifespan() -> None:
    order: list[str] = []
    fake = FakeWorker()

    @asynccontextmanager
    async def existing(app: Any) -> AsyncIterator[None]:
        order.append("existing")
        yield None

    app = install(FastAPI(lifespan=existing), fake)

    async def go() -> None:
        async with app.router.lifespan_context(app) as state:
            assert state == {STATE_KEY: fake}

    run(go())
    assert order == ["existing"] and fake.events == ["start", ("stop", None)]


def test_get_worker_reads_request_then_app_state() -> None:
    fake = FakeWorker()
    req = SimpleNamespace(state=SimpleNamespace(**{STATE_KEY: fake}), app=SimpleNamespace(state=SimpleNamespace()))
    assert get_worker(req) is fake  # type: ignore[arg-type]
    req = SimpleNamespace(state=SimpleNamespace(), app=SimpleNamespace(state=SimpleNamespace(**{STATE_KEY: fake})))
    assert get_worker(req) is fake  # type: ignore[arg-type]
    with pytest.raises(RuntimeError):
        get_worker(SimpleNamespace(state=SimpleNamespace(), app=SimpleNamespace(state=SimpleNamespace())))  # type: ignore[arg-type]


def test_settings_from_env_and_build_worker() -> None:
    with pytest.raises(ValueError):
        WorkerSettings.from_env(environ={})
    s = WorkerSettings.from_env(environ={"SANGAM_URL": "http://sangam.test", "SANGAM_TOKEN": "t",
                                         "SANGAM_WORKER_ID": "w-1", "SANGAM_MAX_CONCURRENCY": "2",
                                         "SANGAM_SHUTDOWN_TIMEOUT": "4.5"})
    assert s == WorkerSettings("http://sangam.test", "t", "w-1", 2, 4.5)
    w = s.build_worker(logger=SangamLogger(hook=lambda *_: None))
    assert isinstance(w, Worker) and isinstance(w.client, SangamClient)
    assert w.worker_id == "w-1" and w.max_concurrency == 2 and w.shutdown_timeout == 4.5

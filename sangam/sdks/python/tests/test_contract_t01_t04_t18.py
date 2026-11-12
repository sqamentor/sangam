# SPDX-License-Identifier: Apache-2.0
"""M-023 / R18 / AC-08: contract tests T01-T04 and T18 (SANGAM.md II §16) for the Python SDK, run against the R18 test
engine (:mod:`sangam_sdk.testing`) through the real ``SangamClient`` and ``Worker``."""
from __future__ import annotations

import json
import threading
import time
from datetime import date, datetime
from decimal import Decimal
from pathlib import Path
from typing import Any, Callable

import pytest

from sangam_sdk import (Backoff, OutcomeUnknown, RetryPolicy, SangamApiError, SangamLogger, StaleLeaseError,
                        TopicSubscription, TransportError, Worker)
from sangam_sdk.testing import SangamTestEngine
from sangam_sdk.typed import decode_value, encode_value

VECTORS = Path(__file__).resolve().parents[2] / "testing" / "t18-vectors.json"
FAST = Backoff(0.001, 0.002, 1, 0)


def until(cond: Callable[[], bool], timeout: float = 10.0) -> None:
    end = time.monotonic() + timeout
    while not cond():
        if time.monotonic() > end:
            raise AssertionError("condition not met in time")
        time.sleep(0.005)


class Run:
    """An engine plus a worker on it, with every log event recorded."""

    def __init__(self, engine: SangamTestEngine, worker_id: str = "worker-1") -> None:
        self.logs: list[tuple[str, dict[str, Any]]] = []
        logger = SangamLogger(hook=lambda _l, event, _m, fields, _e: self.logs.append((event, dict(fields))))
        self.client = engine.client(retry_policy=RetryPolicy.none(), logger=logger)
        self.worker = Worker(self.client, worker_id=worker_id, max_concurrency=1, max_tasks_per_fetch=1,
                             async_response_timeout_ms=10, fetch_backoff=FAST, report_backoff=FAST,
                             shutdown_timeout=2.0, logger=logger)

    def events(self, name: str) -> list[dict[str, Any]]:
        return [f for e, f in self.logs if e == name]


@pytest.fixture()
def engine():
    with SangamTestEngine(irreversible_topics=["pay-irreversible"]) as e:
        yield e


# ------------------------------------------------------------------ T01

def test_t01_duplicate_start_is_one_instance_and_conflicting_payload_rejected(engine: SangamTestEngine) -> None:
    client = engine.client(retry_policy=RetryPolicy.none())
    first = client.processes.start("p1_bill", "BILL-1", {"amount": Decimal("125000.00")}, idempotency_key="start-1")
    again = client.processes.start("p1_bill", "BILL-1", {"amount": Decimal("125000.00")}, idempotency_key="start-1")
    assert again.id == first.id
    assert again.idempotent_replayed is True and first.idempotent_replayed is False
    assert len(engine.instances) == 1
    with pytest.raises(SangamApiError) as err:
        client.processes.start("p1_bill", "BILL-1", {"amount": Decimal("1.00")}, idempotency_key="start-1")
    assert err.value.status == 422 and err.value.problem.code == "IDEMPOTENCY_KEY_REUSED"
    assert len(engine.instances) == 1


def test_t01_simultaneous_duplicate_starts(engine: SangamTestEngine) -> None:
    client = engine.client(retry_policy=RetryPolicy.none())
    ids: list[str] = []
    threads = [threading.Thread(target=lambda: ids.append(client.processes.start("p1", "BK", None,
                                                                                    idempotency_key="same").id))
               for _ in range(5)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert len(set(ids)) == 1 and len(engine.instances) == 1


# ------------------------------------------------------------------ T02

def test_t02_lost_complete_response_is_retried_with_the_same_key_and_completes_once(engine: SangamTestEngine) -> None:
    wid = engine.add_work("pay", {"amount": {"type": "Decimal", "value": "12.50"}})
    engine.lose_next_response("complete")
    run = Run(engine)
    run.worker.subscribe(TopicSubscription("pay", lambda ctx: {"paid": True}, lock_duration_ms=60_000))
    with run.worker:
        until(lambda: engine.state(wid) == "COMPLETED" and len(run.events("work.complete")) == 1)
    calls = engine.calls_of("complete")
    assert len(calls) >= 2, "the lost answer must be retried"
    assert len({c[2] for c in calls}) == 1, "every retry carries the same Idempotency-Key"
    assert engine.completions(wid) == 1


def test_t02_outcome_unknown_opens_reconciliation_and_is_never_reoffered(engine: SangamTestEngine) -> None:
    wid = engine.add_work("pay")

    def pay(_ctx: Any) -> None:
        raise OutcomeUnknown("bank timeout after debit request")

    run = Run(engine)
    run.worker.subscribe(TopicSubscription("pay", pay, lock_duration_ms=60_000))
    with run.worker:
        until(lambda: engine.state(wid) == "UNKNOWN_OUTCOME")
        engine.advance(10 * 60_000)
        time.sleep(0.1)  # further fetches run
    assert [i for i in engine.reconciliation_items if i["workId"] == wid]
    assert len([c for c in engine.calls_of("fetch-and-lock")]) > 1
    assert engine.snapshot(wid)["attempts"] == 1, "UNKNOWN_OUTCOME work is never handed out again"


def test_t02_unexpected_error_on_irreversible_topic_is_outcome_unknown(engine: SangamTestEngine) -> None:
    wid = engine.add_work("pay-irreversible")

    def pay(_ctx: Any) -> None:
        raise RuntimeError("connection reset after send")

    run = Run(engine)
    run.worker.subscribe(TopicSubscription("pay-irreversible", pay, lock_duration_ms=60_000, irreversible=True))
    with run.worker:
        until(lambda: engine.state(wid) == "UNKNOWN_OUTCOME")
    assert engine.calls_of("fail") == []


# ------------------------------------------------------------------ T03

def test_t03_stale_worker_cannot_complete_after_lease_expiry(engine: SangamTestEngine) -> None:
    wid = engine.add_work("pay")
    a = engine.client(retry_policy=RetryPolicy.none())
    b = engine.client(retry_policy=RetryPolicy.none())
    [wa] = a.work.fetch_and_lock("worker-a", [{"name": "pay", "lockDurationMs": 10_000}])
    engine.advance(10_001)
    [wb] = b.work.fetch_and_lock("worker-b", [{"name": "pay", "lockDurationMs": 10_000}])
    assert wb.id == wid and wb.lock_version > wa.lock_version and wb.operation_id == wa.operation_id
    with pytest.raises(StaleLeaseError) as err:
        a.work.complete(wid, "worker-a", wa.lock_version, wa.attempt_id, {"by": "a"})
    assert err.value.retryable is False and err.value.problem.code == "STALE_LEASE"
    assert engine.state(wid) == "LOCKED" and engine.snapshot(wid)["workerId"] == "worker-b"
    b.work.complete(wid, "worker-b", wb.lock_version, wb.attempt_id, {"by": "b"})
    assert engine.completions(wid) == 1 and engine.snapshot(wid)["output"] == {"by": "b"}


def test_t03_worker_drops_a_stale_result_without_retrying(engine: SangamTestEngine) -> None:
    wid = engine.add_work("pay")
    release = threading.Event()
    run = Run(engine, "worker-a")

    def slow(_ctx: Any) -> dict[str, Any]:
        release.wait(10)
        return {"by": "a"}

    run.worker.subscribe(TopicSubscription("pay", slow, lock_duration_ms=30_000))
    other = engine.client(retry_policy=RetryPolicy.none())
    with run.worker:
        until(lambda: engine.state(wid) == "LOCKED")
        engine.advance(30_001)
        [wb] = other.work.fetch_and_lock("worker-b", [{"name": "pay"}])
        release.set()
        until(lambda: bool(run.events("work.abandoned")) or bool(run.events("work.lease.lost")))
        time.sleep(0.05)
    a_completes = [c for c in engine.calls_of("complete") if c[3] and c[3].get("workerId") == "worker-a"]
    assert len(a_completes) <= 1, "a 409 STALE_LEASE is never retried"
    assert engine.state(wid) == "LOCKED" and engine.snapshot(wid)["workerId"] == "worker-b"
    other.work.complete(wid, "worker-b", wb.lock_version, wb.attempt_id)
    assert engine.completions(wid) == 1


# ------------------------------------------------------------------ T04

def test_t04_lost_fetch_answer_keeps_acknowledged_work_locked_until_expiry(engine: SangamTestEngine) -> None:
    wid = engine.add_work("pay")
    client = engine.client(retry_policy=RetryPolicy.none())
    engine.lose_next_response("fetch-and-lock")
    with pytest.raises(TransportError):
        client.work.fetch_and_lock("worker-a", [{"name": "pay", "lockDurationMs": 20_000}])
    assert engine.state(wid) == "LOCKED"
    assert client.work.fetch_and_lock("worker-b", [{"name": "pay"}]) == [], "no immediate re-dispatch"
    engine.advance(19_999)
    assert client.work.fetch_and_lock("worker-b", [{"name": "pay"}]) == []
    engine.advance(1)
    [w] = client.work.fetch_and_lock("worker-b", [{"name": "pay"}])
    assert w.id == wid and w.lock_version > 1


def test_t04_heartbeating_lease_holder_is_never_reoffered(engine: SangamTestEngine) -> None:
    wid = engine.add_work("pay")
    client = engine.client(retry_policy=RetryPolicy.none())
    [w] = client.work.fetch_and_lock("worker-a", [{"name": "pay", "lockDurationMs": 9_000}])
    for _ in range(5):
        engine.advance(6_000)  # under the lease, beyond lease/3
        ls = client.work.extend_lock(wid, "worker-a", w.lock_version, w.attempt_id, 9_000)
        assert ls is not None and ls.lock_version == w.lock_version
        assert client.work.fetch_and_lock("worker-b", [{"name": "pay"}]) == []
    client.work.complete(wid, "worker-a", w.lock_version, w.attempt_id)
    assert engine.completions(wid) == 1


# ------------------------------------------------------------------ T18

def _vectors() -> list[dict[str, Any]]:
    return json.loads(VECTORS.read_text(encoding="utf-8"))["vectors"]


def _same(kind: str, expected: Any, actual: Any) -> None:
    if kind == "null":
        assert actual is None
    elif kind == "decimal":
        assert isinstance(actual, Decimal) and str(actual) == expected  # same number and scale
    elif kind in ("biginteger", "long", "integer"):
        assert isinstance(actual, int) and not isinstance(actual, bool) and actual == int(expected)
    elif kind == "boolean":
        assert actual is (expected == "true")
    elif kind == "string":
        assert actual == expected
    elif kind == "date":
        assert isinstance(actual, datetime)
        assert actual == datetime.fromisoformat(expected.replace("Z", "+00:00"))  # same instant
    elif kind == "localdate":
        assert actual == date.fromisoformat(expected)
    else:
        raise AssertionError(f"unknown kind {kind}")


@pytest.mark.parametrize("v", _vectors(), ids=lambda v: v["name"])
def test_t18_vector_survives_decode_and_re_encode(v: dict[str, Any]) -> None:
    decoded = decode_value(v["wire"])
    _same(v["kind"], v["value"], decoded)
    _same(v["kind"], v["value"], decode_value(json.loads(json.dumps(encode_value(decoded)))))


def test_t18_vectors_survive_a_round_trip_through_a_worker(engine: SangamTestEngine) -> None:
    vectors = _vectors()
    wid = engine.add_work("t18", {v["name"]: v["wire"] for v in vectors})
    run = Run(engine)
    run.worker.subscribe(TopicSubscription("t18", lambda ctx: dict(ctx.variables), lock_duration_ms=60_000))
    with run.worker:
        until(lambda: engine.state(wid) == "COMPLETED")
    out = engine.snapshot(wid)["output"]
    for v in vectors:
        assert v["name"] in out
        _same(v["kind"], v["value"], decode_value(out[v["name"]]))

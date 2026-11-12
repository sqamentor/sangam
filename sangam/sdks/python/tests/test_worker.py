# SPDX-License-Identifier: Apache-2.0
"""Worker runtime: loop, stored-outcome completion (SL-16), outcome mapping, report retries, error mapping.

The real ``SangamClient``/``WorkApi`` build the request bodies; only the transport's ``execute`` is replaced by an
in-process fake, and HTTP errors are produced by the real ``HttpTransport._to_error`` mapping.
"""
from __future__ import annotations

import json
import threading
import time
from datetime import datetime, timedelta, timezone
from typing import Any, Optional

import pytest

from sangam_sdk import (Backoff, BpmnError, GoneError, OutcomeUnknown, SangamApiError, SangamClient, SangamLogger,
                        StaleLeaseError, TaskCancelled, TaskFailed, TopicSubscription, Worker)
from sangam_sdk.http import ApiRequest, ApiResponse
from sangam_sdk.models import ExternalWork
from sangam_sdk.worker import stored_outcome_completion

REPORTS = {"complete", "fail", "bpmn-error", "outcome-unknown", "unlock", "cancel-ack"}

SUCCEEDED = {
    "state": "SUCCEEDED", "attempts": 2, "capabilityClass": "A_IDEMPOTENT_WITH_LOOKUP", "mayDispatch": False,
    "storedOutcome": {"output": {"paymentRef": "PAY-77", "amount": {"type": "Decimal", "value": "12.50"}},
                      "providerRef": "PAY-77", "duplicate": False},
}


class FakeTransport:
    """Records every request; answers fetch-and-lock from a queue and result calls from scripted statuses."""

    def __init__(self, real: Any) -> None:
        self.real = real
        self.calls: list[ApiRequest] = []
        self.batches: list[list[dict[str, Any]]] = []
        self.replies: dict[str, list[tuple[int, Any]]] = {}
        self.cond = threading.Condition()

    def reply(self, action: str, *statuses: tuple[int, Any]) -> "FakeTransport":
        self.replies.setdefault(action, []).extend(statuses)
        return self

    def execute(self, req: ApiRequest) -> ApiResponse:
        with self.cond:
            self.calls.append(req)
            self.cond.notify_all()
        action = req.path.rsplit("/", 1)[-1]
        if action == "fetch-and-lock":
            if self.batches:
                return ApiResponse(200, {}, self.batches.pop(0))
            time.sleep(0.02)
            return ApiResponse(200, {}, [])
        queued = self.replies.get(action)
        status, body = queued.pop(0) if queued else (204, None)
        if status >= 400:
            raise self.real._to_error(req, status, json.dumps(body).encode() if body is not None else b"")
        return ApiResponse(status, {}, body)

    def of(self, action: str) -> list[ApiRequest]:
        return [c for c in self.calls if c.path.rsplit("/", 1)[-1] == action]

    def wait_for(self, pred: Any, timeout: float = 5.0) -> ApiRequest:
        end = time.monotonic() + timeout
        with self.cond:
            while True:
                for c in self.calls:
                    if pred(c):
                        return c
                left = end - time.monotonic()
                if left <= 0:
                    raise AssertionError("no matching call")
                self.cond.wait(left)


class Harness:
    def __init__(self) -> None:
        self.logs: list[tuple[str, dict[str, Any]]] = []
        logger = SangamLogger(hook=lambda _lvl, event, _msg, fields, _err: self.logs.append((event, dict(fields))))
        self.client = SangamClient("http://sangam.test/", logger=logger)
        self.http = FakeTransport(self.client.transport)
        self.client.work._http = self.http  # the real WorkApi builds the bodies; only the wire is faked
        self.worker = Worker(self.client, worker_id="worker-1", max_concurrency=1, max_tasks_per_fetch=1,
                             async_response_timeout_ms=50, fetch_backoff=Backoff(0.001, 0.002, 1, 0),
                             report_backoff=Backoff(0.001, 0.002, 1, 0), shutdown_timeout=2.0, logger=logger)

    def events(self, name: str) -> list[dict[str, Any]]:
        return [f for e, f in self.logs if e == name]


def work(**over: Any) -> dict[str, Any]:
    d: dict[str, Any] = {"id": "wk-1", "topic": "pay", "lockVersion": 3, "attemptId": "att-1", "operationId": "op-1",
                         "lockExpiresAt": (datetime.now(timezone.utc) + timedelta(minutes=1)).isoformat(),
                         "retries": 3, "variables": {}}
    d.update(over)
    return d


def run_one(h: Harness, handler: Any, item: Optional[dict[str, Any]] = None, **sub: Any) -> ApiRequest:
    """Runs one leased item through the worker loop and returns its report call."""
    h.http.batches.append([item or work()])
    h.worker.subscribe(TopicSubscription("pay", handler, lock_duration_ms=60_000, **sub))
    h.worker.start()
    try:
        return h.http.wait_for(lambda c: c.path.rsplit("/", 1)[-1] in REPORTS)
    finally:
        h.worker.stop(2.0)


def until(cond: Any, timeout: float = 5.0) -> None:
    end = time.monotonic() + timeout
    while not cond():
        if time.monotonic() > end:
            raise AssertionError("condition not met in time")
        time.sleep(0.005)


# ------------------------------------------------------------------ stored outcome (SL-16)

def test_stored_outcome_contract():
    assert stored_outcome_completion(SUCCEEDED) == (
        {"paymentRef": "PAY-77", "amount": {"type": "Decimal", "value": "12.50"}}, "PAY-77")
    assert stored_outcome_completion({**SUCCEEDED, "storedOutcome": None}) == ({}, None)
    assert stored_outcome_completion({**SUCCEEDED, "storedOutcome": {"output": [1], "providerRef": None}}) == ({}, None)
    assert stored_outcome_completion(None) is None
    assert stored_outcome_completion({"state": "INTENT", "attempts": 2, "mayDispatch": True}) is None
    assert stored_outcome_completion({"state": "UNKNOWN_OUTCOME", "attempts": 1, "mayDispatch": False}) is None
    assert stored_outcome_completion({"state": "FAILED", "attempts": 1, "mayDispatch": False}) is None


def test_completes_from_stored_outcome_without_calling_the_handler():
    h = Harness()
    called = []
    r = run_one(h, lambda ctx: called.append(1) or {"paymentRef": "NEW"}, work(operation=SUCCEEDED))
    assert called == []
    assert r.path == "/work/wk-1/complete"
    assert r.idempotency_key == "op-1:complete:3"
    assert r.body == {"workerId": "worker-1", "lockVersion": 3, "attemptId": "att-1",
                      "variables": {"paymentRef": "PAY-77", "amount": {"type": "Decimal", "value": "12.50"}},
                      "outcome": {"reference": "PAY-77"}}
    assert h.events("work.replayed")


def test_runs_the_handler_while_the_operation_may_be_dispatched():
    h = Harness()
    seen = []
    r = run_one(h, lambda ctx: seen.append(ctx.operation) or {"done": True},
                work(operation={"state": "INTENT", "attempts": 2, "mayDispatch": True}))
    assert seen[0].attempts == 2 and seen[0].may_dispatch is True
    assert r.body["variables"] == {"done": True}
    assert "outcome" not in r.body


def test_handler_outcome_reference_is_sent_without_null_details():
    h = Harness()

    def handler(ctx):
        ctx.outcome_reference = "BANK-REF-1"
        return {"status": "PAID"}

    r = run_one(h, handler)
    assert r.body["outcome"] == {"reference": "BANK-REF-1"}


# ------------------------------------------------------------------ outcome mapping

def test_bpmn_error():
    h = Harness()

    def handler(ctx):
        raise BpmnError("INSUFFICIENT_FUNDS", "balance too low", {"balance": 3})

    r = run_one(h, handler)
    assert r.path.endswith("/bpmn-error")
    assert (r.body["errorCode"], r.body["errorMessage"], r.body["variables"]) == (
        "INSUFFICIENT_FUNDS", "balance too low", {"balance": 3})
    assert r.idempotency_key == "op-1:bpmn-error:3"


def test_outcome_unknown():
    h = Harness()

    def handler(ctx):
        raise OutcomeUnknown("bank timed out after send", "socket read timeout", {"sent": True})

    r = run_one(h, handler)
    assert r.path.endswith("/outcome-unknown")
    assert r.body["detail"] == "bank timed out after send\nsocket read timeout"
    assert r.body["evidence"] == {"sent": True}


def test_task_failed_overrides_retries_and_timeout():
    h = Harness()

    def handler(ctx):
        raise TaskFailed("downstream 503", details="d", retries=0, retry_timeout=1.5)

    r = run_one(h, handler)
    assert r.path.endswith("/fail")
    assert (r.body["errorMessage"], r.body["errorDetails"], r.body["retries"], r.body["retryTimeoutMs"]) == (
        "downstream 503", "d", 0, 1500)


def test_unexpected_error_fails_with_one_retry_less():
    h = Harness()

    def handler(ctx):
        raise RuntimeError("boom")

    r = run_one(h, handler, work(retries=3), retry_backoff=Backoff(0.01, 0.01, 1, 0))
    assert r.path.endswith("/fail")
    assert (r.body["errorMessage"], r.body["retries"], r.body["retryTimeoutMs"]) == ("boom", 2, 10)


def test_unexpected_error_on_irreversible_topic_is_outcome_unknown():
    h = Harness()

    def handler(ctx):
        raise RuntimeError("connection reset")

    r = run_one(h, handler, irreversible=True)
    assert r.path.endswith("/outcome-unknown")
    assert r.body["detail"].startswith("unexpected error on irreversible topic: RuntimeError('connection reset')")


def test_cancelled_without_server_request_unlocks():
    h = Harness()

    def handler(ctx):
        raise TaskCancelled("stop")

    assert run_one(h, handler).path.endswith("/unlock")


# ------------------------------------------------------------------ report retries and fencing

def test_retryable_answer_is_retried_with_the_same_key():
    h = Harness()
    h.http.reply("complete", (503, {"title": "busy"}), (503, None), (204, None))
    run_one(h, lambda ctx: {"ok": True})
    until(lambda: len(h.http.of("complete")) == 3)
    assert [c.idempotency_key for c in h.http.of("complete")] == ["op-1:complete:3"] * 3
    until(lambda: any(f.get("attempts") == 3 for f in h.events("work.complete")))


def test_non_retryable_answer_is_not_retried():
    h = Harness()
    h.http.reply("complete", (400, {"title": "bad"}))
    run_one(h, lambda ctx: {"ok": True})
    until(lambda: bool(h.events("work.report.failed")))
    assert len(h.http.of("complete")) == 1


def test_stale_lease_abandons():
    h = Harness()
    h.http.reply("complete", (409, {"code": "STALE_LEASE"}))
    run_one(h, lambda ctx: {"ok": True})
    until(lambda: bool(h.events("work.abandoned")))
    assert len(h.http.of("complete")) == 1


def test_no_retry_after_lease_expiry():
    h = Harness()
    h.http.reply("complete", (503, None), (503, None))
    expired = (datetime.now(timezone.utc) - timedelta(seconds=1)).isoformat()
    run_one(h, lambda ctx: {"ok": True}, work(lockExpiresAt=expired))
    until(lambda: bool(h.events("work.report.failed")))
    assert len(h.http.of("complete")) == 1


# ------------------------------------------------------------------ loop

def test_fetch_asks_for_free_slots_with_the_subscription_filters():
    h = Harness()
    run_one(h, lambda ctx: {}, variables=["amount"], business_key="BILL-1")
    f = h.http.of("fetch-and-lock")[0].body
    assert f["workerId"] == "worker-1" and f["maxTasks"] == 1
    assert f["topics"] == [{"name": "pay", "lockDurationMs": 60_000, "variables": ["amount"], "businessKey": "BILL-1"}]


def test_item_without_locked_confirmation_never_starts():
    h = Harness()
    h.http.batches.append([work(attemptId=None)])
    called = []
    h.worker.subscribe(TopicSubscription("pay", lambda ctx: called.append(1)))
    h.worker.start()
    until(lambda: bool(h.events("work.protocol.violation")))
    h.worker.stop(2.0)
    assert called == []
    assert not [c for c in h.http.calls if c.path.rsplit("/", 1)[-1] in REPORTS]


def test_item_of_unsubscribed_topic_is_unlocked():
    h = Harness()
    h.http.batches.append([work(topic="other")])
    h.worker.subscribe(TopicSubscription("pay", lambda ctx: {}))
    h.worker.start()
    r = h.http.wait_for(lambda c: c.path.endswith("/unlock"))
    h.worker.stop(2.0)
    assert r.idempotency_key == "op-1:unlock:3"


def test_invalid_subscriptions_are_rejected():
    with pytest.raises(ValueError):
        TopicSubscription("pay", lambda ctx: {}, lock_duration_ms=1000)
    with pytest.raises(ValueError):
        TopicSubscription("pay", lambda ctx: {}, lock_duration_ms=25 * 3600 * 1000)
    with pytest.raises(ValueError):
        TopicSubscription("pay", lambda ctx: {}, retries=-1)


# ------------------------------------------------------------------ HTTP error mapping

@pytest.mark.parametrize("status,body,kind", [
    (409, {"type": "https://sangam.dev/problems/stale-lease"}, StaleLeaseError),
    (409, {"code": "STALE_LEASE"}, StaleLeaseError),
    (409, {"code": "CONFLICT"}, SangamApiError),
    (410, {"code": "GONE_INSTANCE_DELETED"}, GoneError),
    (502, None, SangamApiError),
])
def test_error_mapping(status, body, kind):
    t = SangamClient("http://sangam.test").transport
    e = t._to_error(ApiRequest("POST", "/work/x/complete"), status, json.dumps(body).encode() if body else b"")
    assert type(e) is kind
    assert e.status == status and e.problem.status == status


def test_retryable_statuses():
    from sangam_sdk.errors import Problem
    statuses = [400, 401, 403, 404, 408, 409, 422, 425, 429, 500, 501, 502, 503, 504]
    assert [s for s in statuses if SangamApiError(s, Problem(status=s), "x").retryable] == [
        408, 425, 429, 500, 502, 503, 504]
    assert not StaleLeaseError(409, Problem(status=409), "x").retryable
    assert not GoneError(410, Problem(status=410), "x").retryable


def test_external_work_types_the_operation():
    w = ExternalWork.from_dict(work(operation=SUCCEEDED))
    assert w.operation_id == "op-1"
    assert (w.operation.state, w.operation.attempts, w.operation.may_dispatch) == ("SUCCEEDED", 2, False)
    assert w.operation.stored_outcome == SUCCEEDED["storedOutcome"]
    assert stored_outcome_completion(w.operation) == stored_outcome_completion(SUCCEEDED)


# ------------------------------------------------------------------ irreversibility (subscription flag only)

def test_a_stray_effect_member_does_not_make_an_item_irreversible():
    """The server sends no ``effect`` (WorkPort.ExternalWork); irreversibility is the subscription flag only."""
    h = Harness()

    def handler(ctx):
        raise RuntimeError("boom")

    r = run_one(h, handler, work(effect="irreversible"))
    assert r.path.endswith("/fail")
    assert not hasattr(ExternalWork.from_dict(work(effect="irreversible")), "effect")


def test_item_without_lock_version_never_starts():
    h = Harness()
    item = work()
    del item["lockVersion"]
    h.http.batches.append([item])
    called = []
    h.worker.subscribe(TopicSubscription("pay", lambda ctx: called.append(1)))
    h.worker.start()
    until(lambda: bool(h.events("work.protocol.violation")))
    h.worker.stop(2.0)
    assert called == []

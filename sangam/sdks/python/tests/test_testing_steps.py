# SPDX-License-Identifier: Apache-2.0
"""M-025: the step journal of the R18 test engine (``sangam_sdk.testing_steps``), driven through
``SangamTestEngine.handle`` and end to end with the real client and worker."""
from __future__ import annotations

import time
from typing import Any, Optional

import pytest

from sangam_sdk import Backoff, RetryPolicy, SangamLogger, Step, TopicSubscription, Worker
from sangam_sdk.testing import DEFAULT_LOCK_MS, SangamTestEngine


def fetch(e: SangamTestEngine, worker: str = "w1") -> dict[str, Any]:
    status, _, body, _ = e.handle("POST", "/work/fetch-and-lock", None,
                                  {"workerId": worker, "topics": [{"name": "pay"}], "maxTasks": 1})
    assert status == 200 and len(body) == 1
    return body[0]


def put(e: SangamTestEngine, w: dict[str, Any], name: str, state: str, worker: str = "w1",
        **extra: Any) -> tuple[int, Any]:
    body = {"workerId": worker, "lockVersion": w["lockVersion"], "attemptId": w["attemptId"], "state": state, **extra}
    status, _, out, _ = e.handle("PUT", f"/work/{w['id']}/step", None, body, {"stepName": name})
    return status, out


def get(e: SangamTestEngine, w: dict[str, Any], name: str, **over: Any) -> tuple[int, Any]:
    q = {"workerId": "w1", "lockVersion": str(w["lockVersion"]), "attemptId": w["attemptId"], "stepName": name, **over}
    status, _, out, _ = e.handle("GET", f"/work/{w['id']}/step", None, None, q)
    return status, out


def started() -> tuple[SangamTestEngine, str, dict[str, Any]]:
    e = SangamTestEngine()
    wid = e.add_work("pay")
    return e, wid, fetch(e)


# ------------------------------------------------------------------ transitions

def test_never_recorded_is_404_then_intent_then_succeeded() -> None:
    e, _, w = started()
    s, p = get(e, w, "charge")
    assert (s, p["code"]) == (404, "NOT_FOUND")
    s, row = put(e, w, "charge", "INTENT")
    assert s == 200 and row["state"] == "INTENT" and row["operationId"] == w["operationId"]
    assert row["attemptId"] == w["attemptId"] and row["recordedAt"]
    s, row = put(e, w, "charge", "SUCCEEDED", result={"ref": "P-1"}, providerRef="P-1")
    assert s == 200 and row["state"] == "SUCCEEDED" and row["result"] == {"ref": "P-1"}
    s, row = get(e, w, "charge")
    assert s == 200 and row["state"] == "SUCCEEDED" and row["providerRef"] == "P-1"
    assert "_workId" not in row


def test_outcome_needs_an_intent() -> None:
    e, _, w = started()
    s, p = put(e, w, "charge", "SUCCEEDED", result=1)
    assert (s, p["code"]) == (409, "STEP_INTENT_REQUIRED")
    s, p = put(e, w, "charge", "FAILED", error="no")
    assert (s, p["code"]) == (409, "STEP_INTENT_REQUIRED")
    assert put(e, w, "charge", "INTENT")[0] == 200
    assert put(e, w, "charge", "FAILED", error="refused")[0] == 200
    s, p = put(e, w, "charge", "SUCCEEDED", result=1)  # FAILED -> SUCCEEDED needs a new INTENT
    assert (s, p["code"]) == (409, "STEP_INTENT_REQUIRED") and "FAILED" in p["detail"]
    assert put(e, w, "charge", "INTENT")[0] == 200  # INTENT allowed again on a FAILED step
    assert put(e, w, "charge", "INTENT")[0] == 200  # identical repeat by the same attempt: no-op


def test_succeeded_is_final_unless_identical() -> None:
    e, _, w = started()
    put(e, w, "charge", "INTENT")
    assert put(e, w, "charge", "SUCCEEDED", result={"a": 1}, providerRef="R")[0] == 200
    s, row = put(e, w, "charge", "SUCCEEDED", result={"a": 1}, providerRef="R")
    assert s == 200 and row["state"] == "SUCCEEDED"  # identical repeat
    for state, extra in (("SUCCEEDED", {"result": {"a": 2}, "providerRef": "R"}), ("INTENT", {}),
                         ("FAILED", {"error": "x"})):
        s, p = put(e, w, "charge", state, **extra)
        assert (s, p["code"]) == (409, "STEP_ALREADY_RECORDED")


def test_validation_and_unknown_work() -> None:
    e, _, w = started()
    s, p = put(e, w, "charge", "DONE")
    assert (s, p["code"]) == (400, "VALIDATION_FAILED")
    s, _, p, _ = e.handle("PUT", f"/work/{w['id']}/step", None,
                          {"workerId": "w1", "lockVersion": w["lockVersion"], "state": "INTENT"}, {"stepName": "charge"})
    assert (s, p["code"]) == (400, "VALIDATION_FAILED")  # attemptId missing
    s, p = put(e, w, "x" * 129, "INTENT")
    assert (s, p["code"]) == (400, "VALIDATION_FAILED")
    s, p = get(e, w, "charge", lockVersion="abc")
    assert (s, p["code"]) == (400, "VALIDATION_FAILED")
    s, p = put(e, {**w, "id": "nope"}, "charge", "INTENT")
    assert (s, p["code"]) == (404, "NOT_FOUND")


def test_fencing_order_on_the_journal() -> None:
    e, wid, w = started()
    s, p = put(e, {**w, "lockVersion": w["lockVersion"] + 1}, "charge", "INTENT")
    assert (s, p["code"], p["reason"]) == (409, "STALE_LEASE", "STALE_VERSION")
    s, p = put(e, w, "charge", "INTENT", worker="intruder")
    assert p["reason"] == "NOT_OWNER"
    s, p = put(e, {**w, "attemptId": "old"}, "charge", "INTENT")
    assert p["reason"] == "ATTEMPT_MISMATCH"
    s, p = get(e, w, "charge", workerId="intruder")
    assert (s, p["reason"]) == (409, "NOT_OWNER")
    assert e.steps(wid) == []  # nothing recorded


def test_expired_lease_and_new_attempt() -> None:
    e, wid, w = started()
    put(e, w, "charge", "INTENT")
    e.advance(DEFAULT_LOCK_MS)
    w2 = fetch(e, "w2")  # the sweep ended the old lease; a new attempt with the same operationId
    assert w2["operationId"] == w["operationId"] and w2["attemptId"] != w["attemptId"]
    s, p = put(e, w, "charge", "SUCCEEDED", result=1)
    assert (s, p["code"]) == (409, "STALE_LEASE")
    s, _, row, _ = e.handle("GET", f"/work/{wid}/step", None, None,
                            {"workerId": "w2", "lockVersion": str(w2["lockVersion"]), "attemptId": w2["attemptId"],
                             "stepName": "charge"})
    assert s == 200 and row["state"] == "INTENT" and row["attemptId"] == w["attemptId"]


def test_deleted_instance_is_gone() -> None:
    e, wid, w = started()
    e.delete_instance(wid)
    s, p = put(e, w, "charge", "INTENT")
    assert (s, p["code"]) == (410, "GONE_INSTANCE_DELETED")
    s, p = get(e, w, "charge")
    assert s == 410


def test_list_in_recording_order() -> None:
    e, wid, w = started()
    for name in ("reserve", "charge", "notify"):
        put(e, w, name, "INTENT")
    put(e, w, "reserve", "SUCCEEDED", result=1)
    s, _, rows, _ = e.handle("GET", f"/work/{wid}/steps", None, None)
    assert s == 200 and [r["name"] for r in rows] == ["reserve", "charge", "notify"]
    assert [r["state"] for r in rows] == ["SUCCEEDED", "INTENT", "INTENT"]
    s, _, p, _ = e.handle("GET", "/work/nope/steps", None, None)
    assert (s, p["code"]) == (404, "NOT_FOUND")


# ------------------------------------------------------------------ end to end with the worker

def _run(e: SangamTestEngine, wid: str, handler: Any, until: tuple[str, ...]) -> None:
    logger = SangamLogger(hook=lambda *_: None)
    client = e.client(retry_policy=RetryPolicy.none(), logger=logger)
    worker = Worker(client, worker_id="wk", max_concurrency=1, async_response_timeout_ms=10,
                    fetch_backoff=Backoff(0.001, 0.002, 1, 0), report_backoff=Backoff(0.001, 0.002, 1, 0),
                    shutdown_timeout=2.0, logger=logger)
    worker.subscribe(TopicSubscription("pay", handler, lock_duration_ms=60_000))
    worker.start()
    try:
        end = time.monotonic() + 5.0
        while e.state(wid) not in until:
            if time.monotonic() > end:
                raise AssertionError(f"work stayed {e.state(wid)}")
            time.sleep(0.005)
    finally:
        worker.stop(2.0)


def test_worker_step_records_and_completes_over_http() -> None:
    with SangamTestEngine() as e:
        wid = e.add_work("pay")
        calls: list[str] = []

        def handler(ctx: Any) -> dict[str, Any]:
            def charge(step: Step) -> str:
                calls.append(step.idempotency_key)
                step.provider_ref = "P-1"
                return "P-1"

            return {"ref": ctx.step("charge", charge)}

        _run(e, wid, handler, ("COMPLETED",))
        [row] = e.steps(wid)
        assert row["state"] == "SUCCEEDED" and row["result"] == "P-1" and row["providerRef"] == "P-1"
        assert calls == [e.snapshot(wid)["operationId"] + ":charge"]


def test_worker_reports_outcome_unknown_for_an_intent_left_by_an_earlier_attempt() -> None:
    with SangamTestEngine() as e:
        wid = e.add_work("pay")
        first = fetch(e, "crashed")
        assert put(e, first, "charge", "INTENT", worker="crashed")[0] == 200
        e.advance(DEFAULT_LOCK_MS)  # the crashed attempt's lease expires
        ran: list[Optional[str]] = []

        def handler(ctx: Any) -> dict[str, Any]:
            return {"ref": ctx.step("charge", lambda s: ran.append(s.previous_state))}

        _run(e, wid, handler, ("UNKNOWN_OUTCOME",))
        assert ran == []  # never re-issued blindly (R37)
        [item] = e.reconciliation_items
        assert item["kind"] == "UNKNOWN_OUTCOME" and "charge" in (item["detail"] or "")
        assert e.steps(wid)[0]["state"] == "INTENT"


@pytest.mark.parametrize("method", ["DELETE", "POST"])
def test_unsupported_methods_on_the_journal(method: str) -> None:
    e, wid, _ = started()
    s, _, p, _ = e.handle(method, f"/work/{wid}/step", None, {}, {"stepName": "charge"})
    assert (s, p["code"]) == (405, "METHOD_NOT_ALLOWED")

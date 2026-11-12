# SPDX-License-Identifier: Apache-2.0
"""M-021: the R18 test engine's state machine (VI.3), driven through ``SangamTestEngine.handle`` (no HTTP) and once
over HTTP with the real client."""
from __future__ import annotations

from typing import Any

import pytest

from sangam_sdk import RetryPolicy, StaleLeaseError
from sangam_sdk.testing import DEFAULT_LOCK_MS, MAX_LOCK_MS, SangamTestEngine


def fetch(e: SangamTestEngine, worker: str = "w1", topic: str = "pay", **extra: Any) -> list[dict[str, Any]]:
    status, _, body, _ = e.handle("POST", "/work/fetch-and-lock", None,
                                  {"workerId": worker, "topics": [{"name": topic, **extra}], "maxTasks": 10})
    assert status == 200
    return body


def post(e: SangamTestEngine, wid: str, action: str, body: dict[str, Any]) -> tuple[int, Any]:
    status, _, out, _ = e.handle("POST", f"/work/{wid}/{action}", None, body)
    return status, out


def fence(w: dict[str, Any], worker: str = "w1", **extra: Any) -> dict[str, Any]:
    return {"workerId": worker, "lockVersion": w["lockVersion"], "attemptId": w["attemptId"], **extra}


def test_grant_gives_lock_version_attempt_and_intent_operation() -> None:
    e = SangamTestEngine()
    wid = e.add_work("pay", {"a": 1})
    [w] = fetch(e)
    assert w["id"] == wid and w["lockVersion"] == 1 and w["attemptId"]
    assert w["operation"] == {"state": "INTENT", "attempts": 1, "mayDispatch": True}
    assert e.state(wid) == "LOCKED"
    assert fetch(e, "w2") == []  # LOCKED work is not handed out again


def test_fetch_validation() -> None:
    e = SangamTestEngine()
    s, _, b, _ = e.handle("POST", "/work/fetch-and-lock", None, {"topics": [{"name": "pay"}]})
    assert (s, b["code"]) == (400, "VALIDATION_FAILED")
    s, _, b, _ = e.handle("POST", "/work/fetch-and-lock", None, {"workerId": "w", "topics": []})
    assert (s, b["code"]) == (400, "VALIDATION_FAILED")


def test_max_tasks_default_one_and_lease_cap() -> None:
    e = SangamTestEngine()
    e.add_work("pay")
    e.add_work("pay")
    s, _, b, _ = e.handle("POST", "/work/fetch-and-lock", None, {"workerId": "w", "topics": [{"name": "pay",
                                                                  "lockDurationMs": MAX_LOCK_MS * 2}]})
    assert s == 200 and len(b) == 1
    snap = e.snapshot(b[0]["id"])
    assert snap["state"] == "LOCKED"
    e.advance(MAX_LOCK_MS - 1)
    assert e.state(b[0]["id"]) == "LOCKED"
    e.advance(1)  # capped at 24 h: expired now
    assert b[0]["id"] in [x["id"] for x in fetch(e, "w2")]


def test_fencing_order_and_problem_shape() -> None:
    e = SangamTestEngine()
    wid = e.add_work("pay")
    [w] = fetch(e)
    s, p = post(e, wid, "complete", fence(w, lockVersion=w["lockVersion"] + 1))
    assert s == 409 and p["code"] == "STALE_LEASE" and p["reason"] == "STALE_VERSION"
    assert p["type"] == "https://sangam.dev/problems/stale-lease" and p["title"] == "Stale lease"
    assert p["workId"] == wid and p["status"] == 409
    s, p = post(e, wid, "complete", fence(w, worker="intruder"))
    assert p["reason"] == "NOT_OWNER"
    s, p = post(e, wid, "complete", fence(w, attemptId="old"))
    assert p["reason"] == "ATTEMPT_MISMATCH"
    assert e.state(wid) == "LOCKED"  # nothing changed
    s, p = post(e, "nope", "complete", fence(w))
    assert (s, p["code"]) == (404, "NOT_FOUND")


def test_expiry_returns_to_available_with_new_version() -> None:
    e = SangamTestEngine()
    wid = e.add_work("pay")
    [w] = fetch(e, lockDurationMs=10_000)
    e.advance(10_000)
    [w2] = fetch(e, "w2")
    assert w2["id"] == wid and w2["lockVersion"] > w["lockVersion"] and w2["attemptId"] != w["attemptId"]
    assert w2["operationId"] == w["operationId"]  # R36: stable across expiry
    assert w2["operation"]["attempts"] == 2
    s, p = post(e, wid, "complete", fence(w))
    assert s == 409 and p["code"] == "STALE_LEASE"


def test_irreversible_expiry_and_unacked_cancel_go_to_unknown_outcome() -> None:
    e = SangamTestEngine(irreversible_topics=["pay"])
    a = e.add_work("pay")
    fetch(e, lockDurationMs=5_000)
    e.advance(5_000)
    assert fetch(e, "w2") == []
    assert e.state(a) == "UNKNOWN_OUTCOME"
    assert e.reconciliation_items[-1]["workId"] == a
    b = e.add_work("ship")
    fetch(e, topic="ship", lockDurationMs=5_000)
    e.request_cancel(b)
    assert e.state(b) == "CANCEL_REQUESTED"
    e.advance(5_000)
    fetch(e, topic="ship")
    assert e.state(b) == "UNKNOWN_OUTCOME"


def test_extend_lock_keeps_version_and_reports_cancel() -> None:
    e = SangamTestEngine()
    wid = e.add_work("pay")
    [w] = fetch(e, lockDurationMs=9_000)
    e.advance(6_000)
    s, ls = post(e, wid, "extend-lock", fence(w, newDurationMs=9_000))
    assert s == 200 and ls["lockVersion"] == w["lockVersion"] and ls["cancelRequested"] is False
    e.advance(6_000)
    assert e.state(wid) == "LOCKED" and fetch(e, "w2") == []
    e.request_cancel(wid)
    s, ls = post(e, wid, "extend-lock", fence(w, newDurationMs=9_000))
    assert ls["cancelRequested"] is True
    assert post(e, wid, "cancel-ack", fence(w))[0] == 204
    assert e.state(wid) == "CANCELLED"


def test_complete_once_and_identical_repeat() -> None:
    e = SangamTestEngine()
    wid = e.add_work("pay")
    [w] = fetch(e)
    body = fence(w, variables={"ok": True})
    assert post(e, wid, "complete", body)[0] == 204
    assert post(e, wid, "complete", body)[0] == 204  # retried request: answered as the first one
    assert e.completions(wid) == 1 and e.state(wid) == "COMPLETED"
    s, p = post(e, wid, "complete", fence(w, variables={"ok": False}))
    assert (s, p["code"]) == (404, "NOT_FOUND")


def test_fail_with_retries_then_incident() -> None:
    e = SangamTestEngine()
    wid = e.add_work("pay", retries=2)
    [w] = fetch(e)
    assert post(e, wid, "fail", fence(w, errorMessage="x", retries=1, retryTimeoutMs=1_000))[0] == 204
    assert e.state(wid) == "AVAILABLE" and fetch(e) == []  # back-off not over
    e.advance(1_000)
    [w2] = fetch(e)
    assert w2["lockVersion"] > w["lockVersion"] and w2["retries"] == 1 and w2["errorMessage"] == "x"
    assert post(e, wid, "fail", fence(w2, retries=0))[0] == 204
    assert e.state(wid) == "INCIDENT"


def test_fail_on_irreversible_topic_is_outcome_unknown() -> None:
    e = SangamTestEngine(irreversible_topics=["pay"])
    wid = e.add_work("pay")
    [w] = fetch(e)
    assert post(e, wid, "fail", fence(w, errorMessage="timeout"))[0] == 204
    assert e.state(wid) == "UNKNOWN_OUTCOME"


def test_bpmn_error_unlock_outcome_unknown() -> None:
    e = SangamTestEngine()
    a, b, c = e.add_work("pay"), e.add_work("pay"), e.add_work("pay")
    wa, wb, wc = fetch(e)
    s, p = post(e, a, "bpmn-error", fence(wa))
    assert (s, p["code"]) == (400, "VALIDATION_FAILED")
    assert post(e, a, "bpmn-error", fence(wa, errorCode="E1"))[0] == 204
    assert e.state(a) == "COMPLETED_WITH_ERROR"
    assert post(e, b, "unlock", fence(wb))[0] == 204
    assert e.state(b) == "AVAILABLE" and e.lock_version(b) == wb["lockVersion"] + 1
    s, r = post(e, c, "outcome-unknown", fence(wc, detail="timeout"))
    assert s == 202 and r["state"] == "UNKNOWN_OUTCOME" and r["operationId"] == wc["operationId"]
    item = r["reconciliationItemId"]
    e.advance(DEFAULT_LOCK_MS * 3)
    assert all(x["id"] != c for x in fetch(e, "w9"))  # never re-offered while unresolved
    e.resolve_reconciliation(item, "RETRY")
    [again] = [x for x in fetch(e, "w9") if x["id"] == c]
    assert again["operationId"] == wc["operationId"]


def test_deleted_instance_answers_gone() -> None:
    e = SangamTestEngine()
    wid = e.add_work("pay")
    [w] = fetch(e)
    e.delete_instance(wid)
    s, p = post(e, wid, "complete", fence(w))
    assert s == 410 and p["code"] == "GONE_INSTANCE_DELETED" and p["reconciliationItemId"]


def test_process_start_idempotency() -> None:
    e = SangamTestEngine()
    s1, h1, b1, _ = e.handle("POST", "/process-instances", "k1", {"key": "p1", "businessKey": "BK"})
    s2, h2, b2, _ = e.handle("POST", "/process-instances", "k1", {"key": "p1", "businessKey": "BK"})
    assert (s1, s2) == (201, 201) and b1 == b2 and h2.get("Idempotent-Replayed") == "true"
    assert "Idempotent-Replayed" not in h1 and len(e.instances) == 1
    s3, _, p, _ = e.handle("POST", "/process-instances", "k1", {"key": "p1", "businessKey": "OTHER"})
    assert (s3, p["code"]) == (422, "IDEMPOTENCY_KEY_REUSED")


def test_over_http_with_the_real_client() -> None:
    with SangamTestEngine() as e:
        client = e.client(retry_policy=RetryPolicy.none())
        wid = e.add_work("pay")
        [w] = client.work.fetch_and_lock("w1", [{"name": "pay"}])
        assert w.id == wid and w.lock_version == 1 and w.operation is not None
        with pytest.raises(StaleLeaseError):
            client.work.complete(wid, "w1", 99, w.attempt_id)
        client.work.complete(wid, "w1", w.lock_version, w.attempt_id, {"x": 1})
        assert e.state(wid) == "COMPLETED"

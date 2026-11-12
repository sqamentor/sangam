# SPDX-License-Identifier: Apache-2.0
"""SL-174: step routes and lease calls on work that is not open answer exactly what the server answers.

The expected problems come from ``sdks/testing/work-step-problems.json``, which the server's ``WorkStepJournalH2Test``
and ``WorkCallProblemsVectorH2Test`` check against the server work adapters too, so the engine and the server are
compared through one table."""
from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest

from sangam_sdk.testing import SangamTestEngine

VECTORS = json.loads((Path(__file__).resolve().parents[2] / "testing" / "work-step-problems.json").read_text("utf-8"))
FIXED = {"id", "scenario", "call", "status", "code"}
CALL_BODY: dict[str, dict[str, Any]] = {
    "complete": {"variables": {}},
    "fail": {"errorMessage": "again", "retries": 1},
    "bpmn-error": {"errorCode": "E1"},
    "unlock": {},
    "extend-lock": {"newDurationMs": 60_000},
    "outcome-unknown": {"detail": "unsure"},
}


def _await_reconciliation(e: SangamTestEngine, wid: str, lease: dict[str, Any]) -> None:
    """The UNKNOWN_OUTCOME scenario: the worker reports an unknown outcome; the work awaits reconciliation."""
    assert e.handle("POST", f"/work/{wid}/outcome-unknown", None, {**lease, "detail": "unsure"})[0] == 202
    assert e.state(wid) == "UNKNOWN_OUTCOME"


def _expect(case: dict[str, Any], route: str, status: int, body: Any) -> None:
    what = f"{case['id']} {route}"
    assert status == case["status"], what
    assert body["code"] == case["code"], what
    for k, v in case.items():
        if k not in FIXED:
            assert body.get(k) == v, f"{what} {k}"


@pytest.mark.parametrize("case", VECTORS["cases"], ids=[c["id"] for c in VECTORS["cases"]])
def test_step_routes_on_work_that_is_not_open_answer_the_servers_problem(case: dict[str, Any]) -> None:
    e = SangamTestEngine()
    wid = e.add_work("pay")
    _, _, fetched, _ = e.handle("POST", "/work/fetch-and-lock", None,
                                {"workerId": "w1", "topics": [{"name": "pay"}], "maxTasks": 1})
    w = fetched[0]
    lease = {"workerId": "w1", "lockVersion": w["lockVersion"], "attemptId": w["attemptId"]}
    target = wid
    scenario = case["scenario"]
    if scenario == "UNKNOWN":
        target = VECTORS["unknownWorkId"]
    elif scenario == "COMPLETED":
        assert e.handle("POST", f"/work/{wid}/complete", None, {**lease, "variables": {}})[0] == 204
    elif scenario == "DELETED":
        e.delete_instance(wid)
    elif scenario == "RELEASED":
        assert e.handle("POST", f"/work/{wid}/unlock", None, lease)[0] == 204
    elif scenario == "UNKNOWN_OUTCOME":
        _await_reconciliation(e, wid, lease)
    else:
        pytest.fail(f"unknown scenario {scenario}")
    path = f"/work/{target}/steps/{VECTORS['stepName']}"
    query = {"workerId": "w1", "lockVersion": str(w["lockVersion"]), "attemptId": w["attemptId"]}
    status, _, body, _ = e.handle("GET", path, None, None, query)
    _expect(case, "GET", status, body)
    status, _, body, _ = e.handle("PUT", path, None, {**lease, "state": "INTENT"})
    _expect(case, "PUT", status, body)


@pytest.mark.parametrize("case", VECTORS["workCalls"], ids=[c["id"] for c in VECTORS["workCalls"]])
def test_lease_calls_on_work_that_is_not_open_answer_the_servers_problem(case: dict[str, Any]) -> None:
    e = SangamTestEngine()
    wid = e.add_work("pay")
    _, _, fetched, _ = e.handle("POST", "/work/fetch-and-lock", None,
                                {"workerId": "w1", "topics": [{"name": "pay"}], "maxTasks": 1})
    w = fetched[0]
    lease = {"workerId": "w1", "lockVersion": w["lockVersion"], "attemptId": w["attemptId"]}
    if case["scenario"] == "INCIDENT":
        assert e.handle("POST", f"/work/{wid}/fail", None,
                        {**lease, "errorMessage": "no retries left", "retries": 0})[0] == 204
        assert e.state(wid) == "INCIDENT"
    elif case["scenario"] == "UNKNOWN_OUTCOME":
        _await_reconciliation(e, wid, lease)
    else:
        pytest.fail(f"unknown scenario {case['scenario']}")
    call = case["call"]
    assert call in CALL_BODY
    status, _, body, _ = e.handle("POST", f"/work/{wid}/{call}", None, {**lease, **CALL_BODY[call]})
    _expect(case, call, status, body)


def test_every_vector_is_read() -> None:
    assert len(VECTORS["cases"]) >= 5
    assert len(VECTORS["workCalls"]) >= 12

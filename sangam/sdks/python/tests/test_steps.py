# SPDX-License-Identifier: Apache-2.0
"""M-025 durable steps (R37): ``TaskContext.step`` against a scripted step journal.

The real ``SangamClient`` and its generated ``client.api.work`` build the requests; only the transport's ``execute`` is
replaced by an in-process journal, and HTTP errors come from the real ``HttpTransport._to_error`` mapping.
"""
from __future__ import annotations

import json
from datetime import datetime, timedelta, timezone
from decimal import Decimal
from typing import Any, Optional

import pytest

from sangam_sdk import (OutcomeUnknown, SangamApiError, SangamClient, SangamLogger, Step, StepNoEffectError,
                        TaskCancelled, TaskContext)
from sangam_sdk.http import ApiRequest, ApiResponse, dumps
from sangam_sdk.models import ExternalWork


class Journal:
    """Answers GET/PUT /work/{id}/step?stepName= from ``rows``; ``get_error`` / ``put_error`` script an error answer."""

    def __init__(self, real: Any) -> None:
        self.real = real
        self.rows: dict[str, dict[str, Any]] = {}
        self.gets: list[ApiRequest] = []
        self.puts: list[dict[str, Any]] = []
        self.get_error: Optional[tuple[int, dict[str, Any]]] = None
        self.put_error: Optional[tuple[int, dict[str, Any]]] = None

    def _raise(self, req: ApiRequest, status: int, body: dict[str, Any]) -> None:
        raise self.real._to_error(req, status, json.dumps(body).encode())

    def execute(self, req: ApiRequest) -> ApiResponse:
        parts = req.path.strip("/").split("/")
        assert parts[0] == "work" and parts[2] == "step"
        name = req.query["stepName"]
        if req.method == "GET":
            self.gets.append(req)
            if self.get_error is not None:
                self._raise(req, *self.get_error)
            row = self.rows.get(name)
            if row is None:
                self._raise(req, 404, {"status": 404, "code": "NOT_FOUND", "detail": "never recorded"})
            return ApiResponse(200, {}, dict(row or {}))
        assert req.method == "PUT"
        body = json.loads(dumps(req.body))  # the wire form
        self.puts.append(body)
        if self.put_error is not None:
            self._raise(req, *self.put_error)
        self.rows[name] = {"operationId": "op-1", "name": name, "state": body["state"], "result": body.get("result"),
                           "providerRef": body.get("providerRef"), "error": body.get("error")}
        return ApiResponse(200, {}, dict(self.rows[name]))

    def states(self) -> list[str]:
        return [p["state"] for p in self.puts]


class Harness:
    def __init__(self) -> None:
        self.logs: list[tuple[str, dict[str, Any]]] = []
        logger = SangamLogger(hook=lambda _l, event, _m, fields, _e: self.logs.append((event, dict(fields))))
        self.client = SangamClient("http://sangam.test/", logger=logger)
        self.journal = Journal(self.client.transport)
        self.client.transport.execute = self.journal.execute  # type: ignore[method-assign]
        work = ExternalWork.from_dict({
            "id": "wk-1", "topic": "pay", "lockVersion": 3, "attemptId": "att-1", "operationId": "op-1",
            "lockExpiresAt": (datetime.now(timezone.utc) + timedelta(minutes=1)).isoformat(), "variables": {}})
        self.ctx = TaskContext(work, "worker-1", self.client, logger)

    def events(self, name: str) -> list[dict[str, Any]]:
        return [f for e, f in self.logs if e == name]


def recorded(h: Harness, name: str, state: str, result: Any = None, provider_ref: Optional[str] = None) -> None:
    h.journal.rows[name] = {"operationId": "op-1", "name": name, "state": state, "result": result,
                            "providerRef": provider_ref, "error": None}


class Calls:
    def __init__(self, value: Any = "ok") -> None:
        self.value = value
        self.seen: list[Step] = []

    def __call__(self, step: Step) -> Any:
        self.seen.append(step)
        return self.value


# ------------------------------------------------------------------ first run

def test_first_run_records_intent_then_succeeded_with_result_and_provider_ref() -> None:
    h = Harness()

    def charge(step: Step) -> dict[str, Any]:
        assert step.idempotency_key == "op-1:charge" and step.previous_state is None
        step.provider_ref = "PAY-1"
        return {"amount": Decimal("12.50"), "ok": True}

    out = h.ctx.step("charge", charge)
    assert out == {"amount": Decimal("12.50"), "ok": True}
    assert h.journal.states() == ["INTENT", "SUCCEEDED"]
    intent, done = h.journal.puts
    assert intent == {"workerId": "worker-1", "lockVersion": 3, "attemptId": "att-1", "state": "INTENT",
                      "result": None, "providerRef": None, "error": None}
    assert done["result"] == {"amount": {"type": "Decimal", "value": "12.50"}, "ok": True}
    assert done["providerRef"] == "PAY-1"
    [get] = h.journal.gets
    assert get.query == {"stepName": "charge", "workerId": "worker-1", "lockVersion": 3, "attemptId": "att-1"}


def test_replay_of_succeeded_step_does_not_call_and_unwraps_typed_values() -> None:
    h = Harness()
    recorded(h, "charge", "SUCCEEDED", {"amount": {"type": "Decimal", "value": "12.50"}, "ref": "PAY-1"}, "PAY-1")
    fn = Calls()
    out = h.ctx.step("charge", fn)
    assert out == {"amount": Decimal("12.50"), "ref": "PAY-1"}
    assert fn.seen == [] and h.journal.puts == []
    assert h.events("step.replayed") and h.events("step.replayed")[0]["step"] == "charge"


def test_replay_of_a_typed_scalar_result() -> None:
    h = Harness()
    recorded(h, "total", "SUCCEEDED", {"type": "Decimal", "value": "99.95"})
    assert h.ctx.step("total", Calls()) == Decimal("99.95")


# ------------------------------------------------------------------ INTENT (the crash window)

def test_intent_with_lookup_value_records_succeeded_without_calling() -> None:
    h = Harness()
    recorded(h, "charge", "INTENT")
    fn = Calls()

    def lookup(step: Step) -> Optional[str]:
        assert step.previous_state == "INTENT" and step.idempotency_key == "op-1:charge"
        step.provider_ref = "PAY-9"
        return "found"

    assert h.ctx.step("charge", fn, lookup=lookup) == "found"
    assert fn.seen == []
    assert h.journal.states() == ["SUCCEEDED"]
    assert h.journal.puts[0]["result"] == "found" and h.journal.puts[0]["providerRef"] == "PAY-9"
    assert h.events("step.looked-up")


def test_intent_with_lookup_none_runs_again() -> None:
    h = Harness()
    recorded(h, "charge", "INTENT")
    fn = Calls("again")
    assert h.ctx.step("charge", fn, lookup=lambda _s: None) == "again"
    assert len(fn.seen) == 1 and fn.seen[0].previous_state == "INTENT"
    assert h.journal.states() == ["INTENT", "SUCCEEDED"]


def test_intent_lookup_error_propagates_and_step_stays_intent() -> None:
    h = Harness()
    recorded(h, "charge", "INTENT")

    def lookup(_s: Step) -> Any:
        raise RuntimeError("provider down")

    with pytest.raises(RuntimeError, match="provider down"):
        h.ctx.step("charge", Calls(), lookup=lookup)
    assert h.journal.puts == [] and h.journal.rows["charge"]["state"] == "INTENT"


def test_intent_with_idempotent_runs_again_with_the_same_key() -> None:
    h = Harness()
    recorded(h, "charge", "INTENT")
    fn = Calls("again")
    assert h.ctx.step("charge", fn, idempotent=True) == "again"
    assert fn.seen[0].idempotency_key == "op-1:charge"
    assert h.journal.states() == ["INTENT", "SUCCEEDED"]


def test_intent_by_default_is_outcome_unknown() -> None:
    h = Harness()
    recorded(h, "charge", "INTENT")
    fn = Calls()
    with pytest.raises(OutcomeUnknown) as ei:
        h.ctx.step("charge", fn)
    e = ei.value
    assert "step charge of operation op-1" in e.reason and "(R37)" in e.reason
    assert e.details == "step=charge"
    assert e.variables == {"step": "charge", "idempotencyKey": "op-1:charge"}
    assert fn.seen == [] and h.journal.puts == []


def test_failed_step_runs_again() -> None:
    h = Harness()
    recorded(h, "charge", "FAILED")
    fn = Calls("second")
    assert h.ctx.step("charge", fn) == "second"
    assert fn.seen[0].previous_state == "FAILED"
    assert h.journal.states() == ["INTENT", "SUCCEEDED"]


# ------------------------------------------------------------------ outcomes of the body

def test_step_no_effect_records_failed_and_reraises() -> None:
    h = Harness()

    def refused(step: Step) -> Any:
        step.provider_ref = "REJ-1"
        raise StepNoEffectError("insufficient funds")

    with pytest.raises(StepNoEffectError, match="insufficient funds"):
        h.ctx.step("charge", refused)
    assert h.journal.states() == ["INTENT", "FAILED"]
    assert h.journal.puts[1]["error"] == "insufficient funds" and h.journal.puts[1]["providerRef"] == "REJ-1"


def test_other_error_leaves_intent_and_propagates_unchanged() -> None:
    h = Harness()
    boom = TimeoutError("read timed out after send")

    def call(_s: Step) -> Any:
        raise boom

    with pytest.raises(TimeoutError) as ei:
        h.ctx.step("charge", call)
    assert ei.value is boom
    assert h.journal.states() == ["INTENT"] and h.journal.rows["charge"]["state"] == "INTENT"


# ------------------------------------------------------------------ lease and errors

def test_stale_lease_on_read_marks_lease_lost() -> None:
    h = Harness()
    h.journal.get_error = (409, {"status": 409, "code": "STALE_LEASE", "reason": "STALE_VERSION"})
    fn = Calls()
    with pytest.raises(TaskCancelled, match="lease lost"):
        h.ctx.step("charge", fn)
    assert h.ctx.lease_lost and h.ctx.cancel_event.is_set()
    assert fn.seen == [] and h.journal.puts == []


def test_gone_on_write_marks_lease_lost() -> None:
    h = Harness()
    h.journal.put_error = (410, {"status": 410, "code": "GONE_INSTANCE_DELETED"})
    fn = Calls()
    with pytest.raises(TaskCancelled):
        h.ctx.step("charge", fn)
    assert h.ctx.lease_lost
    assert fn.seen == []  # the INTENT record failed, so the call never ran


def test_other_api_error_on_read_propagates_without_losing_the_lease() -> None:
    h = Harness()
    h.journal.get_error = (500, {"status": 500, "code": "INTERNAL_ERROR"})
    with pytest.raises(SangamApiError) as ei:
        h.ctx.step("charge", Calls())
    assert ei.value.status == 500 and not h.ctx.lease_lost


@pytest.mark.parametrize("bad", ["", "x" * 129, "naïve", "tab\there", "line\n"])
def test_bad_step_names_are_rejected_before_any_call(bad: str) -> None:
    h = Harness()
    with pytest.raises(ValueError):
        h.ctx.step(bad, Calls())
    assert h.journal.gets == [] and h.journal.puts == []


def test_longest_and_widest_names_are_accepted() -> None:
    h = Harness()
    assert h.ctx.step("x" * 128, Calls(1)) == 1
    assert h.ctx.step(" ~!/?", Calls(2)) == 2  # printable ASCII, '/' included
    # UI-DEMO-1: the name travels as the stepName query parameter; an encoded '/' in the path is refused by the server
    assert h.journal.gets[1].path.endswith("/step") and "%2F" not in h.journal.gets[1].path
    assert h.journal.gets[1].query["stepName"] == " ~!/?"
    assert h.journal.rows[" ~!/?"]["state"] == "SUCCEEDED"

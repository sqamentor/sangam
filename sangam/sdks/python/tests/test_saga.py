# SPDX-License-Identifier: Apache-2.0
"""M-026: the saga helper (forward record, compensation context, subscriptions)."""
from __future__ import annotations

from typing import Any, Optional

import pytest

from sangam_sdk import (CompensationContext, Saga, SagaRecord, SangamClient, SangamLogger, TaskContext, TypedValue,
                        Worker, encode_variables)
from sangam_sdk.models import ExternalWork


def ctx(variables: Optional[dict[str, Any]] = None, operation_id: str = "op-fwd", work_id: str = "wk-1") -> TaskContext:
    logger = SangamLogger(hook=lambda *_: None)
    client = SangamClient("http://sangam.test/", logger=logger)
    work = ExternalWork.from_dict({"id": work_id, "topic": "t", "lockVersion": 1, "attemptId": "a",
                                   "operationId": operation_id, "variables": variables or {}})
    return TaskContext(work, "w", client, logger)


def test_record_variable_and_name_rules() -> None:
    assert Saga.record_variable("pay.charge-1") == "sangamSaga_pay_charge_1"
    assert Saga.record_variable("x_y") == "sangamSaga_x_y"
    for bad in ("", "a" * 65, "has space", "slash/no", None):
        with pytest.raises(ValueError):
            Saga.step(bad)  # type: ignore[arg-type]
    Saga.step("a" * 64)


def test_build_requires_both_handlers_and_distinct_topics() -> None:
    with pytest.raises(ValueError):
        Saga.step("charge").action("pay", lambda c: None).build()
    with pytest.raises(ValueError):
        Saga.step("charge").compensation("refund", lambda c: None).build()
    with pytest.raises(ValueError, match="own topics"):
        Saga.step("charge").action("pay", lambda c: None).compensation("pay", lambda c: None).build()


def test_forward_adds_the_record_as_a_json_typed_value() -> None:
    def charge(c: TaskContext) -> dict[str, Any]:
        c.outcome_reference = "PAY-7"
        return {"chargeId": "C-1"}

    saga = Saga.step("charge").action("pay", charge).compensation("refund", lambda c: None).build()
    out = saga.action.handler(ctx())
    assert out is not None and out["chargeId"] == "C-1"
    rec = out["sangamSaga_charge"]
    assert isinstance(rec, TypedValue) and rec.type == "Json"
    assert rec.value == {"step": "charge", "operationId": "op-fwd", "providerRef": "PAY-7", "workId": "wk-1"}


def test_forward_without_result_variables_still_records() -> None:
    saga = Saga.step("s").action("a", lambda c: None).compensation("b", lambda c: None).build()
    out = saga.action.handler(ctx())
    assert out is not None and list(out) == ["sangamSaga_s"]


def test_compensation_gets_the_forward_record_and_its_idempotency_key() -> None:
    seen: list[CompensationContext] = []
    saga = Saga.step("charge").action("pay", lambda c: {}).compensation(
        "refund", lambda cc: seen.append(cc) or {"refunded": True}).build()
    forward_vars = saga.action.handler(ctx(operation_id="op-fwd"))
    wire = encode_variables(forward_vars)  # as the worker sends it on complete; the engine hands it back
    assert wire is not None and wire["sangamSaga_charge"]["type"] == "Json"
    comp = ctx(wire, operation_id="op-comp", work_id="wk-2")
    assert saga.compensation.handler(comp) == {"refunded": True}
    [cc] = seen
    assert cc.task is comp
    assert cc.forward == SagaRecord("charge", "op-fwd", None, "wk-1")
    assert cc.forward_operation_id == "op-fwd" and cc.forward_provider_ref is None
    assert cc.idempotency_key == "op-fwd:compensate:charge"


def test_compensation_without_a_record_uses_its_own_operation_id() -> None:
    seen: list[CompensationContext] = []
    saga = Saga.step("charge").action("pay", lambda c: {}).compensation("refund", lambda cc: seen.append(cc)).build()
    saga.compensation.handler(ctx(operation_id="op-comp"))
    [cc] = seen
    assert cc.forward is None and cc.forward_operation_id is None and cc.forward_provider_ref is None
    assert cc.idempotency_key == "op-comp"


def test_record_from_a_json_string_value() -> None:
    raw = {"sangamSaga_charge": {"type": "Json",
                                 "value": '{"step":"charge","operationId":"op-9","providerRef":"P","workId":"w"}'}}
    seen: list[CompensationContext] = []
    saga = Saga.step("charge").action("pay", lambda c: {}).compensation("refund", lambda cc: seen.append(cc)).build()
    saga.compensation.handler(ctx(raw))
    assert seen[0].forward == SagaRecord("charge", "op-9", "P", "w")


def test_options_apply_to_both_subscriptions() -> None:
    saga = (Saga.step("pay.charge").action("pay", lambda c: {}).compensation("refund", lambda c: {})
            .lock_duration_ms(120_000).retries(1).irreversible().variables(["amount"]).build())
    fwd, back = saga.subscriptions
    assert (fwd.topic, back.topic) == ("pay", "refund")
    assert fwd.lock_duration_ms == back.lock_duration_ms == 120_000 and fwd.retries == back.retries == 1
    assert fwd.irreversible and not back.irreversible
    assert fwd.variables == ["amount"]
    assert back.variables == ["amount", "sangamSaga_pay_charge"]
    open_saga = Saga.step("s").action("a", lambda c: {}).compensation("b", lambda c: {}).build()
    assert open_saga.action.variables is None and open_saga.compensation.variables is None


def test_register_on_subscribes_both_topics() -> None:
    logger = SangamLogger(hook=lambda *_: None)
    worker = Worker(SangamClient("http://sangam.test/", logger=logger), worker_id="w", logger=logger)
    saga = Saga.step("charge").action("pay", lambda c: {}).compensation("refund", lambda c: {}).build()
    assert saga.register_on(worker) is worker
    assert set(worker._subs) == {"pay", "refund"}
    assert saga.name == "charge"

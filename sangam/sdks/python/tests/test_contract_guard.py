# SPDX-License-Identifier: Apache-2.0
"""Drift guard: the SDK models and request bodies follow ``sangam-rest/api/sangam-api.yaml`` (the generated contract).

For every covered schema the wire field names must equal the contract's properties, and the required-ness must match:
response dataclasses require nothing the contract leaves optional, request bodies send every contract property when
all arguments are given and exactly the contract's ``required`` members when only the required arguments are given
(absent optionals are never sent). The YAML is read with a small line reader for the generator's PyYAML block layout
(no third-party dependency); it fails loudly if that layout changes.
"""
from __future__ import annotations

import re
from dataclasses import MISSING, fields
from pathlib import Path
from typing import Any, Callable

import pytest

from sangam_sdk import SangamClient
from sangam_sdk.http import ApiRequest, ApiResponse
from sangam_sdk.models import (AuditEntry, CompleteTaskResult, ExternalWork, LockState, OutcomeUnknownResult, Task,
                               TopicRequest, WorkOperation, camel)

CONTRACT = Path(__file__).resolve().parents[3] / "sangam-rest" / "api" / "sangam-api.yaml"
_SECTION = re.compile(r"^ {6}(\w+):")
_PROPERTY = re.compile(r"^ {8}([A-Za-z0-9_$]+):")
_REQUIRED = re.compile(r"^ {6}- (\S+)$")


def read_schema(name: str) -> tuple[set[str], set[str]]:
    """(properties, required) of ``components.schemas.<name>``."""
    assert CONTRACT.is_file(), f"contract not found: {CONTRACT}"
    lines = CONTRACT.read_text(encoding="utf-8").splitlines()
    start = lines.index("  schemas:")
    head = lines.index(f"    {name}:", start)
    props: set[str] = set()
    required: set[str] = set()
    section = None
    for line in lines[head + 1:]:
        if line.strip() and len(line) - len(line.lstrip(" ")) < 6:
            break
        m = _SECTION.match(line)
        if m:
            section = m.group(1)
            continue
        if section == "properties" and (m := _PROPERTY.match(line)):
            props.add(m.group(1))
        elif section == "required" and (m := _REQUIRED.match(line)):
            required.add(m.group(1))
    assert props, f"no properties read for {name}: the contract layout changed"
    return props, required


# ------------------------------------------------------------------ response models (dataclasses)

RESPONSES = {
    "ExternalWork": ExternalWork, "WorkOperation": WorkOperation, "LockState": LockState,
    "OutcomeUnknownResult": OutcomeUnknownResult, "Task": Task, "CompleteTaskResult": CompleteTaskResult,
    "AuditEntry": AuditEntry, "TopicRequest": TopicRequest,
}


@pytest.mark.parametrize("schema", sorted(RESPONSES))
def test_model_matches_contract(schema: str) -> None:
    props, required = read_schema(schema)
    model = RESPONSES[schema]
    assert {camel(f.name) for f in fields(model)} == props
    no_default = {camel(f.name) for f in fields(model) if f.default is MISSING and f.default_factory is MISSING}
    assert no_default == required


def test_reader_sees_required_members() -> None:
    assert read_schema("WorkFailRequest")[1] == {"workerId", "lockVersion"}


# ------------------------------------------------------------------ request bodies

class Capture:
    def __init__(self) -> None:
        self.bodies: list[Any] = []

    def execute(self, req: ApiRequest) -> ApiResponse:
        self.bodies.append(req.body)
        return ApiResponse(200, {}, [] if req.path.endswith("fetch-and-lock") else {})


def body_of(call: Callable[[SangamClient], Any]) -> dict[str, Any]:
    client = SangamClient("http://sangam.test")
    cap = Capture()
    client.work._http = cap
    client.tasks._http = cap
    call(client)
    assert len(cap.bodies) == 1
    return cap.bodies[0]


V = {"x": 1}
REQUESTS: dict[str, tuple[Callable[[SangamClient], Any], Callable[[SangamClient], Any]]] = {
    "FetchAndLockRequest": (
        lambda c: c.work.fetch_and_lock("w", [TopicRequest("t")], 2, 100, True),
        lambda c: c.work.fetch_and_lock("w", [TopicRequest("t")])),
    "ExtendLockRequest": (
        lambda c: c.work.extend_lock("wk", "w", 1, "a", 1000),
        lambda c: c.work.extend_lock("wk", "w", 1, None, 1000)),
    "WorkCompleteRequest": (
        lambda c: c.work.complete("wk", "w", 1, "a", V, V, outcome_reference="r", outcome_details={"d": 1}),
        lambda c: c.work.complete("wk", "w", 1, None)),
    "WorkFailRequest": (
        lambda c: c.work.fail("wk", "w", 1, "a", "m", "d", 2, 1000),
        lambda c: c.work.fail("wk", "w", 1, None)),
    "WorkBpmnErrorRequest": (
        lambda c: c.work.bpmn_error("wk", "w", 1, "a", "E", "m", V),
        lambda c: c.work.bpmn_error("wk", "w", 1, None, "E")),
    "WorkUnlockRequest": (
        lambda c: c.work.unlock("wk", "w", 1, "a"),
        lambda c: c.work.unlock("wk", "w", 1, None)),
    "OutcomeUnknownRequest": (
        lambda c: c.work.outcome_unknown("wk", "w", 1, "a", "d", V),
        lambda c: c.work.outcome_unknown("wk", "w", 1, None)),
    "CompleteTaskRequest": (
        lambda c: c.tasks.complete("t", "h", variables=V, decision="APPROVE", remarks="ok", case_version=3,
                                   form_version="f1", reason_code="R", next_seat_id="S", next_seat_reason="why"),
        lambda c: c.tasks.complete("t", "h")),
    "ReturnTaskRequest": (
        lambda c: c.tasks.return_task("t", "r", reason_code="R", target_activity_id="A", variables=V,
                                      target_seat_id="S", target_level=2, payload_hash="h", case_version=3),
        lambda c: c.tasks.return_task("t", "r")),
}


@pytest.mark.parametrize("schema", sorted(REQUESTS))
def test_request_body_matches_contract(schema: str) -> None:
    props, required = read_schema(schema)
    full, minimal = REQUESTS[schema]
    assert set(body_of(full)) == props
    assert set(body_of(minimal)) == required


def test_outcome_members_match_work_outcome() -> None:
    props, _ = read_schema("WorkOutcome")
    body = body_of(lambda c: c.work.complete("wk", "w", 1, "a", outcome_reference="r", outcome_details={"d": 1}))
    assert set(body["outcome"]) == props
    only_ref = body_of(lambda c: c.work.complete("wk", "w", 1, "a", outcome_reference="r"))
    assert only_ref["outcome"] == {"reference": "r"}


def test_topic_members_match_topic_request() -> None:
    props, required = read_schema("TopicRequest")
    assert set(TopicRequest("t", 1000, ["a"], True, "k", "b").to_dict()) == props
    assert set(TopicRequest("t").to_dict()) == required


def test_return_task_answers_the_complete_task_result() -> None:
    client = SangamClient("http://sangam.test")

    class Answer(Capture):
        def execute(self, req: ApiRequest) -> ApiResponse:
            return ApiResponse(200, {}, {"taskId": "t", "instanceEnded": False, "nextTaskIds": ["n"]})

    client.tasks._http = Answer()
    r = client.tasks.return_task("t", "r")
    assert isinstance(r, CompleteTaskResult) and r.task_id == "t" and r.next_task_ids == ["n"]

# SPDX-License-Identifier: Apache-2.0
"""Wire models of the Sangam API, reconciled with ``sangam-rest/api/sangam-api.yaml`` 0.1.0.

camelCase on the wire, snake_case in Python. Responses are dataclasses built with ``from_dict`` (unknown fields
ignored). Requests are built by the API classes as dicts, dropping ``None`` members. Variables (VariableMap) are
kept as received; use :func:`sangam_sdk.typed.decode_variables` or the worker context getters to unwrap R33
TypedValues. Result objects of mutations carry ``idempotent_replayed`` (the ``Idempotent-Replayed`` header).
"""
from __future__ import annotations

import re
import uuid
from dataclasses import dataclass, field, fields
from datetime import datetime, timezone
from typing import Any, Generic, Mapping, Optional, TypeVar

T = TypeVar("T")
_CAMEL = re.compile(r"_([a-z])")


def camel(name: str) -> str:
    return _CAMEL.sub(lambda m: m.group(1).upper(), name)


def parse_time(v: Any) -> Optional[datetime]:
    """ISO-8601 UTC (``Z`` accepted on Python 3.10)."""
    if v is None or isinstance(v, datetime):
        return v
    s = str(v)
    if s.endswith("Z"):
        s = s[:-1] + "+00:00"
    try:
        return datetime.fromisoformat(s)
    except ValueError:
        return None


def compact(d: Mapping[str, Any]) -> dict[str, Any]:
    """Drops None values (the server treats a missing member as absent)."""
    return {k: v for k, v in d.items() if v is not None}


class _Model:
    _times: tuple[str, ...] = ()
    #: True when the server answered from its idempotency store (``Idempotent-Replayed: true``)
    idempotent_replayed: bool = False

    @classmethod
    def from_dict(cls, d: Mapping[str, Any] | None):  # type: ignore[no-untyped-def]
        if d is None or not isinstance(d, Mapping):
            return None
        kwargs: dict[str, Any] = {}
        for f in fields(cls):  # type: ignore[arg-type]
            key = camel(f.name)
            if key in d:
                v = d[key]
                if f.name in cls._times:
                    v = parse_time(v)
                kwargs[f.name] = v
        return cls(**kwargs)  # type: ignore[call-arg]


# ------------------------------------------------------------------ worker

@dataclass
class ExternalWork(_Model):
    """A locked unit of external work (VI.3). Handlers run only after this LOCKED confirmation (A-1).

    ``operation_id`` is stable across retries and lease expiry (R36) and is the downstream idempotency key;
    ``attempt_id`` changes per lease (A-2). Every member is optional in the contract; the worker starts an item only
    when ``id``, ``lock_version`` and ``attempt_id`` are present (A-1).
    """

    id: str = ""
    topic: str = ""
    tenant_id: Optional[str] = None
    process_instance_id: Optional[str] = None
    process_definition_key: Optional[str] = None
    activity_id: Optional[str] = None
    activity_instance_id: Optional[str] = None
    business_key: Optional[str] = None
    variables: dict[str, Any] = field(default_factory=dict)
    lock_version: Optional[int] = None
    attempt_id: Optional[str] = None
    operation_id: Optional[str] = None
    lock_expires_at: Optional[datetime] = None
    retries: Optional[int] = None
    priority: Optional[int] = None
    error_message: Optional[str] = None
    #: WorkOperation (SL-16): server-recorded ledger entry of ``operation_id``. ``may_dispatch`` False with state
    #: SUCCEEDED: complete from ``stored_outcome`` ({output, providerRef, duplicate}) without calling the downstream.
    operation: Optional["WorkOperation"] = None
    #: M-062: W3C trace context the engine stored for the task (``TraceContext``); None when none was stored
    trace_context: Optional["TraceContext"] = None
    _times = ("lock_expires_at",)

    def __post_init__(self) -> None:
        if isinstance(self.operation, Mapping):
            self.operation = WorkOperation.from_dict(self.operation)
        if isinstance(self.trace_context, Mapping):
            self.trace_context = TraceContext.from_dict(self.trace_context)


@dataclass
class TraceContext(_Model):
    """W3C Trace Context of the request that created the work (R13, M-062). ``traceparent`` is required."""

    traceparent: str
    tracestate: Optional[str] = None


@dataclass
class WorkOperation(_Model):
    """Operation-ledger entry of a leased work item (SL-16, R37). ``attempts > 1`` is a repeat attempt."""

    state: Optional[str] = None
    attempts: Optional[int] = None
    capability_class: Optional[str] = None
    may_dispatch: Optional[bool] = None
    #: recorded outcome when settled: {output, providerRef, duplicate} (wire keys); None while INTENT
    stored_outcome: Optional[dict[str, Any]] = None


@dataclass
class TopicRequest(_Model):
    """One topic of a fetch-and-lock (lease default 5 min, maximum 24 h). Only ``name`` is required."""

    name: str
    lock_duration_ms: Optional[int] = None
    variables: Optional[list[str]] = None
    local_variables: Optional[bool] = None
    process_definition_key: Optional[str] = None
    business_key: Optional[str] = None

    def to_dict(self) -> dict[str, Any]:
        """Wire form; absent (None) members are not sent."""
        return compact({camel(f.name): getattr(self, f.name) for f in fields(self)})


@dataclass
class LockState(_Model):
    """Heartbeat answer (``extend-lock``): ``cancel_requested`` tells the worker to stop and acknowledge."""

    id: Optional[str] = None
    lock_version: Optional[int] = None
    lock_expires_at: Optional[datetime] = None
    cancel_requested: bool = False
    _times = ("lock_expires_at",)


ExtendLockResult = LockState  # pre-contract name


@dataclass
class OutcomeUnknownResult(_Model):
    """The work and its ledger operation (``operation_id``, always set) moved to UNKNOWN_OUTCOME (202)."""

    work_id: Optional[str] = None
    operation_id: Optional[str] = None
    reconciliation_item_id: Optional[str] = None
    state: Optional[str] = None


@dataclass
class Page(Generic[T]):
    """One list page. When ``total_count_capped`` the count is a lower bound ("more than N", A-16)."""

    items: list[T]
    page: int
    size: int
    total_count: Optional[int]
    total_count_capped: bool


# ------------------------------------------------------------------ tasks

@dataclass
class Task(_Model):
    """A user task as seen through the VI.4 policy."""

    id: str = ""
    name: Optional[str] = None
    description: Optional[str] = None
    task_definition_key: Optional[str] = None
    process_instance_id: Optional[str] = None
    process_definition_key: Optional[str] = None
    case_instance_id: Optional[str] = None
    business_key: Optional[str] = None
    assignee: Optional[str] = None
    owner: Optional[str] = None
    seat_id: Optional[str] = None
    tenant_id: Optional[str] = None
    created: Optional[datetime] = None
    due: Optional[datetime] = None
    follow_up: Optional[datetime] = None
    last_updated: Optional[datetime] = None
    claim_time: Optional[datetime] = None
    state: Optional[str] = None
    priority: Optional[int] = None
    form_key: Optional[str] = None
    delegation_state: Optional[str] = None
    #: VariableMap as sent (TypedValues not unwrapped)
    variables: Optional[dict[str, Any]] = None
    #: M-042 task index: MoneyAmount, a decimal string in major units with exactly the currency's fraction digits
    #: (never a float); None when the process has no index rule or the rule found no amount
    amount: Optional[str] = None
    #: ISO 4217 code of ``amount``; None with it
    currency: Optional[str] = None
    #: reference the process's index rule set at creation (at most 255 characters); None without one
    reference: Optional[str] = None
    _times = ("created", "due", "follow_up", "last_updated", "claim_time")


TaskView = Task  # pre-contract name


@dataclass
class CompleteTaskResult(_Model):
    task_id: Optional[str] = None
    process_instance_id: Optional[str] = None
    decision: Optional[str] = None
    payload_hash: Optional[str] = None
    instance_ended: Optional[bool] = None
    next_task_ids: list[str] = field(default_factory=list)


# ------------------------------------------------------------------ processes, messages, cases, decisions

@dataclass
class ProcessInstance(_Model):
    id: str = ""
    process_definition_id: Optional[str] = None
    process_definition_key: Optional[str] = None
    process_definition_version: Optional[int] = None
    business_key: Optional[str] = None
    tenant_id: Optional[str] = None
    state: Optional[str] = None
    ended: Optional[bool] = None
    start_time: Optional[datetime] = None
    end_time: Optional[datetime] = None
    active_activity_ids: list[str] = field(default_factory=list)
    case_version: Optional[Any] = None
    started_by: Optional[str] = None
    business_status: Optional[str] = None
    super_process_instance_id: Optional[str] = None
    root_process_instance_id: Optional[str] = None
    _times = ("start_time", "end_time")


@dataclass
class TerminationResult(_Model):
    """Business cancel result (VI.5): open operation ids, cancel-requested work and tombstones."""

    process_instance_id: Optional[str] = None
    state: Optional[str] = None
    open_operation_ids: list[str] = field(default_factory=list)
    cancel_requested_work: Optional[Any] = None
    tombstones: Optional[Any] = None


@dataclass
class MessageResult(_Model):
    message_name: Optional[str] = None
    process_instance_ids: list[str] = field(default_factory=list)
    execution_ids: list[str] = field(default_factory=list)


@dataclass
class CaseInstance(_Model):
    id: str = ""
    case_definition_id: Optional[str] = None
    case_definition_key: Optional[str] = None
    business_key: Optional[str] = None
    tenant_id: Optional[str] = None
    state: Optional[str] = None
    created: Optional[datetime] = None
    executions: Optional[Any] = None
    reactivated_from: Optional[str] = None
    _times = ("created",)


@dataclass
class DecisionEvaluateResult(_Model):
    """Decision output rows (VariableMaps), version, output digest and the audit event id (R33)."""

    decision_definition_id: Optional[str] = None
    decision_key: Optional[str] = None
    version: Optional[int] = None
    result: list[dict[str, Any]] = field(default_factory=list)
    output_digest: Optional[str] = None
    audit_event_id: Optional[str] = None


DecisionResult = DecisionEvaluateResult  # pre-contract name


# ------------------------------------------------------------------ audit, ops

@dataclass
class AuditEntry(_Model):
    """One audit-chain entry: seat, person and acting-for seat (AC-01) plus hash-chain fields."""

    event_id: Optional[str] = None
    event_class: Optional[str] = None
    chain_id: Optional[str] = None
    seq: Optional[int] = None
    type: Optional[str] = None
    occurred_at: Optional[datetime] = None
    actor: Optional[str] = None
    seat_id: Optional[str] = None
    acted_for_seat_id: Optional[str] = None
    process_instance_id: Optional[str] = None
    activity_id: Optional[str] = None
    task_id: Optional[str] = None
    payload: Optional[Any] = None
    erased: Optional[bool] = None
    hash: Optional[str] = None
    evidence: Optional[bool] = None
    prev_hash: Optional[str] = None
    entry_hash: Optional[str] = None
    envelope: Optional[str] = None
    _times = ("occurred_at",)


@dataclass
class AuditVerifyResult(_Model):
    tenant_id: Optional[str] = None
    ok: bool = False
    verified_at: Optional[datetime] = None
    chains: list[Any] = field(default_factory=list)
    _times = ("verified_at",)


@dataclass
class ReconciliationItem(_Model):
    """An UNKNOWN_OUTCOME / LATE_RESULT_AFTER_DELETE reconciliation item (VI.3, VI.5)."""

    id: str = ""
    kind: Optional[str] = None
    operation_id: Optional[str] = None
    root_id: Optional[str] = None
    task_id: Optional[str] = None
    state: Optional[str] = None
    details: Optional[Any] = None
    action: Optional[str] = None
    resolved_by: Optional[str] = None
    resolution: Optional[Any] = None
    created_at: Optional[datetime] = None
    resolved_at: Optional[datetime] = None
    _times = ("created_at", "resolved_at")


@dataclass
class Deployment(_Model):
    id: str = ""
    name: Optional[str] = None
    source: Optional[str] = None
    tenant_id: Optional[str] = None
    deployment_time: Optional[datetime] = None
    resources: list[str] = field(default_factory=list)
    definitions: list[Any] = field(default_factory=list)
    _times = ("deployment_time",)


# ------------------------------------------------------------------ events

@dataclass
class CloudEvent:
    """CloudEvents 1.0 envelope for ``POST /events/by-channel?channel=`` (R16). Required: id, source, type, specversion."""

    source: str
    type: str
    data: Any = None
    id: str = field(default_factory=lambda: str(uuid.uuid4()))
    specversion: str = "1.0"
    subject: Optional[str] = None
    time: Optional[str] = field(default_factory=lambda: datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"))
    datacontenttype: Optional[str] = "application/json"
    dataschema: Optional[str] = None
    extensions: Optional[dict[str, Any]] = None

    def to_dict(self) -> dict[str, Any]:
        return compact({"id": self.id, "source": self.source, "type": self.type, "specversion": self.specversion,
                        "subject": self.subject, "time": self.time, "datacontenttype": self.datacontenttype,
                        "dataschema": self.dataschema, "data": self.data, "extensions": self.extensions})


@dataclass
class PublishResult(_Model):
    """``status``: ACCEPTED | DUPLICATE | BUFFERED."""

    event_id: Optional[str] = None
    status: Optional[str] = None
    event_definition_key: Optional[str] = None
    started_instance_ids: list[str] = field(default_factory=list)
    correlated_instance_ids: list[str] = field(default_factory=list)


__all__ = [
    "AuditEntry", "AuditVerifyResult", "CaseInstance", "CloudEvent", "CompleteTaskResult", "DecisionEvaluateResult",
    "DecisionResult", "Deployment", "ExtendLockResult", "ExternalWork", "LockState", "MessageResult",
    "OutcomeUnknownResult", "Page", "ProcessInstance", "PublishResult", "ReconciliationItem", "Task", "TaskView",
    "TerminationResult", "camel", "compact", "parse_time",
]

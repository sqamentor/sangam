# SPDX-License-Identifier: Apache-2.0
"""Saga and compensation worker helper (M-026, §3.2, R36).

Registers one forward action and its compensation as two external-task handlers and carries the forward action's
identity to the compensation. In the model the forward step is a service task with topic ``action_topic``; its
compensation handler (attached through a compensation boundary event, ``isForCompensation="true"``) is a service task
with topic ``compensation_topic``. When the forward action completes, the helper adds the process variable
:func:`Saga.record_variable` holding a :class:`SagaRecord` (forward operationId, provider reference, work id) as an R33
``Json`` typed value. When the engine compensates, the compensation handler gets that record in its
:class:`CompensationContext` and reuses the forward operationId: the undo call's idempotency key is derived from it,
so compensating the same forward action twice undoes it once.

One record per step name and process scope: a step repeated in a loop or multi-instance body needs its own name per
iteration, or a compensation that reads the iteration's data from local variables::

    Saga.step("charge") \\
        .action("payment.charge", lambda ctx: {"chargeId": charge(ctx)}) \\
        .compensation("payment.refund", lambda c: refund(c.forward_provider_ref, c.idempotency_key)) \\
        .build() \\
        .register_on(worker)
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass
from typing import TYPE_CHECKING, Any, Callable, Mapping, Optional

from .typed import TypedValue, decode_value
from .worker import TopicSubscription

if TYPE_CHECKING:
    from .worker import TaskContext, Worker

RECORD_PREFIX = "sangamSaga_"
_NAME = re.compile(r"[A-Za-z0-9._-]{1,64}")

SagaAction = Callable[["TaskContext"], Optional[Mapping[str, Any]]]
SagaCompensation = Callable[["CompensationContext"], Optional[Mapping[str, Any]]]


@dataclass(frozen=True)
class SagaRecord:
    """What a saga's forward action leaves for its compensation: the forward work's stable operationId, its provider
    reference and its work id. Stored as the process variable :func:`Saga.record_variable`."""

    step: Optional[str]
    operation_id: Optional[str]
    provider_ref: Optional[str]
    work_id: Optional[str]

    def to_dict(self) -> dict[str, Any]:
        return {"step": self.step, "operationId": self.operation_id, "providerRef": self.provider_ref,
                "workId": self.work_id}

    @staticmethod
    def from_dict(m: Any) -> Optional["SagaRecord"]:
        if not isinstance(m, Mapping) or m.get("operationId") is None:
            return None

        def s(v: Any) -> Optional[str]:
            return None if v is None else str(v)

        return SagaRecord(s(m.get("step")), s(m.get("operationId")), s(m.get("providerRef")), s(m.get("workId")))


class CompensationContext:
    """What a saga compensation handler sees: the compensation work's own :class:`~sangam_sdk.worker.TaskContext` and
    the record of the forward action it undoes. The undo call reuses the forward operationId (R36): send
    :attr:`idempotency_key` so that compensating the same forward action twice undoes it once."""

    def __init__(self, task: "TaskContext", forward: Optional[SagaRecord], step: str) -> None:
        self._task = task
        self._forward = forward
        self._step = step

    @property
    def task(self) -> "TaskContext":
        """The compensation work item (lease, variables, durable steps, logger, client)."""
        return self._task

    @property
    def forward(self) -> Optional[SagaRecord]:
        """The forward action's record, or None when the instance holds none (the forward action did not complete
        through :class:`Saga`, so there is nothing this helper knows to undo)."""
        return self._forward

    @property
    def forward_operation_id(self) -> Optional[str]:
        """The forward action's operationId (R36), or None without a record."""
        return None if self._forward is None else self._forward.operation_id

    @property
    def forward_provider_ref(self) -> Optional[str]:
        """The forward action's provider reference (e.g. the payment to refund), or None."""
        return None if self._forward is None else self._forward.provider_ref

    @property
    def idempotency_key(self) -> str:
        """``forwardOperationId + ":compensate:" + step``; without a record, the compensation work's own operationId."""
        if self._forward is None:
            return self._task.operation_id
        return f"{self._forward.operation_id}:compensate:{self._step}"


class Saga:
    """One saga step: a forward action and its compensation as two :class:`~sangam_sdk.worker.TopicSubscription`."""

    RECORD_PREFIX = RECORD_PREFIX

    def __init__(self, name: str, action: TopicSubscription, compensation: TopicSubscription) -> None:
        self._name = name
        self._action = action
        self._compensation = compensation

    @staticmethod
    def step(name: str) -> "SagaBuilder":
        """Starts a saga step named ``name`` (letters, digits, '.', '-', '_'; at most 64 characters)."""
        return SagaBuilder(name)

    @staticmethod
    def record_variable(name: str) -> str:
        """The process variable that holds the record of step ``name``: ``sangamSaga_<name>`` with '.' and '-' as '_'."""
        return RECORD_PREFIX + name.replace(".", "_").replace("-", "_")

    @property
    def name(self) -> str:
        return self._name

    @property
    def action(self) -> TopicSubscription:
        """The forward action's subscription."""
        return self._action

    @property
    def compensation(self) -> TopicSubscription:
        """The compensation's subscription."""
        return self._compensation

    @property
    def subscriptions(self) -> list[TopicSubscription]:
        """Both subscriptions, forward action first."""
        return [self._action, self._compensation]

    def register_on(self, worker: "Worker") -> "Worker":
        """Subscribes both handlers on ``worker``."""
        worker.subscribe(self._action)
        return worker.subscribe(self._compensation)


def with_record(step: str, ctx: "TaskContext", variables: Optional[Mapping[str, Any]]) -> dict[str, Any]:
    """The forward result variables plus the record variable (an R33 ``Json`` typed value)."""
    out: dict[str, Any] = dict(variables or {})
    rec = SagaRecord(step, ctx.operation_id, ctx.outcome_reference, ctx.work_id)
    out[Saga.record_variable(step)] = TypedValue.json(rec.to_dict())
    return out


def record_of(step: str, ctx: "TaskContext") -> Optional[SagaRecord]:
    """The forward record from the compensation's variables, or None when the variable is absent."""
    raw = ctx.raw_variables.get(Saga.record_variable(step))
    if raw is None:
        return None
    value = decode_value(raw)
    if isinstance(value, str):
        try:
            value = json.loads(value)
        except ValueError:
            return None
    return SagaRecord.from_dict(value)


class SagaBuilder:
    """Builder of :class:`Saga`; options apply to both subscriptions (irreversible to the forward action only)."""

    def __init__(self, name: str) -> None:
        if not isinstance(name, str) or not _NAME.fullmatch(name):
            raise ValueError(f"saga step name must be 1-64 of [A-Za-z0-9._-]: {name!r}")
        self._name = name
        self._action_topic: Optional[str] = None
        self._action: Optional[SagaAction] = None
        self._compensation_topic: Optional[str] = None
        self._compensation: Optional[SagaCompensation] = None
        self._lock_duration_ms = 5 * 60 * 1000
        self._retries = 3
        self._irreversible = False
        self._variables: Optional[list[str]] = None

    def action(self, topic: str, handler: SagaAction) -> "SagaBuilder":
        """The forward action: topic and handler (a normal handler; its result variables are kept)."""
        if not topic or handler is None:
            raise ValueError("action needs a topic and a handler")
        self._action_topic, self._action = topic, handler
        return self

    def compensation(self, topic: str, handler: SagaCompensation) -> "SagaBuilder":
        """The compensation: topic and handler receiving a :class:`CompensationContext`."""
        if not topic or handler is None:
            raise ValueError("compensation needs a topic and a handler")
        self._compensation_topic, self._compensation = topic, handler
        return self

    def lock_duration_ms(self, v: int) -> "SagaBuilder":
        """Lease per fetch for both topics (default 5 min)."""
        self._lock_duration_ms = int(v)
        return self

    def retries(self, v: int) -> "SagaBuilder":
        """Retries for the first failure of either handler (default 3)."""
        self._retries = int(v)
        return self

    def irreversible(self, v: bool = True) -> "SagaBuilder":
        """The forward action has an irreversible effect (VI.3.2 rule 5): unexpected errors become outcome-unknown."""
        self._irreversible = bool(v)
        return self

    def variables(self, v: Optional[list[str]]) -> "SagaBuilder":
        """Variables to fetch for both handlers (None = all); the record variable is added for the compensation."""
        self._variables = None if v is None else list(v)
        return self

    def build(self) -> Saga:
        if self._action is None or self._compensation is None or self._action_topic is None \
                or self._compensation_topic is None:
            raise ValueError(f"saga step {self._name} needs an action and a compensation")
        if self._action_topic == self._compensation_topic:
            raise ValueError(f"saga step {self._name}: action and compensation need their own topics")
        step = self._name
        act = self._action
        comp = self._compensation

        def forward(ctx: "TaskContext") -> dict[str, Any]:
            return with_record(step, ctx, act(ctx))

        def undo(ctx: "TaskContext") -> Optional[Mapping[str, Any]]:
            return comp(CompensationContext(ctx, record_of(step, ctx), step))

        comp_vars: Optional[list[str]] = None
        if self._variables is not None:
            comp_vars = list(self._variables)
            rv = Saga.record_variable(step)
            if rv not in comp_vars:
                comp_vars.append(rv)
        fwd = TopicSubscription(self._action_topic, forward, lock_duration_ms=self._lock_duration_ms,
                                variables=None if self._variables is None else list(self._variables),
                                retries=self._retries, irreversible=self._irreversible)
        back = TopicSubscription(self._compensation_topic, undo, lock_duration_ms=self._lock_duration_ms,
                                 variables=comp_vars, retries=self._retries)
        return Saga(step, fwd, back)


__all__ = ["RECORD_PREFIX", "CompensationContext", "Saga", "SagaAction", "SagaBuilder", "SagaCompensation",
           "SagaRecord", "record_of", "with_record"]

# SPDX-License-Identifier: Apache-2.0
"""Durable steps (M-025, R37 / E02): :meth:`TaskContext.step <sangam_sdk.worker.TaskContext.step>`.

A worker records each side-effecting step of a leased work item in the step journal
(``GET/PUT /work/{id}/step?stepName=``) under the work's stable operationId (R36), fenced by the context's lease:

1. read the step: ``SUCCEEDED`` replays the recorded result without calling ``fn`` again;
2. ``INTENT`` left by an earlier attempt (the crash window): ask ``lookup`` if given (a value is recorded
   ``SUCCEEDED`` and returned; None means no effect), else run again only when ``idempotent=True``, else raise
   :class:`~sangam_sdk.worker.OutcomeUnknown` so the worker reports outcome-unknown;
3. record ``INTENT``, run ``fn(step)``, record ``SUCCEEDED`` with its result, or ``FAILED`` on
   :class:`StepNoEffectError`; any other exception leaves ``INTENT`` (uncertain) and propagates unchanged.

A 409 STALE_LEASE or 410 from the journal marks the lease lost (``ctx.lease_lost``, ``ctx.cancel_event``) and raises
:class:`~sangam_sdk.worker.TaskCancelled`.

NOT exactly-once for remote APIs: a crash between the remote call and the ``SUCCEEDED`` record leaves ``INTENT``. The
next attempt cannot know whether the call took effect, so it resolves the step by the call's capability class (R36):
(a) ``idempotent=True`` re-issues with the same :attr:`Step.idempotency_key`; (b) ``lookup`` asks the remote system
first; (c) neither (the default) reports outcome-unknown and reconciliation decides. Never a blind re-issue (R37).
"""
from __future__ import annotations

import threading
from typing import TYPE_CHECKING, Any, Callable, Mapping, Optional, TypeVar

from .errors import GoneError, SangamApiError, StaleLeaseError
from .typed import TypedValue, decode_value, encode_value, is_typed
from .worker import OutcomeUnknown, TaskCancelled

if TYPE_CHECKING:
    from .worker import TaskContext

T = TypeVar("T")

INTENT = "INTENT"
SUCCEEDED = "SUCCEEDED"
FAILED = "FAILED"
MAX_NAME = 128


class StepNoEffectError(Exception):
    """Raised by a step body when the remote call certainly had no effect (refused before it was sent, or rejected as
    a business failure). The step is recorded ``FAILED`` with the message, so a later attempt may run it again; the
    exception then propagates to the handler (R37)."""


class Step:
    """The handle a durable step's body and lookup receive: the step's name, its stable idempotency key, what an
    earlier attempt recorded, and a slot for the provider's reference."""

    def __init__(self, name: str, operation_id: str, previous_state: Optional[str]) -> None:
        self._name = name
        self._operation_id = operation_id
        self._previous_state = previous_state
        self._lock = threading.Lock()
        self._provider_ref: Optional[str] = None

    @property
    def name(self) -> str:
        """The step name, unique within the work's operation."""
        return self._name

    @property
    def operation_id(self) -> str:
        """The work's stable operationId (R36)."""
        return self._operation_id

    @property
    def idempotency_key(self) -> str:
        """Idempotency key for the remote call: ``operationId + ":" + name``. Stable across retries, redelivery and
        lease expiry, and different for each step of the same operation."""
        return f"{self._operation_id}:{self._name}"

    @property
    def previous_state(self) -> Optional[str]:
        """None on the first run, ``"INTENT"`` when an earlier attempt's outcome is unknown, ``"FAILED"`` when it
        failed without effect."""
        return self._previous_state

    @property
    def provider_ref(self) -> Optional[str]:
        """The provider's reference (e.g. a payment id), recorded with the outcome; later lookups can use it."""
        with self._lock:
            return self._provider_ref

    @provider_ref.setter
    def provider_ref(self, ref: Optional[str]) -> None:
        with self._lock:
            self._provider_ref = ref

    def __repr__(self) -> str:
        return f"Step(name={self._name!r}, operation_id={self._operation_id!r}, previous_state={self._previous_state!r})"


StepFn = Callable[[Step], T]
StepLookup = Callable[[Step], Optional[T]]


def require_name(name: Any) -> str:
    """1-128 printable ASCII characters (0x20-0x7E), else :class:`ValueError`."""
    if not isinstance(name, str) or not name or len(name) > MAX_NAME:
        raise ValueError("step name must be 1-128 characters")
    if any(not 0x20 <= ord(ch) <= 0x7E for ch in name):
        raise ValueError("step name must be printable ASCII")
    return name


def run_step(ctx: "TaskContext", name: str, fn: StepFn[T], *, idempotent: bool = False,
             lookup: Optional[StepLookup[T]] = None) -> T:
    """The algorithm of :meth:`TaskContext.step` (see the module docstring)."""
    require_name(name)
    if not callable(fn):
        raise TypeError("fn must be callable")
    if lookup is not None and not callable(lookup):
        raise TypeError("lookup must be callable")
    recorded = _read(ctx, name)
    previous = None if recorded is None else recorded.get("state")
    step = Step(name, ctx.operation_id, previous)
    if previous == SUCCEEDED:
        ctx.logger.info("step.replayed", "step already succeeded; replaying its recorded result", {"step": name})
        return decode_result(recorded.get("result") if recorded is not None else None)
    if previous == INTENT:
        if lookup is not None:
            found = lookup(step)  # an exception propagates: the step stays INTENT
            if found is not None:
                _write(ctx, name, SUCCEEDED, found, step.provider_ref, None)
                ctx.logger.info("step.looked-up", "earlier attempt took effect; recorded from lookup", {"step": name})
                return found
        elif not idempotent:
            raise OutcomeUnknown(
                f"step {name} of operation {ctx.operation_id} was started by an earlier attempt and its outcome is "
                "unknown (R37)", f"step={name}", {"step": name, "idempotencyKey": step.idempotency_key})
    _write(ctx, name, INTENT, None, None, None)
    try:
        value = fn(step)
    except StepNoEffectError as e:
        _write(ctx, name, FAILED, None, step.provider_ref, str(e))
        raise
    _write(ctx, name, SUCCEEDED, value, step.provider_ref, None)
    return value


# --------------------------------------------------------------------------- journal calls

def _read(ctx: "TaskContext", name: str) -> Optional[Mapping[str, Any]]:
    try:
        out = ctx.client.api.work.get_work_step(ctx.work_id, step_name=name, worker_id=ctx.worker_id,
                                                lock_version=ctx.lock_version, attempt_id=ctx.attempt_id or "")
    except (StaleLeaseError, GoneError) as e:
        raise _lease_lost(ctx, name, e) from e
    except SangamApiError as e:
        if e.status == 404:
            return None  # never recorded: run it
        raise
    return out if isinstance(out, Mapping) else None


def _write(ctx: "TaskContext", name: str, state: str, result: Any, provider_ref: Optional[str],
           error: Optional[str]) -> None:
    body: dict[str, Any] = {"workerId": ctx.worker_id, "lockVersion": ctx.lock_version,
                            "attemptId": ctx.attempt_id or "", "state": state, "result": encode_result(result),
                            "providerRef": provider_ref, "error": error}
    try:
        ctx.client.api.work.record_work_step(ctx.work_id, body, step_name=name)  # type: ignore[arg-type]
    except (StaleLeaseError, GoneError) as e:
        raise _lease_lost(ctx, name, e) from e


def _lease_lost(ctx: "TaskContext", name: str, cause: BaseException) -> TaskCancelled:
    ctx.lease_lost = True
    ctx.cancel_event.set()
    ctx.logger.warn("work.lease.lost", f"lease lost while recording step {name}", {"step": name}, cause)
    return TaskCancelled(f"lease lost while recording step {name}")


# --------------------------------------------------------------------------- R33 result values

def encode_result(v: Any) -> Any:
    """A step result for the wire: R33 typed values (Decimal, big int, datetime, date, bytes) at any depth of plain
    dicts and lists, the way the SDK encodes variables."""
    if isinstance(v, TypedValue) or is_typed(v):
        return encode_value(v)
    if isinstance(v, Mapping):
        return {str(k): encode_result(x) for k, x in v.items()}
    if isinstance(v, (list, tuple)):
        return [encode_result(x) for x in v]
    return encode_value(v)


def decode_result(v: Any) -> Any:
    """A recorded step result with R33 typed values unwrapped (the content of a ``Json`` value is returned as is)."""
    if is_typed(v):
        return decode_value(v)
    if isinstance(v, Mapping):
        return {k: decode_result(x) for k, x in v.items()}
    if isinstance(v, list):
        return [decode_result(x) for x in v]
    return v


__all__ = ["FAILED", "INTENT", "SUCCEEDED", "Step", "StepFn", "StepLookup", "StepNoEffectError", "decode_result",
           "encode_result", "require_name", "run_step"]

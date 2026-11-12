# SPDX-License-Identifier: Apache-2.0
"""The step journal of :class:`~sangam_sdk.testing.SangamTestEngine` (M-025, R37).

``GET/PUT /work/{id}/steps/{stepName}`` and ``GET /work/{id}/steps``, keyed by (operationId, step name) and fenced by
the lease like the server (``WorkStepJournal`` in the same transaction as the lease check). Transitions:

* ``INTENT`` is allowed on a new, ``INTENT`` or ``FAILED`` step;
* ``SUCCEEDED`` / ``FAILED`` need an ``INTENT`` (409 ``STEP_INTENT_REQUIRED``);
* ``SUCCEEDED`` is final (409 ``STEP_ALREADY_RECORDED``) unless the request repeats it identically (same result and
  providerRef); an identical repeat of ``INTENT`` / ``FAILED`` by the same attempt is a no-op.

Fencing uses the engine's own fence (order NO_LEASE, STALE_VERSION, NOT_OWNER, EXPIRED, ATTEMPT_MISMATCH). A missing
attemptId or a bad step name is 400 ``VALIDATION_FAILED``; work whose instance was deleted is 410
``GONE_INSTANCE_DELETED``; unknown work and work without an external task (completed, BPMN error, cancelled, failed) is
404 ``NOT_FOUND`` exactly as on the server (SL-174, vectors in ``sdks/testing/work-step-problems.json``). The list answers the steps of the work's operation in recording order. The engine calls
this while it holds its lock.
"""
from __future__ import annotations

from typing import TYPE_CHECKING, Any, Callable, Mapping, Optional

if TYPE_CHECKING:
    from .testing import EngineProblem, _Work

STATES = ("INTENT", "SUCCEEDED", "FAILED")
# States in which the server still has the work's external task (an incident and a reconciliation hold keep it).
TASK_EXISTS = frozenset({"AVAILABLE", "LOCKED", "INCIDENT", "UNKNOWN_OUTCOME"})


def _problem(status: int, code: str, detail: str, **ext: Any) -> "EngineProblem":
    from .testing import EngineProblem

    return EngineProblem(status, code, detail, **ext)


def _valid_name(name: Any) -> bool:
    return isinstance(name, str) and 0 < len(name) <= 128 and all(0x20 <= ord(c) <= 0x7E for c in name)


class EngineSteps:
    """The journal rows: operationId -> step name -> row (dicts keep insertion, i.e. recording, order)."""

    def __init__(self, works: Mapping[str, "_Work"], fence: Callable[["_Work", Mapping[str, Any]], None],
                 now_iso: Callable[[], str]) -> None:
        self._works = works
        self._fence = fence
        self._now_iso = now_iso
        self._steps: dict[str, dict[str, dict[str, Any]]] = {}

    # ------------------------------------------------------------------ routes

    def get(self, work_id: str, name: str, query: Mapping[str, Any]) -> dict[str, Any]:
        lv_raw = query.get("lockVersion")
        try:
            lv: Optional[int] = None if lv_raw is None else int(lv_raw)
        except (TypeError, ValueError):
            raise _problem(400, "VALIDATION_FAILED", "lockVersion must be an integer") from None
        if lv is None:
            raise _problem(400, "VALIDATION_FAILED", "lockVersion is required")
        w = self._fenced(work_id, name, {"workerId": query.get("workerId"), "lockVersion": lv,
                                         "attemptId": query.get("attemptId")})
        row = self._steps.get(w.operation_id, {}).get(name)
        if row is None:
            raise _problem(404, "NOT_FOUND", f"work step {name} was not found or is not visible to you",
                           resource="work step")
        return self._view(row)

    def put(self, work_id: str, name: str, body: Mapping[str, Any]) -> dict[str, Any]:
        w = self._fenced(work_id, name, body)
        state = body.get("state")
        if state not in STATES:
            raise _problem(400, "VALIDATION_FAILED", "state must be INTENT, SUCCEEDED or FAILED")
        attempt = body.get("attemptId")
        result = body.get("result")
        ref = body.get("providerRef")
        error = body.get("error")
        op = self._steps.setdefault(w.operation_id, {})
        row = op.get(name)
        if row is None:
            if state != "INTENT":
                raise self._intent_required(name, None)
            row = {"operationId": w.operation_id, "name": name}
            op[name] = row
        else:
            if self._identical(row, state, attempt, result, ref, error):
                return self._view(row)
            if row["state"] == "SUCCEEDED":
                raise _problem(409, "STEP_ALREADY_RECORDED", f"step {name} already SUCCEEDED")
            if state != "INTENT" and row["state"] != "INTENT":
                raise self._intent_required(name, row["state"])
        row.update({"state": state, "result": result, "providerRef": ref, "error": error, "attemptId": attempt,
                    "recordedAt": self._now_iso(), "_workId": work_id})
        return self._view(row)

    def list_steps(self, work_id: str) -> list[dict[str, Any]]:
        w = self._works.get(work_id)
        # as WorkStepController.list: the operation of open work, else the rows this work id recorded
        if w is not None and not w.deleted and w.state in TASK_EXISTS:
            rows = list(self._steps.get(w.operation_id, {}).values())
        else:
            rows = [r for m in self._steps.values() for r in m.values() if r.get("_workId") == work_id]
            if not rows:
                raise _problem(404, "NOT_FOUND", f"external work {work_id} was not found or is not visible to you",
                               resource="external work")
        return [self._view(r) for r in rows]

    def rows(self, operation_id: str) -> list[dict[str, Any]]:
        """Inspection for tests: the steps of an operation in recording order."""
        return [self._view(r) for r in self._steps.get(operation_id, {}).values()]

    # ------------------------------------------------------------------ helpers

    def _fenced(self, work_id: str, name: str, b: Mapping[str, Any]) -> "_Work":
        if not _valid_name(name):
            raise _problem(400, "VALIDATION_FAILED", "stepName must be 1-128 printable ASCII characters")
        att = b.get("attemptId")
        if not isinstance(att, str) or not att.strip():
            raise _problem(400, "VALIDATION_FAILED", "attemptId is required")
        w = self._works.get(work_id)
        if w is None:
            raise _problem(404, "NOT_FOUND", f"external work {work_id} was not found or is not visible to you",
                           resource="external work")
        # as OperatonWorkAdapter.inFencedWork: a deleted instance (tombstone) is 410, any other ended work 404, then the fence
        if w.deleted:
            raise _problem(410, "GONE_INSTANCE_DELETED", "The instance of this work was deleted", workId=work_id)
        if w.state not in TASK_EXISTS:
            raise _problem(404, "NOT_FOUND", f"external work {work_id} was not found or is not visible to you",
                           resource="external work")
        self._fence(w, b)
        return w

    @staticmethod
    def _identical(row: Mapping[str, Any], state: str, attempt: Any, result: Any, ref: Any, error: Any) -> bool:
        if row.get("state") != state:
            return False
        if state == "SUCCEEDED":
            return row.get("result") == result and row.get("providerRef") == ref
        return (row.get("attemptId") == attempt and row.get("result") == result and row.get("providerRef") == ref
                and row.get("error") == error)

    @staticmethod
    def _intent_required(name: str, current: Optional[str]) -> "EngineProblem":
        return _problem(409, "STEP_INTENT_REQUIRED",
                        f"step {name} has no INTENT" + ("" if current is None else f" (it is {current})"))

    @staticmethod
    def _view(row: Mapping[str, Any]) -> dict[str, Any]:
        return {k: row.get(k) for k in ("operationId", "name", "state", "result", "providerRef", "error", "attemptId",
                                        "recordedAt")}


__all__ = ["EngineSteps", "STATES"]

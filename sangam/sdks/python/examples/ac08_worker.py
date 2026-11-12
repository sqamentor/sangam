# SPDX-License-Identifier: Apache-2.0
"""AC-08 conformance worker (SANGAM.md IX.8 AC-08, M-023) for the Python SDK.

The AC-08 orchestrator runs the same scenario with the worker of every Tier-1 SDK against one server and compares
the results. CLI contract (identical in every SDK):

  env SANGAM_BASE_URL, SANGAM_TOKEN (optional), AC08_TOPIC, AC08_WORKER_ID,
      AC08_SCENARIO = complete | bpmn-error | fail-retry | outcome-unknown | stale-lease,
      AC08_TASKS (default 1), AC08_TIMEOUT_S (default 60), AC08_LOCK_MS (lock duration, default 30000)
  stdout: one JSON line per task {"lang","workId","operationId","lockVersion","attemptId","scenario","result","detail"}
  exit 0 when AC08_TASKS results were printed, 1 on timeout.

Per task the variables amount (Decimal), count (Long), when (Date), day (LocalDate) and note (String or null) are read
and, in the complete scenarios, written back unchanged as out_amount, out_count, out_when, out_day, out_note.
stale-lease uses the work API directly: the runtime's heartbeat would keep the lease alive, so the worker fetches,
lets the lease expire (AC08_LOCK_MS + 2 s) and then completes, expecting 409 STALE_LEASE.
"""
from __future__ import annotations

import json
import os
import sys
import threading
import time
from datetime import date, datetime
from decimal import Decimal
from typing import Any, Mapping, Optional

from sangam_sdk import (BpmnError, NoAuth, OutcomeUnknown, RetryPolicy, SangamClient, SangamLogger, StaleLeaseError,
                        StaticTokenProvider, TaskContext, TaskFailed, TopicSubscription, Worker)
from sangam_sdk.typed import decode_value

LANG = "python"
NAMES = ("amount", "count", "when", "day", "note")


def check_inputs(raw: Mapping[str, Any]) -> Optional[str]:
    """None when the R33 inputs decode to the expected types, else what is wrong."""
    v = {n: decode_value(raw.get(n)) for n in NAMES}
    if not isinstance(v["amount"], Decimal):
        return f"amount is {type(v['amount']).__name__}, not Decimal"
    if not isinstance(v["count"], int) or isinstance(v["count"], bool):
        return f"count is {type(v['count']).__name__}, not Long"
    if not isinstance(v["when"], datetime):
        return f"when is {type(v['when']).__name__}, not Date"
    if not isinstance(v["day"], date) or isinstance(v["day"], datetime):
        return f"day is {type(v['day']).__name__}, not LocalDate"
    if v["note"] is not None and not isinstance(v["note"], str):
        return f"note is {type(v['note']).__name__}, not String"
    return None


def outputs(raw: Mapping[str, Any]) -> dict[str, Any]:
    return {f"out_{n}": raw.get(n) for n in NAMES}


class Results:
    def __init__(self, scenario: str, wanted: int) -> None:
        self.scenario = scenario
        self.wanted = wanted
        self.count = 0
        self.lock = threading.Lock()
        self.done = threading.Event()
        self.details: dict[str, str] = {}

    def emit(self, work_id: Any, operation_id: Any, lock_version: Any, attempt_id: Any, result: str,
             detail: Optional[str] = None) -> None:
        with self.lock:
            if self.done.is_set():
                return
            print(json.dumps({"lang": LANG, "workId": work_id, "operationId": operation_id,
                              "lockVersion": lock_version, "attemptId": attempt_id, "scenario": self.scenario,
                              "result": result, "detail": detail}), flush=True)
            self.count += 1
            if self.count >= self.wanted:
                self.done.set()


def main() -> int:
    base = os.environ["SANGAM_BASE_URL"]
    token = os.environ.get("SANGAM_TOKEN")
    topic = os.environ["AC08_TOPIC"]
    worker_id = os.environ.get("AC08_WORKER_ID", f"ac08-{LANG}")
    scenario = os.environ.get("AC08_SCENARIO", "complete")
    wanted = int(os.environ.get("AC08_TASKS", "1"))
    timeout = float(os.environ.get("AC08_TIMEOUT_S", "60"))
    lock_ms = int(os.environ.get("AC08_LOCK_MS", "30000"))
    results = Results(scenario, wanted)
    auth = StaticTokenProvider(token) if token else NoAuth()

    if scenario == "stale-lease":
        client = SangamClient(base, token_provider=auth, retry_policy=RetryPolicy.none())
        deadline = time.monotonic() + timeout
        while results.count < wanted and time.monotonic() < deadline:
            for w in client.work.fetch_and_lock(worker_id, [{"name": topic, "lockDurationMs": lock_ms}], 1, 5_000):
                time.sleep((lock_ms + 2_000) / 1000.0)
                try:
                    client.work.complete(w.id, worker_id, w.lock_version, w.attempt_id, outputs(w.variables))
                    results.emit(w.id, w.operation_id, w.lock_version, w.attempt_id, "error",
                                 "complete after lease expiry was accepted")
                except StaleLeaseError as e:
                    results.emit(w.id, w.operation_id, w.lock_version, w.attempt_id, "stale-lease",
                                 e.problem.extensions.get("reason"))
        return 0 if results.count >= wanted else 1

    failed_once: set[str] = set()
    pending: dict[str, TaskContext] = {}

    def on_log(_level: Any, event: str, _msg: str, fields: Mapping[str, Any], error: Any) -> None:
        wid = fields.get("workId")
        ctx = pending.get(str(wid)) if wid is not None else None
        if ctx is None:
            return
        args = (ctx.work_id, ctx.operation_id, ctx.lock_version, ctx.attempt_id)
        if event == "work.complete":
            if ctx.work_id in results.details:
                results.emit(*args, "error", results.details[ctx.work_id])
            else:
                results.emit(*args, "failed-then-completed" if scenario == "fail-retry" else "completed")
        elif event == "work.bpmn-error":
            results.emit(*args, "bpmn-error")
        elif event == "work.outcome-unknown":
            results.emit(*args, "outcome-unknown")
        elif event in ("work.report.failed", "work.report.gave-up", "work.abandoned"):
            results.emit(*args, "error", f"{event}: {error}")

    logger = SangamLogger(hook=on_log)
    client = SangamClient(base, token_provider=auth, logger=logger)
    worker = Worker(client, worker_id=worker_id, max_concurrency=1, max_tasks_per_fetch=1, logger=logger)

    def handle(ctx: TaskContext) -> dict[str, Any]:
        pending[ctx.work_id] = ctx
        problem = check_inputs(ctx.raw_variables)
        if problem is not None:
            results.details[ctx.work_id] = problem
        if scenario == "bpmn-error":
            raise BpmnError("AC08_ERROR", "AC-08 business error")
        if scenario == "outcome-unknown":
            raise OutcomeUnknown("AC-08 outcome unknown")
        if scenario == "fail-retry" and ctx.work_id not in failed_once:
            failed_once.add(ctx.work_id)
            raise TaskFailed("AC-08 transient failure", retries=1, retry_timeout=0)
        return outputs(ctx.raw_variables)

    worker.subscribe(TopicSubscription(topic, handle, lock_duration_ms=lock_ms, retries=1))
    worker.start()
    try:
        ok = results.done.wait(timeout)
    finally:
        worker.stop(10.0)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())

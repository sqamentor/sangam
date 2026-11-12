# SPDX-License-Identifier: Apache-2.0
"""R18 test engine (M-021): an in-process fake of the Sangam worker API for unit-testing workers without a server.

:class:`SangamTestEngine` listens on 127.0.0.1 (stdlib ``ThreadingHTTPServer``, no dependency) and answers
``/api/v1/work/**`` and ``POST /api/v1/process-instances`` with the lease semantics of the server (SANGAM.md VI.3),
so the real :class:`~sangam_sdk.SangamClient` and :class:`~sangam_sdk.Worker` run unchanged against it::

    with SangamTestEngine() as engine:
        work_id = engine.add_work("pay", {"amount": {"type": "Decimal", "value": "12.50"}})
        worker = Worker(engine.client(), worker_id="w1")
        ...
        assert engine.state(work_id) == "COMPLETED"

Semantics (mirrors sangam-rest OperatonWorkAdapter, sangam-core LeaseTable, ApiProblem, IdempotencyStore):

* Lease row per item: a grant gives ``lockVersion + 1`` and a new ``attemptId``; a lease that ends without completing
  (fail, unlock, expiry, outcome-unknown) gives ``lockVersion + 1`` and no owner; complete / bpmn-error remove it.
* Fencing on every worker call, in the server's order: no lease, stale version, not owner, expired, then attempt
  mismatch -> ``409 STALE_LEASE`` (``reason`` NO_LEASE | STALE_VERSION | NOT_OWNER | EXPIRED | ATTEMPT_MISMATCH),
  nothing changes. Unknown work -> ``404 NOT_FOUND``. A result for a deleted instance -> ``410 GONE_INSTANCE_DELETED``.
* Lease length per topic: default 5 min, capped at 24 h. Time is the engine's own clock (:meth:`advance`); every
  request first sweeps expired leases: LOCKED -> AVAILABLE (``lockVersion + 1``), or UNKNOWN_OUTCOME with a
  reconciliation item when the topic is irreversible or a cancel was requested and not acknowledged.
* ``fail`` with retries > 0 -> AVAILABLE after ``retryTimeoutMs``; retries = 0 -> INCIDENT. On an irreversible topic a
  failure with retries absent or > 0 becomes outcome-unknown (VI.3 rule 5).
* An identical repeat of a completion (same lockVersion, same variables/outcome) answers 204 again (the server's
  repeated-completion check); the work is completed once.
* R41 for the non-fenced mutation ``POST /process-instances``: same Idempotency-Key + same body -> the stored response
  with ``Idempotent-Replayed: true``; same key + another body -> ``422 IDEMPOTENCY_KEY_REUSED``.
* ``fetch-and-lock`` answers at once (no long-poll wait): work that is there is returned, otherwise ``[]`` after a
  pause of at most 10 ms, so a worker loop on the engine does not spin.

Step journal (M-025, :mod:`sangam_sdk.testing_steps`): ``GET/PUT /work/{id}/steps/{name}`` and ``GET /work/{id}/steps``,
with the server's transitions and fencing; :meth:`steps` lists the recorded steps of a work's operation.

Steering: :meth:`advance`, :meth:`request_cancel`, :meth:`delete_instance`, :meth:`resolve_reconciliation`,
:meth:`lose_next_response` (apply the call, then drop the connection without an answer). Inspection: :meth:`state`,
:meth:`lock_version`, :meth:`completions`, :meth:`snapshot`, :attr:`calls`, :attr:`instances`,
:attr:`reconciliation_items`.
"""
from __future__ import annotations

import json
import threading
import time
import uuid
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Mapping, Optional
from urllib.parse import parse_qsl, unquote

from .testing_steps import EngineSteps

DEFAULT_LOCK_MS = 5 * 60 * 1000
MAX_LOCK_MS = 24 * 60 * 60 * 1000
API_PREFIX = "/api/v1"
TYPE_BASE = "https://sangam.dev/problems/"
_TITLES = {400: "Bad request", 404: "Not found", 405: "Method not allowed", 409: "Conflict", 410: "Gone",
           422: "Unprocessable content"}
RESULT_ACTIONS = ("complete", "fail", "bpmn-error", "outcome-unknown")


class EngineProblem(Exception):
    """An RFC 9457 answer (the server's ApiProblem)."""

    def __init__(self, status: int, code: str, detail: str, title: Optional[str] = None,
                 **extensions: Any) -> None:
        super().__init__(detail)
        self.status, self.code, self.detail, self.title = status, code, detail, title
        self.extensions = extensions

    def body(self, instance: str) -> dict[str, Any]:
        b: dict[str, Any] = {"type": TYPE_BASE + self.code.lower().replace("_", "-"),
                             "title": self.title or _TITLES.get(self.status, "Error"), "status": self.status,
                             "detail": self.detail, "instance": instance, "code": self.code}
        for k, v in self.extensions.items():
            if v is not None:
                b.setdefault(k, v)
        return b


def _stale(work_id: str, reason: str, detail: str) -> EngineProblem:
    return EngineProblem(409, "STALE_LEASE", detail, "Stale lease", workId=work_id, reason=reason)


def _not_found(what: str, ident: str) -> EngineProblem:
    return EngineProblem(404, "NOT_FOUND", f"{what} {ident} was not found or is not visible to you", resource=what)


def _canonical(v: Any) -> str:
    return json.dumps(v, sort_keys=True, separators=(",", ":"))


def _is_steps(path: str) -> bool:
    """``/work/{id}/steps`` or ``/work/{id}/steps/{name}`` (M-025)."""
    parts = [p for p in path.split("/") if p]
    return len(parts) in (3, 4) and parts[0] == "work" and parts[2] == "steps"


def _iso(ms: int) -> str:
    return datetime.fromtimestamp(ms / 1000.0, tz=timezone.utc).isoformat(timespec="milliseconds").replace(
        "+00:00", "Z")


class _Work:
    def __init__(self, work_id: str, topic: str, variables: Mapping[str, Any], retries: Optional[int],
                 priority: int, business_key: Optional[str], process_definition_key: str,
                 process_instance_id: str, tenant_id: str) -> None:
        self.id = work_id
        self.topic = topic
        self.variables = dict(variables)
        self.retries = retries
        self.priority = priority
        self.business_key = business_key
        self.process_definition_key = process_definition_key
        self.process_instance_id = process_instance_id
        self.activity_instance_id = "task:" + uuid.uuid4().hex[:12]
        self.tenant_id = tenant_id
        self.operation_id = str(uuid.uuid4())
        self.state = "AVAILABLE"
        self.available_at = 0
        self.error_message: Optional[str] = None
        # lease row (LeaseTable): None until the first grant and after complete / bpmn-error
        self.row: Optional[dict[str, Any]] = None
        self.cancel_requested = False
        self.deleted = False
        # operation ledger
        self.ledger_state = "INTENT"
        self.attempts = 0
        self.stored_outcome: Optional[dict[str, Any]] = None
        self.completion: Optional[tuple[int, str]] = None
        self.completions = 0
        self.output: Optional[dict[str, Any]] = None
        self.error_code: Optional[str] = None


class SangamTestEngine:
    """In-process fake of the Sangam worker API with the VI.3 lease semantics. Thread-safe."""

    def __init__(self, *, irreversible_topics: tuple[str, ...] | list[str] = (), tenant_id: str = "test",
                 start_ms: Optional[int] = None) -> None:
        self.irreversible_topics = set(irreversible_topics)
        self.tenant_id = tenant_id
        self._now = int(time.time() * 1000) if start_ms is None else int(start_ms)
        self._lock = threading.RLock()
        self._work: dict[str, _Work] = {}
        self._order: list[str] = []
        self._lose: dict[str, int] = {}
        self._idem: dict[tuple[str, str, str], tuple[str, int, Any]] = {}
        self._recon_seq = 0
        #: every request as (method, path without /api/v1, Idempotency-Key, decoded body)
        self.calls: list[tuple[str, str, Optional[str], Any]] = []
        #: started process instances (ProcessInstance wire objects)
        self.instances: list[dict[str, Any]] = []
        #: reconciliation items: {id, kind, workId, operationId, state, detail}
        self.reconciliation_items: list[dict[str, Any]] = []
        self._server: Optional[ThreadingHTTPServer] = None
        self._thread: Optional[threading.Thread] = None
        #: the step journal (M-025): GET/PUT /work/{id}/steps/{name}, GET /work/{id}/steps
        self._journal = EngineSteps(self._work, self._fence, lambda: _iso(self._now))

    # ------------------------------------------------------------------ lifecycle

    def start(self) -> "SangamTestEngine":
        if self._server is None:
            engine = self

            class _Handler(_EngineHandler):
                pass

            _Handler.engine = engine
            self._server = ThreadingHTTPServer(("127.0.0.1", 0), _Handler)
            self._server.daemon_threads = True
            self._thread = threading.Thread(target=self._server.serve_forever, name="sangam-test-engine",
                                            daemon=True)
            self._thread.start()
        return self

    def close(self) -> None:
        if self._server is not None:
            self._server.shutdown()
            self._server.server_close()
            self._server = None

    def __enter__(self) -> "SangamTestEngine":
        return self.start()

    def __exit__(self, *exc: Any) -> None:
        self.close()

    @property
    def base_url(self) -> str:
        self.start()
        assert self._server is not None
        return f"http://127.0.0.1:{self._server.server_address[1]}"

    def client(self, **options: Any) -> Any:
        """A :class:`~sangam_sdk.SangamClient` on this engine (keyword options are passed through)."""
        from .client import SangamClient

        return SangamClient(self.base_url, **options)

    # ------------------------------------------------------------------ clock and steering

    @property
    def now_ms(self) -> int:
        with self._lock:
            return self._now

    def advance(self, by: int | float | timedelta) -> None:
        """Moves the engine clock forward (milliseconds, or a timedelta); leases expire on the next request."""
        ms = int(by.total_seconds() * 1000) if isinstance(by, timedelta) else int(by)
        if ms < 0:
            raise ValueError("the clock only moves forward")
        with self._lock:
            self._now += ms

    def add_work(self, topic: str, variables: Optional[Mapping[str, Any]] = None, *, retries: Optional[int] = 3,
                 priority: int = 0, business_key: Optional[str] = None,
                 process_definition_key: str = "test-process", process_instance_id: Optional[str] = None) -> str:
        """Adds AVAILABLE external work; returns its id. Its operationId is fixed for the life of the item (R36)."""
        with self._lock:
            wid = str(uuid.uuid4())
            self._work[wid] = _Work(wid, topic, variables or {}, retries, priority, business_key,
                                    process_definition_key, process_instance_id or str(uuid.uuid4()), self.tenant_id)
            self._order.append(wid)
            return wid

    def request_cancel(self, work_id: str) -> None:
        """CANCEL_REQUESTED: the next heartbeat answers ``cancelRequested: true`` (VI.3, VI.5)."""
        with self._lock:
            self._get(work_id).cancel_requested = True

    def delete_instance(self, work_id: str) -> None:
        """Deletes the work's instance (tombstone): late results get 410 GONE_INSTANCE_DELETED (VI.5 rule 3)."""
        with self._lock:
            self._get(work_id).deleted = True

    def resolve_reconciliation(self, item_id: str, action: str) -> None:
        """CONFIRM_SUCCESS -> COMPLETED, RETRY -> AVAILABLE with the same operationId, MARK_FAILED -> FAILED."""
        with self._lock:
            item = next((i for i in self.reconciliation_items if i["id"] == item_id), None)
            if item is None or item["state"] != "OPEN":
                raise KeyError(f"no open reconciliation item {item_id}")
            w = self._get(item["workId"])
            if action == "CONFIRM_SUCCESS":
                w.state, w.ledger_state = "COMPLETED", "SUCCEEDED"
                w.completions += 1
            elif action == "RETRY":
                w.state, w.ledger_state, w.available_at = "AVAILABLE", "INTENT", self._now
            elif action == "MARK_FAILED":
                w.state, w.ledger_state = "FAILED", "FAILED"
            else:
                raise ValueError(f"unknown resolution {action}")
            item["state"] = "RESOLVED"
            item["resolution"] = action

    def lose_next_response(self, action: str) -> None:
        """The next call of ``action`` (fetch-and-lock, complete, fail, ...) is applied, then the connection is
        dropped without an answer: the client sees a transport error (T02 / T04 / T36)."""
        with self._lock:
            self._lose[action] = self._lose.get(action, 0) + 1

    # ------------------------------------------------------------------ inspection

    def state(self, work_id: str) -> str:
        """AVAILABLE | LOCKED | CANCEL_REQUESTED | COMPLETED | COMPLETED_WITH_ERROR | INCIDENT | UNKNOWN_OUTCOME |
        CANCELLED | FAILED."""
        with self._lock:
            w = self._get(work_id)
            if w.state == "LOCKED" and w.cancel_requested:
                return "CANCEL_REQUESTED"
            return w.state

    def lock_version(self, work_id: str) -> int:
        with self._lock:
            w = self._get(work_id)
            return 0 if w.row is None else int(w.row["lockVersion"])

    def completions(self, work_id: str) -> int:
        with self._lock:
            return self._get(work_id).completions

    def snapshot(self, work_id: str) -> dict[str, Any]:
        with self._lock:
            w = self._get(work_id)
            return {"id": w.id, "topic": w.topic, "state": self.state(work_id), "lockVersion": self.lock_version(work_id),
                    "attemptId": None if w.row is None else w.row["attemptId"],
                    "workerId": None if w.row is None else w.row["workerId"], "operationId": w.operation_id,
                    "attempts": w.attempts, "ledgerState": w.ledger_state, "retries": w.retries,
                    "completions": w.completions, "output": w.output, "errorCode": w.error_code,
                    "errorMessage": w.error_message}

    def steps(self, work_id: str) -> list[dict[str, Any]]:
        """The steps recorded for the work's operation, in recording order (M-025)."""
        with self._lock:
            return self._journal.rows(self._get(work_id).operation_id)

    def calls_of(self, action: str) -> list[tuple[str, str, Optional[str], Any]]:
        with self._lock:
            return [c for c in self.calls if c[1].rsplit("/", 1)[-1] == action]

    # ------------------------------------------------------------------ dispatch

    def _get(self, work_id: str) -> _Work:
        w = self._work.get(work_id)
        if w is None:
            raise KeyError(f"no work {work_id}")
        return w

    def handle(self, method: str, path: str, key: Optional[str], body: Any,
               query: Optional[Mapping[str, Any]] = None) -> tuple[int, dict[str, str], Any, bool]:
        """One request: (status, headers, JSON body or None, lose the response). Also used by tests directly.
        ``query`` carries the query parameters (the fenced step GET reads workerId, lockVersion, attemptId)."""
        with self._lock:
            self.calls.append((method, path, key, body))
            action = "steps" if _is_steps(path) else path.rsplit("/", 1)[-1]
            lose = self._lose.get(action, 0) > 0
            if lose:
                self._lose[action] -= 1
            try:
                self._sweep()
                b = body if isinstance(body, dict) else {}
                if _is_steps(path):
                    status, headers, out = self._steps_route(method, path, query or {}, b)
                else:
                    status, headers, out = self._route(method, path, key, b)
            except EngineProblem as p:
                return p.status, {"Content-Type": "application/problem+json"}, p.body(API_PREFIX + path), lose
            return status, headers, out, lose

    def _route(self, method: str, path: str, key: Optional[str], b: dict[str, Any]) -> tuple[int, dict[str, str], Any]:
        parts = [p for p in path.split("/") if p]
        if method == "POST" and parts == ["process-instances"]:
            return self._start(key, b)
        if method == "POST" and parts == ["work", "fetch-and-lock"]:
            return 200, {}, self._fetch(b)
        if method == "POST" and len(parts) == 3 and parts[0] == "work":
            wid, action = parts[1], parts[2]
            if action == "extend-lock":
                return 200, {}, self._extend(wid, b)
            if action == "complete":
                self._complete(wid, b)
                return 204, {}, None
            if action == "fail":
                self._fail(wid, b)
                return 204, {}, None
            if action == "bpmn-error":
                self._bpmn_error(wid, b)
                return 204, {}, None
            if action == "unlock":
                self._unlock(wid, b)
                return 204, {}, None
            if action == "outcome-unknown":
                return 202, {}, self._outcome_unknown(wid, b)
            if action == "cancel-ack":
                self._cancel_ack(wid, b)
                return 204, {}, None
        raise EngineProblem(404, "NOT_FOUND", f"{method} {path} is not served by the test engine")

    def _steps_route(self, method: str, path: str, query: Mapping[str, Any],
                     b: dict[str, Any]) -> tuple[int, dict[str, str], Any]:
        parts = [unquote(p) for p in path.split("/") if p]  # work, id, steps[, name]
        wid = parts[1]
        name = parts[3] if len(parts) > 3 else None
        if name is None and method == "GET":
            return 200, {}, self._journal.list_steps(wid)
        if name is not None and method == "GET":
            return 200, {}, self._journal.get(wid, name, query)
        if name is not None and method == "PUT":
            return 200, {}, self._journal.put(wid, name, b)
        raise EngineProblem(405, "METHOD_NOT_ALLOWED", f"{method} {path} is not served by the test engine")

    # ------------------------------------------------------------------ lease table

    def _sweep(self) -> None:
        for w in self._work.values():
            if w.state == "LOCKED" and w.row is not None and w.row["expiresAt"] <= self._now:
                if w.cancel_requested or w.topic in self.irreversible_topics:
                    detail = "cancel not acknowledged before lease expiry" if w.cancel_requested \
                        else "lease expired on irreversible topic"
                    self._to_unknown(w, detail)
                else:
                    self._end(w)
                    w.state = "AVAILABLE"

    def _end(self, w: _Work) -> None:
        """LeaseTable.end: lock_version + 1, no owner."""
        assert w.row is not None
        w.row = {"lockVersion": w.row["lockVersion"] + 1, "attemptId": None, "workerId": None, "expiresAt": None}

    def _fence(self, w: _Work, b: Mapping[str, Any]) -> None:
        worker = b.get("workerId")
        if not worker:
            raise EngineProblem(400, "WORKER_ID_REQUIRED", "workerId is required")
        lv = b.get("lockVersion")
        r = w.row
        if r is None:
            raise _stale(w.id, "NO_LEASE", "no lease was granted for this work")
        if lv != r["lockVersion"]:
            raise _stale(w.id, "STALE_VERSION", f"lockVersion {lv} is not the current version {r['lockVersion']}")
        if r["workerId"] is None or r["workerId"] != worker:
            raise _stale(w.id, "NOT_OWNER", f"{worker} does not hold the lease (owner: {r['workerId']})")
        if r["expiresAt"] is None or r["expiresAt"] <= self._now:
            raise _stale(w.id, "EXPIRED", f"the lease of {worker} expired at {_iso(r['expiresAt'] or 0)}")
        att = b.get("attemptId")
        if att is not None and r["attemptId"] is not None and att != r["attemptId"]:
            raise _stale(w.id, "ATTEMPT_MISMATCH", f"attemptId {att} is not the current attempt")

    def _task(self, wid: str) -> _Work:
        """requireTask: the external task exists until the work finished or was deleted (COMPLETED etc. are gone).

        An incident or an outcome awaiting reconciliation keeps the task, so a call on such work reaches the fence
        (409 STALE_LEASE: outcome-unknown ended the lease) as on both server work adapters (SL-174).
        """
        w = self._work.get(wid)
        if w is None or w.deleted or w.state not in ("AVAILABLE", "LOCKED", "INCIDENT", "UNKNOWN_OUTCOME"):
            raise _not_found("external work", wid)
        return w

    def _gone(self, wid: str, effect: bool, result: Any) -> None:
        w = self._work.get(wid)
        if w is None or not w.deleted:
            return
        item = None
        if effect:
            item = self._open_recon(w, "LATE_RESULT", "late result for a deleted instance: " + _canonical(result))
        raise EngineProblem(410, "GONE_INSTANCE_DELETED", "The instance of this work was deleted; the result was recorded",
                      workId=wid, reconciliationItemId=None if item is None else item["id"])

    def _open_recon(self, w: _Work, kind: str, detail: Optional[str]) -> dict[str, Any]:
        self._recon_seq += 1
        item = {"id": str(self._recon_seq), "kind": kind, "workId": w.id, "operationId": w.operation_id,
                "state": "OPEN", "detail": detail}
        self.reconciliation_items.append(item)
        return item

    def _to_unknown(self, w: _Work, detail: Optional[str]) -> dict[str, Any]:
        if w.row is not None:
            self._end(w)
        w.state, w.ledger_state = "UNKNOWN_OUTCOME", "UNKNOWN_OUTCOME"
        return self._open_recon(w, "UNKNOWN_OUTCOME", detail)

    # ------------------------------------------------------------------ operations

    def _start(self, key: Optional[str], b: dict[str, Any]) -> tuple[int, dict[str, str], Any]:
        fp = _canonical(b)
        if key is not None:
            stored = self._idem.get(("POST", "/process-instances", key))
            if stored is not None:
                if stored[0] != fp:
                    raise EngineProblem(422, "IDEMPOTENCY_KEY_REUSED",
                                  "the Idempotency-Key was used before with a different request")
                return stored[1], {"Idempotent-Replayed": "true", "Location": f"{API_PREFIX}/process-instances/"
                                   f"{stored[2]['id']}"}, stored[2]
        if not b.get("key"):
            raise EngineProblem(400, "VALIDATION_FAILED", "key is required")
        pi = {"id": str(uuid.uuid4()), "processDefinitionId": f"{b['key']}:1:test", "processDefinitionKey": b["key"],
              "processDefinitionVersion": 1, "tenantId": self.tenant_id, "state": "ACTIVE", "ended": False,
              "startTime": _iso(self._now)}
        if b.get("businessKey") is not None:
            pi["businessKey"] = b["businessKey"]
        pi["rootProcessInstanceId"] = pi["id"]
        self.instances.append(pi)
        if key is not None:
            self._idem[("POST", "/process-instances", key)] = (fp, 201, pi)
        return 201, {"Location": f"{API_PREFIX}/process-instances/{pi['id']}"}, pi

    def _fetch(self, b: dict[str, Any]) -> list[dict[str, Any]]:
        worker = b.get("workerId")
        if not isinstance(worker, str) or not worker.strip():
            raise EngineProblem(400, "VALIDATION_FAILED", "workerId is required")
        topics = b.get("topics")
        if not isinstance(topics, list) or not topics:
            raise EngineProblem(400, "VALIDATION_FAILED", "at least one topic is required")
        by_name: dict[str, dict[str, Any]] = {}
        for t in topics:
            if not isinstance(t, dict) or not str(t.get("name") or "").strip():
                raise EngineProblem(400, "TOPIC_NAME_REQUIRED", "every topic needs a name")
            by_name[t["name"]] = t
        mt = b.get("maxTasks")
        n = 1 if mt is None else max(1, min(100, int(mt)))
        candidates = [self._work[i] for i in self._order]
        if b.get("usePriority"):
            candidates.sort(key=lambda x: -x.priority)  # stable: insertion order within one priority
        out = []
        for w in candidates:
            if len(out) >= n:
                break
            t = by_name.get(w.topic)
            if t is None or w.state != "AVAILABLE" or w.deleted or w.available_at > self._now:
                continue
            if t.get("processDefinitionKey") is not None and t["processDefinitionKey"] != w.process_definition_key:
                continue
            if t.get("businessKey") is not None and t["businessKey"] != w.business_key:
                continue
            ms = t.get("lockDurationMs")
            d = DEFAULT_LOCK_MS if not ms or ms <= 0 else min(int(ms), MAX_LOCK_MS)
            version = 1 if w.row is None else w.row["lockVersion"] + 1
            w.row = {"lockVersion": version, "attemptId": str(uuid.uuid4()), "workerId": worker,
                     "expiresAt": self._now + d}
            w.state = "LOCKED"
            w.cancel_requested = False
            w.attempts += 1
            out.append(self._external(w, t))
        return out

    def _external(self, w: _Work, t: Mapping[str, Any]) -> dict[str, Any]:
        assert w.row is not None
        names = t.get("variables")
        variables = {k: v for k, v in w.variables.items() if names is None or k in names}
        op: dict[str, Any] = {"state": w.ledger_state, "attempts": w.attempts,
                              "mayDispatch": w.ledger_state not in ("SUCCEEDED", "UNKNOWN_OUTCOME")}
        if w.stored_outcome is not None:
            op["storedOutcome"] = w.stored_outcome
        e: dict[str, Any] = {"id": w.id, "topic": w.topic, "tenantId": w.tenant_id,
                             "processInstanceId": w.process_instance_id,
                             "processDefinitionKey": w.process_definition_key, "activityId": "task",
                             "activityInstanceId": w.activity_instance_id, "variables": variables,
                             "lockVersion": w.row["lockVersion"], "attemptId": w.row["attemptId"],
                             "operationId": w.operation_id, "lockExpiresAt": _iso(w.row["expiresAt"]),
                             "retries": w.retries, "priority": w.priority, "operation": op}
        if w.business_key is not None:
            e["businessKey"] = w.business_key
        if w.error_message is not None:
            e["errorMessage"] = w.error_message
        return {k: v for k, v in e.items() if v is not None}

    def _extend(self, wid: str, b: dict[str, Any]) -> dict[str, Any]:
        w = self._task(wid)
        self._fence(w, b)
        assert w.row is not None
        ms = b.get("newDurationMs")
        d = DEFAULT_LOCK_MS if not ms or ms <= 0 else min(int(ms), MAX_LOCK_MS)
        w.row["expiresAt"] = self._now + d
        return {"id": wid, "lockVersion": b.get("lockVersion"), "lockExpiresAt": _iso(w.row["expiresAt"]),
                "cancelRequested": w.cancel_requested}

    def _complete(self, wid: str, b: dict[str, Any]) -> None:
        self._gone(wid, True, b.get("variables"))
        outcome = _canonical({"output": b.get("variables"), "outcome": b.get("outcome")})
        w = self._work.get(wid)
        if w is not None and w.state == "COMPLETED" and w.completion == (b.get("lockVersion"), outcome):
            return  # the same completion again (a retried request): answered as the first one
        w = self._task(wid)
        self._fence(w, b)
        w.state, w.ledger_state, w.row = "COMPLETED", "SUCCEEDED", None
        w.completion = (b.get("lockVersion"), outcome)
        w.completions += 1
        w.output = b.get("variables") or {}
        ref = (b.get("outcome") or {}).get("reference") if isinstance(b.get("outcome"), dict) else None
        w.stored_outcome = {"output": w.output, "providerRef": ref, "duplicate": False}

    def _fail(self, wid: str, b: dict[str, Any]) -> None:
        probe = self._work.get(wid)
        retries = b.get("retries")
        if probe is not None and probe.topic in self.irreversible_topics and (retries is None or retries > 0):
            self._outcome_unknown(wid, {"workerId": b.get("workerId"), "lockVersion": b.get("lockVersion"),
                                        "attemptId": b.get("attemptId"),
                                        "detail": f"failure on irreversible topic: {b.get('errorMessage')}"})
            return
        self._gone(wid, False, None)
        w = self._task(wid)
        self._fence(w, b)
        left = max(0, int(retries)) if retries is not None else max(0, (1 if w.retries is None else w.retries) - 1)
        w.retries = left
        w.error_message = b.get("errorMessage")
        self._end(w)
        if left > 0:
            w.state = "AVAILABLE"
            w.available_at = self._now + max(0, int(b.get("retryTimeoutMs") or 0))
        else:
            w.state, w.ledger_state = "INCIDENT", "FAILED"

    def _bpmn_error(self, wid: str, b: dict[str, Any]) -> None:
        if not str(b.get("errorCode") or "").strip():
            raise EngineProblem(400, "VALIDATION_FAILED", "errorCode is required")
        self._gone(wid, False, b.get("variables"))
        w = self._task(wid)
        self._fence(w, b)
        w.state, w.row, w.error_code = "COMPLETED_WITH_ERROR", None, b["errorCode"]
        w.error_message = b.get("errorMessage")

    def _unlock(self, wid: str, b: dict[str, Any]) -> None:
        w = self._task(wid)
        self._fence(w, b)
        self._end(w)
        w.state = "AVAILABLE"

    def _outcome_unknown(self, wid: str, b: Mapping[str, Any]) -> dict[str, Any]:
        self._gone(wid, True, b.get("evidence"))
        w = self._task(wid)
        self._fence(w, b)
        item = self._to_unknown(w, b.get("detail"))
        return {"workId": wid, "operationId": w.operation_id, "reconciliationItemId": item["id"],
                "state": "UNKNOWN_OUTCOME"}

    def _cancel_ack(self, wid: str, b: dict[str, Any]) -> None:
        w = self._work.get(wid)
        if w is None or not w.cancel_requested or w.state not in ("LOCKED", "AVAILABLE"):
            raise _not_found("cancel request for work", wid)
        current = None if w.row is None else w.row["lockVersion"]
        if b.get("lockVersion") != current:
            raise _stale(wid, "STALE_VERSION", f"no CANCEL_REQUESTED tombstone with lockVersion {b.get('lockVersion')}")
        w.state, w.row = "CANCELLED", None


class _EngineHandler(BaseHTTPRequestHandler):
    engine: SangamTestEngine
    protocol_version = "HTTP/1.1"

    def log_message(self, format: str, *args: Any) -> None:  # noqa: A002 - BaseHTTPRequestHandler signature
        pass

    def _serve(self, method: str) -> None:
        n = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(n) if n > 0 else b""
        try:
            body = json.loads(raw.decode("utf-8")) if raw else None
        except ValueError:
            body = None
        path, _, raw_query = self.path.partition("?")
        query = dict(parse_qsl(raw_query, keep_blank_values=True))
        if path.startswith(API_PREFIX):
            path = path[len(API_PREFIX):]
        status, headers, out, lose = self.engine.handle(method, path, self.headers.get("Idempotency-Key"), body,
                                                        query)
        if lose:
            self.close_connection = True  # the effect is applied; the client gets no answer
            return
        if path == "/work/fetch-and-lock" and out == []:
            wait = (body or {}).get("asyncResponseTimeoutMs") if isinstance(body, dict) else None
            time.sleep(min(10, max(0, int(wait if isinstance(wait, int) else 10))) / 1000.0)  # no spin, no long poll
        data = b"" if out is None else json.dumps(out).encode("utf-8")
        self.send_response(status)
        if data:
            self.send_header("Content-Type", headers.pop("Content-Type", "application/json"))
        for k, v in headers.items():
            self.send_header(k, v)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        if data:
            self.wfile.write(data)

    def do_POST(self) -> None:  # noqa: N802
        self._serve("POST")

    def do_GET(self) -> None:  # noqa: N802
        self._serve("GET")

    def do_PUT(self) -> None:  # noqa: N802
        self._serve("PUT")


__all__ = ["DEFAULT_LOCK_MS", "MAX_LOCK_MS", "EngineProblem", "SangamTestEngine"]

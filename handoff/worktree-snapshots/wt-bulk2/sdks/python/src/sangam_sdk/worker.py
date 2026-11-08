# SPDX-License-Identifier: Apache-2.0
"""External-worker runtime for the Sangam worker API (VI.3, A-1, A-2, R9, R20, R36).

* Long-poll fetch-and-lock for all subscribed topics, asking only for as many items as there are free slots
  (``max_concurrency``); fetch errors back off exponentially with jitter.
* A-1: a handler starts only for items that came back LOCKED with a lockVersion and attemptId.
* Heartbeat (``extend-lock``) every lease/3; its answer carries ``cancelRequested``.
* Fencing: every call carries ``(workerId, lockVersion, attemptId)``. 409 stale-lease or 410 gone on any call
  means the lease is lost: the context's cancel event is set and the handler's result is discarded.
* Idempotency-Key per call, ``<operationId>:<action>:<lockVersion>`` (heartbeats add ``:<seq>``), so a repeated
  HTTP request is replayed by the server instead of applied twice (R41).
* Result calls are retried with back-off until the lease expires; failures are reported with decreasing retries
  and a growing ``retryTimeoutMs``.
* Graceful shutdown: :meth:`Worker.stop` stops fetching, waits for in-flight handlers, then signals the rest to
  cancel and unlocks their items. :meth:`Worker.run` installs SIGTERM/SIGINT handlers.

Python threads cannot be interrupted, so cancellation is cooperative: long handlers should call
``ctx.throw_if_cancelled()`` or wait on ``ctx.cancel_event``.
"""
from __future__ import annotations

import os
import random
import signal
import socket
import threading
import traceback
import uuid
from concurrent.futures import Future, ThreadPoolExecutor
from dataclasses import dataclass, field
from datetime import datetime, timezone
from decimal import Decimal
from typing import Any, Callable, Mapping, Optional

from .client import SangamClient
from .errors import GoneError, SangamApiError, SangamError, StaleLeaseError, TransportError
from .log import SangamLogger
from .models import ExternalWork
from .typed import decode_value, decode_variables

MAX_LOCK_MS = 24 * 60 * 60 * 1000


# --------------------------------------------------------------------------- handler outcomes

class BpmnError(Exception):
    """A business error the BPMN model routes (error boundary event). Not retried."""

    def __init__(self, error_code: str, message: str = "", variables: Mapping[str, Any] | None = None) -> None:
        super().__init__(message)
        self.error_code = error_code
        self.message = message
        self.variables = dict(variables) if variables is not None else None


class OutcomeUnknown(Exception):
    """The handler cannot know whether the downstream effect happened (e.g. a payment call timed out after
    sending). Reported as ``outcome-unknown``: an UNKNOWN_OUTCOME reconciliation item, never an automatic repeat
    (VI.3.2 rules 4-5, AC-03). ``reason`` and ``details`` are sent as ``detail``; ``variables`` as ``evidence``."""

    def __init__(self, reason: str, details: str | None = None, variables: Mapping[str, Any] | None = None) -> None:
        super().__init__(reason)
        self.reason = reason
        self.details = details
        self.variables = dict(variables) if variables is not None else None


class TaskFailed(Exception):
    """A technical failure where no external effect happened, so a retry is safe. ``retries`` /
    ``retry_timeout`` (seconds) override the subscription defaults; ``retries=0`` raises an incident."""

    def __init__(self, message: str, details: str | None = None, retries: int | None = None,
                 retry_timeout: float | None = None) -> None:
        super().__init__(message)
        self.details = details
        self.retries = retries
        self.retry_timeout = retry_timeout


class TaskCancelled(Exception):
    """Raised by :meth:`TaskContext.throw_if_cancelled`. After a server cancel request the worker sends
    ``cancel-ack`` (VI.3.1); after lease loss or during shutdown the item is abandoned or unlocked."""


# --------------------------------------------------------------------------- back-off

@dataclass(frozen=True)
class Backoff:
    """``min(max, initial * factor**(n-1))`` +/- ``jitter`` fraction. Seconds."""

    initial: float
    max: float
    factor: float = 2.0
    jitter: float = 0.2

    def delay(self, n: int) -> float:
        capped = min(self.initial * (self.factor ** max(0, n - 1)), self.max)
        j = capped * self.jitter
        return max(0.0, capped + (random.uniform(-j, j) if j else 0.0))

    @staticmethod
    def fetch_defaults() -> "Backoff":
        return Backoff(0.5, 60.0)

    @staticmethod
    def retry_defaults() -> "Backoff":
        """R28 default: first retry after about one minute, growing, capped at 10 minutes."""
        return Backoff(60.0, 600.0)


Handler = Callable[["TaskContext"], Optional[Mapping[str, Any]]]


@dataclass
class TopicSubscription:
    """Handler registration for one topic.

    ``lock_duration_ms``: lease per fetch (VI.3.2 rule 3: default 5 min, max 24 h); heartbeat at lease/3.
    ``irreversible``: treat unexpected handler exceptions as outcome-unknown (VI.3.2 rule 5). Contract 0.1.0 has
    no ``effect`` field, so irreversibility is declared here only.
    ``local_variables``, ``process_definition_key``, ``business_key``: TopicRequest filters (sangam-api.yaml).
    """

    topic: str
    handler: Handler
    lock_duration_ms: int = 5 * 60 * 1000
    variables: Optional[list[str]] = None
    retries: int = 3
    retry_backoff: Backoff = field(default_factory=Backoff.retry_defaults)
    irreversible: bool = False
    local_variables: Optional[bool] = None
    process_definition_key: Optional[str] = None
    business_key: Optional[str] = None

    def __post_init__(self) -> None:
        if self.lock_duration_ms > MAX_LOCK_MS:
            raise ValueError("lock_duration_ms exceeds 24 h (VI.3)")
        if self.lock_duration_ms < 3000:
            raise ValueError("lock_duration_ms must be at least 3 s")
        if self.retries < 0:
            raise ValueError("retries must be >= 0")


# --------------------------------------------------------------------------- context

class TaskContext:
    """What a handler sees while it holds the lease."""

    def __init__(self, work: ExternalWork, worker_id: str, client: SangamClient, logger: SangamLogger) -> None:
        self.work = work
        self.worker_id = worker_id
        self.client = client
        self._lock = threading.Lock()
        self._lock_version = work.lock_version
        self._lock_expires_at = work.lock_expires_at
        self.cancel_requested = False
        self.lease_lost = False
        self.shutting_down = False
        #: set on cancel request, lease loss or shutdown; handlers may ``wait`` on it
        self.cancel_event = threading.Event()
        fields: dict[str, Any] = {"workerId": worker_id, "workId": work.id, "topic": work.topic,
                                  "lockVersion": work.lock_version}
        for k, v in (("operationId", work.operation_id), ("attemptId", work.attempt_id),
                     ("businessKey", work.business_key), ("processInstanceId", work.process_instance_id)):
            if v is not None:
                fields[k] = v
        #: logger whose lines always carry workerId, workId, topic, operationId, attemptId, lockVersion, businessKey
        self.logger = logger.bind(**fields)

    @property
    def work_id(self) -> str:
        return self.work.id

    @property
    def topic(self) -> str:
        return self.work.topic

    @property
    def business_key(self) -> Optional[str]:
        return self.work.business_key

    @property
    def process_instance_id(self) -> Optional[str]:
        return self.work.process_instance_id

    @property
    def operation_id(self) -> str:
        """R36 stable business-operation id (falls back to the work id)."""
        return self.work.operation_id or self.work.id

    @property
    def idempotency_key(self) -> str:
        """Key to forward to downstream systems (= operationId, VI.3.2 rule 4)."""
        return self.operation_id

    @property
    def attempt_id(self) -> Optional[str]:
        """Changes per lease (A-2)."""
        return self.work.attempt_id

    @property
    def lock_version(self) -> int:
        with self._lock:
            return self._lock_version

    @property
    def lock_expires_at(self) -> Optional[datetime]:
        with self._lock:
            return self._lock_expires_at

    def _update_lease(self, expires_at: Optional[datetime], lock_version: Optional[int]) -> None:
        with self._lock:
            if expires_at is not None:
                self._lock_expires_at = expires_at
            if lock_version is not None:
                self._lock_version = int(lock_version)

    @property
    def raw_variables(self) -> dict[str, Any]:
        """The VariableMap as received (TypedValues not unwrapped)."""
        return self.work.variables or {}

    @property
    def variables(self) -> dict[str, Any]:
        """All variables with R33 TypedValues unwrapped (Decimal, int, datetime, date, bytes, ...)."""
        return decode_variables(self.work.variables)

    def variable(self, name: str, default: Any = None) -> Any:
        """One variable, TypedValue unwrapped: ``{"type":"Decimal","value":"12.50"}`` -> ``Decimal("12.50")``."""
        raw = self.raw_variables
        return decode_value(raw[name]) if name in raw else default

    def decimal(self, name: str) -> Optional[Decimal]:
        """A variable as :class:`~decimal.Decimal` (money stays exact, R33); accepts a TypedValue or plain number."""
        v = self.variable(name)
        if v is None:
            return None
        return v if isinstance(v, Decimal) else Decimal(str(v))

    @property
    def cancelled(self) -> bool:
        return self.cancel_event.is_set()

    def throw_if_cancelled(self) -> None:
        if self.cancel_requested:
            raise TaskCancelled("cancel requested by server")
        if self.lease_lost:
            raise TaskCancelled("lease lost")
        if self.shutting_down:
            raise TaskCancelled("worker shutting down")


# --------------------------------------------------------------------------- worker

def _default_worker_id() -> str:
    return f"{socket.gethostname()}-{os.getpid()}-{uuid.uuid4().hex[:8]}"


def _now() -> datetime:
    return datetime.now(timezone.utc)


@dataclass
class _Outcome:
    kind: str
    variables: Optional[Mapping[str, Any]] = None
    error: Optional[BaseException] = None


class Worker:
    """Example::

        worker = Worker(client, max_concurrency=4)

        @worker.topic("payment", lock_duration_ms=120_000, irreversible=True)
        def pay(ctx):
            ...
            return {"paymentStatus": "ACKNOWLEDGED"}

        worker.run()
    """

    def __init__(self, client: SangamClient, *, worker_id: str | None = None, max_concurrency: int = 8,
                 max_tasks_per_fetch: int = 8, async_response_timeout_ms: int = 30_000,
                 heartbeat_fraction: float = 1 / 3, fetch_backoff: Backoff | None = None,
                 report_backoff: Backoff | None = None, shutdown_timeout: float = 30.0,
                 use_priority: bool | None = None, logger: SangamLogger | None = None) -> None:
        if max_concurrency < 1 or max_tasks_per_fetch < 1:
            raise ValueError("max_concurrency and max_tasks_per_fetch must be >= 1")
        if not 0 < heartbeat_fraction < 1:
            raise ValueError("heartbeat_fraction must be in (0,1)")
        self.client = client
        self.worker_id = worker_id or _default_worker_id()
        self.max_concurrency = max_concurrency
        self.max_tasks_per_fetch = min(max_tasks_per_fetch, max_concurrency)
        self.async_response_timeout_ms = async_response_timeout_ms
        self.heartbeat_fraction = heartbeat_fraction
        self.fetch_backoff = fetch_backoff or Backoff.fetch_defaults()
        self.report_backoff = report_backoff or Backoff(0.5, 15.0)
        self.shutdown_timeout = shutdown_timeout
        self.use_priority = use_priority
        self.log = logger or client.logger
        self._subs: dict[str, TopicSubscription] = {}
        self._subs_lock = threading.Lock()
        self._slots = threading.Semaphore(max_concurrency)
        self._stopping = threading.Event()
        self._stopped = threading.Event()
        self._running = False
        self._in_flight: dict[str, TaskContext] = {}
        self._futures: set[Future[None]] = set()
        self._state_lock = threading.Lock()
        self._hb_seq = 0
        self._executor: ThreadPoolExecutor | None = None
        self._poller: threading.Thread | None = None

    # ---- registry

    def subscribe(self, subscription: TopicSubscription) -> "Worker":
        """Registers (or replaces) a handler; takes effect on the next fetch."""
        with self._subs_lock:
            self._subs[subscription.topic] = subscription
        self.log.info("worker.subscribed", "subscribed", {"workerId": self.worker_id, "topic": subscription.topic})
        return self

    def unsubscribe(self, topic: str) -> "Worker":
        """Stops fetching a topic; running items finish normally."""
        with self._subs_lock:
            self._subs.pop(topic, None)
        self.log.info("worker.unsubscribed", "unsubscribed", {"workerId": self.worker_id, "topic": topic})
        return self

    def topic(self, name: str, **options: Any) -> Callable[[Handler], Handler]:
        """Decorator form of :meth:`subscribe`; ``options`` are :class:`TopicSubscription` fields."""
        def deco(fn: Handler) -> Handler:
            self.subscribe(TopicSubscription(name, fn, **options))
            return fn
        return deco

    @property
    def in_flight_count(self) -> int:
        with self._state_lock:
            return len(self._in_flight)

    # ---- lifecycle

    def start(self) -> "Worker":
        with self._state_lock:
            if self._running:
                return self
            self._running = True
            self._stopping.clear()
            self._stopped.clear()
            self._executor = ThreadPoolExecutor(max_workers=self.max_concurrency, thread_name_prefix="sangam-work")
            self._poller = threading.Thread(target=self._poll_loop, name=f"sangam-poller-{self.worker_id}",
                                            daemon=True)
            self._poller.start()
        with self._subs_lock:
            topics = list(self._subs)
        self.log.info("worker.started", "worker started", {"workerId": self.worker_id,
                                                            "maxConcurrency": self.max_concurrency, "topics": topics})
        return self

    def run(self, install_signal_handlers: bool = True) -> None:
        """Starts and blocks until :meth:`stop` (or SIGTERM/SIGINT when called on the main thread)."""
        if install_signal_handlers and threading.current_thread() is threading.main_thread():
            def _handler(signum: int, _frame: Any) -> None:
                threading.Thread(target=self.stop, name="sangam-worker-shutdown", daemon=True).start()
            signal.signal(signal.SIGINT, _handler)
            if hasattr(signal, "SIGTERM"):
                signal.signal(signal.SIGTERM, _handler)
        self.start()
        while not self._stopped.wait(0.5):
            pass

    def stop(self, timeout: float | None = None) -> None:
        """Graceful shutdown: no new fetches, wait for in-flight handlers, then cancel and unlock the rest."""
        with self._state_lock:
            if not self._running or self._stopping.is_set():
                return
            self._stopping.set()
            futures = set(self._futures)
            executor = self._executor
        timeout = self.shutdown_timeout if timeout is None else timeout
        self.log.info("worker.stopping", "stopping: no new fetches",
                      {"workerId": self.worker_id, "inFlight": self.in_flight_count})
        from concurrent.futures import wait
        _, pending = wait(futures, timeout=timeout)
        if pending:
            self.log.warn("worker.shutdown.timeout", "signalling handlers still running to cancel",
                          {"workerId": self.worker_id, "inFlight": len(pending)})
            with self._state_lock:
                ctxs = list(self._in_flight.values())
            for c in ctxs:
                c.shutting_down = True
                c.cancel_event.set()
            wait(pending, timeout=10.0)
        if executor is not None:
            executor.shutdown(wait=False, cancel_futures=True)
        with self._state_lock:
            self._running = False
        self._stopped.set()
        self.log.info("worker.stopped", "worker stopped", {"workerId": self.worker_id})

    def __enter__(self) -> "Worker":
        return self.start()

    def __exit__(self, *exc: Any) -> None:
        self.stop()

    # ---- fetch loop

    def _acquire_slots(self) -> int:
        while not self._stopping.is_set():
            if self._slots.acquire(timeout=0.5):
                break
        else:
            return 0
        n = 1
        while n < self.max_tasks_per_fetch and self._slots.acquire(blocking=False):
            n += 1
        return n

    def _poll_loop(self) -> None:
        errors = 0
        while not self._stopping.is_set():
            permits = self._acquire_slots()
            if permits == 0:
                break
            with self._subs_lock:
                subs = list(self._subs.values())
            if not subs:
                self._release(permits)
                self._stopping.wait(1.0)
                continue
            try:
                topics = [{"name": s.topic, "lockDurationMs": s.lock_duration_ms, "variables": s.variables,
                           "localVariables": s.local_variables, "processDefinitionKey": s.process_definition_key,
                           "businessKey": s.business_key} for s in subs]
                items = self.client.work.fetch_and_lock(self.worker_id, topics, permits,
                                                        self.async_response_timeout_ms, self.use_priority)
                errors = 0
            except Exception as e:  # noqa: BLE001 - any fetch failure backs off
                self._release(permits)
                if self._stopping.is_set():
                    break
                errors += 1
                d = self.fetch_backoff.delay(errors)
                self.log.warn("work.fetch.failed", "fetch-and-lock failed; backing off",
                              {"workerId": self.worker_id, "attempt": errors, "delayMs": int(d * 1000)}, e)
                self._stopping.wait(d)
                continue
            used = 0
            for w in items:
                if self._stopping.is_set():
                    self._unlock_quietly(w, "worker stopping")
                    continue
                if used >= permits:
                    self.log.warn("work.overdelivered", "server returned more items than requested; unlocking",
                                  {"workerId": self.worker_id, "workId": w.id})
                    self._unlock_quietly(w, "overdelivered")
                    continue
                with self._subs_lock:
                    sub = self._subs.get(w.topic)
                if sub is None:
                    self.log.warn("work.unsubscribed", "no handler for topic; unlocking",
                                  {"workerId": self.worker_id, "workId": w.id, "topic": w.topic})
                    self._unlock_quietly(w, "unsubscribed")
                    continue
                if not w.id or not w.attempt_id:
                    # A-1: run only after the LOCKED confirmation with lockVersion + attemptId
                    self.log.error("work.protocol.violation", "item without LOCKED confirmation (attemptId); not started",
                                   {"workerId": self.worker_id, "workId": w.id})
                    continue
                used += 1
                try:
                    assert self._executor is not None
                    fut = self._executor.submit(self._run_item, w, sub)
                except RuntimeError:
                    used -= 1
                    self._unlock_quietly(w, "executor closed")
                    continue
                with self._state_lock:
                    self._futures.add(fut)
                fut.add_done_callback(self._forget)
            if permits - used > 0:
                self._release(permits - used)
        self.log.debug("worker.poller.exit", "poller exited", {"workerId": self.worker_id})

    def _forget(self, fut: "Future[None]") -> None:
        with self._state_lock:
            self._futures.discard(fut)

    def _release(self, n: int) -> None:
        for _ in range(n):
            self._slots.release()

    # ---- one item

    def _run_item(self, work: ExternalWork, sub: TopicSubscription) -> None:
        ctx = TaskContext(work, self.worker_id, self.client, self.log)
        with self._state_lock:
            self._in_flight[work.id] = ctx
        done = threading.Event()
        hb = threading.Thread(target=self._heartbeat_loop, args=(ctx, sub, done), name=f"sangam-hb-{work.id}",
                              daemon=True)
        hb.start()
        try:
            ctx.logger.info("work.started", "handler started")
            outcome = self._invoke(ctx, sub)
            done.set()
            if ctx.lease_lost:
                ctx.logger.warn("work.abandoned", "lease lost; result discarded (another attempt owns it)",
                                {"outcome": outcome.kind})
                return
            self._report(ctx, sub, outcome)
        finally:
            done.set()
            with self._state_lock:
                self._in_flight.pop(work.id, None)
            self._slots.release()

    def _invoke(self, ctx: TaskContext, sub: TopicSubscription) -> _Outcome:
        try:
            result = sub.handler(ctx)
            return _Outcome("complete", result)
        except BpmnError as e:
            return _Outcome("bpmn-error", e.variables, e)
        except OutcomeUnknown as e:
            return _Outcome("outcome-unknown", e.variables, e)
        except TaskFailed as e:
            return _Outcome("fail", None, e)
        except TaskCancelled as e:
            return _Outcome("cancel-ack" if ctx.cancel_requested else "unlock", None, e)
        except Exception as e:  # noqa: BLE001
            if ctx.shutting_down and ctx.cancelled:
                return _Outcome("unlock", None, e)
            return _Outcome("outcome-unknown" if sub.irreversible else "fail", None, e)

    def _next_hb_seq(self) -> int:
        with self._state_lock:
            self._hb_seq += 1
            return self._hb_seq

    def _heartbeat_loop(self, ctx: TaskContext, sub: TopicSubscription, done: threading.Event) -> None:
        lease = sub.lock_duration_ms
        interval = max(1.0, lease * self.heartbeat_fraction / 1000.0)
        failures = 0
        while True:
            wait = interval if failures == 0 else min(interval, self.report_backoff.delay(failures))
            if done.wait(wait):
                return
            try:
                key = f"{ctx.operation_id}:extend-lock:{ctx.lock_version}:{self._next_hb_seq()}"
                r = self.client.work.extend_lock(ctx.work_id, self.worker_id, ctx.lock_version, ctx.attempt_id,
                                                 lease, idempotency_key=key)
                failures = 0
                if r is not None:
                    ctx._update_lease(r.lock_expires_at, r.lock_version)
                    if r.cancel_requested and not ctx.cancel_requested:
                        ctx.cancel_requested = True
                        ctx.cancel_event.set()
                        ctx.logger.warn("work.cancel.requested", "server requested cancel")
                ctx.logger.debug("work.heartbeat", "lease extended", {"lockExpiresAt": str(ctx.lock_expires_at)})
            except (StaleLeaseError, GoneError) as e:
                self._lose_lease(ctx, e)
                return
            except SangamError as e:
                if done.is_set():
                    return
                failures += 1
                exp = ctx.lock_expires_at
                if exp is not None and _now() > _aware(exp):
                    self._lose_lease(ctx, e)
                    return
                ctx.logger.warn("work.heartbeat.failed", "heartbeat failed; will retry before lease expiry",
                                {"failures": failures}, e)

    @staticmethod
    def _lose_lease(ctx: TaskContext, cause: BaseException) -> None:
        ctx.lease_lost = True
        ctx.cancel_event.set()
        ctx.logger.warn("work.lease.lost", "lease lost; signalling handler to stop", None, cause)

    # ---- reporting

    def _report(self, ctx: TaskContext, sub: TopicSubscription, o: _Outcome) -> None:
        w = self.client.work
        wid, att = self.worker_id, ctx.attempt_id
        action = o.kind

        def key() -> str:
            return f"{ctx.operation_id}:{action}:{ctx.lock_version}"

        def call() -> None:
            lv = ctx.lock_version
            if action == "complete":
                w.complete(ctx.work_id, wid, lv, att, o.variables, idempotency_key=key())
            elif action == "bpmn-error":
                e = o.error
                assert isinstance(e, BpmnError)
                w.bpmn_error(ctx.work_id, wid, lv, att, e.error_code, e.message, e.variables, idempotency_key=key())
            elif action == "outcome-unknown":
                e = o.error
                if isinstance(e, OutcomeUnknown):
                    detail = e.reason + ("\n" + e.details if e.details else "")
                else:
                    detail = f"unexpected error on irreversible topic: {e!r}\n{_trace(e)}"
                res = w.outcome_unknown(ctx.work_id, wid, lv, att, detail[:4000], o.variables,
                                        idempotency_key=key())
                if res is not None and res.reconciliation_item_id:
                    ctx.logger.info("work.reconciliation.opened", "UNKNOWN_OUTCOME reconciliation item opened",
                                    {"reconciliationItemId": res.reconciliation_item_id})
            elif action == "fail":
                msg, details, retries, timeout_ms = self._fail_args(ctx, sub, o.error)
                w.fail(ctx.work_id, wid, lv, att, msg, details, retries, timeout_ms, idempotency_key=key())
            elif action == "cancel-ack":
                w.cancel_ack(ctx.work_id, wid, lv, att, idempotency_key=key())
            else:
                w.unlock(ctx.work_id, wid, lv, att, idempotency_key=key())

        attempt = 0
        while True:
            attempt += 1
            try:
                call()
                f: dict[str, Any] = {"attempts": attempt}
                if o.error is not None:
                    f["error"] = str(o.error)
                ctx.logger.info(f"work.{action}", f"reported {action}", f)
                return
            except (StaleLeaseError, GoneError) as e:
                ctx.lease_lost = True
                ctx.logger.warn("work.abandoned", f"{action} rejected: lease no longer ours", None, e)
                return
            except SangamApiError as e:
                if not e.retryable or not self._retry_allowed(ctx):
                    ctx.logger.error("work.report.failed", f"{action} rejected by server", {"status": e.status}, e)
                    return
            except TransportError as e:
                if not self._retry_allowed(ctx):
                    ctx.logger.error("work.report.gave-up",
                                     f"{action} not delivered before lease expiry; the lease will expire and the "
                                     "same operationId is reused", {"attempts": attempt}, e)
                    return
            threading.Event().wait(self.report_backoff.delay(attempt))

    @staticmethod
    def _retry_allowed(ctx: TaskContext) -> bool:
        exp = ctx.lock_expires_at
        return exp is None or _now() < _aware(exp)

    @staticmethod
    def _fail_args(ctx: TaskContext, sub: TopicSubscription,
                   error: Optional[BaseException]) -> tuple[str, Optional[str], int, int]:
        base = ctx.work.retries if ctx.work.retries is not None else sub.retries
        remaining = max(0, base - 1)
        timeout = sub.retry_backoff.delay(max(1, sub.retries - remaining))
        details = _trace(error)
        if isinstance(error, TaskFailed):
            if error.retries is not None:
                remaining = max(0, error.retries)
            if error.retry_timeout is not None:
                timeout = error.retry_timeout
            if error.details is not None:
                details = error.details
        msg = (str(error) or repr(error)) if error is not None else "failed"
        return msg[:1000], details, remaining, int(timeout * 1000)

    def _unlock_quietly(self, w: ExternalWork, why: str) -> None:
        op = w.operation_id or w.id
        try:
            self.client.work.unlock(w.id, self.worker_id, w.lock_version, w.attempt_id,
                                    idempotency_key=f"{op}:unlock:{w.lock_version}")
        except Exception as e:  # noqa: BLE001
            self.log.warn("work.unlock.failed", f"unlock ({why}) failed; lease will expire",
                          {"workerId": self.worker_id, "workId": w.id}, e)


def _aware(dt: datetime) -> datetime:
    return dt if dt.tzinfo is not None else dt.replace(tzinfo=timezone.utc)


def _trace(e: Optional[BaseException]) -> Optional[str]:
    if e is None:
        return None
    s = "".join(traceback.format_exception(type(e), e, e.__traceback__))
    return s[:4000]


__all__ = ["Backoff", "BpmnError", "Handler", "OutcomeUnknown", "TaskCancelled", "TaskContext", "TaskFailed",
           "TopicSubscription", "Worker"]

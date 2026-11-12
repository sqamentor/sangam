// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

import io.micrometer.core.instrument.Metrics;

import org.sangam.rest.adapter.operaton.TrackingWorkPort;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.WorkPort;
import org.sangam.rest.support.Permissions;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.WorkLongPoll;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

/**
 * Worker API (ARCHITECTURE §4, VI.3, FLOWABLE-INTO-OPERATON FC-36). Fenced by (workerId, lockVersion, attemptId): 409 STALE_LEASE.
 *
 * <p><b>Long-poll (M-053, R9).</b> {@code fetch-and-lock} makes its first attempt in the request thread; when that finds
 * nothing and the worker asked to wait ({@code asyncResponseTimeoutMs}, capped by
 * {@code sangam.rest.max-async-response-timeout}), the request continues as an async servlet response held by
 * {@link WorkLongPoll} and the request thread is released. The answer is the same JSON array (or the same problem
 * response) as before. Without a {@link WorkLongPoll} bean, or above its per-node waiter bound, the request waits in
 * its thread as before.
 */
@RestController
@RequestMapping("/api/v1/work")
public class WorkController {

    private static final Logger LOG = LoggerFactory.getLogger(WorkController.class);
    /** Async timeout margin over the waiter's own deadline (the waiter answers first; this only bounds a stuck one). */
    static final long ASYNC_GRACE_MS = 5_000;

    private final WorkPort work;
    private final PermissionPort permissions;
    private final ObjectProvider<WorkLongPoll> longPoll;
    private final ObjectProvider<RestProperties> props;

    public WorkController(WorkPort work, PermissionPort permissions, ObjectProvider<WorkLongPoll> longPoll,
                          ObjectProvider<RestProperties> props) {
        this.work = work;
        this.permissions = permissions;
        this.longPoll = longPoll;
        this.props = props;
    }

    @PostMapping("/fetch-and-lock")
    public DeferredResult<List<WorkPort.ExternalWork>> fetchAndLock(RequestContext ctx,
            @RequestBody WorkPort.FetchAndLockRequest body,
            @RequestHeader(value = "X-Sangam-Sdk", required = false) String sdk,
            @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        permissions.require(ctx, Permissions.WORK_EXECUTE);
        if (body.workerId() == null || body.workerId().isBlank()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "workerId is required");
        }
        if (body.topics() == null || body.topics().isEmpty()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "at least one topic is required");
        }
        if (work instanceof TrackingWorkPort tracking) {
            tracking.noteSdk(ctx.tenantId(), body.workerId(), sdk != null ? sdk : userAgent);
        }
        WorkLongPoll poll = longPoll.getIfAvailable();
        long wait = requestedWaitMs(body);
        if (poll == null || wait == 0) {
            return answered(work.fetchAndLock(ctx, body));
        }
        return longPoll(poll, ctx, body, wait);
    }

    /** The wait the adapters would apply: {@code asyncResponseTimeoutMs} capped by the configured maximum. */
    private long requestedWaitMs(WorkPort.FetchAndLockRequest body) {
        long requested = body.asyncResponseTimeoutMs() == null ? 0 : Math.max(0, body.asyncResponseTimeoutMs());
        RestProperties p = props.getIfAvailable();
        long max = p == null ? requested : p.getMaxAsyncResponseTimeout().toMillis();
        return Math.min(requested, Math.max(0, max));
    }

    private DeferredResult<List<WorkPort.ExternalWork>> longPoll(WorkLongPoll poll, RequestContext ctx,
            WorkPort.FetchAndLockRequest body, long wait) {
        long start = System.nanoTime();
        Supplier<List<WorkPort.ExternalWork>> attempt;
        Function<Long, List<WorkPort.ExternalWork>> blocking;
        long waitMs = wait;
        if (work instanceof TrackingWorkPort tracking) {
            // the console counts one call per HTTP request, however many polls it takes
            TrackingWorkPort.Prepared p = tracking.prepare(ctx, body);
            if (p.allPaused()) {
                waitMs = Math.min(wait, p.pausedWaitMs());
            }
            attempt = () -> tracking.attempt(ctx, p, 0L);
            blocking = left -> tracking.attempt(ctx, p, left);
        } else {
            attempt = () -> work.fetchAndLock(ctx, withWait(body, 0L));
            blocking = left -> work.fetchAndLock(ctx, withWait(body, left));
        }
        // first attempt in the request thread: work that is there now is answered at once, and its errors are thrown
        // exactly as before
        List<WorkPort.ExternalWork> first = attempt.get();
        long left = waitMs - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        if (!first.isEmpty() || left <= 0) {
            return answered(first);
        }
        DeferredResult<List<WorkPort.ExternalWork>> out = new DeferredResult<>(left + ASYNC_GRACE_MS);
        WorkLongPoll.Waiter waiter = poll.await(left, attempt, new WorkLongPoll.Outcome<>() {
            @Override
            public boolean result(List<WorkPort.ExternalWork> items) {
                return out.setResult(items);
            }

            @Override
            public boolean error(Throwable error) {
                return out.setErrorResult(error);
            }
        }, items -> release(ctx, body.workerId(), items));
        if (waiter == null) {
            // the node's waiter bound is reached (or it is shutting down): wait in this thread, the pre-M-053 way
            return answered(blocking.apply(left));
        }
        out.onTimeout(() -> {
            waiter.cancel();
            out.setResult(List.of());
        });
        out.onError(e -> waiter.cancel());
        out.onCompletion(waiter::cancel);
        return out;
    }

    /**
     * Work leased for a request that is gone (client disconnect, async timeout, shutdown): the leases are released at
     * once with their own fencing tokens, as a worker would ({@code unlock}), instead of waiting for the lock to expire.
     */
    private void release(RequestContext ctx, String workerId, List<WorkPort.ExternalWork> items) {
        for (WorkPort.ExternalWork w : items) {
            try {
                work.unlock(ctx, w.id(), new WorkPort.UnlockRequest(workerId, w.lockVersion(), w.attemptId()));
            } catch (RuntimeException e) {
                LOG.warn("work {} was leased after its long-poll request ended and could not be released; the lease "
                        + "expires on its own: {}", w.id(), e.toString());
            }
        }
    }

    private static WorkPort.FetchAndLockRequest withWait(WorkPort.FetchAndLockRequest r, long waitMs) {
        return new WorkPort.FetchAndLockRequest(r.workerId(), r.topics(), r.maxTasks(), waitMs, r.usePriority());
    }

    private static DeferredResult<List<WorkPort.ExternalWork>> answered(List<WorkPort.ExternalWork> items) {
        DeferredResult<List<WorkPort.ExternalWork>> out = new DeferredResult<>();
        out.setResult(items);
        return out;
    }

    @PostMapping("/{id}/extend-lock")
    public WorkPort.LockState extendLock(RequestContext ctx, @PathVariable String id,
                                         @RequestBody WorkPort.ExtendLockRequest body) {
        permissions.require(ctx, Permissions.WORK_EXECUTE);
        Telemetry.workId(id);
        WorkPort.LockState state = work.extendLock(ctx, id, body);
        Metrics.counter("sangam.lease.extended").increment();
        return state;
    }

    @PostMapping("/{id}/complete")
    public ResponseEntity<Void> complete(RequestContext ctx, @PathVariable String id,
                                         @RequestBody WorkPort.CompleteRequest body) {
        permissions.require(ctx, Permissions.WORK_EXECUTE);
        Telemetry.workId(id);
        work.complete(ctx, id, body);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/fail")
    public ResponseEntity<Void> fail(RequestContext ctx, @PathVariable String id, @RequestBody WorkPort.FailRequest body) {
        permissions.require(ctx, Permissions.WORK_EXECUTE);
        Telemetry.workId(id);
        work.fail(ctx, id, body);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/bpmn-error")
    public ResponseEntity<Void> bpmnError(RequestContext ctx, @PathVariable String id,
                                          @RequestBody WorkPort.BpmnErrorRequest body) {
        permissions.require(ctx, Permissions.WORK_EXECUTE);
        Telemetry.workId(id);
        if (body.errorCode() == null || body.errorCode().isBlank()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "errorCode is required");
        }
        work.bpmnError(ctx, id, body);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/unlock")
    public ResponseEntity<Void> unlock(RequestContext ctx, @PathVariable String id, @RequestBody WorkPort.UnlockRequest body) {
        permissions.require(ctx, Permissions.WORK_EXECUTE);
        Telemetry.workId(id);
        work.unlock(ctx, id, body);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/outcome-unknown")
    public ResponseEntity<WorkPort.OutcomeUnknownResult> outcomeUnknown(RequestContext ctx, @PathVariable String id,
                                                                        @RequestBody WorkPort.OutcomeUnknownRequest body) {
        permissions.require(ctx, Permissions.WORK_EXECUTE);
        Telemetry.workId(id);
        WorkPort.OutcomeUnknownResult r = work.outcomeUnknown(ctx, id, body);
        Telemetry.operationId(r.operationId());
        return Lists.accepted(r.reconciliationItemId(), r);
    }

    @PostMapping("/{id}/cancel-ack")
    public ResponseEntity<Void> cancelAck(RequestContext ctx, @PathVariable String id,
                                          @RequestBody WorkPort.CancelAckRequest body) {
        permissions.require(ctx, Permissions.WORK_EXECUTE);
        Telemetry.workId(id);
        work.cancelAck(ctx, id, body);
        return ResponseEntity.noContent().build();
    }
}

// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.lease;

import java.sql.Connection;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.operaton.bpm.engine.ExternalTaskService;
import org.operaton.bpm.engine.externaltask.ExternalTask;
import org.sangam.core.api.ExpiryPolicy;
import org.sangam.core.engine.OperationAwareExpiryPolicy;
import org.sangam.core.engine.SangamClock;

/**
 * A-10 expiry of held leases (FLOWABLE-INTO-OPERATON D-21), run before any re-offer in the transaction that ends the
 * lease; an unacknowledged cancel always holds (VI.3.1 CANCEL_REQUESTED). Moved unchanged out of
 * {@code OperatonEngineAdapter} (class-size ceiling); the adapter calls it inside its own engine commands.
 */
public final class LeaseExpiry {

    private final SangamLeaseTable leases;
    private final ExclusiveLeaseTable exclusive;
    private final SangamClock clock;
    private final ExternalTaskService externalTaskService;
    private final Supplier<ExpiryPolicy> policy;
    private final org.sangam.engine.replay.CapturePoints capture;

    public LeaseExpiry(SangamLeaseTable leases, ExclusiveLeaseTable exclusive, SangamClock clock,
                       ExternalTaskService externalTaskService, Supplier<ExpiryPolicy> policy) {
        this(leases, exclusive, clock, externalTaskService, policy, org.sangam.engine.replay.CapturePoints.NONE);
    }

    /** @param capture decision 151: an expired lease is reported as {@code EXTERNAL_TASK_RESULT TIMEOUT} */
    public LeaseExpiry(SangamLeaseTable leases, ExclusiveLeaseTable exclusive, SangamClock clock,
                       ExternalTaskService externalTaskService, Supplier<ExpiryPolicy> policy,
                       org.sangam.engine.replay.CapturePoints capture) {
        this.leases = leases;
        this.exclusive = exclusive;
        this.clock = clock;
        this.externalTaskService = externalTaskService;
        this.policy = policy;
        this.capture = capture == null ? org.sangam.engine.replay.CapturePoints.NONE : capture;
    }

    /** Expired held leases: the policy per lease, then the lease ends. Returns the ids the policy held. */
    public Set<String> sweep(Connection conn, int limit) {
        Set<String> held = new HashSet<>();
        for (SangamLeaseTable.Row row : leases.lockExpired(conn, clock.now().toEpochMilli(), limit)) {
            if (apply(conn, row, null) == ExpiryPolicy.Decision.HOLD) {
                held.add(row.workId());
            }
        }
        return held;
    }

    /**
     * A-10 on one expired, row-locked lease: the policy decides, the lease ends (version + 1), the exclusive scope is
     * released, and on HOLD the external task is parked with retries 0 (an incident {@code LEASE_EXPIRED_HELD}, visible
     * in Cockpit and the reconciliation queue). An unacknowledged cancel always holds (VI.3.1). A lease whose
     * external task is gone is removed without a policy call.
     */
    public ExpiryPolicy.Decision apply(Connection conn, SangamLeaseTable.Row row, String engineWorker) {
        ExternalTask task = externalTaskService.createExternalTaskQuery().externalTaskId(row.workId()).singleResult();
        long now = clock.now().toEpochMilli();
        if (task == null) {
            leases.remove(conn, row.workId());
            releaseExclusive(conn, row);
            return ExpiryPolicy.Decision.REOFFER;
        }
        ExpiryPolicy.ExpiredWork work = new ExpiryPolicy.ExpiredWork(row.workId(), row.workerId(), row.lockVersion(),
                row.attemptId(), row.expiresAtMs() == null ? null : Instant.ofEpochMilli(row.expiresAtMs()),
                task.getTopicName(), task.getProcessInstanceId(), task.getActivityId(), task.getActivityInstanceId());
        ExpiryPolicy p = policy.get();
        ExpiryPolicy.Decision decision;
        if (p instanceof OperationAwareExpiryPolicy oa) {
            decision = oa.onExpired(work, new OperationAwareExpiryPolicy.Context(row.tenantId(), task.getBusinessKey(),
                    row.rootId(), row.operationId() == null ? null : UUID.fromString(row.operationId()),
                    row.capabilityClass(), row.attemptNo()), conn);
        } else {
            decision = p.onExpired(work, conn);
        }
        if (row.state() == SangamLeaseTable.State.CANCEL_REQUESTED) {
            decision = ExpiryPolicy.Decision.HOLD; // VI.3.1 / VI.5 rule 4: cancel never acknowledged
        }
        capture.externalTimeout(task, row.attemptNo()); // decision 151: the attempt ended by its lease expiring
        leases.end(conn, row.workId(), decision == ExpiryPolicy.Decision.HOLD
                ? SangamLeaseTable.State.HELD : SangamLeaseTable.State.AVAILABLE, now);
        releaseExclusive(conn, row);
        if (decision == ExpiryPolicy.Decision.HOLD) {
            String lockOwner = engineWorker != null ? engineWorker : task.getWorkerId();
            String message = "LEASE_EXPIRED_HELD: lease " + row.lockVersion() + " of " + row.workerId() + " expired at "
                    + work.expiredAt() + "; held by the expiry policy (A-10)";
            if (lockOwner != null) {
                externalTaskService.handleFailure(row.workId(), lockOwner, message, null, 0, 0L);
            } else {
                externalTaskService.setRetries(row.workId(), 0);
            }
        }
        return decision;
    }

    /** Releases the VI.6 exclusive scope a lease holds, if any. */
    public void releaseExclusive(Connection conn, SangamLeaseTable.Row row) {
        if (row.exclusiveScope() != null) {
            String[] ts = ExclusiveLeaseTable.decode(row.exclusiveScope());
            exclusive.release(conn, ts[0], ts[1], row.workId());
        }
    }
}

// SPDX-License-Identifier: Apache-2.0
package org.sangam.host.conformance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.DeploymentPort;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.ProcessPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.WorkPort;

/**
 * RM-30 (decision 386(2)): the scenarios on the host-neutral job-lease port ({@link WorkPort}: lease, fencing token
 * {@code lockVersion}, fenced work) and on the authorization ports ({@link PermissionPort}).
 * They run only through the host's own ports.
 *
 * <ul>
 *   <li>{@code job-fencing}: a worker whose lease expired and was taken by another worker is refused with
 *       {@code 409 STALE_LEASE} on every call and changes nothing; the current lease completes;</li>
 *   <li>{@code p1-rollback}: inside the transaction of a fenced lease, a product row (audit event) and an engine step
 *       (a process variable) both commit, or, with a failure injected after both, both roll back (P-1);</li>
 *   <li>{@code deny-wins}: a user-level deny on one process instance wins over a group grant on every instance and over
 *       a user grant on the same instance; removing the deny restores the grant.</li>
 * </ul>
 */
final class WorkScenarios {

    static final String WORKER_1 = "conformance-worker-1";
    static final String WORKER_2 = "conformance-worker-2";

    /** The command type whose replay re-checks READ on the process instance (contract route, process.write). */
    static final String REPLAY_COMMAND = "process.variables.update";
    static final String CLERK = "conformance-clerk";
    static final String OTHER_CLERK = "conformance-clerk-2";
    static final String CLERKS = "conformance-clerks";
    /** The persona group that holds the command's endpoint permission ({@code process.write}). */
    static final String PERSONA = "sangam-system";

    private WorkScenarios() {
    }

    // ------------------------------------------------------------------ job fencing after a lease loss

    static BusinessResult jobFencing(HostHarness host) throws InterruptedException {
        RequestContext a = host.administrator(HostHarness.TENANT_A);
        WorkPort work = host.port(WorkPort.class);
        ProcessPort processes = host.port(ProcessPort.class);
        BusinessResult.Builder r = BusinessResult.builder();

        // (1) the lease of worker 1 expires: the expiry ends it (A-10), so every later call under it is stale
        ProcessPort.ProcessInstance expired = startWork(host, a);
        WorkPort.ExternalWork lapsed = fetch(work, a, WORKER_1, expired.businessKey(), 1L).get(0);
        Thread.sleep(250);
        r.put("expired.refetch.count", fetch(work, a, WORKER_2, expired.businessKey(), 60_000L).size());
        staleCalls(r, "expired", work, a, lapsed);
        r.put("expired.active", processes.get(a, expired.id()).activeActivityIds())
                .put("expired.chargedBy", String.valueOf(processes.variables(a, expired.id()).get("chargedBy")));

        // (2) worker 1 releases its lease and worker 2 takes the work over
        ProcessPort.ProcessInstance pi = startWork(host, a);
        WorkPort.ExternalWork lost = fetch(work, a, WORKER_1, pi.businessKey(), 60_000L).get(0);
        r.put("released.unlock", problem(() -> work.unlock(a, lost.id(), new WorkPort.UnlockRequest(WORKER_1,
                lost.lockVersion(), lost.attemptId()))));
        List<WorkPort.ExternalWork> second = fetch(work, a, WORKER_2, pi.businessKey(), 60_000L);
        r.put("takeover.count", second.size());
        if (second.isEmpty()) {
            return r.build();
        }
        WorkPort.ExternalWork current = second.get(0);
        r.put("takeover.sameWork", current.id().equals(lost.id()))
                .put("takeover.lockVersion.greater", current.lockVersion() > lost.lockVersion())
                .put("takeover.sameOperation", current.operationId().equals(lost.operationId()));
        staleCalls(r, "released", work, a, lost);
        r.put("released.active", processes.get(a, pi.id()).activeActivityIds())
                .put("released.chargedBy", String.valueOf(processes.variables(a, pi.id()).get("chargedBy")));
        r.put("current.complete", problem(() -> work.complete(a, current.id(), new WorkPort.CompleteRequest(WORKER_2,
                current.lockVersion(), current.attemptId(), Map.of("chargedBy", WORKER_2), null, null))));
        return r.put("after.active", processes.get(a, pi.id()).activeActivityIds())
                .put("after.chargedBy", String.valueOf(processes.variables(a, pi.id()).get("chargedBy")))
                .build();
    }

    /** Complete, heartbeat and fail under a lost lease: each must be refused and change nothing. */
    private static void staleCalls(BusinessResult.Builder r, String prefix, WorkPort work, RequestContext a,
                                   WorkPort.ExternalWork lost) {
        r.put(prefix + ".stale.complete", problem(() -> work.complete(a, lost.id(), new WorkPort.CompleteRequest(
                WORKER_1, lost.lockVersion(), lost.attemptId(), Map.of("chargedBy", WORKER_1), null, null))));
        r.put(prefix + ".stale.extend", problem(() -> work.extendLock(a, lost.id(), new WorkPort.ExtendLockRequest(
                WORKER_1, lost.lockVersion(), lost.attemptId(), 60_000L))));
        r.put(prefix + ".stale.fail", problem(() -> work.fail(a, lost.id(), new WorkPort.FailRequest(WORKER_1,
                lost.lockVersion(), lost.attemptId(), "late", null, 0, 0L))));
    }
    // ------------------------------------------------------------------ P-1: engine step and product row, one commit

    static BusinessResult p1Rollback(HostHarness host) {
        RequestContext a = host.administrator(HostHarness.TENANT_A);
        WorkPort work = host.port(WorkPort.class);
        ProcessPort processes = host.port(ProcessPort.class);
        AuditPort audit = host.port(AuditPort.class);
        ProcessPort.ProcessInstance pi = startWork(host, a);
        WorkPort.ExternalWork w = fetch(work, a, WORKER_1, pi.businessKey(), 60_000L).get(0);
        String chain = "conformance-p1-" + UUID.randomUUID();

        // a failure injected after the product row and the engine step: both roll back
        String failed = problemOrError(() -> work.inFencedWork(a, w.id(), WORKER_1, w.lockVersion(), w.attemptId(),
                (c, lease) -> {
                    audit.record(a, chain, "CONFORMANCE_CHARGED", Map.of("amountMinor", 2500));
                    processes.setVariables(a, pi.id(), Map.of("chargeMinor", 2500L));
                    throw new IllegalStateException("conformance: failure injected after the writes");
                }));
        BusinessResult.Builder r = BusinessResult.builder()
                .put("injected", failed)
                .put("injected.auditEvents", events(audit, a, chain))
                .put("injected.variable", String.valueOf(processes.variables(a, pi.id()).get("chargeMinor")));

        // the same body without the failure: both commit
        String ok = problemOrError(() -> work.inFencedWork(a, w.id(), WORKER_1, w.lockVersion(), w.attemptId(),
                (c, lease) -> {
                    audit.record(a, chain, "CONFORMANCE_CHARGED", Map.of("amountMinor", 2500));
                    processes.setVariables(a, pi.id(), Map.of("chargeMinor", 2500L));
                    return null;
                }));
        return r.put("committed", ok)
                .put("committed.auditEvents", events(audit, a, chain))
                .put("committed.variable", String.valueOf(processes.variables(a, pi.id()).get("chargeMinor")))
                .build();
    }

    private static long events(AuditPort audit, RequestContext a, String chain) {
        return audit.search(a, new AuditPort.AuditFilter(chain, null, null, null, null), new Page.PageRequest(0, 10))
                .items().size();
    }

    // ------------------------------------------------------------------ deny wins

    /**
     * The grants and the deny are written as effective rows ({@code AUTH_EFFECTIVE}) into the host's database: the
     * conformance tenants require four-eyes, and no port approves a pending policy change yet (recorded on RM-30). The
     * decision itself is the host's, through its {@link PermissionPort} (the replay re-check, decision 124).
     */
    static BusinessResult denyWins(HostHarness host) throws SQLException {
        RequestContext a = host.administrator(HostHarness.TENANT_A);
        PermissionPort permissions = host.port(PermissionPort.class);
        String denied = EquivalenceScenarios.startFeeProcess(host, a, 50_000L).id();
        String other = EquivalenceScenarios.startFeeProcess(host, a, 50_000L).id();
        RequestContext clerk = clerk(CLERK);
        RequestContext otherClerk = clerk(OTHER_CLERK);
        DataSource db = host.database();

        BusinessResult.Builder r = BusinessResult.builder()
                .put("before.grant", permissions.mayReplay(clerk, REPLAY_COMMAND, denied));
        effective(db, "group:" + CLERKS, "*", false);
        effective(db, "user:" + CLERK, denied, false);
        effective(db, "user:" + CLERK, denied, true);
        r.put("denied", permissions.mayReplay(clerk, REPLAY_COMMAND, denied))
                .put("otherInstance", permissions.mayReplay(clerk, REPLAY_COMMAND, other))
                .put("otherClerk", permissions.mayReplay(otherClerk, REPLAY_COMMAND, denied));
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement("DELETE FROM AUTH_EFFECTIVE"
                + " WHERE tenant_id=? AND principal_id=? AND resource_id=? AND deny_flag=1")) {
            ps.setString(1, HostHarness.TENANT_A);
            ps.setString(2, "user:" + CLERK);
            ps.setString(3, denied);
            r.put("deny.removed", ps.executeUpdate());
        }
        return r.put("afterDelete", permissions.mayReplay(clerk, REPLAY_COMMAND, denied)).build();
    }

    private static void effective(DataSource db, String principal, String resourceId, boolean deny) throws SQLException {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement("INSERT INTO AUTH_EFFECTIVE("
                + "tenant_id, principal_id, resource_type, resource_id, deny_flag, perms, condition_id, cond_key,"
                + " policy_version) VALUES (?, ?, 'PROCESS_INSTANCE', ?, ?, 1, NULL, '-', 0)")) {
            ps.setString(1, HostHarness.TENANT_A);
            ps.setString(2, principal);
            ps.setString(3, resourceId);
            ps.setInt(4, deny ? 1 : 0);
            if (ps.executeUpdate() != 1) {
                throw new IllegalStateException("AUTH_EFFECTIVE row not written for " + principal);
            }
        }
    }

    private static RequestContext clerk(String principal) {
        return new RequestContext(HostHarness.TENANT_A, principal, Set.of(PERSONA, CLERKS), null, null, null, null,
                null, "OIDC");
    }
    // ------------------------------------------------------------------ helpers

    static ProcessPort.ProcessInstance startWork(HostHarness host, RequestContext ctx) {
        host.port(DeploymentPort.class).deploy(ctx, new DeploymentPort.DeployRequest("conformance-work-process",
                EquivalenceScenarios.SOURCE, true, Map.of(ConformanceModels.WORK_RESOURCE,
                ConformanceModels.workProcess())));
        return host.port(ProcessPort.class).start(ctx, new ProcessPort.StartProcessRequest(
                ConformanceModels.WORK_PROCESS, "conformance-" + UUID.randomUUID(), Map.of("amountMinor", 2500L),
                null, null, null, null));
    }

    static List<WorkPort.ExternalWork> fetch(WorkPort work, RequestContext ctx, String workerId, String businessKey,
                                             long lockMs) {
        return work.fetchAndLock(ctx, new WorkPort.FetchAndLockRequest(workerId, List.of(new WorkPort.TopicRequest(
                ConformanceModels.CHARGE_TOPIC, lockMs, null, null, ConformanceModels.WORK_PROCESS, businessKey)), 1,
                null, null));
    }

    /** {@code ok}, or {@code refused-<status>-<code>} for an {@link ApiProblem}. */
    static String problem(Runnable call) {
        try {
            call.run();
            return "ok";
        } catch (ApiProblem p) {
            return "refused-" + p.status() + "-" + p.code();
        }
    }

    /** As {@link #problem}, and {@code failed} for any other exception (the injected failure). */
    static String problemOrError(Supplier<?> call) {
        try {
            call.get();
            return "ok";
        } catch (ApiProblem p) {
            return "refused-" + p.status() + "-" + p.code();
        } catch (RuntimeException e) {
            return "failed";
        }
    }
}

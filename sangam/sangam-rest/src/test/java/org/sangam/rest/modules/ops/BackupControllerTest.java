// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.sangam.backup.BackupConsole;
import org.sangam.backup.BackupTargets;
import org.sangam.rest.config.SpringTxSupport;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.Download;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * Decision 155: the backup console routes. Off by default (501), ops.read to look, ops.write and a reason to trigger,
 * 202 with the run, the audit event committed before the platform call, and console refusals as 400/404/409/502.
 */
class BackupControllerTest {

    static final RequestContext READER = ctx("reader", Set.of(Permissions.OPS_READ));
    static final RequestContext OPERATOR = ctx("operator", Set.of(Permissions.OPS_READ, Permissions.OPS_WRITE));
    static final RequestContext NOBODY = ctx("nobody", Set.of());
    static final BackupTargets.Target PG = new BackupTargets.Target("prod-pg-full", "pg-full", "15 1 * * *", "Etc/UTC",
            false, 0, null, null);

    final List<Object[]> audited = new CopyOnWriteArrayList<>();
    final List<String> started = new ArrayList<>();
    boolean platformDown;

    static RequestContext ctx(String who, Set<String> permissions) {
        return new RequestContext("t1", who, permissions, null, null, null, null, "127.0.0.1", "TOKEN");
    }

    /** Permissions are the context's groups, so each test context states exactly what it holds. */
    static final PermissionPort PERMISSIONS = new PermissionPort() {
        @Override
        public boolean has(RequestContext ctx, String permission) {
            return ctx.groups().contains(permission);
        }

        @Override
        public Set<String> permissions(RequestContext ctx) {
            return ctx.groups();
        }

        @Override
        public boolean mayReplay(RequestContext ctx, String commandType, String resourceRef) {
            return true;
        }
    };

    BackupTargets targets() {
        return new BackupTargets() {
            @Override
            public List<Target> targets() throws IOException {
                if (platformDown) {
                    throw new IOException("Kubernetes API answered 403: cronjobs.batch is forbidden");
                }
                return List.of(PG);
            }

            @Override
            public List<Run> runs(String targetId) {
                return started.stream().map(id -> new Run(id, PG.id(), Trigger.MANUAL, Status.PENDING, null, null, null, null)).toList();
            }

            @Override
            public String runId(Target target, Instant now) {
                return target.id() + "-m1";
            }

            @Override
            public Optional<Run> start(Target target, String runId) {
                started.add(runId);
                return Optional.of(new Run(runId, target.id(), Trigger.MANUAL, Status.PENDING, null, null, null, null));
            }
        };
    }

    BackupController controller(boolean enabled) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory(enabled
                ? Map.of("console", new BackupConsole(targets(), Clock.fixed(Instant.parse("2026-11-12T10:00:00Z"), ZoneOffset.UTC)))
                : Map.of());
        AuditPort audit = new AuditPort() {
            @Override
            public Page<AuditEntry> timeline(RequestContext ctx, String businessKey, boolean includeHistory, Page.PageRequest page) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Page<AuditEntry> search(RequestContext ctx, AuditFilter filter, Page.PageRequest page) {
                throw new UnsupportedOperationException();
            }

            @Override
            public VerifyResult verify(RequestContext ctx, String chainId, String businessKey) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Download export(RequestContext ctx, String businessKey, String format) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String record(RequestContext ctx, String chainId, String eventType, Map<String, ?> payload) {
                assertTrue(started.isEmpty() || !eventType.equals(BackupConsole.EVENT_TRIGGERED),
                        "the trigger event is written before the run starts");
                audited.add(new Object[]{chainId, eventType, Map.copyOf(payload), ctx.principalId()});
                return "e" + audited.size();
            }
        };
        PlatformTransactionManager noTx = new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        };
        return new BackupController(beans.getBeanProvider(BackupConsole.class), PERMISSIONS, audit, new SpringTxSupport(null, noTx, null));
    }

    @Test
    void offByDefaultEveryRouteAnswers501() {
        BackupController c = controller(false);
        ApiProblem p = assertThrows(ApiProblem.class, () -> c.targets(OPERATOR));
        assertEquals(501, p.status());
        assertEquals("BACKUP_CONSOLE_NOT_CONFIGURED", p.code());
        assertEquals(501, assertThrows(ApiProblem.class, () -> c.runs(OPERATOR, null, null, null)).status());
        assertEquals(501, assertThrows(ApiProblem.class,
                () -> c.trigger(OPERATOR, "prod-pg-full", new BackupController.TriggerRequest("x"))).status());
    }

    @Test
    void lookingNeedsOpsReadAndTriggeringNeedsOpsWrite() {
        BackupController c = controller(true);
        assertEquals(403, assertThrows(ApiProblem.class, () -> c.targets(NOBODY)).status());
        assertEquals(1, c.targets(READER).getBody().size());
        assertEquals(403, assertThrows(ApiProblem.class,
                () -> c.trigger(READER, "prod-pg-full", new BackupController.TriggerRequest("x"))).status());
        assertTrue(started.isEmpty());
        assertTrue(audited.isEmpty());
    }

    @Test
    void aTriggerIsAcceptedAuditedAndStarted() {
        BackupController c = controller(true);
        var answer = c.trigger(OPERATOR, "prod-pg-full", new BackupController.TriggerRequest("before the upgrade"));
        assertEquals(202, answer.getStatusCode().value());
        assertEquals("prod-pg-full-m1", answer.getBody().id());
        assertEquals(List.of("prod-pg-full-m1"), started);
        assertEquals(1, audited.size());
        assertEquals("backup:prod-pg-full", audited.get(0)[0]);
        assertEquals(BackupConsole.EVENT_TRIGGERED, audited.get(0)[1]);
        assertEquals("operator", audited.get(0)[3]);
        assertEquals(1, c.runs(READER, "prod-pg-full", null, null).getBody().size());
    }

    @Test
    void consoleRefusalsMapToProblems() {
        BackupController c = controller(true);
        ApiProblem noReason = assertThrows(ApiProblem.class, () -> c.trigger(OPERATOR, "prod-pg-full", null));
        assertEquals(400, noReason.status());
        assertEquals("REASON_REQUIRED", noReason.code());
        assertEquals(404, assertThrows(ApiProblem.class,
                () -> c.trigger(OPERATOR, "other", new BackupController.TriggerRequest("x"))).status());
        c.trigger(OPERATOR, "prod-pg-full", new BackupController.TriggerRequest("x"));
        ApiProblem running = assertThrows(ApiProblem.class,
                () -> c.trigger(OPERATOR, "prod-pg-full", new BackupController.TriggerRequest("again")));
        assertEquals(409, running.status());
        assertEquals("BACKUP_RUNNING", running.code());
        platformDown = true;
        ApiProblem down = assertThrows(ApiProblem.class, () -> c.targets(READER));
        assertEquals(502, down.status());
        assertEquals(BackupConsole.PLATFORM_ERROR, down.code());
    }
}

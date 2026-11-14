// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import org.sangam.rest.config.SpringTxSupport;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.Download;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.TestClockPort;
import org.sangam.rest.support.TxSupport;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** M-021: the test-mode clock route: 404 off, platform admin only, one action, audited before the answer. */
class TestModeClockControllerTest {

    private static final RequestContext ADMIN = ctx("sangam-admin");
    private static final RequestContext TENANT_ADMIN = ctx("sangam-tenant-admin");

    private final List<Object[]> audited = new CopyOnWriteArrayList<>();
    private boolean auditDown;

    private static RequestContext ctx(String... groups) {
        return new RequestContext("t1", "tester", Set.of(groups), null, null, null, null, "127.0.0.1", "DEV_TOKEN");
    }

    /** A clock that undoes a change its audit rejects (the TestModeClock rule, checked in TestModeClockTest). */
    static final class FakeClock implements TestClockPort {
        long offset;

        @Override
        public TestClock read() {
            return new TestClock(Instant.now().plusMillis(offset), offset, null);
        }

        private Change apply(String action, long next, String actor, String reason, Consumer<Change> audit) {
            long previous = offset;
            offset = next;
            Change c = new Change(action, previous, next, Instant.now().plusMillis(next), actor, reason);
            try {
                audit.accept(c);
            } catch (RuntimeException e) {
                offset = previous;
                throw e;
            }
            return c;
        }

        @Override
        public Change advanceBy(Duration by, String actor, String reason, Consumer<Change> audit) {
            return apply("ADVANCE", offset + by.toMillis(), actor, reason, audit);
        }

        @Override
        public Change set(Instant target, String actor, String reason, Consumer<Change> audit) {
            return apply("SET", target.toEpochMilli() - System.currentTimeMillis(), actor, reason, audit);
        }

        @Override
        public Change reset(String actor, String reason, Consumer<Change> audit) {
            return apply("RESET", 0, actor, reason, audit);
        }
    }

    private TestModeClockController controller(TestClockPort clock) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        if (clock != null) {
            beans.addBean("clock", clock);
        }
        PermissionPort permissions = new PermissionPort() {
            @Override
            public boolean has(RequestContext ctx, String permission) {
                return true;
            }

            @Override
            public Set<String> permissions(RequestContext ctx) {
                return Set.of("admin.read", "admin.write");
            }

            @Override
            public boolean mayReplay(RequestContext ctx, String commandType, String resourceRef) {
                return true;
            }
        };
        AuditPort audit = new AuditPort() {
            @Override
            public Page<AuditEntry> timeline(RequestContext ctx, String businessKey, boolean includeHistory,
                                             Page.PageRequest page) {
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
                if (auditDown) {
                    throw new IllegalStateException("audit down");
                }
                audited.add(new Object[]{chainId, eventType, Map.copyOf(payload)});
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
        return new TestModeClockController(beans.getBeanProvider(TestClockPort.class), permissions, audit,
                new SpringTxSupport(null, noTx, null));
    }

    @Test
    void withoutTestModeTheRouteIsNotFoundEvenForAnAdmin() {
        TestModeClockController c = controller(null);
        assertEquals(404, assertThrows(ApiProblem.class, () -> c.getTestClock(ADMIN)).status());
        assertEquals(404, assertThrows(ApiProblem.class, () -> c.changeTestClock(ADMIN,
                new TestClockPort.TestClockChange("P1D", null, null, null))).status());
    }

    @Test
    void onlyThePlatformAdministratorMayReadOrMoveTheClock() {
        TestModeClockController c = controller(new FakeClock());
        assertEquals(403, assertThrows(ApiProblem.class, () -> c.getTestClock(TENANT_ADMIN)).status());
        assertEquals(403, assertThrows(ApiProblem.class, () -> c.changeTestClock(TENANT_ADMIN,
                new TestClockPort.TestClockChange("P1D", null, null, null))).status());
        assertEquals(0, c.getTestClock(ADMIN).offsetMs());
        assertTrue(audited.isEmpty());
    }

    @Test
    void exactlyOnePositiveActionIsRequired() {
        TestModeClockController c = controller(new FakeClock());
        for (TestClockPort.TestClockChange bad : List.of(new TestClockPort.TestClockChange(null, null, null, "r"),
                new TestClockPort.TestClockChange("P1D", Instant.now(), null, null),
                new TestClockPort.TestClockChange("PT0S", null, null, null),
                new TestClockPort.TestClockChange("-PT1H", null, null, null),
                new TestClockPort.TestClockChange("three days", null, null, null),
                new TestClockPort.TestClockChange(null, null, false, null))) {
            ApiProblem p = assertThrows(ApiProblem.class, () -> c.changeTestClock(ADMIN, bad));
            assertEquals(400, p.status());
            assertEquals("VALIDATION_FAILED", p.code());
        }
        assertTrue(audited.isEmpty());
    }

    @Test
    void aChangeIsAuditedBeforeTheAnswerAndUndoneWhenTheAuditFails() {
        FakeClock clock = new FakeClock();
        TestModeClockController c = controller(clock);
        TestClockPort.TestClock moved = c.changeTestClock(ADMIN, new TestClockPort.TestClockChange("P3D", null, null,
                " SLA breach "));
        assertEquals("ADVANCE", moved.action());
        assertEquals(Duration.ofDays(3).toMillis(), moved.offsetMs());
        assertEquals(1, audited.size());
        assertEquals("sec:t1", audited.get(0)[0]);
        assertEquals(TestModeClockController.EVENT, audited.get(0)[1]);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) audited.get(0)[2];
        assertEquals("ADVANCE", payload.get("action"));
        assertEquals(0L, payload.get("previousOffsetMs"));
        assertEquals("tester", payload.get("actor"));
        assertEquals("SLA breach", payload.get("reason"));

        auditDown = true;
        assertThrows(IllegalStateException.class, () -> c.changeTestClock(ADMIN,
                new TestClockPort.TestClockChange(null, null, true, null)));
        assertEquals(Duration.ofDays(3).toMillis(), clock.offset, "a change whose audit failed is undone");
        assertNull(c.getTestClock(ADMIN).action());
    }
}

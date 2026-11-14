// SPDX-License-Identifier: Apache-2.0
package org.sangam.assembly.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Transactions;

/** M-144: a host cannot claim a seam it did not hand over. Written for the test phase (not run). */
class HostSeamsTest {

    /** A data source that is never asked for a connection in these tests. */
    static final DataSource NO_DB = new DataSource() {
        @Override
        public Connection getConnection() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Connection getConnection(String u, String p) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getGlobal();
        }

        @Override
        public <T> T unwrap(Class<T> iface) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    };

    static final Transactions NO_TX = new Transactions() {
        @Override
        public <T> T inTransaction(java.util.function.Function<Connection, T> work) {
            throw new UnsupportedOperationException();
        }
    };

    @Test
    void anEmptyHostSuppliesOnlyTheStandaloneScheduler() {
        HostSeams seams = HostSeams.builder("bare").build();
        assertEquals(Set.of(Capabilities.SCHEDULING), seams.capabilities());
        assertFalse(seams.hostScheduler());
        assertTrue(seams.scheduler() instanceof HostScheduler.Standalone);
        CapabilityMissingException e = assertThrows(CapabilityMissingException.class,
                () -> seams.resolve(Capabilities.required()).refuseStartIfMissing());
        assertTrue(e.missing().contains(Capabilities.SHARED_TRANSACTION));
        assertTrue(e.missing().contains(Capabilities.LIFECYCLE));
    }

    /** Decision 279: a host that declines scheduling gets no standalone fallback and is refused. */
    @Test
    void aHostThatDeclinesSchedulingLacksIt() {
        HostSeams seams = HostSeams.builder("declined").withoutScheduler().build();
        assertFalse(seams.capabilities().contains(Capabilities.SCHEDULING));
        assertFalse(seams.hostScheduler());
        CapabilityMissingException e = assertThrows(CapabilityMissingException.class, seams::scheduler);
        assertEquals(List.of(Capabilities.SCHEDULING), e.missing());
        SangamRuntime runtime = seams.runtime(Capabilities.required(), List.of(), List.of());
        assertTrue(runtime.capabilities().missingRequired().contains(Capabilities.SCHEDULING));
        assertFalse(runtime.startOrder().stream().anyMatch(n -> n.startsWith("sangam-scheduler")),
                "no standalone scheduler component: " + runtime.startOrder());
        CapabilityMissingException refused = assertThrows(CapabilityMissingException.class, runtime::start);
        assertTrue(refused.getMessage().contains("scheduling (" + Capabilities.SCHEDULING.whenMissing() + ")"),
                refused.getMessage());
    }

    @Test
    void aHostSchedulerAfterWithoutSchedulerIsTheHostsOwn() {
        HostScheduler own = (name, initial, delay, task) -> () -> { };
        HostSeams seams = HostSeams.builder("own").withoutScheduler().scheduler(own).build();
        assertTrue(seams.capabilities().contains(Capabilities.SCHEDULING));
        assertTrue(seams.hostScheduler());
    }

    @Test
    void theSeamCapabilitiesFollowWhatWasHandedOver() {
        HostSeams seams = HostSeams.builder("full")
                .properties(SangamProperties.of(Map.of()), true)
                .lifecycleHook()
                .connection(ConnectionBinding.of(NO_DB, NO_TX, true))
                .securityContext(new SecurityContextBinding<String>() {
                    @Override
                    public <T> T callAs(String principal, java.util.function.Supplier<T> call) {
                        return call.get();
                    }
                })
                .health()
                .http(new HttpBinding() {
                    @Override
                    public String contractVersion() {
                        return "1";
                    }

                    @Override
                    public String basePath() {
                        return "/api/v1";
                    }
                })
                .build();
        assertEquals(Set.of(Capabilities.CONFIG_SOURCE, Capabilities.DEFAULTS_SOURCE, Capabilities.LIFECYCLE,
                Capabilities.SHARED_TRANSACTION, Capabilities.SECURITY_CONTEXT, Capabilities.SCHEDULING,
                Capabilities.HEALTH, Capabilities.HTTP), seams.capabilities());
    }

    @Test
    void aConnectionThatDoesNotShareTheEngineConnectionIsNotP1() {
        HostSeams seams = HostSeams.builder("split-tx").connection(ConnectionBinding.of(NO_DB, NO_TX, false)).build();
        assertFalse(seams.capabilities().contains(Capabilities.SHARED_TRANSACTION));
        assertTrue(seams.connection().isPresent());
    }

    @Test
    void seamCapabilitiesCanNeverBeClaimed() {
        for (Capability c : HostSeams.SEAM_DERIVED) {
            assertThrows(IllegalArgumentException.class,
                    () -> HostSeams.builder("liar").assembled(List.of(c)), c.id());
        }
        HostSeams seams = HostSeams.builder("assembled").assembled(List.of(Capabilities.AUDIT_CHAIN)).build();
        assertTrue(seams.capabilities().contains(Capabilities.AUDIT_CHAIN));
    }

    @Test
    void hostNamesAreLowerCase() {
        assertThrows(IllegalArgumentException.class, () -> HostSeams.builder("Spring"));
        assertThrows(IllegalArgumentException.class, () -> HostSeams.builder(null));
    }

    @Test
    void theStandaloneSchedulerStopsAfterEverythingElse() {
        HostSeams seams = HostSeams.builder("plain").build();
        List<String> events = new ArrayList<>();
        SangamRuntime rt = seams.runtime(List.of(), List.of(Lifecycle.of("relay", 0, () -> events.add("start relay"),
                d -> events.add("stop relay"))), List.of());
        assertEquals("sangam-scheduler-plain", rt.startOrder().get(0));
        rt.start();
        rt.stop(Duration.ofSeconds(1));
        assertEquals(List.of("start relay", "stop relay"), events);
        assertTrue(((HostScheduler.Standalone) seams.scheduler()).isShutdown());
    }

    @Test
    void aHostSchedulerIsNotAddedAsAComponent() {
        HostScheduler own = (name, initial, delay, task) -> () -> { };
        HostSeams seams = HostSeams.builder("own").scheduler(own).build();
        assertTrue(seams.hostScheduler());
        assertEquals(List.of(), seams.runtime(List.of(), List.of(), List.of()).startOrder());
    }

    @Test
    void theStandaloneSchedulerKeepsRunningAfterAFailedRun() throws Exception {
        HostScheduler.Standalone s = HostScheduler.standalone("sangam-test-scheduler");
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch twice = new CountDownLatch(2);
        HostScheduler.Scheduled job = s.scheduleWithFixedDelay("flaky", Duration.ZERO, Duration.ofMillis(10), () -> {
            runs.incrementAndGet();
            twice.countDown();
            throw new IllegalStateException("first runs fail");
        });
        try {
            assertTrue(twice.await(10, TimeUnit.SECONDS), "a failing run did not cancel the schedule");
        } finally {
            job.cancel();
            s.stop(Duration.ofSeconds(1));
        }
        assertTrue(runs.get() >= 2);
        assertThrows(IllegalArgumentException.class,
                () -> HostScheduler.standalone("x").scheduleWithFixedDelay("t", Duration.ZERO, Duration.ZERO, () -> { }));
    }
}

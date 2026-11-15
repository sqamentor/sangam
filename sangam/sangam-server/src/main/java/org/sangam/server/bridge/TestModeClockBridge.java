// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

import org.sangam.engine.clock.TestModeClock;
import org.sangam.rest.port.TestClockPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * M-021: the REST {@link TestClockPort} over the engine's {@link TestModeClock}. The server creates it only with
 * {@code sangam.test-mode.enabled} on (EngineWiring re-checks H2 and production first). Besides the route's class-B
 * {@code TEST_CLOCK_CHANGED}, every change is logged at WARN, so a steered clock is visible in the server log too.
 * Closing the bean (context close) restores the process-wide business clock.
 */
public final class TestModeClockBridge implements TestClockPort, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(TestModeClockBridge.class);

    private final TestModeClock clock;

    public TestModeClockBridge(TestModeClock clock) {
        this.clock = clock;
    }

    /**
     * Gives the process-wide business clock back ({@link TestModeClock#close}): the test-mode clock replaces it for
     * the whole JVM when it is built, so the bean releases it when the context closes. Without this a closed server
     * left its engine-following clock installed for whatever runs next in the same JVM.
     */
    @Override
    public void close() {
        clock.close();
    }

    /** The engine-side auditor of every change (the log line); the route adds its class-B audit per call. */
    public static void log(TestModeClock.Change c) {
        LOG.warn("M-021 test-mode clock {}: offset {} ms -> {} ms, now {}, by {}", c.action(), c.previousOffsetMs(),
                c.offsetMs(), c.now(), c.actor());
    }

    @Override
    public TestClock read() {
        return new TestClock(clock.now(), clock.offsetMs(), null);
    }

    @Override
    public Change advanceBy(Duration by, String actor, String reason, Consumer<Change> audit) {
        return map(clock.advanceBy(by, actor, reason, c -> audit.accept(map(c))));
    }

    @Override
    public Change set(Instant target, String actor, String reason, Consumer<Change> audit) {
        return map(clock.set(target, actor, reason, c -> audit.accept(map(c))));
    }

    @Override
    public Change reset(String actor, String reason, Consumer<Change> audit) {
        return map(clock.reset(actor, reason, c -> audit.accept(map(c))));
    }

    private static Change map(TestModeClock.Change c) {
        return new Change(c.action(), c.previousOffsetMs(), c.offsetMs(), c.now(), c.actor(), c.reason());
    }
}

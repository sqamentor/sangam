// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

/**
 * M-021 (C3-13): the test-mode clock behind {@code /api/v1/test-mode/clock}. The server binds an implementation only
 * when {@code sangam.test-mode.enabled} is on (H2, never in production); without a bean the route answers 404.
 * Each change runs {@code audit} before it returns; when {@code audit} throws, the change is undone and the exception
 * propagates.
 */
public interface TestClockPort {

    /** The contract's {@code TestClock}: the instant the engine and business clocks read, their offset, the action. */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    record TestClock(Instant now, long offsetMs, String action) {
    }

    /** The contract's {@code TestClockChange}: exactly one of advanceBy, set or reset (true), and an optional reason. */
    record TestClockChange(String advanceBy, Instant set, Boolean reset, String reason) {
    }

    /** One applied change, as recorded in {@code TEST_CLOCK_CHANGED}. */
    record Change(String action, long previousOffsetMs, long offsetMs, Instant now, String actor, String reason) {
    }

    TestClock read();

    Change advanceBy(Duration by, String actor, String reason, Consumer<Change> audit);

    Change set(Instant target, String actor, String reason, Consumer<Change> audit);

    Change reset(String actor, String reason, Consumer<Change> audit);
}

// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.contract;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.sangam.sdk.log.SangamLogger;
import org.sangam.sdk.testing.SangamTestEngine;
import org.sangam.sdk.worker.Backoff;
import org.sangam.sdk.worker.SangamWorker;

/** Shared set-up of the SDK contract tests (T01-T04, T18; R18, ADR-D-4) on the R18 test engine. */
final class ContractSupport {

    record Log(String event, Map<String, ?> fields) { }

    static final Backoff FAST = new Backoff(Duration.ofMillis(5), Duration.ofMillis(20), 1.0, 0.0);

    private ContractSupport() { }

    /** A worker on the engine: fast back-offs, short long-poll, the engine clock, and a log recorder. */
    static SangamWorker.Builder worker(SangamTestEngine engine, String workerId, List<Log> logs) {
        SangamLogger logger = (level, event, message, fields, error) -> logs.add(new Log(event, fields));
        return SangamWorker.builder(engine.client()).workerId(workerId).maxConcurrency(1).maxTasksPerFetch(1)
                .asyncResponseTimeout(Duration.ofMillis(100)).fetchBackoff(FAST).reportBackoff(FAST)
                .shutdownTimeout(Duration.ofSeconds(2)).clock(engine.clock()).logger(logger);
    }

    static List<Log> logs() {
        return new CopyOnWriteArrayList<>();
    }

    static boolean logged(List<Log> logs, String event) {
        return logs.stream().anyMatch(l -> l.event().equals(event));
    }

    static void until(BooleanSupplier cond, String what) throws InterruptedException {
        long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!cond.getAsBoolean()) {
            if (System.nanoTime() > end) throw new AssertionError("not reached in time: " + what);
            Thread.sleep(5);
        }
    }
}

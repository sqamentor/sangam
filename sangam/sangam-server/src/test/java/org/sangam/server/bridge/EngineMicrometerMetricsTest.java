// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;

/**
 * SL-89: {@code sangam_jobs_failed_total} carries {@code conflict} on every series (a Prometheus meter needs one
 * tag-key set per name), {@code true} only for a transient conflict whose job retry was kept.
 */
class EngineMicrometerMetricsTest {

    @Test
    void everyJobFailureSeriesCarriesCauseAndConflict() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        EngineMicrometerMetrics metrics = new EngineMicrometerMetrics(registry);

        metrics.jobFailed("IllegalStateException");
        metrics.jobFailed("LayerSqlException", true);
        metrics.jobFailed("LayerSqlException", true);
        metrics.jobFailed("org.example.BoomException", false);

        assertEquals(1.0, count(registry, "IllegalStateException", "false"));
        assertEquals(2.0, count(registry, "LayerSqlException", "true"));
        assertEquals(1.0, count(registry, "BoomException", "false"));
        assertEquals(3, registry.find("sangam.jobs.failed").counters().size());
        registry.find("sangam.jobs.failed").counters().forEach(c -> assertEquals(2, c.getId().getTags().size(), c.getId().toString()));
    }

    private static double count(SimpleMeterRegistry registry, String cause, String conflict) {
        Counter c = registry.find("sangam.jobs.failed").tag("cause", cause).tag("conflict", conflict).counter();
        return c == null ? 0 : c.count();
    }
}
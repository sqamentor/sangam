// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.time.Duration;
import java.util.List;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.sangam.security.crypto.FieldReencryptionJob;
import org.sangam.security.crypto.ReencryptionSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.mock.env.MockEnvironment;

/**
 * M-085 server wiring: off unless enabled; settings and their bounds; the metric counter per source and outcome; a
 * scheduler without the tenant-key encryptor or without sources never starts and a pass does nothing. The job itself
 * is covered by {@code FieldReencryptionJobH2Test} (sangam-security). Written for the test phase (not run here).
 */
class FieldReencryptionWiringTest {

    static MockEnvironment env() {
        MockEnvironment env = new MockEnvironment();
        env.setConversionService(new ApplicationConversionService());
        return env;
    }

    static final ReencryptionSource SOURCE = new ReencryptionSource() {
        @Override
        public String name() {
            return "doc";
        }

        @Override
        public List<Row> page(Connection c, String tenantId, String afterKey, int limit) {
            return List.of();
        }

        @Override
        public String withEnvelope(String stored, String newEnvelope) {
            return newEnvelope;
        }

        @Override
        public boolean replace(Connection c, String tenantId, String key, String expectedStored, String newStored) {
            return false;
        }
    };

    @Test
    void offUnlessEnabled() {
        ConditionalOnProperty c = FieldReencryptionWiring.class.getAnnotation(ConditionalOnProperty.class);
        assertEquals("sangam.security.reencryption.enabled", String.join(",", c.name()));
        assertEquals("true", c.havingValue());
        assertFalse(c.matchIfMissing());
    }

    @Test
    void settingsHaveSafeDefaultsAndBounds() {
        assertEquals(new FieldReencryptionWiring.Settings(Duration.ofMinutes(15), FieldReencryptionJob.DEFAULT_BATCH, 50, false),
                FieldReencryptionWiring.settings(env()));
        assertEquals(new FieldReencryptionWiring.Settings(Duration.ofMinutes(1), 500, 3, true),
                FieldReencryptionWiring.settings(env().withProperty("sangam.security.reencryption.period", "1m")
                        .withProperty("sangam.security.reencryption.batch-size", "500")
                        .withProperty("sangam.security.reencryption.max-batches", "3")
                        .withProperty("sangam.security.reencryption.dry-run", "true")));
        assertThrows(IllegalStateException.class, () -> FieldReencryptionWiring.settings(
                env().withProperty("sangam.security.reencryption.batch-size", "0")));
        assertThrows(IllegalStateException.class, () -> FieldReencryptionWiring.settings(
                env().withProperty("sangam.security.reencryption.batch-size", "10001")));
        assertThrows(IllegalStateException.class, () -> FieldReencryptionWiring.settings(
                env().withProperty("sangam.security.reencryption.max-batches", "0")));
        assertThrows(IllegalStateException.class, () -> FieldReencryptionWiring.settings(
                env().withProperty("sangam.security.reencryption.period", "0s")));
    }

    @Test
    void theMetricCountsRowsPerSourceAndOutcome() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        FieldReencryptionJob.Metrics m = FieldReencryptionWiring.metrics(meters);
        m.rows("t1", "doc", "rewritten", 5);
        m.rows("t2", "doc", "rewritten", 2);
        m.rows("t1", "doc", "failed", 1);
        assertEquals(7.0, meters.get(FieldReencryptionWiring.METRIC).tag("source", "doc").tag("outcome", "rewritten")
                .counter().count());
        assertEquals(1.0, meters.get(FieldReencryptionWiring.METRIC).tag("outcome", "failed").counter().count());
        assertEquals(FieldReencryptionJob.Metrics.NONE, FieldReencryptionWiring.metrics(null));
    }

    @Test
    void aSchedulerWithoutJobOrSourcesNeverStartsAndDoesNothing() {
        FieldReencryptionWiring.Settings s = FieldReencryptionWiring.settings(env());
        try (FieldReencryptionWiring.Scheduler noJob = new FieldReencryptionWiring.Scheduler(null, List.of(SOURCE),
                () -> List.of("t1"), s).start()) {
            assertFalse(noJob.running());
            assertTrue(noJob.runOnce().isEmpty());
        }
    }
}

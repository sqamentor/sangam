// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.resources.ResourcesContext;
import org.sangam.resources.ResourcesSchema;
import org.sangam.resources.dialect.ResourcesDialect;

/**
 * SL-92: the four nested service-point settings take effect end to end (stored as {@code config_json}, read back
 * through {@code Sql.mapJson}, applied by {@link QueueService#issue}). The context's rule evaluator builds Sangam's
 * FEEL-Scala DMN engine, so jackson-module-scala is loaded on this class path exactly as on the server.
 */
class QueueNestedSettingsH2Test {

    /** A validated development environment (decision 143): the only one in which a context runs without authorization. */
    static final org.sangam.core.security.EnvironmentPolicy.Resolution DEVELOPMENT =
            org.sangam.core.security.EnvironmentPolicy.resolve("false", "test",
                    org.sangam.core.security.EnvironmentPolicy.signals(java.util.List.of("test")));

    private static final SecurityIdentity ADMIN = new SecurityIdentity("t1", "admin", "user:admin", Set.of(),
            Set.of("admin"), Set.of(), Set.of(), "OIDC", null, null, false, Map.of());

    private Connection c;
    private QueueService queue;

    @BeforeEach
    void setUp() throws Exception {
        c = DriverManager.getConnection("jdbc:h2:mem:sl92" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=TRUE", "sa", "");
        ResourcesDialect d = ResourcesDialect.detect(c);
        assertEquals(ResourcesDialect.H2, d);
        ResourcesSchema.create(c, d);
        queue = new QueueService(ResourcesContext.builder(d).deliverDirectly(true).withoutAuthorization(DEVELOPMENT).build());
    }

    @AfterEach
    void tearDown() throws Exception {
        c.close();
    }

    private static Map<String, Object> config() {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("zone", "UTC");
        cfg.put("skillRouting", Map.of("R1", "PASSPORT"));
        cfg.put("priorityLevels", Map.of("SENIOR", 5));
        cfg.put("seriesBySkill", Map.of("PASSPORT", "P"));
        cfg.put("slaMinutesByClass", Map.of("SENIOR", 7));
        return cfg;
    }

    @Test
    void storedConfigReadsBackAsJavaMaps() {
        QueueModel.ServicePoint sp = queue.defineServicePoint(c, ADMIN, "SP1", "Front desk", null, null, "A", 30,
                null, null, config());
        assertEquals(config(), sp.config());
        assertTrue(sp.config().get("skillRouting") instanceof java.util.Map<?, ?>,
                "nested setting must be a java.util.Map, was " + sp.config().get("skillRouting").getClass().getName());
    }

    @Test
    void skillRoutingPriorityLevelsSeriesAndSlaByClassTakeEffect() {
        queue.defineServicePoint(c, ADMIN, "SP1", "Front desk", null, null, "A", 30, null, null, config());

        QueueModel.Token tok = queue.issue(c, ADMIN, new QueueModel.IssueRequest("SP1", QueueModel.Channel.WALK_IN,
                null, null, "SENIOR", null, "R1", Map.of()));

        assertEquals("PASSPORT", tok.skill(), "skillRouting R1 -> PASSPORT");
        assertEquals(5, tok.priorityLevel(), "priorityLevels SENIOR -> 5");
        assertEquals("AP001", tok.displayNo(), "token prefix A + seriesBySkill PASSPORT -> P + number");
        assertEquals(Duration.ofMinutes(7), Duration.between(tok.queuedAt(), tok.slaDueAt()),
                "slaMinutesByClass SENIOR -> 7 (service point default is 30)");
    }

    @Test
    void unconfiguredClassAndKeyFallBackToServicePointDefaults() {
        queue.defineServicePoint(c, ADMIN, "SP1", "Front desk", null, null, "A", 30, null, null, config());

        QueueModel.Token tok = queue.issue(c, ADMIN, new QueueModel.IssueRequest("SP1", QueueModel.Channel.WALK_IN,
                null, null, "REGULAR", null, "UNKNOWN", Map.of()));

        assertEquals("GENERAL", tok.skill());
        assertEquals(100, tok.priorityLevel());
        assertEquals("A001", tok.displayNo());
        assertEquals(Duration.ofMinutes(30), Duration.between(tok.queuedAt(), tok.slaDueAt()));
    }
}

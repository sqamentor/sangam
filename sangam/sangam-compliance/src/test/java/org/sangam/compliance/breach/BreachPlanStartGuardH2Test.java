// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.breach;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.compliance.ComplianceException;
import org.sangam.compliance.ComplianceSchema;
import org.sangam.compliance.SangamCompliance;
import org.sangam.compliance.config.ComplianceConfig;
import org.sangam.compliance.jurisdiction.JurisdictionRegistry;
import org.sangam.compliance.ports.CompliancePorts.WorkflowStarter;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;

/**
 * M-010c, owner decision (a): the breach notification plan process is started once per breach, and that is stored:
 * {@link BreachNotificationPlanner#start} claims {@code CMP_BREACH.plan_process_instance_id} with a conditional UPDATE
 * (changeset 0041-02) before it asks the engine, so a second start for the same breach is refused and starts nothing.
 * H2 in PostgreSQL mode; the workflow starter records what it would start.
 */
class BreachPlanStartGuardH2Test {

    static final Dialect D = Dialect.POSTGRESQL;
    static final String TENANT = "t1";
    static final JurisdictionRegistry PACKS = JurisdictionRegistry.discover();
    static final Map<String, String> SEATS = Map.of("seats.DPO", "SEAT-DPO", "seats.CISO", "SEAT-CISO",
            "seats.PRIVACY-OFFICER", "SEAT-PRIVACY");

    final List<String> started = new ArrayList<>();
    String url;
    Connection keepAlive;

    @BeforeEach
    void db() throws Exception {
        url = "jdbc:h2:mem:m010cguard" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        keepAlive = DriverManager.getConnection(url, "sa", "");
        Schema.create(keepAlive, D);
        ComplianceSchema.create(keepAlive, D);
    }

    @AfterEach
    void close() throws Exception {
        keepAlive.close();
    }

    SangamCompliance compliance() {
        Map<String, String> props = new HashMap<>(SEATS);
        props.put("jurisdiction", "EU-GDPR"); // defines breach notification rules: the plan applies
        ComplianceConfig.Provider config = t -> ComplianceConfig.fromProperties(PACKS, props);
        WorkflowStarter starter = (c, t, key, bk, vars) -> {
            started.add(key + "|" + bk);
            return "pi-" + started.size();
        };
        Transactions tx = new Transactions() {
            @Override
            public <T> T inTransaction(Function<Connection, T> work) {
                throw new AssertionError("not used by these calls");
            }
        };
        SangamCompliance.Ports ports = new SangamCompliance.Ports(null, null, null, null, null, null, null, null, null,
                starter, null, null, null, null, null, null, null);
        return new SangamCompliance(D, tx, config, ports, null, org.sangam.compliance.PrincipalKey.of(new byte[32]));
    }

    static String planInstance(Connection c, String breachId) {
        return Jdbc.one(c, "SELECT plan_process_instance_id FROM CMP_BREACH WHERE tenant_id=? AND breach_id=?",
                rs -> rs.getString(1), TENANT, breachId).orElse(null);
    }

    long planStarts(String breachId) {
        return started.stream().filter(s -> s.equals(BreachNotificationPlanner.PROCESS_KEY + "|" + breachId)).count();
    }

    @Test
    void theRegistrationStartsThePlanOnceAndStoresItsInstance() throws Exception {
        SangamCompliance cmp = compliance();
        try (Connection c = DriverManager.getConnection(url, "sa", "")) {
            c.setAutoCommit(false);
            String id = cmp.breaches.register(c, TENANT, Map.of("description", "test breach"), null, null, null, null,
                    null, List.of("portal"), "officer-1").breachId();
            assertEquals(1, planStarts(id));
            String pi = planInstance(c, id);
            assertTrue(pi != null && pi.startsWith("pi-"), "the engine's instance id is stored: " + pi);
            c.rollback();
        }
    }

    @Test
    void aSecondStartForTheSameBreachIsRefusedAndStartsNothing() throws Exception {
        SangamCompliance cmp = compliance();
        try (Connection c = DriverManager.getConnection(url, "sa", "")) {
            c.setAutoCommit(false);
            String id = cmp.breaches.register(c, TENANT, Map.of("description", "test breach"), null, null, null, null,
                    null, List.of("portal"), "officer-1").breachId();
            String first = planInstance(c, id);
            int before = started.size();
            ComplianceException e = assertThrows(ComplianceException.class,
                    () -> cmp.breachPlans.start(c, TENANT, id, Map.of("privacyOfficerSeat", "SEAT-PRIVACY")));
            assertEquals(ComplianceException.Code.INVALID_STATE, e.code());
            assertTrue(e.getMessage().contains(id), e.getMessage());
            assertEquals(before, started.size(), "the engine was not asked again");
            assertEquals(1, planStarts(id));
            assertEquals(first, planInstance(c, id), "the stored instance is unchanged");
            c.rollback();
        }
    }

    @Test
    void aStartForAnUnknownBreachIsNotFound() throws Exception {
        SangamCompliance cmp = compliance();
        try (Connection c = DriverManager.getConnection(url, "sa", "")) {
            c.setAutoCommit(false);
            ComplianceException e = assertThrows(ComplianceException.class,
                    () -> cmp.breachPlans.start(c, TENANT, "no-such-breach", Map.of("privacyOfficerSeat", "SEAT-PRIVACY")));
            assertEquals(ComplianceException.Code.NOT_FOUND, e.code());
            assertTrue(started.isEmpty());
            c.rollback();
        }
    }
}

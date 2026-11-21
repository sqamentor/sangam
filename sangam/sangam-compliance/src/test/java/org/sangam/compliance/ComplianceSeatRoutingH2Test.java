// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance;

import org.sangam.core.api.ReadWork;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.compliance.breach.BreachIncidentService;
import org.sangam.compliance.breach.BreachNotificationPlanner;
import org.sangam.compliance.config.ComplianceConfig;
import org.sangam.compliance.jurisdiction.JurisdictionRegistry;
import org.sangam.compliance.ports.CompliancePorts.PrincipalAuthenticator;
import org.sangam.compliance.ports.CompliancePorts.WorkflowStarter;
import org.sangam.compliance.rights.DataSubjectRightsService;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;

/**
 * M-010c (SL-134): every compliance process is started with the seats of its roles bound as process variables
 * ({@code sangam.compliance.seats.<ROLE>} -> {@code <role>Seat}), and a start whose seat is not configured is refused
 * with {@code INVALID_STATE} before anything is written: no request, breach or incident row, no process started.
 * H2 in PostgreSQL mode; the workflow starter records what it would start.
 */
class ComplianceSeatRoutingH2Test {

    static final Dialect D = Dialect.POSTGRESQL;
    static final String TENANT = "t1";
    static final String PRINCIPAL = "person-1";
    static final JurisdictionRegistry PACKS = JurisdictionRegistry.discover();
    static final Map<String, String> ALL_SEATS = Map.of("seats.DPO", "SEAT-DPO", "seats.CISO", "SEAT-CISO",
            "seats.CISO-HEAD", "SEAT-HEAD", "seats.PRIVACY-OFFICER", "SEAT-PRIVACY", "seats.RECORDS-OFFICER", "SEAT-RECORDS",
            "seats.GRIEVANCE-OFFICER", "SEAT-GRIEVANCE");

    record Start(String processKey, String businessKey, Map<String, Object> vars) {
        Map<String, Object> seats() {
            Map<String, Object> m = new HashMap<>();
            vars.forEach((k, v) -> {
                if (k.endsWith("Seat")) {
                    m.put(k, v);
                }
            });
            return m;
        }
    }

    final List<Start> starts = new ArrayList<>();
    String url;
    Connection keepAlive;

    @BeforeEach
    void db() throws Exception {
        url = "jdbc:h2:mem:m010c" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        keepAlive = DriverManager.getConnection(url, "sa", "");
        Schema.create(keepAlive, D);
        ComplianceSchema.create(keepAlive, D);
    }

    @AfterEach
    void close() throws Exception {
        keepAlive.close();
    }

    Transactions newConnections() {
        return new Transactions() {
            @Override
            public <T> T inTransaction(Function<Connection, T> work) {
                try (Connection c = DriverManager.getConnection(url, "sa", "")) {
                    c.setAutoCommit(false);
                    T r = work.apply(c);
                    c.commit();
                    return r;
                } catch (java.sql.SQLException e) {
                    throw new IllegalStateException(e);
                }
            }

            @Override
            public <T> T read(ReadWork<T> work) {
                try (Connection c = DriverManager.getConnection(url, "sa", "")) {
                    c.setAutoCommit(false);
                    T r = work.read(c);
                    c.commit();
                    return r;
                } catch (java.sql.SQLException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }

    SangamCompliance compliance(String pack, Map<String, String> seats, boolean withEngine) {
        Map<String, String> props = new HashMap<>(seats);
        props.put("jurisdiction", pack);
        ComplianceConfig.Provider config = t -> ComplianceConfig.fromProperties(PACKS, props);
        PrincipalAuthenticator auth = (c, t, type, value, proof) -> Optional.of(PRINCIPAL);
        WorkflowStarter starter = withEngine ? (c, t, key, bk, vars) -> {
            starts.add(new Start(key, bk, new LinkedHashMap<>(vars)));
            return "pi-" + starts.size();
        } : null;
        SangamCompliance.Ports ports = new SangamCompliance.Ports(null, null, null, null, auth, null, null, null, null,
                starter, null, null, null, null, null, null, null);
        return new SangamCompliance(D, newConnections(), config, ports, null, org.sangam.compliance.PrincipalKey.of(new byte[32]));
    }

    Connection caller() throws Exception {
        Connection c = DriverManager.getConnection(url, "sa", "");
        c.setAutoCommit(false);
        return c;
    }

    static long count(Connection c, String table) {
        return Jdbc.one(c, "SELECT COUNT(*) FROM " + table + " WHERE tenant_id=?", rs -> rs.getLong(1), TENANT).orElse(0L);
    }

    static DataSubjectRightsService.Submission request(String right) {
        return new DataSubjectRightsService.Submission(PRINCIPAL, right, "EMAIL", "person-1@example.org", Map.of(), null,
                "PORTAL", Map.of());
    }

    /** One right of each rights process (pack, right code, process key, the seat variables it must carry). */
    record Case(String pack, String right, String processKey, Map<String, Object> seats) {
    }

    static final List<Case> RIGHTS = List.of(
            new Case("EU-GDPR", "ACCESS", "sangam-dsr-access", Map.of("dpoSeat", "SEAT-DPO")),
            new Case("EU-GDPR", "RECTIFICATION", "sangam-dsr-rectification", Map.of("dpoSeat", "SEAT-DPO")),
            new Case("EU-GDPR", "ERASURE", "sangam-dsr-erasure", Map.of("recordsOfficerSeat", "SEAT-RECORDS")),
            new Case("EU-GDPR", "RESTRICTION", "sangam-dsr-restriction", Map.of("privacyOfficerSeat", "SEAT-PRIVACY")),
            new Case("EU-GDPR", "OBJECTION", "sangam-dsr-objection", Map.of("dpoSeat", "SEAT-DPO")),
            new Case("EU-GDPR", "AUTOMATED_DECISION", "sangam-dsr-automated-decision", Map.of("dpoSeat", "SEAT-DPO")),
            new Case("US-HIPAA", "ACCOUNTING", "sangam-dsr-accounting", Map.of("privacyOfficerSeat", "SEAT-PRIVACY")),
            new Case("IN", "GRIEVANCE", "sangam-dsr-grievance",
                    Map.of("grievanceOfficerSeat", "SEAT-GRIEVANCE", "dpoSeat", "SEAT-DPO")),
            new Case("IN", "NOMINATION", "sangam-dsr-nomination", Map.of("dpoSeat", "SEAT-DPO")));

    @Test
    void eachRightsProcessStartsWithItsSeatsBound() throws Exception {
        for (Case k : RIGHTS) {
            starts.clear();
            SangamCompliance cmp = compliance(k.pack(), ALL_SEATS, true);
            try (Connection c = caller()) {
                DataSubjectRightsService.RightsRequest r = cmp.rights.submit(c, TENANT, request(k.right()));
                c.rollback();
                assertEquals(1, starts.size(), k.right());
                Start s = starts.get(0);
                assertEquals(k.processKey(), s.processKey(), k.right());
                assertEquals(r.requestId(), s.businessKey());
                assertEquals(k.seats(), s.seats(), k.pack() + " " + k.right() + ": exactly its seats");
            }
        }
    }

    @Test
    void aMissingSeatRefusesTheRequestBeforeAnythingIsWritten() throws Exception {
        Map<String, String> seats = new HashMap<>(ALL_SEATS);
        seats.remove("seats.RECORDS-OFFICER");
        SangamCompliance cmp = compliance("EU-GDPR", seats, true);
        try (Connection c = caller()) {
            ComplianceException e = assertThrows(ComplianceException.class, () -> cmp.rights.submit(c, TENANT, request("ERASURE")));
            assertEquals(ComplianceException.Code.INVALID_STATE, e.code());
            assertTrue(e.getMessage().contains("sangam.compliance.seats.RECORDS-OFFICER"), e.getMessage());
            assertEquals(0, count(c, "CMP_RIGHTS_REQUEST"), "no row, even inside the refused transaction");
            c.rollback();
        }
        assertTrue(starts.isEmpty(), "no process started");
        // the same tenant's DPO processes still start: only the process that needs the missing seat is refused
        try (Connection c = caller()) {
            cmp.rights.submit(c, TENANT, request("ACCESS"));
            c.rollback();
        }
        assertEquals(List.of("sangam-dsr-access"), starts.stream().map(Start::processKey).toList());
    }

    @Test
    void withoutAWorkflowEngineNoSeatIsNeeded() throws Exception {
        SangamCompliance cmp = compliance("EU-GDPR", Map.of(), false);
        try (Connection c = caller()) {
            assertNotNull(cmp.rights.submit(c, TENANT, request("ERASURE")).requestId(), "no process, no task to route");
            c.rollback();
        }
    }

    @Test
    void aBreachStartsItsProcessAndTheLinkedIncidentReportWithTheirSeats() throws Exception {
        SangamCompliance cmp = compliance("IN", ALL_SEATS, true);
        try (Connection c = caller()) {
            cmp.breaches.register(c, TENANT, Map.of("description", "test breach"), null, null, null, null, null,
                    List.of("portal"), "officer-1");
            c.rollback();
        }
        Map<String, Map<String, Object>> byKey = new HashMap<>();
        starts.forEach(s -> byKey.put(s.processKey(), s.seats()));
        assertEquals(Map.of("dpoSeat", "SEAT-DPO", "cisoSeat", "SEAT-CISO"), byKey.get("sangam-breach-incident"));
        assertEquals(Map.of("cisoSeat", "SEAT-CISO", "cisoHeadSeat", "SEAT-HEAD"), byKey.get("sangam-incident-report"),
                "IN links a CERT-In incident report to every breach");
        assertFalse(byKey.containsKey(BreachNotificationPlanner.PROCESS_KEY),
                "IN defines no breach notification rules or risk factors: no plan process");
    }

    /** Owner decision (a): a breach registration starts the notification plan process once, with its seat bound. */
    @Test
    void aBreachRegistrationStartsTheNotificationPlanOnceWithItsSeat() throws Exception {
        SangamCompliance cmp = compliance("EU-GDPR", ALL_SEATS, true);
        BreachIncidentService.Breach b;
        try (Connection c = caller()) {
            b = cmp.breaches.register(c, TENANT, Map.of("description", "test breach"), null, null, null, null, null,
                    List.of("portal"), "officer-1");
            c.rollback();
        }
        List<Start> plans = starts.stream().filter(s -> s.processKey().equals(BreachNotificationPlanner.PROCESS_KEY)).toList();
        assertEquals(1, plans.size(), "started exactly once: " + starts);
        assertEquals(b.breachId(), plans.get(0).businessKey(), "keyed by the breach id");
        assertEquals(b.breachId(), plans.get(0).vars().get("breachId"));
        assertEquals(Map.of("privacyOfficerSeat", "SEAT-PRIVACY"), plans.get(0).seats());
        assertEquals(1, starts.stream().filter(s -> s.processKey().equals("sangam-breach-incident")).count());
    }

    @Test
    void aMissingPrivacyOfficerSeatRefusesTheBreachBeforeAnythingIsWritten() throws Exception {
        Map<String, String> seats = new HashMap<>(ALL_SEATS);
        seats.remove("seats.PRIVACY-OFFICER");
        SangamCompliance cmp = compliance("EU-GDPR", seats, true);
        try (Connection c = caller()) {
            ComplianceException e = assertThrows(ComplianceException.class, () -> cmp.breaches.register(c, TENANT,
                    Map.of("description", "test breach"), null, null, null, null, null, List.of("portal"), "officer-1"));
            assertEquals(ComplianceException.Code.INVALID_STATE, e.code());
            assertTrue(e.getMessage().contains("sangam.compliance.seats.PRIVACY-OFFICER"), e.getMessage());
            assertEquals(0, count(c, "CMP_BREACH"));
            c.rollback();
        }
        assertTrue(starts.isEmpty(), "no breach process and no plan process started");
    }

    /** A jurisdiction without a notification plan (IN) does not need the PRIVACY-OFFICER seat to register a breach. */
    @Test
    void withoutAPlanInTheJurisdictionNoPrivacyOfficerSeatIsNeeded() throws Exception {
        Map<String, String> seats = new HashMap<>(ALL_SEATS);
        seats.remove("seats.PRIVACY-OFFICER");
        SangamCompliance cmp = compliance("IN", seats, true);
        try (Connection c = caller()) {
            cmp.breaches.register(c, TENANT, Map.of("description", "test breach"), null, null, null, null, null,
                    List.of("portal"), "officer-1");
            c.rollback();
        }
        assertTrue(starts.stream().noneMatch(s -> s.processKey().equals(BreachNotificationPlanner.PROCESS_KEY)));
    }

    @Test
    void aMissingIncidentSeatRefusesTheBreachBeforeAnythingIsWritten() throws Exception {
        Map<String, String> seats = new HashMap<>(ALL_SEATS);
        seats.remove("seats.CISO-HEAD");
        SangamCompliance cmp = compliance("IN", seats, true);
        try (Connection c = caller()) {
            ComplianceException e = assertThrows(ComplianceException.class, () -> cmp.breaches.register(c, TENANT,
                    Map.of("description", "test breach"), null, null, null, null, null, List.of("portal"), "officer-1"));
            assertEquals(ComplianceException.Code.INVALID_STATE, e.code());
            assertTrue(e.getMessage().contains("sangam.compliance.seats.CISO-HEAD"), e.getMessage());
            assertEquals(0, count(c, "CMP_BREACH"));
            assertEquals(0, count(c, "CMP_INCIDENT"));
            c.rollback();
        }
        assertTrue(starts.isEmpty(), "no process started");
    }

    @Test
    void anIncidentWithoutItsSeatIsRefusedBeforeAnythingIsWritten() throws Exception {
        Map<String, String> seats = new HashMap<>(ALL_SEATS);
        seats.remove("seats.CISO");
        SangamCompliance cmp = compliance("IN", seats, true);
        try (Connection c = caller()) {
            ComplianceException e = assertThrows(ComplianceException.class, () -> cmp.incidents.register(c, TENANT, "CERT-IN",
                    "xi", null, null, List.of("portal"), Map.of(), "test incident", null, "officer-1"));
            assertEquals(ComplianceException.Code.INVALID_STATE, e.code());
            assertTrue(e.getMessage().contains("sangam.compliance.seats.CISO"), e.getMessage());
            assertEquals(0, count(c, "CMP_INCIDENT"));
            c.rollback();
        }
        assertTrue(starts.isEmpty(), "no process started");
    }
}

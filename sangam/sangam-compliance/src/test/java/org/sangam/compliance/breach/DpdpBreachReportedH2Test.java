// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.breach;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.compliance.ComplianceAudit;
import org.sangam.compliance.ComplianceException;
import org.sangam.compliance.ComplianceSchema;
import org.sangam.compliance.SangamCompliance;
import org.sangam.compliance.config.ComplianceConfig;
import org.sangam.compliance.jurisdiction.JurisdictionRegistry;
import org.sangam.compliance.ports.CompliancePorts.IncidentAuthorityGateway;
import org.sangam.compliance.ports.CompliancePorts.SupervisoryAuthorityGateway;
import org.sangam.compliance.support.Sql;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;

/**
 * Owner decision 308 (SANGAM.md OJ-06 step 7): {@link BreachIncidentService#submitDetailedReport} writes
 * {@code DPDP_BREACH_REPORTED} on the organisation chain, in the same transaction as the report, only when the tenant's
 * jurisdiction pack is IN (DPDP s.8(6), r.7); the EU-GDPR and US-HIPAA packs keep only the generic
 * {@code CMP_BREACH_DETAILED_REPORT}. A refused report writes neither. H2 in PostgreSQL mode.
 */
class DpdpBreachReportedH2Test {

    static final Dialect D = Dialect.POSTGRESQL;
    static final String TENANT = "t1";
    static final String CHAIN = ComplianceAudit.tenantChain(TENANT);
    static final JurisdictionRegistry PACKS = JurisdictionRegistry.discover();

    /** The initial items of all three packs, so one map serves every registration. */
    static final Map<String, String> FACTS = Map.ofEntries(Map.entry("description", "portal database exposed"), Map.entry("nature", "confidentiality"),
            Map.entry("extent", "120 records"), Map.entry("timing", "2026-01-05"), Map.entry("location", "state data centre"),
            Map.entry("likelyImpact", "identity misuse"), Map.entry("contact", "dpo@example.gov.in"), Map.entry("mitigation", "access revoked"),
            Map.entry("safeguards", "encryption at rest"));

    /** The IN pack's detailed items (r.7(2)(b)). */
    static final Map<String, String> REPORT = Map.of("updatedDescription", "full scope known", "factsAndReasons", "misconfigured bucket",
            "mitigation", "bucket closed", "findingsOnCause", "no external actor identified", "remedialMeasures", "configuration review");

    String url;
    Connection keepAlive;

    @BeforeEach
    void db() throws Exception {
        url = "jdbc:h2:mem:d308dpdp" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        keepAlive = DriverManager.getConnection(url, "sa", "");
        Schema.create(keepAlive, D);
        ComplianceSchema.create(keepAlive, D);
    }

    @AfterEach
    void close() throws Exception {
        keepAlive.close();
    }

    SangamCompliance compliance(String jurisdiction) {
        Map<String, String> props = new HashMap<>();
        props.put("jurisdiction", jurisdiction);
        props.put("organisation-name", "Test Department");
        ComplianceConfig.Provider config = t -> ComplianceConfig.fromProperties(PACKS, props);
        SupervisoryAuthorityGateway authority = s -> "ACK-" + s.kind() + "-" + s.caseId();
        IncidentAuthorityGateway incidents = s -> "INC-" + s.incidentId();
        Transactions tx = new Transactions() {
            @Override
            public <T> T inTransaction(Function<Connection, T> work) {
                throw new AssertionError("not used by these calls");
            }
        };
        // no workflow starter: no process is started, so no seat is needed
        SangamCompliance.Ports ports = new SangamCompliance.Ports(null, null, null, null, null, null, null, null, null,
                null, authority, incidents, null, null, null, null, null);
        return new SangamCompliance(D, tx, config, ports);
    }

    /** A registered breach with two affected principals, the authority already notified. */
    static String notifiedBreach(SangamCompliance cmp, Connection c) {
        String id = cmp.breaches.register(c, TENANT, new LinkedHashMap<>(FACTS), null, null, Set.of("p-1", "p-2"), null, null,
                List.of("portal"), "dpo-1").breachId();
        cmp.breaches.notifyAuthority(c, TENANT, id, "dpo-1");
        return id;
    }

    static List<Map<String, Object>> payloads(Connection c, String eventType) {
        return Jdbc.query(c, "SELECT p.payload FROM AUDIT_PAYLOAD p JOIN AUDIT_EVENT e ON e.event_id=p.event_id"
                + " WHERE e.tenant_id=? AND e.chain_id=? AND e.event_type=? ORDER BY e.seq", rs -> Sql.map(rs.getString(1)), TENANT, CHAIN, eventType);
    }

    @Test
    void theInPackWritesTheDpdpBoardReportExactlyOnce() throws Exception {
        SangamCompliance cmp = compliance("IN");
        try (Connection c = DriverManager.getConnection(url, "sa", "")) {
            c.setAutoCommit(false);
            String id = notifiedBreach(cmp, c);
            String ref = cmp.breaches.submitDetailedReport(c, TENANT, id, REPORT, "dpo-1");

            List<Map<String, Object>> dpdp = payloads(c, ComplianceAudit.DPDP_BREACH_REPORTED);
            assertEquals(1, dpdp.size(), "exactly one DPDP Board report event");
            Map<String, Object> p = dpdp.get(0);
            assertEquals(id, p.get("breach"));
            assertEquals("DPB", p.get("authority"));
            assertEquals(ref, p.get("ref"));
            assertEquals(2L, ((Number) p.get("principalsAffected")).longValue());
            assertEquals("dpo-1", p.get("actor"));
            assertEquals(1, payloads(c, ComplianceAudit.BREACH_REPORT_SUBMITTED).size(), "the generic event stays");

            // a second report is refused and writes nothing more
            assertThrows(ComplianceException.class, () -> cmp.breaches.submitDetailedReport(c, TENANT, id, REPORT, "dpo-1"));
            assertEquals(1, payloads(c, ComplianceAudit.DPDP_BREACH_REPORTED).size());

            c.rollback();
            assertEquals(0, payloads(c, ComplianceAudit.DPDP_BREACH_REPORTED).size(), "the event rolls back with the report");
        }
    }

    @Test
    void theEuAndUsPacksKeepOnlyTheGenericEvent() throws Exception {
        for (String jurisdiction : List.of("EU-GDPR", "US-HIPAA")) {
            SangamCompliance cmp = compliance(jurisdiction);
            try (Connection c = DriverManager.getConnection(url, "sa", "")) {
                c.setAutoCommit(false);
                String id = notifiedBreach(cmp, c);
                cmp.breaches.submitDetailedReport(c, TENANT, id, Map.of(), "dpo-1");
                assertEquals(1, payloads(c, ComplianceAudit.BREACH_REPORT_SUBMITTED).size(), jurisdiction + ": generic event");
                assertEquals(0, payloads(c, ComplianceAudit.DPDP_BREACH_REPORTED).size(), jurisdiction + ": no DPDP event");
                c.rollback();
            }
        }
    }

    @Test
    void aRefusedReportWritesNothing() throws Exception {
        SangamCompliance cmp = compliance("IN");
        try (Connection c = DriverManager.getConnection(url, "sa", "")) {
            c.setAutoCommit(false);
            // before the authority was notified
            String early = cmp.breaches.register(c, TENANT, new LinkedHashMap<>(FACTS), null, null, Set.of("p-1"), null, null,
                    List.of("portal"), "dpo-1").breachId();
            ComplianceException e1 = assertThrows(ComplianceException.class,
                    () -> cmp.breaches.submitDetailedReport(c, TENANT, early, REPORT, "dpo-1"));
            assertEquals(ComplianceException.Code.INVALID_STATE, e1.code());

            // a required r.7(2)(b) item missing
            String id = notifiedBreach(cmp, c);
            Map<String, String> incomplete = new HashMap<>(REPORT);
            incomplete.remove("remedialMeasures");
            ComplianceException e2 = assertThrows(ComplianceException.class,
                    () -> cmp.breaches.submitDetailedReport(c, TENANT, id, incomplete, "dpo-1"));
            assertEquals(ComplianceException.Code.REPORT_INCOMPLETE, e2.code());

            assertEquals(0, payloads(c, ComplianceAudit.DPDP_BREACH_REPORTED).size());
            assertEquals(0, payloads(c, ComplianceAudit.BREACH_REPORT_SUBMITTED).size());
            c.rollback();
        }
    }
}

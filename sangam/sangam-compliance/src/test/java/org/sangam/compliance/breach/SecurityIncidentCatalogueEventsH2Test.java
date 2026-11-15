// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.breach;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.compliance.ComplianceAudit;
import org.sangam.compliance.ComplianceException;
import org.sangam.compliance.ComplianceSchema;
import org.sangam.compliance.SangamCompliance;
import org.sangam.compliance.accountability.ContactDirectory;
import org.sangam.compliance.config.ComplianceConfig;
import org.sangam.compliance.jurisdiction.JurisdictionRegistry;
import org.sangam.compliance.ports.CompliancePorts.IncidentAuthorityGateway;
import org.sangam.compliance.support.Sql;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;

/**
 * Owner decision 299: the X.4.0.3 Security names of the incident journey (SANGAM.md OJ-06) are written by
 * {@link IncidentReportingService} on the organisation chain {@code cmp:<tenant>}, in the same transaction as the
 * incident change: {@code SEC_INCIDENT_OPENED} and {@code SEC_INCIDENT_CLASSIFIED} at registration,
 * {@code SEC_REPORT_SUBMITTED} at submission, {@code SEC_INCIDENT_CLOSED} when a reported incident is closed. A refused
 * step writes none of them, and a rollback removes them with the change. H2 in PostgreSQL mode, IN pack (CERT-In,
 * Annexure I).
 */
class SecurityIncidentCatalogueEventsH2Test {

    static final Dialect D = Dialect.POSTGRESQL;
    static final String TENANT = "t1";
    static final String CHAIN = ComplianceAudit.tenantChain(TENANT);
    static final JurisdictionRegistry PACKS = JurisdictionRegistry.discover();
    /** Well in the past, so a submission is always after the 6 hour deadline. */
    static final Instant NOTICED = Instant.parse("2026-01-05T04:00:00Z");
    static final String ROOT_CAUSE = "unpatched web framework on the portal";
    static final String LESSONS = "monthly patch window; WAF rule review";

    String url;
    Connection keepAlive;

    @BeforeEach
    void db() throws Exception {
        url = "jdbc:h2:mem:d299sec" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        keepAlive = DriverManager.getConnection(url, "sa", "");
        Schema.create(keepAlive, D);
        ComplianceSchema.create(keepAlive, D);
    }

    @AfterEach
    void close() throws Exception {
        keepAlive.close();
    }

    SangamCompliance compliance() {
        Map<String, String> props = new HashMap<>();
        props.put("jurisdiction", "IN");
        props.put("organisation-name", "Test Department");
        ComplianceConfig.Provider config = t -> ComplianceConfig.fromProperties(PACKS, props);
        IncidentAuthorityGateway gateway = s -> "ACK-" + s.incidentId() + "-" + s.recipient();
        Transactions tx = new Transactions() {
            @Override
            public <T> T inTransaction(Function<Connection, T> work) {
                throw new AssertionError("not used by these calls");
            }
        };
        // no workflow starter: no process is started, so no seat is needed
        SangamCompliance.Ports ports = new SangamCompliance.Ports(null, null, null, null, null, null, null, null, null,
                null, null, gateway, null, null, null, null, null);
        return new SangamCompliance(D, tx, config, ports, null, org.sangam.compliance.PrincipalKey.of(new byte[32]));
    }

    static void pointOfContact(SangamCompliance cmp, Connection c) {
        cmp.contacts.set(c, TENANT, new ContactDirectory.Contact(IncidentReportingService.pocRole("CERT-IN"), "PoC Officer", "Deputy CISO",
                "Test Department", "poc@example.gov.in", "0110000000", "9000000000", "Block A, New Delhi", true, null), "admin-1");
    }

    /** The event types written on the organisation chain, in chain order. */
    static List<String> events(Connection c) {
        return Jdbc.query(c, "SELECT event_type FROM AUDIT_EVENT WHERE tenant_id=? AND chain_id=? ORDER BY seq", rs -> rs.getString(1), TENANT, CHAIN);
    }

    static List<Map<String, Object>> payloads(Connection c, String eventType) {
        return Jdbc.query(c, "SELECT p.payload FROM AUDIT_PAYLOAD p JOIN AUDIT_EVENT e ON e.event_id=p.event_id"
                + " WHERE e.tenant_id=? AND e.chain_id=? AND e.event_type=? ORDER BY e.seq", rs -> Sql.map(rs.getString(1)), TENANT, CHAIN, eventType);
    }

    static long count(Connection c, String eventType) {
        return payloads(c, eventType).size();
    }

    @Test
    void registrationOpensAndClassifiesTheIncidentInItsTransaction() throws Exception {
        SangamCompliance cmp = compliance();
        try (Connection c = DriverManager.getConnection(url, "sa", "")) {
            c.setAutoCommit(false);
            IncidentReportingService.Incident i = cmp.incidents.register(c, TENANT, "CERT-IN", "x", null, NOTICED, List.of("portal"),
                    Map.of(), "web application attack", null, "ciso-1");

            List<String> types = events(c);
            int opened = types.indexOf(ComplianceAudit.SEC_INCIDENT_OPENED);
            assertEquals(List.of(ComplianceAudit.SEC_INCIDENT_OPENED, ComplianceAudit.SEC_INCIDENT_CLASSIFIED, ComplianceAudit.INCIDENT_REGISTERED),
                    types.subList(opened, opened + 3), "opened, classified, then the specific registration event: " + types);

            Map<String, Object> open = payloads(c, ComplianceAudit.SEC_INCIDENT_OPENED).get(0);
            assertEquals(i.incidentId(), open.get("incident"));
            assertEquals("CERT-IN", open.get("authority"));
            assertEquals(NOTICED.toString(), open.get("noticedAt"), "noticed_at is fixed in the event");
            assertEquals(NOTICED.plusSeconds(6 * 3600).toString(), open.get("dueAt"), "CERT-In 6 hour clock");
            assertEquals("ciso-1", open.get("actor"));

            Map<String, Object> cls = payloads(c, ComplianceAudit.SEC_INCIDENT_CLASSIFIED).get(0);
            assertEquals(i.incidentId(), cls.get("incident"));
            assertEquals("x", cls.get("type"), "Annexure I type code");
            assertEquals("Attacks on Application such as E-Governance, E-Commerce etc.", cls.get("label"));
            assertEquals(Boolean.FALSE, cls.get("personalData"), "no breach link: no personal data marked");
            assertNull(cls.get("breach"));

            c.rollback();
            assertEquals(0, count(c, ComplianceAudit.SEC_INCIDENT_OPENED), "the events roll back with the incident row");
            assertEquals(0, count(c, ComplianceAudit.SEC_INCIDENT_CLASSIFIED));
        }
    }

    @Test
    void anIncidentLinkedToABreachIsClassifiedAsInvolvingPersonalData() throws Exception {
        SangamCompliance cmp = compliance();
        try (Connection c = DriverManager.getConnection(url, "sa", "")) {
            c.setAutoCommit(false);
            cmp.incidents.register(c, TENANT, "CERT-IN", "xi", null, NOTICED, List.of("portal"), Map.of(), "data breach", "breach-1", "ciso-1");
            Map<String, Object> cls = payloads(c, ComplianceAudit.SEC_INCIDENT_CLASSIFIED).get(0);
            assertEquals("xi", cls.get("type"));
            assertEquals("Data Breach", cls.get("label"));
            assertEquals(Boolean.TRUE, cls.get("personalDataLikely"), "catalogue flag of type xi");
            assertEquals(Boolean.TRUE, cls.get("personalData"));
            assertEquals("breach-1", cls.get("breach"));
            assertEquals("breach-1", payloads(c, ComplianceAudit.SEC_INCIDENT_OPENED).get(0).get("breach"));
            c.rollback();
        }
    }

    @Test
    void aTypeOutsideTheCatalogueIsRefusedAndWritesNoCatalogueEvent() throws Exception {
        SangamCompliance cmp = compliance();
        try (Connection c = DriverManager.getConnection(url, "sa", "")) {
            c.setAutoCommit(false);
            ComplianceException e = assertThrows(ComplianceException.class, () -> cmp.incidents.register(c, TENANT, "CERT-IN", "no-such-type",
                    null, NOTICED, List.of(), Map.of(), "x", null, "ciso-1"));
            // owner decision 344: refused by name (it was INVALID_INPUT before)
            assertEquals(ComplianceException.Code.INCIDENT_TYPE_NOT_IN_CATALOGUE, e.code());
            assertEquals(0, count(c, ComplianceAudit.SEC_INCIDENT_OPENED));
            assertEquals(0, count(c, ComplianceAudit.SEC_INCIDENT_CLASSIFIED));
            c.rollback();
        }
    }

    @Test
    void submissionRecordsTheReferencesAndClosureIsWrittenOnceForAReportedIncident() throws Exception {
        SangamCompliance cmp = compliance();
        try (Connection c = DriverManager.getConnection(url, "sa", "")) {
            c.setAutoCommit(false);
            pointOfContact(cmp, c);
            String id = cmp.incidents.register(c, TENANT, "CERT-IN", "x", null, NOTICED, List.of("portal"), Map.of(), "attack", null, "ciso-1")
                    .incidentId();

            // closing an incident that is not reported changes nothing and writes nothing
            cmp.incidents.close(c, TENANT, id, ROOT_CAUSE, LESSONS, "ciso-1");
            assertEquals(IncidentReportingService.Status.OPEN, cmp.incidents.get(c, TENANT, id).status());
            assertEquals(0, count(c, ComplianceAudit.SEC_INCIDENT_CLOSED));

            List<String> refs = cmp.incidents.submit(c, TENANT, id, "isolated the server", "poc-1");
            List<Map<String, Object>> submitted = payloads(c, ComplianceAudit.SEC_REPORT_SUBMITTED);
            assertEquals(1, submitted.size());
            Map<String, Object> s = submitted.get(0);
            assertEquals(id, s.get("incident"));
            assertEquals("CERT-IN", s.get("authority"));
            assertEquals(refs, s.get("references"), "the submission references are recorded");
            assertEquals(Boolean.TRUE, s.get("late"), "submitted after noticed_at + 6 h");
            assertEquals("poc-1", s.get("actor"));
            List<String> types = events(c);
            assertEquals(ComplianceAudit.INCIDENT_REPORTED, types.get(types.indexOf(ComplianceAudit.SEC_REPORT_SUBMITTED) + 1),
                    "the catalogue event sits next to the specific event of the same change: " + types);

            cmp.incidents.close(c, TENANT, id, ROOT_CAUSE, LESSONS, "ciso-1");
            assertEquals(IncidentReportingService.Status.CLOSED, cmp.incidents.get(c, TENANT, id).status());
            List<Map<String, Object>> closed = payloads(c, ComplianceAudit.SEC_INCIDENT_CLOSED);
            assertEquals(1, closed.size());
            assertEquals(id, closed.get(0).get("incident"));
            assertEquals(ROOT_CAUSE, closed.get(0).get("rootCause"), "OJ-06 step 8: the root cause is in the CLOSED event");
            assertEquals(LESSONS, closed.get(0).get("lessonsLearned"), "and so are the lessons");
            assertEquals("ciso-1", closed.get(0).get("actor"));
            assertEquals(List.of(ROOT_CAUSE, LESSONS, "ciso-1"), Jdbc.one(c, "SELECT root_cause, lessons_learned, closed_by FROM CMP_INCIDENT"
                    + " WHERE tenant_id=? AND incident_id=?", rs -> List.of(rs.getString(1), rs.getString(2), rs.getString(3)), TENANT, id)
                    .orElseThrow(), "stored on the incident");

            // a repeated close changes nothing and writes nothing
            cmp.incidents.close(c, TENANT, id, ROOT_CAUSE, LESSONS, "ciso-1");
            assertEquals(1, count(c, ComplianceAudit.SEC_INCIDENT_CLOSED));

            c.rollback();
            assertEquals(0, count(c, ComplianceAudit.SEC_REPORT_SUBMITTED), "the events roll back with the incident change");
            assertEquals(0, count(c, ComplianceAudit.SEC_INCIDENT_CLOSED));
        }
    }

    /** Coordinator ruling on OJ-06 step 8: a close without the root cause or the lessons is refused and writes nothing. */
    @Test
    void aCloseWithoutRootCauseOrLessonsIsRefusedAndWritesNothing() throws Exception {
        SangamCompliance cmp = compliance();
        try (Connection c = DriverManager.getConnection(url, "sa", "")) {
            c.setAutoCommit(false);
            pointOfContact(cmp, c);
            String id = cmp.incidents.register(c, TENANT, "CERT-IN", "x", null, NOTICED, List.of("portal"), Map.of(), "attack", null, "ciso-1")
                    .incidentId();
            cmp.incidents.submit(c, TENANT, id, "isolated the server", "poc-1");

            String[][] incomplete = {{null, LESSONS}, {"  ", LESSONS}, {ROOT_CAUSE, null}, {ROOT_CAUSE, ""}, {null, null}};
            for (String[] in : incomplete) {
                ComplianceException e = assertThrows(ComplianceException.class,
                        () -> cmp.incidents.close(c, TENANT, id, in[0], in[1], "ciso-1"));
                assertEquals(ComplianceException.Code.INCIDENT_CLOSURE_INCOMPLETE, e.code());
                assertEquals(422, e.code().httpStatus());
                if (in[0] == null || in[0].isBlank()) {
                    assertTrue(e.getMessage().contains("rootCause"), e.getMessage());
                }
                if (in[1] == null || in[1].isBlank()) {
                    assertTrue(e.getMessage().contains("lessonsLearned"), e.getMessage());
                }
            }
            assertEquals(IncidentReportingService.Status.REPORTED, cmp.incidents.get(c, TENANT, id).status(), "still reported");
            assertEquals(0, count(c, ComplianceAudit.SEC_INCIDENT_CLOSED));
            assertEquals(List.of(), Jdbc.query(c, "SELECT incident_id FROM CMP_INCIDENT WHERE tenant_id=? AND (root_cause IS NOT NULL"
                    + " OR lessons_learned IS NOT NULL OR closed_at IS NOT NULL)", rs -> rs.getString(1), TENANT), "nothing stored");
            c.rollback();
        }
    }

    @Test
    void aRefusedSubmissionWritesNoReportEvent() throws Exception {
        SangamCompliance cmp = compliance();
        try (Connection c = DriverManager.getConnection(url, "sa", "")) {
            c.setAutoCommit(false);
            // no CERT-In point of contact designated: the pack's form needs one
            String id = cmp.incidents.register(c, TENANT, "CERT-IN", "x", null, NOTICED, List.of("portal"), Map.of(), "attack", null, "ciso-1")
                    .incidentId();
            ComplianceException e = assertThrows(ComplianceException.class, () -> cmp.incidents.submit(c, TENANT, id, null, "poc-1"));
            assertEquals(ComplianceException.Code.REPORT_INCOMPLETE, e.code());
            assertEquals(0, count(c, ComplianceAudit.SEC_REPORT_SUBMITTED));
            c.rollback();
        }
    }
}

// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.breach;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.compliance.ComplianceException;
import org.sangam.compliance.ComplianceSchema;
import org.sangam.compliance.SangamCompliance;
import org.sangam.compliance.config.ComplianceConfig;
import org.sangam.compliance.jurisdiction.JurisdictionPack;
import org.sangam.compliance.jurisdiction.JurisdictionRegistry;
import org.sangam.compliance.ports.CompliancePorts;
import org.sangam.compliance.ports.CompliancePorts.IncidentAuthorityGateway;
import org.sangam.compliance.ports.CompliancePorts.SupervisoryAuthorityGateway;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;

/**
 * Owner decision 347: every notice {@link BreachIncidentService#notifySubjects} sends to an affected data subject is
 * also handed, inside the sending transaction, to the subject-notice listeners with the pack's subject items as
 * structured items (IN: DPDP Rules r.7(1)(a)-(e), in pack order, with the pack's label and citation), so the citizen
 * portal can show it in the principal's account. A refused notice (an item missing) reaches no listener; a listener
 * that fails rolls the delivery back. H2 in PostgreSQL mode.
 */
class BreachSubjectNoticeListenerH2Test {

    static final Dialect D = Dialect.POSTGRESQL;
    static final String TENANT = "t1";
    static final JurisdictionRegistry PACKS = JurisdictionRegistry.discover();

    static final Map<String, String> FACTS = Map.ofEntries(Map.entry("description", "portal database exposed"),
            Map.entry("nature", "confidentiality"), Map.entry("extent", "120 records"), Map.entry("timing", "2026-01-05"),
            Map.entry("location", "state data centre"), Map.entry("likelyImpact", "identity misuse"),
            Map.entry("contact", "dpo@example.gov.in"), Map.entry("mitigation", "access revoked"),
            Map.entry("safeguards", "encryption at rest"));

    /** The IN pack's five r.7(1) items. */
    static final Map<String, String> NOTICE = Map.of("description", "exposed on 5 January at the state data centre",
            "consequences", "your name and address may be misused", "mitigation", "access was revoked",
            "safetySteps", "watch for messages that ask for your details", "contact", "dpo@example.gov.in");

    record Sent(String breachId, String principal, String deliveryRef, String subject, String language,
                List<BreachIncidentService.SubjectNoticeItem> items) {
    }

    String url;
    Connection keepAlive;

    @BeforeEach
    void db() throws Exception {
        url = "jdbc:h2:mem:d347notice" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        keepAlive = DriverManager.getConnection(url, "sa", "");
        Schema.create(keepAlive, D);
        ComplianceSchema.create(keepAlive, D);
    }

    @AfterEach
    void close() throws Exception {
        keepAlive.close();
    }

    static SangamCompliance compliance() {
        Map<String, String> props = new HashMap<>();
        props.put("jurisdiction", "IN");
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
        CompliancePorts.PrincipalNotifier outbox = (c, m) -> "DLV-" + m.principalRef();
        SangamCompliance.Ports ports = new SangamCompliance.Ports(null, null, outbox, null, null, null, null, null, null,
                null, authority, incidents, null, null, null, null, null);
        return new SangamCompliance(D, tx, config, ports);
    }

    static String breach(SangamCompliance cmp, Connection c) {
        return cmp.breaches.register(c, TENANT, new LinkedHashMap<>(FACTS), null, null, Set.of("p-1", "p-2"), null, null,
                List.of("portal"), "dpo-1").breachId();
    }

    static long sentCount(Connection c, String breachId) {
        return Jdbc.one(c, "SELECT COUNT(*) FROM CMP_BREACH_NOTICE WHERE tenant_id=? AND breach_id=? AND sent_at IS NOT NULL",
                rs -> rs.getLong(1), TENANT, breachId).orElseThrow();
    }

    @Test
    void eachSentNoticeReachesTheListenerWithTheFiveItemsInPackOrder() throws Exception {
        SangamCompliance cmp = compliance();
        List<Sent> sent = new ArrayList<>();
        cmp.breaches.addSubjectNoticeListener((c, tenant, breachId, principal, ref, subject, language, items) -> {
            assertEquals(TENANT, tenant);
            sent.add(new Sent(breachId, principal, ref, subject, language.code(), items));
        });
        JurisdictionPack pack = PACKS.require("IN");
        try (Connection c = DriverManager.getConnection(url, "sa", "")) {
            c.setAutoCommit(false);
            String id = breach(cmp, c);
            assertEquals(2, cmp.breaches.notifySubjects(c, TENANT, id, NOTICE, "dpo-1"));

            assertEquals(List.of("p-1", "p-2"), sent.stream().map(Sent::principal).toList());
            List<JurisdictionPack.ReportItem> packItems = pack.breach().subjectItems();
            assertEquals(List.of("description", "consequences", "mitigation", "safetySteps", "contact"),
                    packItems.stream().map(JurisdictionPack.ReportItem::key).toList(), "IN pack r.7(1) items");
            for (Sent s : sent) {
                assertEquals(id, s.breachId());
                assertEquals("DLV-" + s.principal(), s.deliveryRef());
                // the subject the delivery carried: IN notice_en.properties breach.subject
                assertEquals("Personal data breach affecting you", s.subject());
                assertEquals(5, s.items().size());
                for (int i = 0; i < packItems.size(); i++) {
                    JurisdictionPack.ReportItem pi = packItems.get(i);
                    BreachIncidentService.SubjectNoticeItem item = s.items().get(i);
                    assertEquals(pi.key(), item.key());
                    assertEquals(pi.label(), item.label());
                    assertEquals(pi.citation(), item.citation());
                    assertEquals(NOTICE.get(pi.key()), item.text());
                }
                assertTrue(s.items().get(0).citation().startsWith("r.7(1)(a)"), s.items().get(0).citation());
            }
            // a second run sends nothing more: every subject was notified
            assertEquals(0, cmp.breaches.notifySubjects(c, TENANT, id, NOTICE, "dpo-1"));
            assertEquals(2, sent.size());
            c.rollback();
        }
    }

    @Test
    void aRefusedNoticeReachesNoListenerAndAFailingListenerRollsTheDeliveryBack() throws Exception {
        SangamCompliance cmp = compliance();
        List<String> seen = new ArrayList<>();
        cmp.breaches.addSubjectNoticeListener((c, tenant, breachId, principal, ref, subject, language, items) -> {
            seen.add(principal);
            throw new IllegalStateException("portal inbox unavailable");
        });
        try (Connection c = DriverManager.getConnection(url, "sa", "")) {
            c.setAutoCommit(false);
            String id = breach(cmp, c);
            c.commit();

            Map<String, String> incomplete = new HashMap<>(NOTICE);
            incomplete.remove("safetySteps");
            ComplianceException e = assertThrows(ComplianceException.class,
                    () -> cmp.breaches.notifySubjects(c, TENANT, id, incomplete, "dpo-1"));
            assertEquals(ComplianceException.Code.REPORT_INCOMPLETE, e.code());
            assertEquals(List.of(), seen, "a refused notice reaches no listener");

            assertThrows(IllegalStateException.class, () -> cmp.breaches.notifySubjects(c, TENANT, id, NOTICE, "dpo-1"));
            assertEquals(List.of("p-1"), seen);
            c.rollback();
            assertEquals(0, sentCount(c, id), "the delivery rolls back with the failing listener");
        }
    }
}

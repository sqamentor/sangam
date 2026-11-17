// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.compliance;

import org.sangam.core.api.ReadWork;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.compliance.ComplianceException;
import org.sangam.compliance.ComplianceSchema;
import org.sangam.compliance.SangamCompliance;
import org.sangam.compliance.accountability.ContactDirectory;
import org.sangam.compliance.config.ComplianceConfig;
import org.sangam.compliance.consent.ChildSafeguards.ProcessingFlag;
import org.sangam.compliance.jurisdiction.JurisdictionPack;
import org.sangam.compliance.jurisdiction.JurisdictionRegistry;
import org.sangam.compliance.lawful.PurposeDefinition;
import org.sangam.compliance.ports.CompliancePorts;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;
import org.sangam.rest.config.SpringTxSupport;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.PortalCompliancePort;
import org.sangam.rest.port.PortalPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.DocumentStore;
import org.sangam.rest.support.TxSupport;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * The citizen portal over the data-protection module (owner decisions 339, 341, 343, 347, 350), against the real
 * {@link SangamCompliance} services on H2 (PostgreSQL mode), the module joining the portal's transaction as in the
 * server:
 * <ul>
 *   <li>339/341: the notice is the current published version with one entry per purpose; a consent is recorded for the
 *       purposes ticked one by one, against that version; a stale version, another notice, no consent, no purpose and no
 *       age declaration are refused by name and record nothing;</li>
 *   <li>343: a declared child without a valid verification of the parent's verifiable consent is refused
 *       ({@code GUARDIAN_CONSENT_REQUIRED}, fail closed); with one, the consent is given by the verified parent; a flagged
 *       purpose is refused for a child ({@code CHILD_PROCESSING_FORBIDDEN});</li>
 *   <li>347: a breach notice sent to a principal is in her portal account with the five r.7(1) items, and only in hers;</li>
 *   <li>350: the grievance period is the pack's (configured SLA, capped at the pack maximum); none without a jurisdiction.</li>
 * </ul>
 */
class PortalComplianceAdapterH2Test {

    static final Dialect D = Dialect.POSTGRESQL;
    static final String TENANT = "t1";
    static final String NO_JURISDICTION = "t-none";
    static final String NOTICE = "svc-notice";
    static final String SERVICE = "birth-certificate";
    static final JurisdictionRegistry PACKS = JurisdictionRegistry.discover();

    static final Map<String, String> FACTS = Map.ofEntries(Map.entry("description", "portal database exposed"),
            Map.entry("nature", "confidentiality"), Map.entry("extent", "120 records"), Map.entry("timing", "2026-01-05"),
            Map.entry("location", "state data centre"), Map.entry("likelyImpact", "identity misuse"),
            Map.entry("contact", "dpo@example.gov.in"), Map.entry("mitigation", "access revoked"),
            Map.entry("safeguards", "encryption at rest"));

    static final Map<String, String> SUBJECT_NOTICE = Map.of("description", "exposed on 5 January at the state data centre",
            "consequences", "your name and address may be misused", "mitigation", "access was revoked",
            "safetySteps", "watch for messages that ask for your details", "contact", "dpo@example.gov.in");

    JdbcDataSource ds;
    TxSupport tx;
    SangamCompliance compliance;
    PortalComplianceAdapter portal;
    final Map<String, String> props = new HashMap<>();

    static RequestContext citizen(String id) {
        return new RequestContext(TENANT, id, Set.of(), null, null, null, null, null, "token");
    }

    static PurposeDefinition purpose(String code, String name, Set<ProcessingFlag> flags) {
        return new PurposeDefinition(code, Map.of("en", name), Map.of("en", "Lets the department " + name.toLowerCase()),
                List.of(new PurposeDefinition.DataItem("NAME", Map.of("en", "Name"), false)), "CONSENT", null, null, null,
                false, Set.of(), null, Set.of(), flags, null, null, null, null, null, null, 0, true, null);
    }

    @BeforeEach
    void setUp() throws Exception {
        ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:d2portal" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            Schema.create(c, D);
            ComplianceSchema.create(c, D);
            // REST_DOCUMENT as in sangam-persistence (changeset 0016-01-rest_document)
            s.execute("CREATE TABLE REST_DOCUMENT (tenant_id VARCHAR(64) NOT NULL, kind VARCHAR(64) NOT NULL,"
                    + " doc_id VARCHAR(64) NOT NULL, doc_key VARCHAR(255), revision BIGINT NOT NULL, state VARCHAR(32),"
                    + " owner_id VARCHAR(255), ref_id VARCHAR(255), body CHARACTER LARGE OBJECT NOT NULL, created_by VARCHAR(255),"
                    + " created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL,"
                    + " CONSTRAINT PK_REST_DOCUMENT PRIMARY KEY (tenant_id, kind, doc_id))");
        }
        tx = new SpringTxSupport(ds, new DataSourceTransactionManager(ds), D);
        // the module joins the caller's transaction, as Transactions.of(engineAdapter) does in the server
        Transactions joined = new Transactions() {
            @Override
            public <T> T inTransaction(Function<Connection, T> work) {
                return tx.withConnection(work);
            }

            @Override
            public <T> T read(ReadWork<T> work) {
                return tx.withReadConnection(work);
            }
        };
        props.put("jurisdiction", "IN");
        props.put("organisation-name", "Test Department");
        props.put("links.withdraw-consent", "https://portal.example.gov.in/consents");
        props.put("links.rights-portal", "https://portal.example.gov.in/rights");
        props.put("links.authority-complaint", "https://dpb.example.gov.in");
        ComplianceConfig.Provider config = t -> {
            if (NO_JURISDICTION.equals(t)) {
                throw new ComplianceException(ComplianceException.Code.JURISDICTION_NOT_SET, "no jurisdiction for " + t);
            }
            return ComplianceConfig.fromProperties(PACKS, props);
        };
        CompliancePorts.PrincipalNotifier outbox = (c, m) -> "DLV-" + m.principalRef();
        CompliancePorts.AdultIdentityVerifier adults = (tenant, person, method, evidence) ->
                new CompliancePorts.AdultVerification(true, true, "test-issuer", "tok-" + person, "verified adult");
        CompliancePorts.SupervisoryAuthorityGateway authority = s -> "ACK-" + s.kind() + "-" + s.caseId();
        CompliancePorts.IncidentAuthorityGateway incidents = s -> "INC-" + s.incidentId();
        SangamCompliance.Ports ports = new SangamCompliance.Ports(null, null, outbox, null, null, null, adults, null, null,
                null, authority, incidents, null, null, null, null, null);
        compliance = new SangamCompliance(D, joined, config, ports, null, org.sangam.compliance.PrincipalKey.of(new byte[32]));
        portal = new PortalComplianceAdapter(compliance, PortalComplianceAdapter.Inbox.documents(new DocumentStore(tx)));
        tx.withConnection(c -> {
            compliance.purposes.define(c, TENANT, purpose("P_APPLY", "Process the application", Set.of()), "dpo-1");
            compliance.purposes.define(c, TENANT, purpose("P_UPDATES", "Send updates on the service", Set.of()), "dpo-1");
            compliance.purposes.define(c, TENANT, purpose("P_TRACK", "Track usage", Set.of(ProcessingFlag.TRACKING)), "dpo-1");
            compliance.contacts.set(c, TENANT, new ContactDirectory.Contact("CONTACT_PERSON", "Grievance Cell", "Officer",
                    "Test Department", "contact@example.gov.in", null, null, null, true, null), "dpo-1");
            compliance.notices.publish(c, TENANT, NOTICE, Set.of("P_APPLY", "P_UPDATES"), "dpo-1");
            compliance.notices.publish(c, TENANT, "tracking-notice", Set.of("P_APPLY", "P_TRACK"), "dpo-1");
            return null;
        });
    }

    PortalPort.ConsentRecord record(RequestContext who, PortalPort.PortalConsent consent) {
        return tx.inTx(() -> portal.recordConsent(who, SERVICE, NOTICE, consent));
    }

    static PortalPort.PortalConsent consent(Integer version, Set<String> purposes, String age, String verification) {
        return new PortalPort.PortalConsent(NOTICE, version, "en", purposes, age, verification);
    }

    List<String> activePurposes(String principal) {
        return tx.withConnection(c -> Jdbc.query(c, "SELECT purpose_code FROM CMP_CONSENT_PURPOSE WHERE tenant_id=? AND"
                + " principal_ref=? AND status='ACTIVE' ORDER BY purpose_code", rs -> rs.getString(1), TENANT, principal));
    }

    long consents(String principal) {
        return tx.withConnection(c -> Jdbc.one(c, "SELECT COUNT(*) FROM CMP_CONSENT WHERE tenant_id=? AND principal_ref=?",
                rs -> rs.getLong(1), TENANT, principal).orElse(0L));
    }

    // ---------------------------------------------------------------- 339 / 341

    @Test
    void theNoticeIsTheCurrentPublishedVersionWithOneEntryPerPurposeAndThePackAgeOfMajority() {
        PortalCompliancePort.PortalNotice n = tx.inTx(() -> portal.notice(citizen("citizen-1"), NOTICE, null));
        assertEquals(NOTICE, n.noticeKey());
        assertEquals(1, n.version());
        assertEquals("en", n.language());
        assertNotNull(n.text());
        assertEquals(64, n.contentHash().length(), "SHA-256 of the published text");
        assertEquals(List.of("P_APPLY", "P_UPDATES"), n.purposes().stream().map(PortalCompliancePort.NoticePurpose::code).toList());
        assertEquals("Process the application", n.purposes().get(0).name());
        assertTrue(n.purposes().get(1).description().contains("send updates"), n.purposes().get(1).description());
        assertEquals(18, n.childAgeYears(), "IN pack limits.childAgeYears (DPDP s.2(f))");

        ApiProblem lang = assertThrows(ApiProblem.class, () -> tx.inTx(() -> portal.notice(citizen("citizen-1"), NOTICE, "xx")));
        assertEquals("LANGUAGE_NOT_CONFIGURED", lang.code());
        ApiProblem missing = assertThrows(ApiProblem.class, () -> tx.inTx(() -> portal.notice(citizen("citizen-1"), "no-such", null)));
        assertEquals(404, missing.status());
    }

    @Test
    void theConsentIsRecordedForTheTickedPurposesOnlyAgainstTheCurrentVersion() {
        PortalPort.ConsentRecord r = record(citizen("citizen-1"), consent(1, Set.of("P_APPLY"), "ADULT", null));
        assertNotNull(r.consentId());
        assertEquals("ADULT", r.ageDeclaration());
        assertNull(r.guardianVerificationId());
        assertEquals(List.of("P_APPLY"), activePurposes("citizen-1"), "one purpose ticked, one recorded (decision 341)");
        Map<String, Object> row = tx.withConnection(c -> Jdbc.one(c, "SELECT notice_key, notice_version, channel, given_by FROM"
                + " CMP_CONSENT WHERE tenant_id=? AND consent_id=?", rs -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("notice", rs.getString(1));
                    m.put("version", rs.getInt(2));
                    m.put("channel", rs.getString(3));
                    m.put("givenBy", rs.getString(4));
                    return m;
                }, TENANT, r.consentId()).orElseThrow());
        assertEquals(Map.of("notice", NOTICE, "version", 1, "channel", "portal:" + SERVICE, "givenBy", "citizen-1"), row);
    }

    @Test
    void aSubmissionOnANoticeThatIsNotTheCurrentPublishedOneIsRefusedByNameAndRecordsNothing() {
        tx.withConnection(c -> compliance.notices.publish(c, TENANT, NOTICE, Set.of("P_APPLY", "P_UPDATES"), "dpo-1"));
        ApiProblem stale = assertThrows(ApiProblem.class,
                () -> record(citizen("citizen-1"), consent(1, Set.of("P_APPLY"), "ADULT", null)));
        assertEquals(409, stale.status());
        assertEquals("NOTICE_NOT_CURRENT", stale.code());
        assertTrue(stale.detail().contains("current published version is 2"), stale.detail());

        ApiProblem noVersion = assertThrows(ApiProblem.class,
                () -> record(citizen("citizen-1"), consent(null, Set.of("P_APPLY"), "ADULT", null)));
        assertEquals("NOTICE_NOT_CURRENT", noVersion.code());

        ApiProblem other = assertThrows(ApiProblem.class, () -> record(citizen("citizen-1"),
                new PortalPort.PortalConsent("tracking-notice", 1, "en", Set.of("P_APPLY"), "ADULT", null)));
        assertEquals("NOTICE_NOT_CURRENT", other.code(), "a consent on another notice than the service's");

        ApiProblem unpublished = assertThrows(ApiProblem.class, () -> tx.inTx(() -> portal.recordConsent(citizen("citizen-1"),
                SERVICE, "never-published", new PortalPort.PortalConsent("never-published", 1, "en", Set.of("P_APPLY"), "ADULT", null))));
        assertEquals("NOTICE_NOT_CURRENT", unpublished.code());

        assertEquals(0, consents("citizen-1"));
        assertNotNull(record(citizen("citizen-1"), consent(2, Set.of("P_APPLY", "P_UPDATES"), "ADULT", null)).consentId(),
                "the current version is accepted");
        assertEquals(List.of("P_APPLY", "P_UPDATES"), activePurposes("citizen-1"));
    }

    @Test
    void noConsentNoPurposeAndNoAgeDeclarationAreRefusedByName() {
        ApiProblem none = assertThrows(ApiProblem.class, () -> record(citizen("citizen-1"), null));
        assertEquals(403, none.status());
        assertEquals("CONSENT_REQUIRED", none.code());
        ApiProblem noPurpose = assertThrows(ApiProblem.class,
                () -> record(citizen("citizen-1"), consent(1, Set.of(), "ADULT", null)));
        assertEquals("CONSENT_REQUIRED", noPurpose.code());
        ApiProblem notOnNotice = assertThrows(ApiProblem.class,
                () -> record(citizen("citizen-1"), consent(1, Set.of("P_TRACK"), "ADULT", null)));
        assertEquals("NOTICE_INVALID", notOnNotice.code(), "a purpose that is not on the notice (module refusal kept)");
        ApiProblem noAge = assertThrows(ApiProblem.class,
                () -> record(citizen("citizen-1"), consent(1, Set.of("P_APPLY"), null, null)));
        assertEquals(422, noAge.status());
        assertEquals("AGE_DECLARATION_REQUIRED", noAge.code());
        ApiProblem badAge = assertThrows(ApiProblem.class,
                () -> record(citizen("citizen-1"), consent(1, Set.of("P_APPLY"), "TEEN", null)));
        assertEquals("AGE_DECLARATION_REQUIRED", badAge.code());
        assertEquals(0, consents("citizen-1"));
    }

    // ---------------------------------------------------------------- 343

    @Test
    void aDeclaredChildWithoutAValidParentVerificationIsRefusedFailClosed() {
        ApiProblem none = assertThrows(ApiProblem.class,
                () -> record(citizen("child-1"), consent(1, Set.of("P_APPLY"), "CHILD", null)));
        assertEquals(403, none.status());
        assertEquals("GUARDIAN_CONSENT_REQUIRED", none.code());
        ApiProblem unknown = assertThrows(ApiProblem.class,
                () -> record(citizen("child-1"), consent(1, Set.of("P_APPLY"), "CHILD", "no-such-verification")));
        assertEquals("GUARDIAN_CONSENT_REQUIRED", unknown.code());
        // a verification of another child's parent does not cover this child
        String otherChild = tx.withConnection(c -> compliance.guardians.verifyParent(c, TENANT, "child-2", "parent-2",
                "VOLUNTARY_DETAILS", Map.of(), null, true, "officer-1").verificationId());
        ApiProblem foreign = assertThrows(ApiProblem.class,
                () -> record(citizen("child-1"), consent(1, Set.of("P_APPLY"), "CHILD", otherChild)));
        assertEquals("GUARDIAN_CONSENT_REQUIRED", foreign.code());
        assertEquals(0, consents("child-1"));
    }

    @Test
    void aDeclaredChildWithAValidParentVerificationConsentsThroughTheVerifiedParent() {
        String vid = tx.withConnection(c -> compliance.guardians.verifyParent(c, TENANT, "child-1", "parent-1",
                "VOLUNTARY_DETAILS", Map.of(), null, true, "officer-1").verificationId());
        PortalPort.ConsentRecord r = record(citizen("child-1"), consent(1, Set.of("P_APPLY"), "CHILD", vid));
        assertEquals("CHILD", r.ageDeclaration());
        assertEquals(vid, r.guardianVerificationId());
        String givenBy = tx.withConnection(c -> Jdbc.one(c, "SELECT given_by FROM CMP_CONSENT WHERE tenant_id=? AND consent_id=?",
                rs -> rs.getString(1), TENANT, r.consentId()).orElseThrow());
        assertEquals("parent-1", givenBy, "the consent is the verified parent's");
        // decision 365: the portal's age answer is the declared category, so the row of a declared child carries the
        // guardian verification although no principal directory knows the child
        String stored = tx.withConnection(c -> Jdbc.one(c, "SELECT COALESCE(guardian_verification_id, 'none')"
                + " FROM CMP_CONSENT WHERE tenant_id=? AND consent_id=?", rs -> rs.getString(1), TENANT, r.consentId())
                .orElseThrow());
        assertEquals(vid, stored, "the consent row of a declared child carries the guardian verification id");
    }

    @Test
    void aFlaggedPurposeIsRefusedForADeclaredChild() {
        String vid = tx.withConnection(c -> compliance.guardians.verifyParent(c, TENANT, "child-1", "parent-1",
                "VOLUNTARY_DETAILS", Map.of(), null, true, "officer-1").verificationId());
        ApiProblem p = assertThrows(ApiProblem.class, () -> tx.inTx(() -> portal.recordConsent(citizen("child-1"), SERVICE,
                "tracking-notice", new PortalPort.PortalConsent("tracking-notice", 1, "en", Set.of("P_TRACK"), "CHILD", vid))));
        assertEquals("CHILD_PROCESSING_FORBIDDEN", p.code());
        assertEquals(0, consents("child-1"));
    }

    // ---------------------------------------------------------------- 350

    @Test
    void theGrievancePeriodIsThePacksConfiguredPeriodCappedAtItsMaximum() {
        JurisdictionPack.Right grievance = PACKS.require("IN").rights().stream()
                .filter(r -> "GRIEVANCE".equals(r.category())).findFirst().orElseThrow();
        assertEquals(OptionalInt.of(grievance.defaultDays()), portal.grievanceDueDays(citizen("citizen-1")), "pack default");
        props.put("sla.grievance-days", "15");
        assertEquals(OptionalInt.of(15), portal.grievanceDueDays(citizen("citizen-1")), "the tenant's configured period");
        props.put("sla.grievance-days", "400");
        assertEquals(OptionalInt.of(grievance.maxDays()), portal.grievanceDueDays(citizen("citizen-1")),
                "never above the pack maximum");
        RequestContext unbound = new RequestContext(NO_JURISDICTION, "citizen-1", Set.of(), null, null, null, null, null, "token");
        assertEquals(OptionalInt.empty(), portal.grievanceDueDays(unbound), "no jurisdiction: no published period");
    }

    // ---------------------------------------------------------------- 347

    @Test
    void aBreachNoticeIsInTheAffectedPrincipalsPortalAccountWithTheFiveItems() {
        String breach = tx.withConnection(c -> compliance.breaches.register(c, TENANT, new LinkedHashMap<>(FACTS), null, null,
                Set.of("citizen-1"), null, null, List.of("portal"), "dpo-1").breachId());
        assertEquals(List.of(), portal.notifications(citizen("citizen-1")));
        assertEquals(1, (int) tx.withConnection(c -> compliance.breaches.notifySubjects(c, TENANT, breach, SUBJECT_NOTICE, "dpo-1")));

        List<PortalCompliancePort.PortalNotification> mine = portal.notifications(citizen("citizen-1"));
        assertEquals(1, mine.size());
        PortalCompliancePort.PortalNotification n = mine.get(0);
        assertEquals("BREACH_NOTICE", n.kind());
        assertEquals(breach, n.reference());
        assertNotNull(n.at());
        // IN notice_en.properties breach.subject
        assertEquals("Personal data breach affecting you", n.subject());
        List<String> keys = new ArrayList<>();
        List<String> citations = new ArrayList<>();
        for (PortalCompliancePort.NoticeItem i : n.items()) {
            keys.add(i.key());
            citations.add(i.citation());
            assertEquals(SUBJECT_NOTICE.get(i.key()), i.text());
            assertTrue(!i.label().isBlank(), i.key());
        }
        assertEquals(List.of("description", "consequences", "mitigation", "safetySteps", "contact"), keys);
        assertEquals(List.of("r.7(1)(a)", "r.7(1)(b)", "r.7(1)(c)", "r.7(1)(d)", "r.7(1)(e)"), citations);
        assertEquals(List.of(), portal.notifications(citizen("citizen-2")), "another principal sees nothing");
    }
}

// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.consent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.compliance.ComplianceException;
import org.sangam.compliance.ComplianceSchema;
import org.sangam.compliance.SangamCompliance;
import org.sangam.compliance.accountability.ContactDirectory;
import org.sangam.compliance.config.ComplianceConfig;
import org.sangam.compliance.jurisdiction.JurisdictionRegistry;
import org.sangam.compliance.lawful.PurposeDefinition;
import org.sangam.compliance.ports.CompliancePorts.AdultIdentityVerifier;
import org.sangam.compliance.ports.CompliancePorts.AdultVerification;
import org.sangam.compliance.ports.CompliancePorts.PrincipalCategory;
import org.sangam.compliance.ports.CompliancePorts.PrincipalDirectory;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;

/**
 * Owner decision 365: {@code GiveConsent} carries the declared category (adult or child) of the data principal, so the
 * consent row of a child carries the guardian verification id; no principal directory is needed for it. Before the
 * decision the guardian verification was checked and stored only when a principal directory said CHILD: with none
 * (the standalone server) a child's consent was stored with no verification. The more protective of the declared and
 * the directory category applies. H2 in PostgreSQL mode, IN pack.
 */
class DeclaredChildConsentH2Test {

    static final Dialect D = Dialect.POSTGRESQL;
    static final String TENANT = "t1";
    static final String CHILD = "child-1";
    static final String PARENT = "parent-1";
    static final String PURPOSE = "SERVICE";
    static final String NOTICE = "service";
    static final JurisdictionRegistry PACKS = JurisdictionRegistry.discover();

    Connection c;

    @BeforeEach
    void db() throws Exception {
        c = DriverManager.getConnection("jdbc:h2:mem:d365" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        Schema.create(c, D);
        ComplianceSchema.create(c, D);
    }

    @AfterEach
    void close() throws Exception {
        c.close();
    }

    SangamCompliance compliance(PrincipalDirectory directory) {
        return compliance(directory, org.sangam.compliance.PrincipalKey.of(new byte[32]));
    }

    SangamCompliance compliance(PrincipalDirectory directory, org.sangam.compliance.PrincipalKey key) {
        Map<String, String> props = new HashMap<>();
        props.put("jurisdiction", "IN");
        props.put("links.rights-portal", "https://example.gov.in/rights");
        props.put("links.withdraw-consent", "https://example.gov.in/withdraw");
        props.put("links.authority-complaint", "https://example.gov.in/board");
        ComplianceConfig.Provider config = t -> ComplianceConfig.fromProperties(PACKS, props);
        Transactions tx = new Transactions() {
            @Override
            public <T> T inTransaction(Function<Connection, T> work) {
                throw new AssertionError("the tests pass their own connection");
            }
        };
        AdultIdentityVerifier adults = (tenant, person, method, evidence) -> new AdultVerification(true, true, "test-issuer",
                "token-" + person, "verified");
        SangamCompliance.Ports ports = new SangamCompliance.Ports(null, null, null, directory, null, null, adults, null, null,
                null, null, null, null, null, null, null, null);
        SangamCompliance cmp = new SangamCompliance(D, tx, config, ports, null, key);
        cmp.contacts.set(c, TENANT, new ContactDirectory.Contact("DPO", "Data Protection Officer", "Joint Secretary",
                "Test Department", "dpo@example.gov.in", null, null, null, true, null), "admin-1");
        cmp.purposes.define(c, TENANT, new PurposeDefinition(PURPOSE, Map.of("en", "Service delivery"),
                Map.of("en", "Processing an application for the service."),
                List.of(new PurposeDefinition.DataItem("name", Map.of("en", "Full name"), false)), "CONSENT", null, null, null,
                false, Set.of(), null, Set.of(), Set.of(), null, Duration.ofDays(400), null, null, null, null, 0, true, null),
                "admin-1");
        cmp.notices.publish(c, TENANT, NOTICE, Set.of(PURPOSE), "admin-1");
        return cmp;
    }

    static PrincipalDirectory says(PrincipalCategory category) {
        return new PrincipalDirectory() {
            @Override
            public PrincipalCategory category(Connection c, String tenantId, String principalRef) {
                return category;
            }

            @Override
            public Optional<org.sangam.compliance.jurisdiction.JurisdictionPack.Language> preferredLanguage(Connection c, String tenantId,
                                                                                         String principalRef) {
                return Optional.empty();
            }
        };
    }

    static ConsentService.GiveConsent give(String givenBy, String verificationId, PrincipalCategory declared) {
        return new ConsentService.GiveConsent(CHILD, NOTICE, null, null, Set.of(PURPOSE), true, "portal", givenBy,
                verificationId, null, null, declared);
    }

    String verifiedParent(SangamCompliance cmp) {
        return cmp.guardians.verifyParent(c, TENANT, CHILD, PARENT, "DIGILOCKER_TOKEN", Map.of(), null, true, "officer-1")
                .verificationId();
    }

    String storedVerification(String consentId) {
        // Jdbc.one refuses a NULL mapped value: map to Optional so "no verification" reads as null
        return Jdbc.one(c, "SELECT guardian_verification_id FROM CMP_CONSENT WHERE tenant_id=? AND consent_id=?",
                rs -> Optional.ofNullable(rs.getString(1)), TENANT, consentId).orElseThrow().orElse(null);
    }

    long consents() {
        return Jdbc.one(c, "SELECT COUNT(*) FROM CMP_CONSENT WHERE tenant_id=?", rs -> rs.getLong(1), TENANT).orElse(0L);
    }

    /**
     * Owner decision 369 (e): the consent proof refers to the principal and the giver by the keyed token; without the
     * compliance principal key the consent is refused by name before anything is stored, never proven with an unkeyed
     * token.
     */
    @Test
    void withoutThePrincipalKeyAConsentIsRefusedByNameAndNotStored() {
        SangamCompliance cmp = compliance(null, org.sangam.compliance.PrincipalKey.unconfigured());
        ComplianceException e = assertThrows(ComplianceException.class,
                () -> cmp.consents.give(c, TENANT, give(CHILD, null, PrincipalCategory.ADULT)));
        assertEquals(ComplianceException.Code.PRINCIPAL_KEY_NOT_CONFIGURED, e.code());
        assertEquals(0, consents());
    }

    @Test
    void aDeclaredChildsConsentCarriesTheGuardianVerificationWithNoDirectory() {
        SangamCompliance cmp = compliance(null);
        String v = verifiedParent(cmp);
        ConsentService.Consent k = cmp.consents.give(c, TENANT, give(PARENT, v, PrincipalCategory.CHILD));
        assertEquals(v, storedVerification(k.consentId()), "the consent row of a declared child carries the verification");
    }

    @Test
    void aDeclaredChildWithoutAGuardianVerificationIsRefusedByName() {
        SangamCompliance cmp = compliance(null);
        ComplianceException e = assertThrows(ComplianceException.class,
                () -> cmp.consents.give(c, TENANT, give(CHILD, null, PrincipalCategory.CHILD)));
        assertEquals(ComplianceException.Code.GUARDIAN_CONSENT_REQUIRED, e.code());
        assertTrue(e.getMessage().contains("parent"), e.getMessage());
        assertEquals(0, consents(), "nothing written");
    }

    @Test
    void aDeclaredChildsConsentMustBeGivenByTheVerifiedParent() {
        SangamCompliance cmp = compliance(null);
        String v = verifiedParent(cmp);
        ComplianceException e = assertThrows(ComplianceException.class,
                () -> cmp.consents.give(c, TENANT, give("someone-else", v, PrincipalCategory.CHILD)));
        assertEquals(ComplianceException.Code.GUARDIAN_CONSENT_REQUIRED, e.code());
    }

    @Test
    void aDeclaredAdultNeedsNoVerificationAndTheRowCarriesNone() {
        SangamCompliance cmp = compliance(null);
        ConsentService.Consent k = cmp.consents.give(c, TENANT, give(CHILD, null, PrincipalCategory.ADULT));
        assertNull(storedVerification(k.consentId()));
    }

    @Test
    void aDeclarationOfAdultNeverLowersADirectoryChild() {
        SangamCompliance cmp = compliance(says(PrincipalCategory.CHILD));
        ComplianceException e = assertThrows(ComplianceException.class,
                () -> cmp.consents.give(c, TENANT, give(CHILD, null, PrincipalCategory.ADULT)));
        assertEquals(ComplianceException.Code.GUARDIAN_CONSENT_REQUIRED, e.code());
    }

    @Test
    void aDeclaredChildIsAChildEvenWhereTheDirectorySaysAdult() {
        SangamCompliance cmp = compliance(says(PrincipalCategory.ADULT));
        assertThrows(ComplianceException.class, () -> cmp.consents.give(c, TENANT, give(CHILD, null, PrincipalCategory.CHILD)));
        String v = verifiedParent(cmp);
        assertEquals(v, storedVerification(cmp.consents.give(c, TENANT, give(PARENT, v, PrincipalCategory.CHILD)).consentId()));
    }

    @Test
    void onlyAdultOrChildMayBeDeclared() {
        SangamCompliance cmp = compliance(null);
        for (PrincipalCategory other : List.of(PrincipalCategory.UNKNOWN, PrincipalCategory.PERSON_WITH_DISABILITY_WITH_GUARDIAN)) {
            ComplianceException e = assertThrows(ComplianceException.class,
                    () -> cmp.consents.give(c, TENANT, give(CHILD, null, other)), other.name());
            assertTrue(e.getMessage().contains("declaredCategory"), e.getMessage());
        }
        assertEquals(0, consents());
    }

    @Test
    void aChannelThatDeclaresNothingLeavesTheDirectoryToDecide() {
        assertEquals(PrincipalCategory.UNKNOWN, ConsentService.effectiveCategory(null, PrincipalCategory.UNKNOWN));
        assertEquals(PrincipalCategory.CHILD, ConsentService.effectiveCategory(null, PrincipalCategory.CHILD));
        assertEquals(PrincipalCategory.PERSON_WITH_DISABILITY_WITH_GUARDIAN, ConsentService.effectiveCategory(
                PrincipalCategory.ADULT, PrincipalCategory.PERSON_WITH_DISABILITY_WITH_GUARDIAN));
    }
}

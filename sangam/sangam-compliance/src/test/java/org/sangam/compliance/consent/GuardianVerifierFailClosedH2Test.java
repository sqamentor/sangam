// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.consent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.compliance.ComplianceAudit;
import org.sangam.compliance.ComplianceException;
import org.sangam.compliance.ComplianceSchema;
import org.sangam.compliance.config.ComplianceConfig;
import org.sangam.compliance.jurisdiction.JurisdictionRegistry;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Schema;

/**
 * Owner decision 68 (M-087): no guardianship verifier ships, so a lawful-guardian verification for a person with
 * disability FAILS CLOSED. With the verifier unbound the verification is refused before anything is written (no
 * verification row, no audit event), and no guardian-dependent consent can be given because no verified guardian
 * verification can exist. H2 in PostgreSQL mode.
 */
class GuardianVerifierFailClosedH2Test {

    static final Dialect D = Dialect.POSTGRESQL;

    Connection c;
    VerifiableParentalConsentService guardians;

    @BeforeEach
    void db() throws Exception {
        c = DriverManager.getConnection("jdbc:h2:mem:m087guardian" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa", "");
        Schema.create(c, D);
        ComplianceSchema.create(c, D);
        JurisdictionRegistry packs = JurisdictionRegistry.discover();
        ComplianceConfig.Provider config = t -> ComplianceConfig.fromProperties(packs, Map.of("jurisdiction", "IN"));
        guardians = new VerifiableParentalConsentService(D, new ComplianceAudit(new AuditChain(D)), config, null, null);
    }

    @AfterEach
    void close() throws Exception {
        c.close();
    }

    private long count(String table) {
        return Jdbc.one(c, "SELECT COUNT(*) FROM " + table, rs -> rs.getLong(1)).orElse(0L);
    }

    @Test
    void anUnboundGuardianVerifierRefusesAndWritesNothing() {
        long auditBefore = count("AUDIT_EVENT");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> guardians.verifyLawfulGuardian(c, "t1",
                "principal-1", "guardian-1", "COURT", "order-1", Map.of(), "officer-1"));
        assertTrue(e.getMessage().contains("GuardianshipVerifier"), e.getMessage());
        assertEquals(0, count("CMP_GUARDIAN_VERIFICATION"), "no verification row");
        assertEquals(auditBefore, count("AUDIT_EVENT"), "no audit event");
        assertTrue(guardians.currentFor(c, "t1", "principal-1").isEmpty(), "no current verification");
    }

    @Test
    void anUnboundAdultVerifierRefusesAParentVerificationToo() {
        assertThrows(IllegalStateException.class, () -> guardians.verifyParent(c, "t1", "child-1", "parent-1",
                "DIGILOCKER_TOKEN", Map.of(), null, true, "officer-1"));
        assertEquals(0, count("CMP_GUARDIAN_VERIFICATION"));
    }

    @Test
    void withoutAVerifiedGuardianAnyGuardianConsentCheckIsRefused() {
        ComplianceException e = assertThrows(ComplianceException.class,
                () -> guardians.requireValid(c, "t1", "principal-1", "any-verification-id"));
        assertEquals(ComplianceException.Code.GUARDIAN_CONSENT_REQUIRED, e.code());
    }

    @Test
    void noGuardianshipVerifierIsPublishedByTheProduct() throws Exception {
        // The server binds the port only from a bean (ComplianceWiring, getIfUnique); decision 68 keeps it unbound, so
        // no product source may implement the port until the officer-attested verifier passes legal review.
        try (var files = Files.walk(Path.of("src/main/java"))) {
            assertTrue(files.filter(p -> p.toString().endsWith(".java")).noneMatch(p -> {
                try {
                    return Files.readString(p).contains("implements CompliancePorts.GuardianshipVerifier")
                            || Files.readString(p).contains("implements GuardianshipVerifier");
                } catch (java.io.IOException io) {
                    throw new IllegalStateException(io);
                }
            }));
        }
    }
}

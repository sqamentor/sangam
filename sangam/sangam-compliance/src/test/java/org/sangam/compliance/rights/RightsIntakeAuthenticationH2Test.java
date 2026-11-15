// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.rights;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.compliance.ComplianceException;
import org.sangam.compliance.ComplianceSchema;
import org.sangam.compliance.SangamCompliance;
import org.sangam.compliance.accountability.ContactDirectory;
import org.sangam.compliance.config.ComplianceConfig;
import org.sangam.compliance.jurisdiction.JurisdictionPack;
import org.sangam.compliance.jurisdiction.JurisdictionRegistry;
import org.sangam.compliance.ports.CompliancePorts.PrincipalAuthenticator;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;

/**
 * Owner decision 335: a rights request is authenticated by a method the jurisdiction pack names
 * ({@code verification.principal}): the caller's own signed-in identity ({@code SIGNED_IN}) or a one-time code to
 * the published identifier ({@code OTP}, through the authenticator port). Anything else is refused with
 * {@code PRINCIPAL_NOT_AUTHENTICATED} before a row is written (fail closed).
 *
 * <p>Owner decision 346: the acknowledgement (the submit result) and every later read of the request carry the
 * tenant's published contact, the DPO first (IN pack contactRoles order).
 */
class RightsIntakeAuthenticationH2Test {

    static final Dialect D = Dialect.POSTGRESQL;
    static final String TENANT = "t1";
    static final String CITIZEN = "citizen-1";
    static final JurisdictionRegistry PACKS = JurisdictionRegistry.discover();

    String url;
    Connection keepAlive;

    @BeforeEach
    void db() throws Exception {
        url = "jdbc:h2:mem:d335" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        keepAlive = DriverManager.getConnection(url, "sa", "");
        Schema.create(keepAlive, D);
        ComplianceSchema.create(keepAlive, D);
    }

    @AfterEach
    void close() throws Exception {
        keepAlive.close();
    }

    SangamCompliance compliance(String pack, PrincipalAuthenticator authenticator) {
        Map<String, String> props = new HashMap<>();
        props.put("jurisdiction", pack);
        ComplianceConfig.Provider config = t -> ComplianceConfig.fromProperties(PACKS, props);
        Transactions tx = new Transactions() {
            @Override
            public <T> T inTransaction(Function<Connection, T> work) {
                throw new AssertionError("the tests pass their own connection");
            }
        };
        // no workflow starter: no process, so no seat is needed
        SangamCompliance.Ports ports = new SangamCompliance.Ports(null, null, null, null, authenticator, null, null, null, null,
                null, null, null, null, null, null, null, null);
        return new SangamCompliance(D, tx, config, ports);
    }

    Connection caller() throws Exception {
        Connection c = DriverManager.getConnection(url, "sa", "");
        c.setAutoCommit(false);
        return c;
    }

    static long requests(Connection c) {
        return Jdbc.one(c, "SELECT COUNT(*) FROM CMP_RIGHTS_REQUEST WHERE tenant_id=?", rs -> rs.getLong(1), TENANT).orElse(0L);
    }

    static DataSubjectRightsService.Submission signedIn(String principalRef, String identifierValue) {
        return new DataSubjectRightsService.Submission(principalRef, "ACCESS", DataSubjectRightsService.SIGNED_IN, identifierValue,
                Map.of(), null, "PORTAL", Map.of());
    }

    static void dpo(SangamCompliance cmp, Connection c) {
        cmp.contacts.set(c, TENANT, new ContactDirectory.Contact("DPO", "Data Protection Officer", "Joint Secretary",
                "Test Department", "dpo@example.gov.in", null, null, null, true, null), "admin-1");
    }

    static void assertRefused(Connection c, Runnable submit, String why) {
        ComplianceException e = assertThrows(ComplianceException.class, submit::run, why);
        assertEquals(ComplianceException.Code.PRINCIPAL_NOT_AUTHENTICATED, e.code(), why + ": " + e.getMessage());
        assertEquals(0, requests(c), why + ": nothing is written");
    }

    @Test
    void theIndiaPackNamesTheSignedInIdentityAndOtp() {
        // pack data, IN pack.json verification.principal (DPDP Rules 2025 r.14(1)): the methods the fiduciary publishes
        JurisdictionPack in = PACKS.require("IN");
        assertEquals(List.of(DataSubjectRightsService.SIGNED_IN, DataSubjectRightsService.OTP),
                in.principalVerification().stream().map(JurisdictionPack.VerificationMethod::code).toList());
        assertEquals(List.of(), PACKS.require("EU-GDPR").principalVerification(), "the EU pack names no method yet");
    }

    @Test
    void aSignedInCallerIsTheRequesterAndTheAcknowledgementCarriesTheDpo() throws Exception {
        SangamCompliance cmp = compliance("IN", null); // no OTP authenticator: the signed-in path needs none
        try (Connection c = caller()) {
            dpo(cmp, c);
            DataSubjectRightsService.RightsRequest r = cmp.rights.submit(c, TENANT, signedIn(null, null), CITIZEN);
            assertEquals(CITIZEN, r.principalRef(), "the requester is the signed-in identity");
            assertNotNull(r.contact(), "decision 346: the acknowledgement carries the published contact");
            assertEquals("DPO", r.contact().role());
            assertEquals("dpo@example.gov.in", r.contact().email());

            DataSubjectRightsService.RightsRequest same = cmp.rights.submit(c, TENANT, signedIn(CITIZEN, CITIZEN), CITIZEN);
            assertEquals(CITIZEN, same.principalRef(), "an identifierValue equal to the signed-in identity is accepted");

            assertEquals("DPO", cmp.rights.get(c, TENANT, r.requestId()).contact().role(), "the request read carries it");
            List<DataSubjectRightsService.RightsRequest> mine = cmp.rights.forPrincipal(c, TENANT, CITIZEN);
            assertEquals(2, mine.size());
            assertTrue(mine.stream().allMatch(x -> x.contact() != null && "DPO".equals(x.contact().role())),
                    "every listed request carries it");
        }
    }

    @Test
    void withoutAPublishedContactTheContactIsNull() throws Exception {
        SangamCompliance cmp = compliance("IN", null);
        try (Connection c = caller()) {
            assertNull(cmp.rights.submit(c, TENANT, signedIn(null, null), CITIZEN).contact(), "none designated, none invented");
        }
    }

    @Test
    void signedInWithoutASignedInCallerIsRefused() throws Exception {
        SangamCompliance cmp = compliance("IN", null);
        try (Connection c = caller()) {
            assertRefused(c, () -> cmp.rights.submit(c, TENANT, signedIn(null, null), null), "anonymous SIGNED_IN");
            assertRefused(c, () -> cmp.rights.submit(c, TENANT, signedIn(null, null), " "), "blank identity");
            assertRefused(c, () -> cmp.rights.submit(c, TENANT, signedIn(null, null)), "the anonymous overload");
        }
    }

    @Test
    void aSignedInCallerCannotActForSomeoneElse() throws Exception {
        SangamCompliance cmp = compliance("IN", null);
        try (Connection c = caller()) {
            assertRefused(c, () -> cmp.rights.submit(c, TENANT, signedIn(null, "citizen-2"), CITIZEN),
                    "identifierValue of another person");
            assertRefused(c, () -> cmp.rights.submit(c, TENANT, signedIn("citizen-2", null), CITIZEN),
                    "principalRef of another person");
        }
    }

    @Test
    void aPackThatDoesNotNameSignedInRefusesIt() throws Exception {
        SangamCompliance cmp = compliance("EU-GDPR", (c, t, type, value, proof) -> Optional.of(CITIZEN));
        try (Connection c = caller()) {
            assertRefused(c, () -> cmp.rights.submit(c, TENANT, signedIn(null, null), CITIZEN), "EU pack, SIGNED_IN");
        }
    }

    @Test
    void anOtpRequestWithoutAnAuthenticatorIsRefused() throws Exception {
        SangamCompliance cmp = compliance("IN", null);
        try (Connection c = caller()) {
            DataSubjectRightsService.Submission otp = new DataSubjectRightsService.Submission(CITIZEN, "ACCESS", "MOBILE",
                    "9000000000", Map.of("otp", "123456"), null, "PORTAL", Map.of());
            assertRefused(c, () -> cmp.rights.submit(c, TENANT, otp, null), "no OTP authenticator wired");
        }
    }

    @Test
    void anOtpRequestIsDecidedByTheAuthenticator() throws Exception {
        SangamCompliance cmp = compliance("IN", (c, t, type, value, proof) ->
                "123456".equals(proof.get("otp")) ? Optional.of(CITIZEN) : Optional.empty());
        try (Connection c = caller()) {
            DataSubjectRightsService.Submission wrong = new DataSubjectRightsService.Submission(CITIZEN, "ACCESS", "MOBILE",
                    "9000000000", Map.of("otp", "000000"), null, "PORTAL", Map.of());
            assertRefused(c, () -> cmp.rights.submit(c, TENANT, wrong, null), "a wrong code");
            DataSubjectRightsService.Submission right = new DataSubjectRightsService.Submission(CITIZEN, "ACCESS", "MOBILE",
                    "9000000000", Map.of("otp", "123456"), null, "PORTAL", Map.of());
            assertEquals(CITIZEN, cmp.rights.submit(c, TENANT, right, null).principalRef());
        }
    }
}

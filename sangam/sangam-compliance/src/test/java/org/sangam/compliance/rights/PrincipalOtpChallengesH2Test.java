// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.rights;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.compliance.ComplianceException;
import org.sangam.compliance.ComplianceException.Code;
import org.sangam.compliance.ComplianceSchema;
import org.sangam.compliance.SangamCompliance;
import org.sangam.compliance.accountability.ContactDirectory;
import org.sangam.compliance.config.ComplianceConfig;
import org.sangam.compliance.jurisdiction.JurisdictionRegistry;
import org.sangam.compliance.ports.CompliancePorts.PrincipalMessage;
import org.sangam.compliance.ports.CompliancePorts.PrincipalNotifier;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;

/**
 * Owner decision 360: a rights request authenticated by a compliance-owned one-time code, sent through the principal
 * notifier to the identifier the pack accepts (IN: OTP, DPDP Rules r.14(1)). Single use, short-lived, attempt-limited
 * with lock-out, stored only as a salted hash, tenant-scoped, audited. Before the decision the standalone server bound
 * no authenticator, so an OTP request could never be made. H2 in PostgreSQL mode, IN pack (its defaults: 10 minutes,
 * 5 attempts, 30 minutes lock-out, 6 digits, MOBILE and EMAIL).
 */
class PrincipalOtpChallengesH2Test {

    static final Dialect D = Dialect.POSTGRESQL;
    static final String TENANT = "t1";
    static final String EMAIL = "Citizen@Example.in";
    static final JurisdictionRegistry PACKS = JurisdictionRegistry.discover();
    static final Pattern CODE = Pattern.compile("\\b(\\d{6})\\b");

    String url;
    Connection c;
    final List<PrincipalMessage> sent = new ArrayList<>();
    final Map<String, String> props = new HashMap<>();

    @BeforeEach
    void db() throws Exception {
        url = "jdbc:h2:mem:d360" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        c = DriverManager.getConnection(url, "sa", "");
        Schema.create(c, D);
        ComplianceSchema.create(c, D);
        props.put("jurisdiction", "IN");
        props.put("organisation-name", "Test Department");
    }

    @AfterEach
    void close() throws Exception {
        c.close();
    }

    SangamCompliance compliance(PrincipalNotifier notifier) {
        ComplianceConfig.Provider config = t -> ComplianceConfig.fromProperties(PACKS, props);
        Transactions tx = new Transactions() {
            @Override
            public <T> T inTransaction(Function<Connection, T> work) {
                throw new AssertionError("the tests pass their own connection");
            }
        };
        SangamCompliance.Ports ports = new SangamCompliance.Ports(null, null, notifier, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
        SangamCompliance cmp = new SangamCompliance(D, tx, config, ports);
        cmp.contacts.set(c, TENANT, new ContactDirectory.Contact("DPO", "Data Protection Officer", "Joint Secretary",
                "Test Department", "dpo@example.gov.in", null, null, null, true, null), "admin-1");
        return cmp;
    }

    SangamCompliance compliance() {
        return compliance((conn, m) -> {
            sent.add(m);
            return "ref-" + sent.size();
        });
    }

    String lastCode() {
        Matcher m = CODE.matcher(sent.get(sent.size() - 1).body());
        assertTrue(m.find(), "the message carries the code");
        return m.group(1);
    }

    static String wrong(String code) {
        return code.equals("000000") ? "111111" : "000000";
    }

    DataSubjectRightsService.Submission otpSubmission(String value, String challengeId) {
        return new DataSubjectRightsService.Submission(null, "ACCESS", "EMAIL", value,
                challengeId == null ? Map.of() : Map.of(PrincipalOtpChallenges.PROOF_CHALLENGE, challengeId), null, "PORTAL", Map.of());
    }

    long events(String type) {
        return Jdbc.one(c, "SELECT COUNT(*) FROM AUDIT_EVENT WHERE tenant_id=? AND event_type=?", rs -> rs.getLong(1), TENANT, type)
                .orElse(0L);
    }

    @Test
    void aCodeIsSentVerifiedAndAuthenticatesOneRequestOnly() {
        SangamCompliance cmp = compliance();
        PrincipalOtpChallenges.Challenge ch = cmp.otp.issue(c, TENANT, "email", EMAIL, null);
        assertEquals("EMAIL", ch.identifierType());
        assertTrue(ch.expiresAt().isAfter(Instant.now().plusSeconds(9 * 60)) && ch.expiresAt().isBefore(Instant.now().plusSeconds(11 * 60)),
                "the pack's 10 minutes");
        PrincipalMessage m = sent.get(0);
        assertEquals(EMAIL.toLowerCase(), m.attributes().get("email"));
        assertEquals(PrincipalOtpChallenges.MESSAGE_KIND, m.kind());
        String code = lastCode();
        assertFalse(Jdbc.query(c, "SELECT code_hash FROM CMP_OTP_CHALLENGE", rs -> rs.getString(1)).contains(code), "hash only");
        assertEquals(PrincipalOtpChallenges.Outcome.VERIFIED, cmp.otp.verify(c, TENANT, ch.challengeId(), code).outcome());

        DataSubjectRightsService.RightsRequest r = cmp.rights.submit(c, TENANT, otpSubmission(EMAIL, ch.challengeId()), null);
        assertEquals(EMAIL.toLowerCase(), r.principalRef());
        ComplianceException reuse = assertThrows(ComplianceException.class,
                () -> cmp.rights.submit(c, TENANT, otpSubmission(EMAIL, ch.challengeId()), null));
        assertEquals(Code.PRINCIPAL_NOT_AUTHENTICATED, reuse.code(), "single use");
        assertEquals(PrincipalOtpChallenges.Outcome.INVALID, cmp.otp.verify(c, TENANT, ch.challengeId(), code).outcome(),
                "a used challenge cannot be verified again");
        assertEquals(1, events(PrincipalOtpChallenges.ISSUED));
        assertEquals(1, events(PrincipalOtpChallenges.VERIFIED));
        assertTrue(events(PrincipalOtpChallenges.FAILED) >= 2);
        assertEquals(0L, Jdbc.one(c, "SELECT COUNT(*) FROM AUDIT_PAYLOAD WHERE payload LIKE ? OR payload LIKE ?",
                rs -> rs.getLong(1), "%" + code + "%", "%" + EMAIL.toLowerCase() + "%").orElse(-1L), "neither code nor identifier audited");
    }

    @Test
    void anUnverifiedChallengeOrAnotherIdentifierDoesNotAuthenticate() {
        SangamCompliance cmp = compliance();
        PrincipalOtpChallenges.Challenge ch = cmp.otp.issue(c, TENANT, "EMAIL", EMAIL, null);
        assertEquals(Code.PRINCIPAL_NOT_AUTHENTICATED, assertThrows(ComplianceException.class,
                () -> cmp.rights.submit(c, TENANT, otpSubmission(EMAIL, ch.challengeId()), null)).code(), "not yet verified");
        cmp.otp.verify(c, TENANT, ch.challengeId(), lastCode());
        assertEquals(Code.PRINCIPAL_NOT_AUTHENTICATED, assertThrows(ComplianceException.class,
                () -> cmp.rights.submit(c, TENANT, otpSubmission("someone@example.in", ch.challengeId()), null)).code(),
                "the challenge is bound to its identifier");
        assertEquals(Code.PRINCIPAL_NOT_AUTHENTICATED, assertThrows(ComplianceException.class,
                () -> cmp.rights.submit(c, TENANT, otpSubmission(EMAIL, null), null)).code(), "no challenge named");
    }

    @Test
    void anExpiredCodeIsRefusedAndCannotAuthenticate() {
        SangamCompliance cmp = compliance();
        PrincipalOtpChallenges.Challenge ch = cmp.otp.issue(c, TENANT, "EMAIL", EMAIL, null);
        String code = lastCode();
        Jdbc.update(c, "UPDATE CMP_OTP_CHALLENGE SET expires_at=? WHERE challenge_id=?", Timestamp.from(Instant.now().minusSeconds(1)),
                ch.challengeId());
        PrincipalOtpChallenges.Verification v = cmp.otp.verify(c, TENANT, ch.challengeId(), code);
        assertEquals(PrincipalOtpChallenges.Outcome.EXPIRED, v.outcome());
        assertEquals(Code.OTP_EXPIRED, assertThrows(ComplianceException.class, v::require).code());
        // a verified challenge that has since expired is not consumed either
        PrincipalOtpChallenges.Challenge ch2 = cmp.otp.issue(c, TENANT, "EMAIL", EMAIL, null);
        cmp.otp.verify(c, TENANT, ch2.challengeId(), lastCode());
        Jdbc.update(c, "UPDATE CMP_OTP_CHALLENGE SET expires_at=? WHERE challenge_id=?", Timestamp.from(Instant.now().minusSeconds(1)),
                ch2.challengeId());
        assertThrows(ComplianceException.class, () -> cmp.rights.submit(c, TENANT, otpSubmission(EMAIL, ch2.challengeId()), null));
    }

    @Test
    void wrongCodesLockTheIdentifierOutEvenAcrossNewChallenges() {
        SangamCompliance cmp = compliance();
        PrincipalOtpChallenges.Challenge a = cmp.otp.issue(c, TENANT, "EMAIL", EMAIL, null);
        String codeA = lastCode();
        for (int i = 0; i < 4; i++) {
            PrincipalOtpChallenges.Verification v = cmp.otp.verify(c, TENANT, a.challengeId(), wrong(codeA));
            assertEquals(PrincipalOtpChallenges.Outcome.WRONG_CODE, v.outcome());
            assertEquals(Code.OTP_INVALID, assertThrows(ComplianceException.class, v::require).code());
        }
        // a new challenge supersedes the first, and the wrong codes still count for the identifier
        PrincipalOtpChallenges.Challenge b = cmp.otp.issue(c, TENANT, "EMAIL", EMAIL, null);
        assertEquals(PrincipalOtpChallenges.Outcome.INVALID, cmp.otp.verify(c, TENANT, a.challengeId(), codeA).outcome(), "superseded");
        String codeB = lastCode();
        assertEquals(PrincipalOtpChallenges.Outcome.LOCKED, cmp.otp.verify(c, TENANT, b.challengeId(), wrong(codeB)).outcome(),
                "the fifth wrong code for the identifier locks it out");
        PrincipalOtpChallenges.Verification locked = cmp.otp.verify(c, TENANT, b.challengeId(), codeB);
        assertEquals(PrincipalOtpChallenges.Outcome.LOCKED, locked.outcome(), "even the right code is refused while locked");
        assertEquals(Code.OTP_LOCKED, assertThrows(ComplianceException.class, locked::require).code());
        assertEquals(Code.OTP_LOCKED, assertThrows(ComplianceException.class,
                () -> cmp.otp.issue(c, TENANT, "EMAIL", EMAIL, null)).code(), "no new code during the lock-out");
        // another identifier is not affected
        assertNotNull(cmp.otp.issue(c, TENANT, "EMAIL", "other@example.in", null));
    }

    @Test
    void aChallengeIsTenantScoped() {
        SangamCompliance cmp = compliance();
        PrincipalOtpChallenges.Challenge ch = cmp.otp.issue(c, TENANT, "EMAIL", EMAIL, null);
        assertEquals(PrincipalOtpChallenges.Outcome.INVALID, cmp.otp.verify(c, "t2", ch.challengeId(), lastCode()).outcome());
        assertEquals(PrincipalOtpChallenges.Outcome.VERIFIED, cmp.otp.verify(c, TENANT, ch.challengeId(), lastCode()).outcome());
        assertThrows(ComplianceException.class, () -> cmp.rights.submit(c, "t2", otpSubmission(EMAIL, ch.challengeId()), null));
    }

    @Test
    void identifierTypesAndSettingsComeFromConfigurationAndFailClosed() {
        SangamCompliance cmp = compliance();
        ComplianceException type = assertThrows(ComplianceException.class, () -> cmp.otp.issue(c, TENANT, "AADHAAR", "1234", null));
        assertEquals(Code.INVALID_INPUT, type.code());
        assertEquals(Code.OTP_NOT_CONFIGURED, assertThrows(ComplianceException.class,
                () -> cmp.otp.issue(c, TENANT, "MOBILE", "+919800000000", null)).code(), "no DLT template configured");
        props.put("rights.otp.sms-template-id", "1107000000000000001");
        cmp.otp.issue(c, TENANT, "MOBILE", "+919800000000", null);
        assertEquals("1107000000000000001", sent.get(sent.size() - 1).attributes().get("templateId"));
        props.put("rights.otp.ttl-minutes", " ");
        assertEquals(Code.OTP_NOT_CONFIGURED, assertThrows(ComplianceException.class,
                () -> cmp.otp.issue(c, TENANT, "EMAIL", EMAIL, null)).code(), "an unset lifetime is not defaulted");
        props.remove("rights.otp.ttl-minutes");
        SangamCompliance silent = compliance(null);
        assertEquals(Code.OTP_NOT_CONFIGURED, assertThrows(ComplianceException.class,
                () -> silent.otp.issue(c, TENANT, "EMAIL", EMAIL, null)).code(), "no notifier, no code");
    }
}

// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.dsc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

import javax.security.auth.x500.X500Principal;

import org.junit.jupiter.api.Test;
import org.sangam.integration.dsc.DscCertificateEnrolments.Enrolment;
import org.sangam.integration.dsc.DscCertificateEnrolments.Status;

/**
 * Owner decision 227: a signer enrols their DSC certificate; the server derives fingerprint, issuer and serial; an
 * admin other than the signer approves or rejects it; the signer or an admin revokes it; only the signer's own APPROVED
 * certificate binds. The certificates are public test certificates.
 */
class DscEnrolmentTest {

    static final Instant T0 = Instant.parse("2026-11-13T10:00:00Z");
    static final Properties CERTS = new Properties();

    static {
        try (InputStream in = DscEnrolmentTest.class.getResourceAsStream("/dsc/test-certificates.properties")) {
            CERTS.load(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private final MemoryEnrolments store = new MemoryEnrolments();
    private final DscEnrolment enrolment = new DscEnrolment(store, Clock.fixed(T0, ZoneOffset.UTC));

    static String cert(String name) {
        return CERTS.getProperty(name);
    }

    static String sha(String name) {
        return DscSigningFlow.sha256Hex(Base64.getDecoder().decode(cert(name)));
    }

    private static X509Certificate x509(String name) throws Exception {
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(Base64.getDecoder().decode(cert(name))));
    }

    /** Enrols and approves {@code name} for {@code principal} (approved by another principal). */
    static Enrolment approved(DscEnrolment s, String tenant, String principal, String name) {
        Enrolment e = s.enrol(tenant, principal, cert(name));
        return s.approve(tenant, e.enrolmentId(), "user:admin", "token seen");
    }

    private static String code(Runnable r) {
        return assertThrows(DscEnrolment.Refused.class, r::run).code();
    }

    @Test
    void theServerDerivesTheIdentityFromTheCertificate() throws Exception {
        Enrolment e = enrolment.enrol("T1", "user:asha", cert("rsa"));
        X509Certificate c = x509("rsa");
        assertEquals(Status.PENDING, e.status());
        assertEquals(sha("rsa"), e.certificateSha256());
        assertEquals(c.getIssuerX500Principal().getName(X500Principal.RFC2253), e.issuer());
        assertEquals(c.getSerialNumber().toString(16), e.serialHex());
        assertEquals(c.getSubjectX500Principal().getName(X500Principal.RFC2253), e.subject());
        assertArrayEquals(c.getEncoded(), e.certificate());
        assertTrue(e.enrolmentId().matches("[0-9a-f]{32}"));
        assertEquals(T0, e.enrolledAt());
        assertTrue(enrolment.approvedFor("T1", "user:asha", sha("rsa")).isEmpty(), "pending does not bind");
    }

    @Test
    void anAdminOtherThanTheSignerApproves() {
        Enrolment e = enrolment.enrol("T1", "user:asha", cert("rsa"));
        assertEquals("SELF_APPROVAL", code(() -> enrolment.approve("T1", e.enrolmentId(), "user:asha", null)));
        assertEquals("SELF_APPROVAL", code(() -> enrolment.reject("T1", e.enrolmentId(), "user:asha", "no")));
        Enrolment a = enrolment.approve("T1", e.enrolmentId(), "user:admin", " checked ");
        assertEquals(Status.APPROVED, a.status());
        assertEquals("user:admin", a.decidedBy());
        assertEquals("checked", a.decisionReason());
        assertEquals(e.enrolmentId(), enrolment.approvedFor("T1", "user:asha", sha("rsa")).orElseThrow().enrolmentId());
        assertEquals("ENROLMENT_NOT_PENDING", code(() -> enrolment.approve("T1", e.enrolmentId(), "user:admin2", null)));
    }

    @Test
    void onlyTheSignersOwnApprovedCertificateBindsAndOnlyInItsTenant() {
        approved(enrolment, "T1", "user:asha", "rsa");
        assertTrue(enrolment.approvedFor("T1", "user:asha", sha("rsa")).isPresent());
        assertTrue(enrolment.approvedFor("T1", "user:ravi", sha("rsa")).isEmpty(), "another principal");
        assertTrue(enrolment.approvedFor("T2", "user:asha", sha("rsa")).isEmpty(), "another tenant");
        assertTrue(enrolment.approvedFor("T1", "user:asha", sha("ec")).isEmpty(), "another certificate");
        assertTrue(enrolment.approvedFor("T1", null, sha("rsa")).isEmpty());
    }

    @Test
    void aLiveCertificateCannotBeEnrolledTwiceButARevokedOneCan() {
        Enrolment first = approved(enrolment, "T1", "user:asha", "rsa");
        assertEquals("CERTIFICATE_ALREADY_ENROLLED", code(() -> enrolment.enrol("T1", "user:ravi", cert("rsa"))));
        assertEquals("CERTIFICATE_ALREADY_ENROLLED", code(() -> enrolment.enrol("T1", "user:asha", cert("rsa"))));
        enrolment.revoke("T1", first.enrolmentId(), "user:asha", false, "token lost");
        assertTrue(enrolment.approvedFor("T1", "user:asha", sha("rsa")).isEmpty(), "revoked does not bind");
        Enrolment again = enrolment.enrol("T1", "user:asha", cert("rsa"));
        assertNotEquals(first.enrolmentId(), again.enrolmentId());
        assertEquals(Status.PENDING, again.status());
        assertTrue(enrolment.approvedFor("T1", "user:asha", sha("rsa")).isEmpty(), "a new approval is needed");
        assertEquals(Status.PENDING, enrolment.enrol("T2", "user:asha", cert("rsa")).status(), "tenants are separate");
    }

    @Test
    void everyApprovalHasItsOwnIdentityAndOnlyTheBoundOneStaysApproved() {
        // integrator ruling on decision 241: (enrolment id, approval sequence) names one approval; the clock never moves
        Enrolment first = approved(enrolment, "T1", "user:asha", "rsa");
        assertEquals(1, first.approvalSeq());
        assertTrue(enrolment.stillApproved("T1", "user:asha", sha("rsa"), first.enrolmentId(), 1));
        assertFalse(enrolment.stillApproved("T1", "user:ravi", sha("rsa"), first.enrolmentId(), 1), "another principal");
        assertFalse(enrolment.stillApproved("T1", "user:asha", sha("ec"), first.enrolmentId(), 1), "another certificate");
        assertFalse(enrolment.stillApproved("T2", "user:asha", sha("rsa"), first.enrolmentId(), 1), "another tenant");
        assertFalse(enrolment.stillApproved("T1", "user:asha", sha("rsa"), first.enrolmentId(), 2), "another approval");
        enrolment.revoke("T1", first.enrolmentId(), "user:asha", false, "token lost");
        assertFalse(enrolment.stillApproved("T1", "user:asha", sha("rsa"), first.enrolmentId(), 1), "revoked");
        Enrolment again = approved(enrolment, "T1", "user:asha", "rsa");
        assertEquals(2, again.approvalSeq(), "the counter carries on across the re-enrolment");
        assertNotEquals(first.enrolmentId(), again.enrolmentId());
        assertFalse(enrolment.stillApproved("T1", "user:asha", sha("rsa"), first.enrolmentId(), 1),
                "the new approval never revives the old binding, at the same instant too");
        assertTrue(enrolment.stillApproved("T1", "user:asha", sha("rsa"), again.enrolmentId(), 2));
        assertFalse(enrolment.stillApproved("T1", "user:asha", sha("rsa"), null, 2));
    }

    @Test
    void revocationByTheSignerOrAnAdminAndRejectionNeedAReason() {
        Enrolment e = approved(enrolment, "T1", "user:asha", "rsa");
        assertEquals("REASON_REQUIRED", code(() -> enrolment.revoke("T1", e.enrolmentId(), "user:asha", false, " ")));
        assertEquals("ENROLMENT_NOT_FOUND", code(() -> enrolment.revoke("T1", e.enrolmentId(), "user:ravi", false,
                "not mine")), "a stranger cannot revoke and learns nothing");
        assertEquals(Status.REVOKED, enrolment.revoke("T1", e.enrolmentId(), "user:admin", true, "left").status());
        assertEquals("ENROLMENT_NOT_LIVE", code(() -> enrolment.revoke("T1", e.enrolmentId(), "user:asha", false,
                "again")));
        Enrolment p = enrolment.enrol("T1", "user:asha", cert("ec"));
        assertEquals("REASON_REQUIRED", code(() -> enrolment.reject("T1", p.enrolmentId(), "user:admin", null)));
        assertEquals(Status.REJECTED, enrolment.reject("T1", p.enrolmentId(), "user:admin", "wrong token").status());
        assertEquals("ENROLMENT_NOT_PENDING", code(() -> enrolment.approve("T1", p.enrolmentId(), "user:admin", null)));
        assertEquals("REASON_TOO_LONG", code(() -> enrolment.revoke("T1", e.enrolmentId(), "user:admin", true,
                "x".repeat(513))));
    }

    @Test
    void badCertificatesAndUnknownIdsAreRefused() {
        assertEquals("CERTIFICATE_INVALID", code(() -> enrolment.enrol("T1", "user:asha", "bm90IGEgY2VydA==")));
        assertEquals("CERTIFICATE_INVALID", code(() -> enrolment.enrol("T1", "user:asha", null)));
        assertEquals("CERTIFICATE_KEY_UNSUPPORTED", code(() -> enrolment.enrol("T1", "user:asha", cert("dsa"))));
        assertEquals("ENROLMENT_NOT_FOUND", code(() -> enrolment.approve("T1", "nope", "user:admin", null)));
        Enrolment e = enrolment.enrol("T1", "user:asha", cert("rsa"));
        assertEquals("ENROLMENT_NOT_FOUND", code(() -> enrolment.approve("T2", e.enrolmentId(), "user:admin", null)),
                "another tenant's enrolment is not visible");
    }

    @Test
    void anExpiredCertificateIsNeitherEnrolledNorApproved() throws Exception {
        Instant after = x509("rsa").getNotAfter().toInstant();
        DscEnrolment late = new DscEnrolment(store, Clock.fixed(after, ZoneOffset.UTC));
        assertEquals("CERTIFICATE_EXPIRED", code(() -> late.enrol("T1", "user:asha", cert("rsa"))));
        Enrolment e = enrolment.enrol("T1", "user:asha", cert("rsa"));
        assertEquals("CERTIFICATE_EXPIRED", code(() -> late.approve("T1", e.enrolmentId(), "user:admin", null)));
        assertFalse(enrolment.approvedFor("T1", "user:asha", sha("rsa")).isPresent());
    }

    @Test
    void theAdminQueueListsPendingOldestFirst() {
        enrolment.enrol("T1", "user:asha", cert("rsa"));
        enrolment.enrol("T1", "user:ravi", cert("other"));
        approved(enrolment, "T1", "user:asha", "ec");
        assertEquals(2, enrolment.withStatus("T1", Status.PENDING, 50).size());
        assertEquals(1, enrolment.withStatus("T1", Status.PENDING, 1).size());
        assertEquals(1, enrolment.withStatus("T1", Status.APPROVED, 50).size());
        assertEquals(2, enrolment.mine("T1", "user:asha").size());
    }

    /** The store's contract in memory: one row per tenant and certificate, conditional decisions. */
    static final class MemoryEnrolments implements DscCertificateEnrolments {
        private final Map<String, Enrolment> rows = new LinkedHashMap<>();

        private static String key(String tenant, String sha) {
            return tenant + "|" + sha;
        }

        @Override
        public synchronized boolean enrol(Enrolment pending) {
            Enrolment cur = rows.get(key(pending.tenantId(), pending.certificateSha256()));
            if (cur != null && LIVE.contains(cur.status())) {
                return false;
            }
            // like the table: a re-enrolment updates the row and keeps its approval counter
            rows.put(key(pending.tenantId(), pending.certificateSha256()), cur == null ? pending : cur.reenrolled(pending));
            return true;
        }

        @Override
        public synchronized Optional<Enrolment> byId(String tenantId, String enrolmentId) {
            return rows.values().stream().filter(e -> e.tenantId().equals(tenantId)
                    && e.enrolmentId().equals(enrolmentId)).findFirst();
        }

        @Override
        public synchronized Optional<Enrolment> byCertificate(String tenantId, String certificateSha256) {
            return Optional.ofNullable(rows.get(key(tenantId, certificateSha256)));
        }

        @Override
        public synchronized List<Enrolment> ofPrincipal(String tenantId, String principalId) {
            return rows.values().stream().filter(e -> e.tenantId().equals(tenantId)
                    && e.principalId().equals(principalId)).toList();
        }

        @Override
        public synchronized List<Enrolment> withStatus(String tenantId, Status status, int limit) {
            List<Enrolment> out = new ArrayList<>(rows.values().stream().filter(e -> e.tenantId().equals(tenantId)
                    && e.status() == status).sorted(Comparator.comparing(Enrolment::enrolledAt)).toList());
            return out.size() <= limit ? out : out.subList(0, limit);
        }

        @Override
        public synchronized boolean decide(String tenantId, String enrolmentId, Set<Status> from, Status to,
                                           String decidedBy, Instant at, String reason) {
            Optional<Enrolment> e = byId(tenantId, enrolmentId);
            if (e.isEmpty() || !from.contains(e.get().status())) {
                return false;
            }
            rows.put(key(tenantId, e.get().certificateSha256()), e.get().decided(to, decidedBy, at, reason));
            return true;
        }
    }
}

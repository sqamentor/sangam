// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.dsc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.sangam.integration.signature.DscSignatureVerifier;
import org.sangam.integration.signature.DscSigning;
import org.sangam.integration.signature.SignatureVerification;

/**
 * Owner decisions 165 and 140: the prepared state of a DSC signature is bound to tenant, task, document, signer and
 * prepareId, expires and is used once; only a verified signature returns the signed PDF. The PDF and CMS work is a
 * stand-in here (sangam-esign has its own tests); the certificates are public test certificates.
 */
class DscSigningFlowTest {

    private static final Instant T0 = Instant.parse("2026-11-13T10:00:00Z");
    private static final Properties CERTS = new Properties();

    static {
        try (InputStream in = DscSigningFlowTest.class.getResourceAsStream("/dsc/test-certificates.properties")) {
            CERTS.load(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private final AtomicReference<Instant> now = new AtomicReference<>(T0);
    private final MemoryStore store = new MemoryStore();
    private final FakeSigning signing = new FakeSigning();
    private final List<DscSignatureVerifier.Expectation> verified = new ArrayList<>();
    private SignatureVerification.Outcome verdict = SignatureVerification.Outcome.VERIFIED_VALID;
    private final DscEnrolment enrolment = new DscEnrolment(new DscEnrolmentTest.MemoryEnrolments(), clock());
    /** Owner decision 227: Asha's RSA certificate is enrolled and approved; her EC certificate (same CN) is not. */
    private final DscCertificateEnrolments.Enrolment ashaRsa = DscEnrolmentTest.approved(enrolment, "T1", "user:asha",
            "rsa");

    private static String cert(String name) {
        return CERTS.getProperty(name);
    }

    private Clock clock() {
        return new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
    }

    private DscSigningFlow flow() {
        DscSignatureVerifier verifier = (expected, pdf) -> {
            verified.add(expected);
            return switch (verdict) {
                case VERIFIED_VALID -> new SignatureVerification(SignatureVerification.Outcome.VERIFIED_VALID, null, null,
                        false, "p1", "CN=Asha Verma", expected.signerCertificateSha256(), null, null, null, null,
                        now.get(), false);
                case VERIFIED_INVALID -> SignatureVerification.invalid("UNTRUSTED_CHAIN", "no anchor", "p1", now.get());
                case INDETERMINATE -> SignatureVerification.indeterminate("REVOCATION_UNAVAILABLE", "ocsp down", true,
                        "p1", now.get());
            };
        };
        return new DscSigningFlow(signing, verifier, store, enrolment, clock(), Duration.ofMinutes(10), "p1");
    }

    private static DscSigningFlow.Binding binding() {
        return new DscSigningFlow.Binding("T1", "task-1", "doc-1", 3, "a".repeat(64), "user:asha");
    }

    private static final byte[] PDF = "%PDF-1.7 test".getBytes(StandardCharsets.US_ASCII);

    @Test
    void prepareKeepsEverythingAndReturnsOnlyWhatTheAgentSigns() {
        DscSigningFlow.PrepareResult r = flow().prepare(binding(), PDF, List.of(cert("rsa")));
        assertEquals("SHA256withRSA", r.algorithm());
        assertEquals(T0.plus(Duration.ofMinutes(10)), r.expiresAt());
        assertTrue(r.prepareId().matches("[0-9a-f]{32}"), "128-bit random id");
        assertEquals(FakeSigning.DIGEST, r.preparedDigestSha256());
        assertArrayEquals(FakeSigning.ATTRS, Base64.getDecoder().decode(r.signedAttributes()));
        DscPreparedStore.Entry e = store.rows.get(r.prepareId()).entry;
        assertEquals("task-1", e.taskId());
        assertEquals(3, e.documentVersion());
        assertEquals("user:asha", e.principalId());
        assertEquals(DscSigningFlow.sha256Hex(encoded("rsa")), e.signerCertificateSha256());
        assertArrayEquals(FakeSigning.PREPARED, e.preparedPdf());
        assertNotEquals(r.prepareId(), flow().prepare(binding(), PDF, List.of(cert("rsa"))).prepareId());
    }

    private static byte[] encoded(String name) {
        return Base64.getDecoder().decode(cert(name));
    }

    @Test
    void anEcKeyUsesEcdsaAndOtherKeysOrSignersAreRefusedBeforeAnythingIsKept() {
        DscEnrolmentTest.approved(enrolment, "T1", "user:asha", "ec");
        assertEquals("SHA256withECDSA", flow().prepare(binding(), PDF, List.of(cert("ec"))).algorithm());
        int kept = store.rows.size();
        assertEquals("DSC_ALGORITHM", assertThrows(DscSigningFlow.Rejected.class,
                () -> flow().prepare(binding(), PDF, List.of(cert("dsa")))).code());
        assertEquals("CERTIFICATE_NOT_ENROLLED", assertThrows(DscSigningFlow.Rejected.class,
                () -> flow().prepare(binding(), PDF, List.of(cert("other")))).code());
        assertEquals("CERTIFICATE_CHAIN_INVALID", assertThrows(DscSigningFlow.Rejected.class,
                () -> flow().prepare(binding(), PDF, List.of("bm90IGEgY2VydA=="))).code());
        assertEquals("CERTIFICATE_CHAIN_INVALID", assertThrows(DscSigningFlow.Rejected.class,
                () -> flow().prepare(binding(), PDF, List.of())).code());
        assertEquals(kept, store.rows.size(), "nothing is kept for a refused prepare");
    }

    @Test
    void onlyTheCallersOwnApprovedCertificateBindsNeverTheName() {
        // owner decision 227: the EC certificate carries the same CN as the approved RSA one, and is still refused
        assertEquals("CERTIFICATE_NOT_ENROLLED", assertThrows(DscSigningFlow.Rejected.class,
                () -> flow().prepare(binding(), PDF, List.of(cert("ec")))).code());
        DscCertificateEnrolments.Enrolment pending = enrolment.enrol("T1", "user:asha", cert("ec"));
        assertEquals("CERTIFICATE_NOT_ENROLLED", assertThrows(DscSigningFlow.Rejected.class,
                () -> flow().prepare(binding(), PDF, List.of(cert("ec")))).code(), "pending is not approved");
        enrolment.approve("T1", pending.enrolmentId(), "user:admin", null);
        assertEquals("SHA256withECDSA", flow().prepare(binding(), PDF, List.of(cert("ec"))).algorithm());
        DscSigningFlow.Binding ravi = new DscSigningFlow.Binding("T1", "task-1", "doc-1", 3, "a".repeat(64),
                "user:ravi");
        assertEquals("CERTIFICATE_NOT_ENROLLED", assertThrows(DscSigningFlow.Rejected.class,
                () -> flow().prepare(ravi, PDF, List.of(cert("rsa")))).code(), "Asha's certificate is not Ravi's");
        DscSigningFlow.Binding otherTenant = new DscSigningFlow.Binding("T2", "task-1", "doc-1", 3, "a".repeat(64),
                "user:asha");
        assertEquals("CERTIFICATE_NOT_ENROLLED", assertThrows(DscSigningFlow.Rejected.class,
                () -> flow().prepare(otherTenant, PDF, List.of(cert("rsa")))).code());
    }

    @Test
    void anEnrolmentRevokedBeforeCompleteMakesTheSignatureInvalid() {
        DscSigningFlow f = flow();
        String id = f.prepare(binding(), PDF, List.of(cert("rsa"))).prepareId();
        enrolment.revoke("T1", ashaRsa.enrolmentId(), "user:asha", false, "token lost");
        DscSigningFlow.CompleteResult r = f.complete("T1", "task-1", "user:asha", id, b64("good"), "SHA256withRSA",
                List.of(cert("rsa")));
        assertEquals(SignatureVerification.Outcome.VERIFIED_INVALID, r.verification().outcome());
        assertEquals("CERTIFICATE_NOT_ENROLLED", r.verification().reasonCode());
        assertNull(r.signedPdf());
        assertEquals("VERIFIED_INVALID", store.rows.get(id).outcome);
        assertTrue(verified.isEmpty(), "nothing is embedded or verified for a revoked certificate");
        assertThrows(DscSigningFlow.NotUsable.class, () -> f.complete("T1", "task-1", "user:asha", id, b64("good"),
                "SHA256withRSA", List.of(cert("rsa"))), "the attempt used the prepare up");
    }

    @Test
    void aReApprovalBetweenPrepareAndCompleteIsNotTheApprovalThePrepareBound() {
        DscSigningFlow f = flow();
        String id = f.prepare(binding(), PDF, List.of(cert("rsa"))).prepareId();
        assertEquals(ashaRsa.enrolmentId(), store.rows.get(id).entry.enrolmentId(), "prepare stores the approval");
        assertEquals(ashaRsa.approvalSeq(), store.rows.get(id).entry.enrolmentApprovalSeq());
        enrolment.revoke("T1", ashaRsa.enrolmentId(), "user:asha", false, "token lost");
        DscEnrolmentTest.approved(enrolment, "T1", "user:asha", "rsa");   // same certificate, same instant
        DscSigningFlow.CompleteResult r = f.complete("T1", "task-1", "user:asha", id, b64("good"), "SHA256withRSA",
                List.of(cert("rsa")));
        assertEquals(SignatureVerification.Outcome.VERIFIED_INVALID, r.verification().outcome());
        assertEquals("CERTIFICATE_NOT_ENROLLED", r.verification().reasonCode());
        assertNull(r.signedPdf());
    }

    @Test
    void aVerifiedSignatureReturnsTheSignedPdfAndIsBoundToTheServersRecord() {
        DscSigningFlow f = flow();
        String id = f.prepare(binding(), PDF, List.of(cert("rsa"))).prepareId();
        DscSigningFlow.CompleteResult r = f.complete("T1", "task-1", "user:asha", id, b64("good"), "SHA256withRSA",
                List.of(cert("rsa")));
        assertEquals(SignatureVerification.Outcome.VERIFIED_VALID, r.verification().outcome());
        assertArrayEquals(FakeSigning.SIGNED, r.signedPdf());
        DscSignatureVerifier.Expectation x = verified.get(0);
        assertEquals(FakeSigning.DIGEST, x.preparedDigestSha256());
        assertEquals("doc-1", x.documentId());
        assertEquals(DscEnrolmentTest.sha("rsa"), x.signerCertificateSha256(), "the enrolled certificate binds");
        assertEquals(r.entry().signerCertificateSha256(), x.signerCertificateSha256());
        assertEquals("Asha Verma", r.entry().intendedSignerName(), "the shown name comes from the certificate");
        assertNull(store.rows.get(id).outcome, "the caller records VALID after storing the signed version");
        f.recordSigned(r.entry(), 4);
        assertEquals("VERIFIED_VALID", store.rows.get(id).outcome);
        assertTrue(f.hasVerifiedSignature("T1", "task-1", "doc-1", "user:asha"));
        assertFalse(f.hasVerifiedSignature("T1", "task-2", "doc-1", "user:asha"));
    }

    @Test
    void aSignatureCountsOnlyForItsSignerAndItsExactDocument() {
        // owner decisions 236 and 237: Ravi signs with his own approved certificate; that is not Asha's signature
        DscEnrolmentTest.approved(enrolment, "T1", "user:ravi", "other");
        DscSigningFlow f = flow();
        DscSigningFlow.Binding ravi = new DscSigningFlow.Binding("T1", "task-1", "doc-1", 3, "a".repeat(64),
                "user:ravi");
        String id = f.prepare(ravi, PDF, List.of(cert("other"))).prepareId();
        DscSigningFlow.CompleteResult r = f.complete("T1", "task-1", "user:ravi", id, b64("good"), "SHA256withRSA",
                List.of(cert("other")));
        assertEquals(SignatureVerification.Outcome.VERIFIED_VALID, r.verification().outcome());
        f.recordSigned(r.entry(), 4);
        assertTrue(f.hasVerifiedSignature("T1", "task-1", "doc-1", "user:ravi"));
        assertFalse(f.hasVerifiedSignature("T1", "task-1", "doc-1", "user:asha"), "another officer's signature");
        assertFalse(f.hasVerifiedSignature("T1", "task-1", "doc-2", "user:ravi"), "another document");
        assertFalse(f.hasVerifiedSignature("T2", "task-1", "doc-1", "user:ravi"), "another tenant");
        assertFalse(f.hasVerifiedSignature("T1", "task-1", "doc-1", null));
        assertFalse(f.hasVerifiedSignature(null, "task-1", "doc-1", "user:ravi"));
    }

    @Test
    void aRevokeVoidsTheSignatureUntilTheOfficerSignsAgainUnderANewApproval() {
        // owner decision 241 with the integrator ruling (identity, not time): the clock never moves in this test, so a
        // timestamp comparison could not tell the approvals apart; the enrolment id and approval sequence do
        DscSigningFlow f = flow();
        signAndStore(f, 4);
        assertTrue(f.hasVerifiedSignature("T1", "task-1", "doc-1", "user:asha"));
        enrolment.revoke("T1", ashaRsa.enrolmentId(), "user:admin", true, "token reported lost");
        assertFalse(f.hasVerifiedSignature("T1", "task-1", "doc-1", "user:asha"), "a revoke voids");
        assertEquals("VERIFIED_VALID", store.rows.values().iterator().next().outcome, "the evidence stays recorded");
        DscCertificateEnrolments.Enrolment again = enrolment.enrol("T1", "user:asha", cert("rsa"));
        assertNotEquals(ashaRsa.enrolmentId(), again.enrolmentId());
        assertFalse(f.hasVerifiedSignature("T1", "task-1", "doc-1", "user:asha"), "pending is not approved");
        DscCertificateEnrolments.Enrolment reapproved = enrolment.approve("T1", again.enrolmentId(), "user:admin",
                "token found");
        assertEquals(ashaRsa.approvalSeq() + 1, reapproved.approvalSeq());
        assertFalse(f.hasVerifiedSignature("T1", "task-1", "doc-1", "user:asha"),
                "the old signature was bound to the revoked approval");
        signAndStore(f, 5);
        assertTrue(f.hasVerifiedSignature("T1", "task-1", "doc-1", "user:asha"), "signed again under the new approval");
        assertEquals(2, store.rows.size(), "both signatures stay recorded");
    }

    @Test
    void theStatusNamesARevokedEnrolmentSoCompletionCanAnswerItsOwnCode() {
        // decision 241 follow-up: the completion gate answers DSC_ENROLMENT_REVOKED, not the generic refusal, when the
        // officer did sign but the enrolment the signature was bound to no longer stands
        DscSigningFlow f = flow();
        assertEquals(DscSigningFlow.SignatureStatus.NOT_SIGNED,
                f.signatureStatus("T1", "task-1", "doc-1", "user:asha"), "nothing signed yet");
        signAndStore(f, 4);
        assertEquals(DscSigningFlow.SignatureStatus.VERIFIED, f.signatureStatus("T1", "task-1", "doc-1", "user:asha"));
        assertEquals(DscSigningFlow.SignatureStatus.NOT_SIGNED,
                f.signatureStatus("T1", "task-1", "doc-1", "user:ravi"), "another officer did not sign");
        assertEquals(DscSigningFlow.SignatureStatus.NOT_SIGNED,
                f.signatureStatus("T1", "task-1", "doc-2", "user:asha"), "another document was not signed");
        enrolment.revoke("T1", ashaRsa.enrolmentId(), "user:admin", true, "token reported lost");
        assertEquals(DscSigningFlow.SignatureStatus.ENROLMENT_REVOKED,
                f.signatureStatus("T1", "task-1", "doc-1", "user:asha"));
        assertFalse(f.hasVerifiedSignature("T1", "task-1", "doc-1", "user:asha"));
        assertEquals("DSC_ENROLMENT_REVOKED", DscSigningFlow.SignatureStatus.ENROLMENT_REVOKED_CODE);
        DscCertificateEnrolments.Enrolment again = enrolment.enrol("T1", "user:asha", cert("rsa"));
        enrolment.approve("T1", again.enrolmentId(), "user:admin", "token found");
        assertEquals(DscSigningFlow.SignatureStatus.ENROLMENT_REVOKED,
                f.signatureStatus("T1", "task-1", "doc-1", "user:asha"), "a re-approval does not revive it");
        signAndStore(f, 5);
        assertEquals(DscSigningFlow.SignatureStatus.VERIFIED, f.signatureStatus("T1", "task-1", "doc-1", "user:asha"),
                "one signature under the current approval is enough");
    }

    @Test
    void aRevokeOfAnotherCertificateDoesNotVoidTheSignature() {
        DscCertificateEnrolments.Enrolment ec = DscEnrolmentTest.approved(enrolment, "T1", "user:asha", "ec");
        DscSigningFlow f = flow();
        now.set(T0.plusSeconds(60));
        signAndStore(f, 4);
        enrolment.revoke("T1", ec.enrolmentId(), "user:asha", false, "not used");
        assertTrue(f.hasVerifiedSignature("T1", "task-1", "doc-1", "user:asha"), "the RSA approval is unchanged");
    }

    private void signAndStore(DscSigningFlow f, int signedVersion) {
        String id = f.prepare(binding(), PDF, List.of(cert("rsa"))).prepareId();
        DscSigningFlow.CompleteResult r = f.complete("T1", "task-1", "user:asha", id, b64("good"), "SHA256withRSA",
                List.of(cert("rsa")));
        assertEquals(SignatureVerification.Outcome.VERIFIED_VALID, r.verification().outcome());
        f.recordSigned(r.entry(), signedVersion);
    }

    @Test
    void aVerifiedSignatureWhoseSignedVersionWasNotStoredDoesNotCount() {
        DscSigningFlow f = flow();
        String id = f.prepare(binding(), PDF, List.of(cert("rsa"))).prepareId();
        DscSigningFlow.CompleteResult r = f.complete("T1", "task-1", "user:asha", id, b64("good"), "SHA256withRSA",
                List.of(cert("rsa")));
        assertEquals(SignatureVerification.Outcome.VERIFIED_VALID, r.verification().outcome());
        assertFalse(f.hasVerifiedSignature("T1", "task-1", "doc-1", "user:asha"), "not stored yet");
        f.recordNotStored(r.entry(), "DOCUMENT_CHANGED");   // decision 228: another version won the write
        assertFalse(f.hasVerifiedSignature("T1", "task-1", "doc-1", "user:asha"));
    }

    @Test
    void aPrepareIsUsedOnceOnlyAndOnlyByItsTaskAndSigner() {
        DscSigningFlow f = flow();
        String id = f.prepare(binding(), PDF, List.of(cert("rsa"))).prepareId();
        assertThrows(DscSigningFlow.NotUsable.class, () -> f.complete("T2", "task-1", "user:asha", id, b64("good"),
                "SHA256withRSA", List.of(cert("rsa"))), "other tenant");
        assertThrows(DscSigningFlow.NotUsable.class, () -> f.complete("T1", "task-9", "user:asha", id, b64("good"),
                "SHA256withRSA", List.of(cert("rsa"))), "other task");
        assertThrows(DscSigningFlow.NotUsable.class, () -> f.complete("T1", "task-1", "user:ravi", id, b64("good"),
                "SHA256withRSA", List.of(cert("rsa"))), "other principal");
        assertEquals(SignatureVerification.Outcome.VERIFIED_INVALID, f.complete("T1", "task-1", "user:asha", id,
                b64("bad"), "SHA256withRSA", List.of(cert("rsa"))).verification().outcome());
        assertThrows(DscSigningFlow.NotUsable.class, () -> f.complete("T1", "task-1", "user:asha", id, b64("good"),
                "SHA256withRSA", List.of(cert("rsa"))), "a failed attempt used it up");
        assertThrows(DscSigningFlow.NotUsable.class, () -> f.complete("T1", "task-1", "user:asha", "nope", b64("good"),
                "SHA256withRSA", List.of(cert("rsa"))));
    }

    @Test
    void anExpiredPrepareIsNotUsable() {
        DscSigningFlow f = flow();
        String id = f.prepare(binding(), PDF, List.of(cert("rsa"))).prepareId();
        now.set(T0.plus(Duration.ofMinutes(10)));
        assertThrows(DscSigningFlow.NotUsable.class, () -> f.complete("T1", "task-1", "user:asha", id, b64("good"),
                "SHA256withRSA", List.of(cert("rsa"))));
    }

    @Test
    void anotherCertificateOrAlgorithmOrABadSignatureIsInvalidAndRecorded() {
        DscSigningFlow f = flow();
        String a = f.prepare(binding(), PDF, List.of(cert("rsa"))).prepareId();
        DscSigningFlow.CompleteResult r1 = f.complete("T1", "task-1", "user:asha", a, b64("good"), "SHA256withRSA",
                List.of(cert("other")));
        assertEquals("DSC_SIGNER_CHANGED", r1.verification().reasonCode());
        assertNull(r1.signedPdf());
        assertEquals("VERIFIED_INVALID", store.rows.get(a).outcome);
        String b = f.prepare(binding(), PDF, List.of(cert("rsa"))).prepareId();
        assertEquals("DSC_ALGORITHM", f.complete("T1", "task-1", "user:asha", b, b64("good"), "SHA256withECDSA",
                List.of(cert("rsa"))).verification().reasonCode());
        String c = f.prepare(binding(), PDF, List.of(cert("rsa"))).prepareId();
        assertEquals("DSC_SIGNATURE_INVALID", f.complete("T1", "task-1", "user:asha", c, b64("bad"), "SHA256withRSA",
                List.of(cert("rsa"))).verification().reasonCode());
        assertTrue(verified.isEmpty(), "nothing reaches the verifier when the token's signature is not the prepared one");
        String d = f.prepare(binding(), PDF, List.of(cert("rsa"))).prepareId();
        assertEquals("INVALID_INPUT", f.complete("T1", "task-1", "user:asha", d, "%%%", "SHA256withRSA",
                List.of(cert("rsa"))).verification().reasonCode());
    }

    @Test
    void anInvalidOrIndeterminateVerificationNeverReturnsTheSignedPdf() {
        for (SignatureVerification.Outcome o : List.of(SignatureVerification.Outcome.VERIFIED_INVALID,
                SignatureVerification.Outcome.INDETERMINATE)) {
            verdict = o;
            DscSigningFlow f = flow();
            String id = f.prepare(binding(), PDF, List.of(cert("rsa"))).prepareId();
            DscSigningFlow.CompleteResult r = f.complete("T1", "task-1", "user:asha", id, b64("good"), "SHA256withRSA",
                    List.of(cert("rsa")));
            assertEquals(o, r.verification().outcome());
            assertNull(r.signedPdf());
            assertEquals(o.name(), store.rows.get(id).outcome);
            assertFalse(f.hasVerifiedSignature("T1", "task-1", "doc-1", "user:asha"));
        }
    }

    @Test
    void anEmbeddingProblemIsIndeterminateNotValid() {
        DscSigningFlow f = flow();
        String id = f.prepare(binding(), PDF, List.of(cert("rsa"))).prepareId();
        DscSigningFlow.CompleteResult r = f.complete("T1", "task-1", "user:asha", id, b64("too-large"), "SHA256withRSA",
                List.of(cert("rsa")));
        assertEquals(SignatureVerification.Outcome.INDETERMINATE, r.verification().outcome());
        assertEquals("SIGNATURE_TOO_LARGE", r.verification().reasonCode());
    }

    @Test
    void aDocumentThatCannotBePreparedIsRejected() {
        signing.refusePrepare = true;
        assertEquals("PDF_INVALID", assertThrows(DscSigningFlow.Rejected.class,
                () -> flow().prepare(binding(), PDF, List.of(cert("rsa")))).code());
        assertEquals("DOCUMENT_EMPTY", assertThrows(DscSigningFlow.Rejected.class,
                () -> flow().prepare(binding(), new byte[0], List.of(cert("rsa")))).code());
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.US_ASCII));
    }

    /** A stand-in for sangam-esign: "good" is the token's valid signature, "bad" is not. */
    static final class FakeSigning implements DscSigning {
        static final String DIGEST = "b".repeat(64);
        static final byte[] ATTRS = {0x31, 0x03, 0x02, 0x01, 0x01};
        static final byte[] PREPARED = "prepared".getBytes(StandardCharsets.US_ASCII);
        static final byte[] SIGNED = "signed".getBytes(StandardCharsets.US_ASCII);
        boolean refusePrepare;

        @Override
        public Prepared prepare(byte[] pdf, X509Certificate signer, Instant signingTime) {
            if (refusePrepare) {
                throw new Refused("PDF_INVALID", "broken", null);
            }
            try {
                return new Prepared(PREPARED, DIGEST, ATTRS, DscSigningFlow.sha256Hex(signer.getEncoded()), signingTime);
            } catch (java.security.cert.CertificateEncodingException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public byte[] complete(Prepared prepared, X509Certificate signer, List<X509Certificate> chain, String algorithm,
                               byte[] signature) {
            String s = new String(signature, StandardCharsets.US_ASCII);
            if ("too-large".equals(s)) {
                throw new Refused("SIGNATURE_TOO_LARGE", "no room", null);
            }
            if (!"good".equals(s) || !java.util.Arrays.equals(ATTRS, prepared.signedAttributes())) {
                throw new Refused("DSC_SIGNATURE_INVALID", "no", null);
            }
            return SIGNED;
        }
    }

    /** The store's contract in memory: consume is once only, bound and time-limited. */
    static final class MemoryStore implements DscPreparedStore {
        static final class Row {
            Entry entry;
            Instant usedAt;
            String outcome;
            Integer signedVersion;
        }

        final Map<String, Row> rows = new HashMap<>();

        @Override
        public synchronized void save(Entry entry) {
            Row r = new Row();
            r.entry = entry;
            if (rows.putIfAbsent(entry.prepareId(), r) != null) {
                throw new IllegalStateException("duplicate");
            }
        }

        @Override
        public synchronized Optional<Entry> consume(String tenantId, String prepareId, String taskId, String principalId,
                                                    Instant now) {
            Row r = rows.get(prepareId);
            if (r == null || r.usedAt != null || !r.entry.tenantId().equals(tenantId) || !r.entry.taskId().equals(taskId)
                    || !r.entry.principalId().equals(principalId) || !r.entry.expiresAt().isAfter(now)) {
                return Optional.empty();
            }
            r.usedAt = now;
            return Optional.of(r.entry);
        }

        @Override
        public synchronized void recordOutcome(String tenantId, String prepareId, String outcome, String reasonCode,
                                               Integer signedDocumentVersion, Instant at) {
            Row r = rows.get(prepareId);
            if (r == null || r.usedAt == null) {
                throw new IllegalStateException("not consumed");
            }
            r.outcome = outcome;
            r.signedVersion = signedDocumentVersion;
        }

        @Override
        public synchronized List<VerifiedSignature> verifiedSignatures(String tenantId, String taskId,
                                                                        String documentId, String principalId) {
            return rows.values().stream().filter(r -> "VERIFIED_VALID".equals(r.outcome) && r.signedVersion != null
                            && r.entry.tenantId().equals(tenantId) && r.entry.taskId().equals(taskId)
                            && r.entry.documentId().equals(documentId) && r.entry.principalId().equals(principalId))
                    .map(r -> new VerifiedSignature(r.entry.prepareId(), r.entry.signerCertificateSha256(),
                            r.entry.enrolmentId(), r.entry.enrolmentApprovalSeq()))
                    .toList();
        }

        @Override
        public synchronized int purgeExpired(Instant before) {
            int n = rows.size();
            rows.values().removeIf(r -> r.usedAt == null && r.entry.expiresAt().isBefore(before));
            return n - rows.size();
        }
    }
}

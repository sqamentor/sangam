// SPDX-License-Identifier: Apache-2.0
package org.sangam.esign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.cert.TrustAnchor;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * M-080 two-step external signing and AU-01 validation: the prepared PDF hashes to the digest handed to the signer, the
 * CMS is embedded only when it signs that digest (and, for the PAdES baseline profile, names its certificate), the
 * signed PDF validates with the signer chained to a configured anchor, and any change to the signed bytes is reported.
 */
class PadesSigningTest {

    static final Instant SIGNED_AT = Instant.parse("2026-11-12T10:00:00Z");
    static final Clock CLOCK = Clock.fixed(SIGNED_AT, ZoneOffset.UTC);

    @Test
    void prepareReservesAZeroFilledGapAndReturnsTheDigestOfTheByteRanges() throws Exception {
        byte[] pdf = SignTestKit.onePagePdf();
        PadesSigning.Prepared p = PadesSigning.prepare(pdf, PadesSigning.Profile.PKCS7_DETACHED,
                new PadesSigning.Details("Approver", "approved", "HQ", null), SIGNED_AT, 8192);
        int[] br = p.byteRange();
        assertEquals(0, br[0]);
        assertEquals(p.pdf().length, br[2] + br[3]);
        assertTrue(br[2] - br[1] >= 2 * 8192 + 2, "at least the hex of the reserved bytes plus the angle brackets");
        assertEquals(HexFormat.of().formatHex(PadesSigning.sha256(SignTestKit.signedContent(p.pdf(), br))), p.digestSha256());
        for (int i = 0; i < pdf.length; i++) {
            if (pdf[i] != p.pdf()[i]) {
                throw new AssertionError("an incremental update must keep the original bytes (differs at " + i + ")");
            }
        }
    }

    @Test
    void aCmsOverThePreparedDigestIsEmbeddedAndTheResultValidates() throws Exception {
        SignTestKit.Signer signer = SignTestKit.selfSigned("Signer", false);
        PadesSigning.Prepared p = PadesSigning.prepare(SignTestKit.onePagePdf(), PadesSigning.Profile.PKCS7_DETACHED, null,
                SIGNED_AT, 0);
        byte[] cms = SignTestKit.detachedCms(signer, SignTestKit.signedContent(p.pdf(), p.byteRange()), false);
        byte[] signed = PadesSigning.embed(p.pdf(), p.digestSha256(), PadesSigning.Profile.PKCS7_DETACHED, cms);
        assertEquals(p.pdf().length, signed.length, "only the reserved gap changes");

        PdfSignatureValidator.Report report = new PdfSignatureValidator(Set.of(new TrustAnchor(signer.cert(), null)), CLOCK)
                .validate(signed);
        assertTrue(report.valid(), report.toString());
        assertEquals(PdfSignatureValidator.LIMITS, report.limits());
        PdfSignatureValidator.SignatureReport s = report.signatures().get(0);
        assertTrue(s.coversWholeDocument());
        assertTrue(s.integrityValid());
        assertEquals(p.digestSha256(), s.byteRangeDigestSha256());
        assertEquals(Boolean.TRUE, s.chainTrusted());
        assertFalse(s.timestamped());
        assertNull(s.problem());

        PdfSignatureValidator.Report untrusted = new PdfSignatureValidator(
                Set.of(new TrustAnchor(SignTestKit.selfSigned("Other root", false).cert(), null)), CLOCK).validate(signed);
        assertFalse(untrusted.valid());
        assertEquals(Boolean.FALSE, untrusted.signatures().get(0).chainTrusted());

        PdfSignatureValidator.Report noAnchors = new PdfSignatureValidator(Set.of(), CLOCK).validate(signed);
        assertTrue(noAnchors.valid());
        assertNull(noAnchors.signatures().get(0).chainTrusted(), "without anchors the chain is not judged");
    }

    @Test
    void aChangeToTheSignedBytesIsReported() throws Exception {
        SignTestKit.Signer signer = SignTestKit.selfSigned("Signer", false);
        PadesSigning.Prepared p = PadesSigning.prepare(SignTestKit.onePagePdf(), null, null, SIGNED_AT, 0);
        byte[] signed = PadesSigning.embed(p.pdf(), p.digestSha256(), null,
                SignTestKit.detachedCms(signer, SignTestKit.signedContent(p.pdf(), p.byteRange()), false));
        int i = indexOf(signed, "/MediaBox".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertTrue(i > 0 && i < p.byteRange()[1]);
        signed[i + 1] = 'X';
        PdfSignatureValidator.Report r = new PdfSignatureValidator(null, CLOCK).validate(signed);
        assertFalse(r.valid());
        assertFalse(r.signatures().isEmpty() && r.signatures().get(0).integrityValid());
    }

    @Test
    void embedRefusesAnotherDocumentsSignatureAChangedPdfAndAnOversizedCms() throws Exception {
        SignTestKit.Signer signer = SignTestKit.selfSigned("Signer", false);
        PadesSigning.Prepared p = PadesSigning.prepare(SignTestKit.onePagePdf(), null, null, SIGNED_AT, 4096);
        byte[] otherCms = SignTestKit.detachedCms(signer, "another document".getBytes(), false);
        assertEquals("DIGEST_MISMATCH", assertThrows(PadesSigning.SigningException.class,
                () -> PadesSigning.embed(p.pdf(), p.digestSha256(), null, otherCms)).code());

        byte[] cms = SignTestKit.detachedCms(signer, SignTestKit.signedContent(p.pdf(), p.byteRange()), false);
        assertEquals("DIGEST_MISMATCH", assertThrows(PadesSigning.SigningException.class,
                () -> PadesSigning.embed(p.pdf(), "00".repeat(32), null, cms)).code(), "not the digest bound to the approval");

        PadesSigning.Prepared tiny = PadesSigning.prepare(SignTestKit.onePagePdf(), null, null, SIGNED_AT, 64);
        byte[] tinyCms = SignTestKit.detachedCms(signer, SignTestKit.signedContent(tiny.pdf(), tiny.byteRange()), false);
        assertEquals("SIGNATURE_TOO_LARGE", assertThrows(PadesSigning.SigningException.class,
                () -> PadesSigning.embed(tiny.pdf(), tiny.digestSha256(), null, tinyCms)).code());

        byte[] signed = PadesSigning.embed(p.pdf(), p.digestSha256(), null, cms);
        assertEquals("ALREADY_SIGNED", assertThrows(PadesSigning.SigningException.class,
                () -> PadesSigning.embed(signed, p.digestSha256(), null, cms)).code());
        assertEquals("CMS_INVALID", assertThrows(PadesSigning.SigningException.class,
                () -> PadesSigning.embed(p.pdf(), p.digestSha256(), null, new byte[] {1, 2, 3})).code());
        assertEquals("PDF_INVALID", assertThrows(PadesSigning.SigningException.class,
                () -> PadesSigning.prepare("not a pdf".getBytes(), null, null, SIGNED_AT, 0)).code());
    }

    @Test
    void thePadesBaselineProfileNeedsTheSigningCertificateAttribute() throws Exception {
        SignTestKit.Signer signer = SignTestKit.selfSigned("Signer", false);
        PadesSigning.Prepared p = PadesSigning.prepare(SignTestKit.onePagePdf(), PadesSigning.Profile.CADES_DETACHED, null,
                SIGNED_AT, 0);
        byte[] content = SignTestKit.signedContent(p.pdf(), p.byteRange());
        byte[] plain = SignTestKit.detachedCms(signer, content, false);
        assertEquals("CMS_INVALID", assertThrows(PadesSigning.SigningException.class,
                () -> PadesSigning.embed(p.pdf(), p.digestSha256(), PadesSigning.Profile.CADES_DETACHED, plain)).code());

        byte[] cades = SignTestKit.detachedCms(signer, content, true);
        byte[] signed = PadesSigning.embed(p.pdf(), p.digestSha256(), PadesSigning.Profile.CADES_DETACHED, cades);
        PdfSignatureValidator.SignatureReport s = new PdfSignatureValidator(null, CLOCK).validate(signed).signatures().get(0);
        assertEquals("ETSI.CAdES.detached", s.subFilter());
        assertTrue(s.integrityValid());
        assertNotNull(s.signerCertificateSha256());
    }

    @Test
    void aPdfWithoutSignaturesIsNotValid() throws Exception {
        PdfSignatureValidator.Report r = new PdfSignatureValidator(null, CLOCK).validate(SignTestKit.onePagePdf());
        assertFalse(r.valid());
        assertTrue(r.signatures().isEmpty());
        assertFalse(new PdfSignatureValidator(null, CLOCK).validate("junk".getBytes()).valid());
    }

    private static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}

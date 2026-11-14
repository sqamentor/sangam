// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.signature;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.regex.Pattern;

import org.sangam.integration.connector.HttpTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owner decisions 165 and 140: server verification of a PDF signed with a DSC (a signing token driven by the loopback
 * agent of {@code org.sangam.integration.dsc}). The agent's answer is never trusted: the server verifies the signed PDF
 * itself, under the same rules as eSign (configured trust anchors only, revocation required, three outcomes).
 *
 * <p>The implementation needs Bouncy Castle and PDFBox and lives in the optional module sangam-esign (owner decision 217); {@link #load} is empty
 * without it, and DSC signing then stays blocked (nothing else is affected).
 */
public interface DscSignatureVerifier {

    /**
     * What the signed PDF must be bound to, all taken from the server's own record of the prepared document.
     *
     * <p>Owner decision 227: the signer is bound by the exact certificate the signer enrolled and an admin approved.
     * A display name or the certificate's common name is shown, never trusted, so it is not part of the expectation.
     *
     * @param preparedDigestSha256     the byte-range digest {@code PadesSigning.prepare} returned (lower-case hex);
     *                                 the last signature must cover exactly these bytes and nothing may follow it
     * @param signerCertificateSha256  required: SHA-256 (hex) of the DER of the enrolled, approved signer certificate;
     *                                 the signature's signer certificate must be exactly this one
     */
    record Expectation(String tenantId, String documentId, String preparedDigestSha256,
                       String signerCertificateSha256) {

        private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

        public Expectation {
            Objects.requireNonNull(tenantId, "tenantId");
            if (documentId == null || documentId.isBlank()) {
                throw new IllegalArgumentException("documentId is required");
            }
            preparedDigestSha256 = preparedDigestSha256 == null ? null
                    : preparedDigestSha256.trim().toLowerCase(Locale.ROOT);
            if (preparedDigestSha256 == null || !SHA256_HEX.matcher(preparedDigestSha256).matches()) {
                throw new IllegalArgumentException("preparedDigestSha256 must be a SHA-256 digest in hex");
            }
            signerCertificateSha256 = signerCertificateSha256 == null ? null
                    : signerCertificateSha256.trim().toLowerCase(Locale.ROOT);
            if (signerCertificateSha256 == null || !SHA256_HEX.matcher(signerCertificateSha256).matches()) {
                throw new IllegalArgumentException("signerCertificateSha256 is required as SHA-256 hex (decision 227)");
            }
        }
    }

    /** Verifies the signed PDF; never throws for a bad document: every failure is an outcome. */
    SignatureVerification verify(Expectation expected, byte[] signedPdf);

    /** Implementations offered through {@link ServiceLoader}. */
    interface Factory {
        DscSignatureVerifier create(SignaturePolicy policy, HttpTransport revocationTransport, Clock clock);
    }

    /** The installed implementation, or empty when sangam-esign or its libraries are not on the class path. */
    static Optional<DscSignatureVerifier> load(SignaturePolicy policy, HttpTransport revocationTransport, Clock clock) {
        Logger log = LoggerFactory.getLogger(DscSignatureVerifier.class);
        try {
            for (Factory f : ServiceLoader.load(Factory.class, DscSignatureVerifier.class.getClassLoader())) {
                return Optional.of(f.create(policy, revocationTransport, clock));
            }
        } catch (ServiceConfigurationError | LinkageError e) {
            log.warn("DSC verification is not available ({}); DSC signing stays blocked", e.toString());
        }
        return Optional.empty();
    }

    /** A verifier that is always indeterminate: the explicit stand-in when nothing is installed. */
    static DscSignatureVerifier unavailable(String why, Clock clock) {
        return (expected, pdf) -> SignatureVerification.indeterminate("VERIFIER_UNAVAILABLE", why, false, null,
                Instant.now(clock));
    }
}

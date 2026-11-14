// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.signature;

import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owner decision 165: the two server steps of signing a PDF with a DSC through the loopback agent. {@link #prepare}
 * reserves the signature in the PDF and builds the CMS signed attributes the token signs; {@link #complete} checks the
 * token's signature over exactly those attributes, assembles the detached CMS and embeds it. Neither step decides
 * whether the signature is acceptable: that is {@link DscSignatureVerifier} (decision 140, three outcomes).
 *
 * <p>The implementation needs Bouncy Castle and PDFBox and lives in the optional module sangam-esign (owner decision
 * 217); {@link #load} is empty without it, and DSC signing then stays blocked.
 */
public interface DscSigning {

    /** Signature algorithms the agent may use, by JCA name (the agent's list, {@code DscLoopbackAgent}). */
    List<String> ALGORITHMS = List.of("SHA256withRSA", "SHA256withECDSA");

    /**
     * What {@link #prepare} produced: the PDF with the reserved signature, the SHA-256 of its byte ranges (lower-case
     * hex), the DER of the signed attributes the token signs, the SHA-256 of the signer certificate (lower-case hex) and
     * the signing time written into both. All of it is kept by the server; only the attributes and the digest go out.
     */
    record Prepared(byte[] preparedPdf, String preparedDigestSha256, byte[] signedAttributes,
                    String signerCertificateSha256, Instant signingTime) {

        private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

        public Prepared {
            preparedPdf = Objects.requireNonNull(preparedPdf, "preparedPdf").clone();
            signedAttributes = Objects.requireNonNull(signedAttributes, "signedAttributes").clone();
            preparedDigestSha256 = hex(preparedDigestSha256, "preparedDigestSha256");
            signerCertificateSha256 = hex(signerCertificateSha256, "signerCertificateSha256");
            Objects.requireNonNull(signingTime, "signingTime");
        }

        @Override
        public byte[] preparedPdf() {
            return preparedPdf.clone();
        }

        @Override
        public byte[] signedAttributes() {
            return signedAttributes.clone();
        }

        private static String hex(String v, String name) {
            String s = v == null ? null : v.trim().toLowerCase(Locale.ROOT);
            if (s == null || !SHA256_HEX.matcher(s).matches()) {
                throw new IllegalArgumentException(name + " must be a SHA-256 digest in hex");
            }
            return s;
        }
    }

    /** The PDF or the signature cannot be used; nothing was produced. {@link #code()} is stable. */
    final class Refused extends RuntimeException {
        private final String code;

        public Refused(String code, String message, Throwable cause) {
            super(code + ": " + message, cause);
            this.code = Objects.requireNonNull(code, "code");
        }

        public String code() {
            return code;
        }
    }

    /**
     * Step 1 for one signer certificate.
     *
     * @throws Refused {@code PDF_INVALID} when the PDF cannot be prepared
     */
    Prepared prepare(byte[] pdf, X509Certificate signer, Instant signingTime);

    /**
     * Step 2: the signed PDF.
     *
     * @throws Refused {@code DSC_SIGNATURE_INVALID}, {@code DSC_ALGORITHM} or {@code DSC_SIGNER_CHANGED} when the
     *                 token's signature does not belong to the prepared attributes and signer; {@code DIGEST_MISMATCH},
     *                 {@code PDF_INVALID} or {@code SIGNATURE_TOO_LARGE} when the prepared PDF cannot take it
     */
    byte[] complete(Prepared prepared, X509Certificate signer, List<X509Certificate> chain, String algorithm,
                    byte[] signature);

    /** The algorithm for the signer's key ({@code SHA256withRSA} or {@code SHA256withECDSA}), or null for any other key. */
    static String algorithmFor(X509Certificate signer) {
        if (signer == null) {
            return null;
        }
        if (signer.getPublicKey() instanceof RSAPublicKey) {
            return "SHA256withRSA";
        }
        return signer.getPublicKey() instanceof ECPublicKey ? "SHA256withECDSA" : null;
    }

    /** Implementations offered through {@link ServiceLoader}. */
    interface Factory {
        DscSigning create();
    }

    /** The installed implementation, or empty when sangam-esign or its libraries are not on the class path. */
    static Optional<DscSigning> load() {
        Logger log = LoggerFactory.getLogger(DscSigning.class);
        try {
            for (Factory f : ServiceLoader.load(Factory.class, DscSigning.class.getClassLoader())) {
                return Optional.of(f.create());
            }
        } catch (ServiceConfigurationError | LinkageError e) {
            log.warn("DSC signing is not available ({}); DSC signing stays blocked", e.toString());
        }
        return Optional.empty();
    }
}

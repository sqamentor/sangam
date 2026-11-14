// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.signature;

import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Owner decision 140: the configured verification policy. Certificate paths are validated only against
 * {@link #trustAnchors()} (never a root that arrives in a response); the ESP's XML response signature only against
 * {@link #responseSignerCertificates()} (the ESP's published response-signing certificates). Revocation is always
 * required: there is no switch to turn it off, so missing configuration can never make it optional.
 *
 * @param version                    policy version recorded with every verification
 * @param trustAnchors               approved trust anchors (for example the national root CA); none = nothing verifies
 * @param intermediates              extra CA certificates offered for path building (they are not trusted by themselves)
 * @param responseSignerCertificates the ESP's response-signing certificates (XML signature of the response)
 * @param maxResponseAge             how long after the request a response may be time-stamped
 * @param clockSkew                  tolerance for the provider's clock
 * @param minRsaBits                 smallest accepted RSA key
 * @param revocationTimeout          timeout of one OCSP or CRL fetch
 * @param responseTimeZone           zone of a response {@code ts} written without an offset (the provider's local
 *                                   time); null = such a time cannot be read and the response is indeterminate
 * @param ocspMaxAge                 owner decision 259: an OCSP answer whose thisUpdate is older than this is not used
 *                                   (the status stays unavailable, the verification INDETERMINATE), even when its
 *                                   nonce matches; null = {@link #DEFAULT_OCSP_MAX_AGE}
 */
public record SignaturePolicy(String version, Set<TrustAnchor> trustAnchors, List<X509Certificate> intermediates,
                              List<X509Certificate> responseSignerCertificates, Duration maxResponseAge,
                              Duration clockSkew, int minRsaBits, Duration revocationTimeout, ZoneId responseTimeZone,
                              Duration ocspMaxAge) {

    /** Signature algorithms accepted for documents and responses (no SHA-1, no MD5). */
    public static final Set<String> ACCEPTED_DIGESTS = Set.of("2.16.840.1.101.3.4.2.1", "2.16.840.1.101.3.4.2.2",
            "2.16.840.1.101.3.4.2.3");

    /** Owner decision 259: default {@code sangam.dsc.ocsp-max-age}. */
    public static final Duration DEFAULT_OCSP_MAX_AGE = Duration.ofHours(24);

    /** A policy with the default OCSP maximum age (decision 259). */
    public SignaturePolicy(String version, Set<TrustAnchor> trustAnchors, List<X509Certificate> intermediates,
                           List<X509Certificate> responseSignerCertificates, Duration maxResponseAge,
                           Duration clockSkew, int minRsaBits, Duration revocationTimeout, ZoneId responseTimeZone) {
        this(version, trustAnchors, intermediates, responseSignerCertificates, maxResponseAge, clockSkew, minRsaBits,
                revocationTimeout, responseTimeZone, null);
    }

    public SignaturePolicy {
        Objects.requireNonNull(version, "version");
        trustAnchors = trustAnchors == null ? Set.of() : Set.copyOf(trustAnchors);
        intermediates = intermediates == null ? List.of() : List.copyOf(intermediates);
        responseSignerCertificates = responseSignerCertificates == null ? List.of() : List.copyOf(responseSignerCertificates);
        maxResponseAge = maxResponseAge == null ? Duration.ofMinutes(30) : maxResponseAge;
        clockSkew = clockSkew == null ? Duration.ofMinutes(5) : clockSkew;
        minRsaBits = Math.max(minRsaBits, 2048);
        revocationTimeout = revocationTimeout == null ? Duration.ofSeconds(10) : revocationTimeout;
        ocspMaxAge = ocspMaxAge == null ? DEFAULT_OCSP_MAX_AGE : ocspMaxAge;
        if (ocspMaxAge.isZero() || ocspMaxAge.isNegative()) {
            throw new IllegalArgumentException("ocspMaxAge must be positive");
        }
    }

    /** What is missing before anything can verify, or null. */
    public String incomplete() {
        if (trustAnchors.isEmpty()) {
            return "no trust anchors are configured";
        }
        if (responseSignerCertificates.isEmpty()) {
            return "no ESP response-signing certificate is configured";
        }
        return null;
    }
}

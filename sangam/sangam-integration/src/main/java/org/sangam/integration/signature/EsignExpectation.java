// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.signature;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Owner decision 140: what an eSign response must be bound to. Everything here comes from the ASP's own request record,
 * never from the response: the transaction id (the operationId), the exact SHA-256 of the signed content, the document
 * id, the intended signer and the time the request was issued.
 *
 * @param tenantId            tenant of the request (revocation fetches go through its egress policy)
 * @param txn                 request {@code txn}; the response must carry the same one
 * @param aspId               the ASP id sent in the request
 * @param documentHashHex     SHA-256 of the exact content (lower-case hex); the CMS {@code messageDigest} must equal it
 * @param documentId          the caller's id of the document (kept in the evidence)
 * @param intendedSignerName  the name the signer certificate must carry (compared case- and space-insensitively with
 *                            the subject common name)
 * @param intendedSignerCertificateSha256 optional: the exact signer certificate expected (lower-case hex), or null
 * @param requestedAt         when the request was issued; a response time-stamped outside [requestedAt - skew,
 *                            requestedAt + maximum age] is refused. Null only for a later status lookup, where the
 *                            txn binding (the operationId) is what ties the response to the request
 */
public record EsignExpectation(String tenantId, String txn, String aspId, String documentHashHex, String documentId,
                               String intendedSignerName, String intendedSignerCertificateSha256, Instant requestedAt) {

    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    public EsignExpectation {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(txn, "txn");
        Objects.requireNonNull(aspId, "aspId");
        documentHashHex = documentHashHex == null ? null : documentHashHex.trim().toLowerCase(Locale.ROOT);
        if (documentHashHex == null || !SHA256_HEX.matcher(documentHashHex).matches()) {
            throw new IllegalArgumentException("documentHashHex must be a SHA-256 digest in hex");
        }
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("documentId is required");
        }
        if (intendedSignerName == null || intendedSignerName.isBlank()) {
            throw new IllegalArgumentException("intendedSignerName is required");
        }
        if (intendedSignerCertificateSha256 != null) {
            intendedSignerCertificateSha256 = intendedSignerCertificateSha256.trim().toLowerCase(Locale.ROOT);
            if (!SHA256_HEX.matcher(intendedSignerCertificateSha256).matches()) {
                throw new IllegalArgumentException("intendedSignerCertificateSha256 must be SHA-256 hex");
            }
        }
    }

    /** True when {@code commonName} names the intended signer (case, accents kept; spaces and case folded). */
    public boolean namesIntendedSigner(String commonName) {
        return commonName != null && fold(commonName).equals(fold(intendedSignerName));
    }

    static String fold(String s) {
        return s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}

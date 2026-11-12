// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.connectorshealth;

/**
 * M-006: verifies the JWT a CDS Hooks 2.0 client (the EHR) signs with its own key, against the per-client JWKS registry
 * ({@code sangam.connectors-health.cds-service.clients.*}): {@code iss} allow-list, signature against that client's
 * JWKS, {@code aud} = the public URL of the endpoint called, {@code exp}/{@code iat}, {@code jti} replay. Implemented in
 * sangam-server over sangam-security's {@code ClientJwtVerifier}. Used only by the {@code /cds-services} routes; the
 * other hooks keep their own checks (FHIR channel secret, provider signatures).
 */
@FunctionalInterface
public interface CdsClientVerifier {

    /**
     * @param bearerToken  the token from {@code Authorization: Bearer}
     * @param endpointPath the path being called, {@code /cds-services} or {@code /cds-services/{id}}; the audience is
     *                     the configured public base URL followed by this path
     * @return the verified client ({@code iss})
     * @throws Rejected when the token is refused
     */
    String verify(String bearerToken, String endpointPath);

    /** A refused client token; {@code reason} is a stable code, the message never contains the token. */
    final class Rejected extends RuntimeException {
        private final String reason;

        public Rejected(String reason, String message) {
            super(message);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }
}

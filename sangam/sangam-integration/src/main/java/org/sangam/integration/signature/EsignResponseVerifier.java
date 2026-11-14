// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.signature;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

import org.sangam.integration.connector.HttpTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owner decision 140 (RM-07): framework-independent verification of a CCA eSign 2.1 response. An implementation must
 * verify the ESP's XML signature over the whole response, the document CMS/PKCS#7 signature over the exact expected
 * content, the binding to the request ({@link EsignExpectation}), the certificate path to configured anchors only, and
 * revocation (required). It never throws for a bad response: every failure is an outcome.
 *
 * <p>The implementation that needs Bouncy Castle lives in the optional module sangam-esign (owner decision 217) and is found with
 * {@link ServiceLoader}; {@link #load} returns empty when it (or Bouncy Castle) is absent, and the eSign connector then
 * refuses to sign (operations needing verified eSign stay blocked; nothing else is affected).
 */
public interface EsignResponseVerifier {

    /** Verifies one response body (the raw bytes as received) against what was requested. */
    SignatureVerification verify(EsignExpectation expected, byte[] responseXml);

    /** Implementations offered through {@link ServiceLoader}. */
    interface Factory {
        EsignResponseVerifier create(SignaturePolicy policy, HttpTransport revocationTransport, Clock clock);
    }

    /** The installed implementation, or empty when sangam-esign or Bouncy Castle is not on the class path. */
    static Optional<EsignResponseVerifier> load(SignaturePolicy policy, HttpTransport revocationTransport, Clock clock) {
        Logger log = LoggerFactory.getLogger(EsignResponseVerifier.class);
        try {
            for (Factory f : ServiceLoader.load(Factory.class, EsignResponseVerifier.class.getClassLoader())) {
                return Optional.of(f.create(policy, revocationTransport, clock));
            }
        } catch (ServiceConfigurationError | LinkageError e) {
            log.warn("eSign verification is not available ({}); signing that needs it stays blocked", e.toString());
        }
        return Optional.empty();
    }

    /** A verifier that is always indeterminate: the explicit stand-in when nothing is installed. */
    static EsignResponseVerifier unavailable(String why, Clock clock) {
        return (expected, xml) -> SignatureVerification.indeterminate("VERIFIER_UNAVAILABLE", why, false, null,
                Instant.now(clock));
    }
}

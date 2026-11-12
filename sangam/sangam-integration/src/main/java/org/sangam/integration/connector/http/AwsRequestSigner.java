// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.http;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.sangam.integration.connector.SecretResolver;
import org.sangam.ports.events.channel.AwsSigV4;

/**
 * AWS SigV4 for the HTTP connector and the AWS secret provider (M-067, M-075). It holds no signing code: the product's
 * one generic signer is {@link AwsSigV4} (sangam-flowable-ports, so the S3 document store can share it). This class only chooses which of the headers
 * that will be sent are signed (not the ones the HTTP client sets or drops itself: host, connection, content-length,
 * expect, upgrade, user-agent, and never a caller's authorization) and reads the credentials by reference.
 */
public final class AwsRequestSigner {

    private static final Set<String> NOT_SIGNED = Set.of("connection", "content-length", "expect", "upgrade", "host",
            "authorization", "user-agent", "x-amz-date", "x-amz-security-token");

    private AwsRequestSigner() {
    }

    /** The headers to add (x-amz-date, x-amz-security-token when temporary, Authorization). */
    public static Map<String, String> sign(String method, URI uri, Map<String, String> headers, byte[] body,
                                           String region, String service, AwsSigV4.Credentials credentials, Instant now) {
        Map<String, String> signed = new LinkedHashMap<>();
        if (headers != null) {
            headers.forEach((k, v) -> {
                if (!NOT_SIGNED.contains(k.toLowerCase(Locale.ROOT))) {
                    signed.put(k, v);
                }
            });
        }
        return AwsSigV4.sign(method, uri, signed, body, region, service, credentials, now);
    }

    /**
     * A {@link HttpRestConnector.RequestSigner} for a LIVE connector: the credentials are secret references read from the
     * secret port on every call (a rotated key needs no rebuild); a reference that cannot be read means the request is
     * not sent ({@link HttpRestConnector.AuthUnavailableException}).
     */
    public static HttpRestConnector.RequestSigner signer(String region, String service, String accessKeyRef,
                                                         String secretKeyRef, String sessionTokenRef,
                                                         SecretResolver secrets, Clock clock) {
        Objects.requireNonNull(secrets, "secrets");
        Clock c = clock == null ? Clock.systemUTC() : clock;
        return (tenantId, method, uri, headers, body) -> sign(method, uri, headers, body, region, service,
                new AwsSigV4.Credentials(read(secrets, accessKeyRef), read(secrets, secretKeyRef),
                        sessionTokenRef == null || sessionTokenRef.isBlank() ? null : read(secrets, sessionTokenRef)),
                c.instant());
    }

    private static String read(SecretResolver secrets, String ref) {
        String v;
        try {
            v = secrets.require(ref);
        } catch (RuntimeException e) {
            throw new HttpRestConnector.AuthUnavailableException(ref + " cannot be resolved (" + e.getMessage() + ")");
        }
        if (v == null || v.isBlank() || !HttpRestConnector.isTidySecret(v)) {
            throw new HttpRestConnector.AuthUnavailableException(ref + " resolves to an empty or untidy value"
                    + " (the value is not shown)");
        }
        return v;
    }
}

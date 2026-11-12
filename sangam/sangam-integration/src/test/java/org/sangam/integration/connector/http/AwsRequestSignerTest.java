// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.ports.events.channel.AwsSigV4;

/** M-067: the W3-B adapter over the product's one SigV4 signer (header selection and secret references only). */
class AwsRequestSignerTest {

    private static final AwsSigV4.Credentials SUITE = new AwsSigV4.Credentials("AKIDEXAMPLE",
            "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY", null);
    private static final Instant SUITE_TIME = Instant.parse("2015-08-30T12:36:00Z");
    private static final String VANILLA = "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, "
            + "SignedHeaders=host;x-amz-date, Signature=5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31";

    @Test
    void getVanillaMatchesTheAwsTestSuite() {
        assertEquals(VANILLA, AwsRequestSigner.sign("GET", URI.create("https://example.amazonaws.com/"), Map.of(), null,
                "us-east-1", "service", SUITE, SUITE_TIME).get("Authorization"));
    }

    @Test
    void headersTheClientSetsItselfAreNotSigned() {
        Map<String, String> sent = Map.of("Host", "evil.example", "User-Agent", "x", "Content-Length", "0",
                "Connection", "keep-alive", "Authorization", "Bearer old");
        assertEquals(VANILLA, AwsRequestSigner.sign("GET", URI.create("https://example.amazonaws.com/"), sent, null,
                "us-east-1", "service", SUITE, SUITE_TIME).get("Authorization"),
                "host comes from the URI; client-managed headers and a caller's Authorization are left out");
    }

    @Test
    void aBusinessHeaderIsSigned() {
        String a = AwsRequestSigner.sign("POST", URI.create("https://example.amazonaws.com/"),
                Map.of("Content-Type", "application/json"), new byte[0], "us-east-1", "service", SUITE, SUITE_TIME)
                .get("Authorization");
        assertTrue(a.contains("SignedHeaders=content-type;host;x-amz-date,"), a);
    }

    @Test
    void theSignerReadsCredentialsByReferenceOnEveryCall() {
        Map<String, String> values = new java.util.HashMap<>(Map.of("secret://aws/a", "AKIDEXAMPLE",
                "secret://aws/s", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"));
        HttpRestConnector.RequestSigner s = AwsRequestSigner.signer("us-east-1", "service", "secret://aws/a",
                "secret://aws/s", null, SecretResolver.of(values), Clock.fixed(SUITE_TIME, ZoneOffset.UTC));
        URI u = URI.create("https://example.amazonaws.com/");
        assertEquals(VANILLA, s.sign("t1", "GET", u, Map.of(), null).get("Authorization"));
        values.put("secret://aws/s", "rotated");
        assertFalse(VANILLA.equals(s.sign("t1", "GET", u, Map.of(), null).get("Authorization")));
        values.remove("secret://aws/s");
        assertThrows(HttpRestConnector.AuthUnavailableException.class, () -> s.sign("t1", "GET", u, Map.of(), null));
    }
}

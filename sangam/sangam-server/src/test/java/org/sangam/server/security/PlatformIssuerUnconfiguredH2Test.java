// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.server.acceptance.AcceptanceIdp;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * SL-70, fail closed: with no platform issuer configured ({@code sangam.security.platform-issuer} empty) nobody is a
 * platform administrator. {@code sangam-admin} in the dev token and in a tenant-realm JWT is the tenant administrator
 * of its tenant, and a platform-realm token is not even trusted (its issuer is unknown). Before the fix every token
 * with {@code sangam-admin} was a platform administrator.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "sangam.security.platform-issuer=")
@ActiveProfiles("h2")
@DirtiesContext
class PlatformIssuerUnconfiguredH2Test {

    @DynamicPropertySource
    static void oidc(DynamicPropertyRegistry r) {
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> AcceptanceIdp.get().issuer());
        r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> AcceptanceIdp.get().jwksUri());
    }

    @LocalServerPort
    int port;

    @Test
    void nobodyIsPlatformAdministrator() throws Exception {
        Map<String, String> tokens = Map.of(
                "dev token with sangam-admin", PlatformIssuerH2Test.DEV_TOKEN,
                "tenant-realm JWT with sangam-admin", AcceptanceIdp.get().token(PlatformIssuerH2Test.TENANT,
                        "sl70-u-admin", List.of("sangam-admin", "sangam-user", "sangam-operator")));
        for (Map.Entry<String, String> t : tokens.entrySet()) {
            HttpResponse<String> me = PlatformIssuerH2Test.call(port, "GET", "/api/v1/me", t.getValue(), null);
            assertEquals(200, me.statusCode(), t.getKey() + ": " + me.body());
            assertFalse(me.body().contains("PLATFORM_ADMIN"), t.getKey() + ": " + me.body());
            for (String[] op : PlatformIssuerH2Test.platformOperations()) {
                HttpResponse<String> r = PlatformIssuerH2Test.call(port, op[0], op[1], t.getValue(), op[2]);
                assertEquals(403, r.statusCode(), t.getKey() + ": " + op[0] + " " + op[1] + " -> " + r.body());
            }
        }
    }

    @Test
    void aPlatformRealmTokenIsNotTrustedWhenNoPlatformIssuerIsConfigured() throws Exception {
        String token = AcceptanceIdp.get().platformToken(PlatformIssuerH2Test.TENANT, "sl70-u-platform",
                List.of("sangam-admin"));
        HttpResponse<String> r = PlatformIssuerH2Test.call(port, "GET", "/api/v1/me", token, null);
        assertEquals(401, r.statusCode(), r.body());
    }
}

// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.sangam.server.acceptance.AcceptanceIdp;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * SL-70 (owner decision 2026-11-10 "Platform issuer"): only a token from a separately configured platform issuer
 * ({@code sangam.security.platform-issuer}) may hold the platform administrator role. {@code sangam-admin} in a
 * tenant token means tenant administrator of that tenant. Before the fix any token with {@code sangam-admin} (and any
 * token claiming the sangam-security role {@code platform-admin}) was a platform administrator: it resolved packs for
 * and exported the bundle of any tenant, triggered the engine-wide history cleanup and created tenants.
 *
 * <p>Real server on H2, the production OIDC path ({@code OidcConfig}) with two realms of {@link AcceptanceIdp}: the
 * tenant realm ({@code issuer-uri}) and the platform realm (listed as the platform issuer). The dev token is a tenant
 * token here (its issuer is not listed). {@link PlatformIssuerUnconfiguredH2Test} covers the unconfigured case.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
@DirtiesContext
class PlatformIssuerH2Test {

    static final String DEV_TOKEN = "sangam-dev-token-change-me";
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    static final String TENANT = "dev";

    @DynamicPropertySource
    static void oidc(DynamicPropertyRegistry r) {
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> AcceptanceIdp.get().issuer());
        r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> AcceptanceIdp.get().jwksUri());
        r.add("sangam.security.platform-issuer", () -> AcceptanceIdp.get().platformIssuer());
    }

    @LocalServerPort
    int port;
    @LocalManagementPort
    int managementPort;

    static HttpResponse<String> call(int port, String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Accept", "application/json").timeout(Duration.ofSeconds(60))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Every platform operation of the finding, as {method, path, body}; the tenant is always another one. */
    static List<String[]> platformOperations() {
        String newTenant = "sl70-" + UUID.randomUUID().toString().substring(0, 8);
        return List.of(
                new String[] {"POST", "/api/v1/onboarding/packs/resolve", "{\"ids\":[],\"tenant\":\"sl70-other\"}"},
                new String[] {"GET", "/api/v1/onboarding/tenants/sl70-other/bundle", null},
                new String[] {"POST", "/api/v1/ops/cleanup?reason=sl70", null},
                new String[] {"POST", "/api/v1/admin/tenants", "{\"id\":\"" + newTenant + "\",\"name\":\"SL-70\",\"revision\":0}"});
    }

    static Map<String, String> tenantTokens() {
        Map<String, String> t = new LinkedHashMap<>();
        // dev token: sangam-admin, sangam-user, sangam-operator, sangam-worker (application-h2.yaml)
        t.put("dev token with sangam-admin", DEV_TOKEN);
        t.put("tenant-realm JWT with sangam-admin", AcceptanceIdp.get().token(TENANT, "sl70-tenant-admin",
                List.of("sangam-admin", "sangam-user", "sangam-operator")));
        // every platform marker a tenant IdP could put into a token
        t.put("tenant-realm JWT claiming every platform role", AcceptanceIdp.get().token(TENANT, "sl70-forger",
                List.of("sangam-admin", "platform-admin", "sangam-platform", "sangam-system", "sangam-operator")));
        return t;
    }

    @Test
    void aTenantTokenWithSangamAdminGets403OnEveryPlatformOperation() throws Exception {
        for (Map.Entry<String, String> token : tenantTokens().entrySet()) {
            for (String[] op : platformOperations()) {
                HttpResponse<String> r = call(port, op[0], op[1], token.getValue(), op[2]);
                assertEquals(403, r.statusCode(), token.getKey() + ": " + op[0] + " " + op[1] + " -> " + r.body());
            }
            // the actuator (management port) exposes every tenant's metrics: platform callers only
            HttpResponse<String> metrics = call(managementPort, "GET", "/actuator/metrics", token.getValue(), null);
            assertEquals(403, metrics.statusCode(), token.getKey() + ": /actuator/metrics -> " + metrics.body());
        }
    }

    @Test
    void sangamAdminInATenantTokenIsTheTenantAdministratorOfThatTenant() throws Exception {
        for (Map.Entry<String, String> token : tenantTokens().entrySet()) {
            HttpResponse<String> me = call(port, "GET", "/api/v1/me", token.getValue(), null);
            assertEquals(200, me.statusCode(), token.getKey() + ": " + me.body());
            assertFalse(me.body().contains("PLATFORM_ADMIN"), token.getKey() + ": " + me.body());
            assertFalse(me.body().contains("\"sangam-admin\""), token.getKey() + ": " + me.body());
            assertTrue(me.body().contains("\"sangam-tenant-admin\""), token.getKey() + ": " + me.body());
            assertTrue(me.body().contains("\"ADMIN\""), token.getKey() + ": " + me.body());
            // the tenant administration of its own tenant still works
            HttpResponse<String> own = call(port, "GET", "/api/v1/admin/tenants/" + TENANT, token.getValue(), null);
            assertEquals(200, own.statusCode(), token.getKey() + ": " + own.body());
        }
    }

    @Test
    void aPlatformIssuerTokenWithSangamAdminIsThePlatformAdministrator() throws Exception {
        String token = AcceptanceIdp.get().platformToken(TENANT, "sl70-platform-admin",
                List.of("sangam-admin", "sangam-user"));
        HttpResponse<String> me = call(port, "GET", "/api/v1/me", token, null);
        assertEquals(200, me.statusCode(), me.body());
        assertTrue(me.body().contains("\"PLATFORM_ADMIN\""), me.body());
        Map<String, Integer> expected = Map.of("/api/v1/onboarding/packs/resolve", 200,
                "/api/v1/onboarding/tenants/sl70-other/bundle", 404, // no onboarding run of that tenant: the call is admitted
                "/api/v1/ops/cleanup?reason=sl70", 202,
                "/api/v1/admin/tenants", 201);
        for (String[] op : platformOperations()) {
            HttpResponse<String> r = call(port, op[0], op[1], token, op[2]);
            assertEquals(expected.get(op[1]), r.statusCode(), "platform admin: " + op[0] + " " + op[1] + " -> " + r.body());
        }
        HttpResponse<String> metrics = call(managementPort, "GET", "/actuator/metrics", token, null);
        assertEquals(200, metrics.statusCode(), "platform admin: /actuator/metrics -> " + metrics.body());
        // a platform operator (no sangam-admin) keeps the actuator, as before
        String operator = AcceptanceIdp.get().platformToken(TENANT, "sl70-platform-operator", List.of("sangam-operator"));
        metrics = call(managementPort, "GET", "/actuator/metrics", operator, null);
        assertEquals(200, metrics.statusCode(), "platform operator: /actuator/metrics -> " + metrics.body());
    }

    @Test
    void aTenantRealmTokenThatClaimsThePlatformIssuerIsRefused() throws Exception {
        String forged = AcceptanceIdp.get().forgedPlatformToken(TENANT, "sl70-forger", List.of("sangam-admin"));
        HttpResponse<String> r = call(port, "GET", "/api/v1/me", forged, null);
        assertEquals(401, r.statusCode(), r.body());
    }
}

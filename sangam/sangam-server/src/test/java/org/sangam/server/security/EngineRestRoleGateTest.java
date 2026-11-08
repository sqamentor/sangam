// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * SL-37, UI {@code /engine-rest} role gate (OPERATON-UI-PARITY: "every /engine-rest screen is limited to platform
 * roles"; sangam-server README: "/engine-rest/** needs sangam-admin or sangam-engine-admin, all methods").
 * <ul>
 *   <li>server: every method on {@code /engine-rest/**} is 401 without a token and 403 for every non-engine group;</li>
 *   <li>UI role source: {@code /api/v1/me} gives {@code PLATFORM_OPERATOR} (the role guarding the UI engine screens)
 *       exactly to the groups the server admits, and the engine call then succeeds. Before the fix no group produced
 *       {@code PLATFORM_OPERATOR} and {@code sangam-engine-admin} produced no role at all, so the guarded screens were
 *       unreachable for the very callers the server admits.</li>
 * </ul>
 * h2 profile, dev token (no Docker).
 */
class EngineRestRoleGateTest {

    static final String TOKEN = "sangam-dev-token-change-me";
    static final HttpClient HTTP = HttpClient.newHttpClient();

    static HttpResponse<String> call(int port, String method, String path, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, "GET".equals(method) || "DELETE".equals(method)
                        ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString("{}"));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static final List<String[]> ENGINE_CALLS = List.of(
            new String[] {"GET", "/engine-rest/engine"},
            new String[] {"GET", "/engine-rest/process-definition"},
            new String[] {"POST", "/engine-rest/task"},
            new String[] {"PUT", "/engine-rest/user/someone/profile"},
            new String[] {"DELETE", "/engine-rest/process-instance/any"});

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"sangam.security.dev-token.groups=sangam-user,sangam-operator,sangam-worker,sangam-tenant-admin,"
                    + "sangam-auditor,sangam-model-author,sangam-integration"})
    @ActiveProfiles("h2")
    @DirtiesContext
    class NonEngineGroups {

        @LocalServerPort
        int port;

        @Test
        void everyMethodIs401WithoutTokenAnd403ForEveryNonEngineGroup() throws Exception {
            for (String[] c : ENGINE_CALLS) {
                assertEquals(401, call(port, c[0], c[1], null).statusCode(), c[0] + " " + c[1] + " anonymous");
                HttpResponse<String> r = call(port, c[0], c[1], TOKEN);
                assertEquals(403, r.statusCode(), c[0] + " " + c[1] + " non-engine groups: " + r.body());
            }
        }

        @Test
        void meGivesNoPlatformRoleToNonEngineGroups() throws Exception {
            HttpResponse<String> me = call(port, "GET", "/api/v1/me", TOKEN);
            assertEquals(200, me.statusCode(), me.body());
            assertFalse(me.body().contains("PLATFORM_OPERATOR"), me.body());
            assertFalse(me.body().contains("PLATFORM_ADMIN"), me.body());
            assertTrue(me.body().contains("TASK_USER") && me.body().contains("OPERATOR"), me.body());
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"sangam.security.dev-token.groups=sangam-engine-admin"})
    @ActiveProfiles("h2")
    @DirtiesContext
    class EngineAdminGroup {

        @LocalServerPort
        int port;

        @Test
        void engineAdminGetsThePlatformOperatorRoleAndTheEngineCallSucceeds() throws Exception {
            HttpResponse<String> me = call(port, "GET", "/api/v1/me", TOKEN);
            assertEquals(200, me.statusCode(), me.body());
            assertTrue(me.body().contains("\"PLATFORM_OPERATOR\""), "UI engine screens need PLATFORM_OPERATOR: " + me.body());
            assertFalse(me.body().contains("PLATFORM_ADMIN"), "engine admin is not a Sangam administrator: " + me.body());
            HttpResponse<String> engine = call(port, "GET", "/engine-rest/engine", TOKEN);
            assertEquals(200, engine.statusCode(), engine.body());
        }
    }
}

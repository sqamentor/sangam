// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.connectorshealth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;
import org.sangam.rest.port.ApiProblem;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;

/**
 * M-006: {@code /cds-services} accepts only a CDS client JWT verified by {@link CdsClientVerifier} (audience = the
 * endpoint called); no verifier, no or a refused token is 401; the FHIR hook keeps its own channel-secret check.
 */
class HealthHooksControllerCdsTest {

    static final String TOKEN = "eyJ.header.sig";

    /** Records the port calls. */
    static final class Hooks implements HealthHooksPort {
        final List<String> cdsCalls = new ArrayList<>();
        int discoveries;
        int fhir;

        @Override
        public RemittanceResult remittance835(String tenantId, String principal, String x12) {
            throw new AssertionError("not used");
        }

        @Override
        public HookResult fhirNotification(String channel, Function<String, String> header, byte[] body) {
            fhir++;
            return new HookResult(202, 1, 0, "event-notification");
        }

        @Override
        public CdsReply cdsDiscovery() {
            discoveries++;
            return new CdsReply(200, "{\"services\":[]}");
        }

        @Override
        public CdsReply cdsCall(String serviceId, byte[] body, String authorizedClient) {
            cdsCalls.add(serviceId + " by " + authorizedClient);
            return new CdsReply(200, "{\"cards\":[]}");
        }
    }

    /** Records the audiences asked for; accepts {@link #TOKEN} only. */
    static final class Verifier implements CdsClientVerifier {
        final List<String> paths = new ArrayList<>();

        @Override
        public String verify(String bearerToken, String endpointPath) {
            paths.add(endpointPath);
            if (!TOKEN.equals(bearerToken)) {
                throw new Rejected("BAD_SIGNATURE", "token of client https://ehr.example.org is not valid");
            }
            return "https://ehr.example.org";
        }
    }

    static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }
        };
    }

    static HttpServletRequest request(Map<String, String> headers) {
        return (HttpServletRequest) Proxy.newProxyInstance(HealthHooksControllerCdsTest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getHeader")) {
                        return headers.get(String.valueOf(args[0]));
                    }
                    if (method.getName().equals("getAttribute")) {
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    final Hooks hooks = new Hooks();
    final Verifier verifier = new Verifier();
    final byte[] body = "{\"hook\":\"order-select\"}".getBytes(StandardCharsets.UTF_8);

    HealthHooksController controller(CdsClientVerifier v) {
        return new HealthHooksController(hooks, null, provider(v));
    }

    static int status(ApiProblem p) {
        return p.status();
    }

    @Test
    void aVerifiedClientReachesTheServiceWithTheEndpointAsAudience() {
        ResponseEntity<String> r = controller(verifier).cdsCall("check", body,
                request(Map.of("Authorization", "Bearer " + TOKEN)));
        assertEquals(200, r.getStatusCode().value());
        assertEquals(List.of("check by https://ehr.example.org"), hooks.cdsCalls);
        assertEquals(List.of("/cds-services/check"), verifier.paths);
    }

    @Test
    void discoveryNeedsAVerifiedClientToo() {
        assertEquals(200, controller(verifier).cdsDiscovery(request(Map.of("Authorization", "bearer " + TOKEN)))
                .getStatusCode().value());
        assertEquals(List.of("/cds-services"), verifier.paths);
        assertEquals(1, hooks.discoveries);
    }

    @Test
    void noRegisteredClientRefusesEveryCall() {
        ApiProblem p = assertThrows(ApiProblem.class, () -> controller(null).cdsCall("check", body,
                request(Map.of("Authorization", "Bearer " + TOKEN))));
        assertEquals(401, status(p));
        assertEquals("CDS_CLIENT_NOT_CONFIGURED", p.code());
        assertTrue(hooks.cdsCalls.isEmpty());
        assertThrows(ApiProblem.class, () -> controller(null).cdsDiscovery(request(Map.of())));
        assertEquals(0, hooks.discoveries);
    }

    @Test
    void aMissingOrNonBearerAuthorizationIsRefused() {
        for (Map<String, String> h : List.of(Map.<String, String>of(), Map.of("Authorization", "Basic dXNlcjpwYXNz"),
                Map.of("Authorization", "Bearer "))) {
            ApiProblem p = assertThrows(ApiProblem.class, () -> controller(verifier).cdsCall("check", body, request(h)));
            assertEquals(401, status(p));
            assertEquals("UNAUTHENTICATED", p.code());
        }
        assertTrue(verifier.paths.isEmpty(), "nothing to verify");
        assertTrue(hooks.cdsCalls.isEmpty());
    }

    @Test
    void aRefusedTokenIs401WithTheReasonAndNeverReachesTheService() {
        ApiProblem p = assertThrows(ApiProblem.class, () -> controller(verifier).cdsCall("check", body,
                request(Map.of("Authorization", "Bearer forged"))));
        assertEquals(401, status(p));
        assertEquals("CDS_CLIENT_REJECTED", p.code());
        assertEquals("BAD_SIGNATURE", p.extensions().get("reason"));
        assertFalse(p.getMessage().contains("forged"), "the token is not echoed");
        assertTrue(hooks.cdsCalls.isEmpty());
    }

    @Test
    void theFhirHookKeepsItsOwnCheckAndIgnoresTheCdsVerifier() {
        ResponseEntity<Map<String, Object>> r = controller(null).fhirNotification("ch1", body,
                request(Map.of("Authorization", "channel-secret")));
        assertEquals(202, r.getStatusCode().value());
        assertEquals(1, hooks.fhir);
        assertTrue(verifier.paths.isEmpty());
        assertTrue(hooks.cdsCalls.isEmpty());
    }
}

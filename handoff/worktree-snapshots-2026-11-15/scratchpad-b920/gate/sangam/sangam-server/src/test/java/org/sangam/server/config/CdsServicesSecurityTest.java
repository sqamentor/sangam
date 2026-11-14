// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.junit.jupiter.api.Test;
import org.sangam.rest.modules.connectorshealth.CdsClientVerifier;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;

/**
 * SL-138 / M-006 server wiring: the CDS Hooks routes are open at the filter chain because the controller checks the
 * CDS client's own JWT, so the Sangam JWT decoder must never see a token from those routes (it would answer 401 before
 * the controller); every other route keeps its bearer token. The {@link CdsClientVerifier} bean comes from
 * {@code sangam.connectors-health.cds-service}.
 */
class CdsServicesSecurityTest {

    private static MockHttpServletRequest request(String path, String bearer) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", path);
        r.addHeader("Authorization", "Bearer " + bearer);
        return r;
    }

    @Test
    void theCdsRoutesAreTheTwoHl7Paths() {
        assertArrayEquals(new String[] {"/cds-services", "/cds-services/**"}, SecurityConfig.CDS_SERVICES_PATHS);
    }

    @Test
    void noTokenFromTheCdsRoutesReachesTheSangamDecoder() {
        BearerTokenResolver resolver = SecurityConfig.bearerResolver(null);
        assertNull(resolver.resolve(request("/cds-services", "ehr.signed.jwt")), "discovery");
        assertNull(resolver.resolve(request("/cds-services/order-sign-crd", "ehr.signed.jwt")), "a service call");
        assertNull(resolver.resolve(request("/cds-services/order-sign-crd/feedback", "ehr.signed.jwt")), "deeper path");
    }

    @Test
    void everyOtherRouteKeepsItsBearerToken() {
        BearerTokenResolver resolver = SecurityConfig.bearerResolver(null);
        assertEquals("tenant.jwt", resolver.resolve(request("/api/v1/me", "tenant.jwt")));
        assertEquals("tenant.jwt", resolver.resolve(request("/cds-servicesx", "tenant.jwt")), "not a CDS Hooks path");
        assertEquals("tenant.jwt", resolver.resolve(request("/api/v1/cds-services", "tenant.jwt")), "not at the root");
        assertNull(resolver.resolve(request("/api/v1/me", SecurityConfig.API_TOKEN_PREFIX + "abc")),
                "API tokens still go to sangam-security's filter, as before");
    }

    @Test
    void noRegisteredClientMeansNoVerifierInstance() {
        assertNull(new HealthConnectorsWiring().sangamCdsClientVerifier(new HealthConnectorsProperties()));
    }

    @Test
    void aRegisteredClientGivesTheVerifierOfThoseSettings() throws Exception {
        RSAKey key = new RSAKeyGenerator(2048).keyID("k1").generate();
        HealthConnectorsProperties props = new HealthConnectorsProperties();
        HealthConnectorsProperties.CdsService configured =
                HealthConnectorsPropertiesCdsTest.service(key, "https://sangam.example.org");
        props.getCdsService().setBaseUrl(configured.getBaseUrl());
        props.getCdsService().setClients(configured.getClients());
        CdsClientVerifier v = new HealthConnectorsWiring().sangamCdsClientVerifier(props);
        assertNotNull(v);
        assertEquals(HealthConnectorsPropertiesCdsTest.EHR, v.verify(HealthConnectorsPropertiesCdsTest.token(key,
                "https://sangam.example.org/cds-services"), "/cds-services"));
    }
}

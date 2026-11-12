// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.sangam.rest.modules.connectorshealth.CdsClientVerifier;

/** M-006: the {@code cds-service} registry binds to a {@link CdsClientVerifier}; the audience is the configured URL. */
class HealthConnectorsPropertiesCdsTest {

    static final String EHR = "https://ehr.example.org";

    static HealthConnectorsProperties.CdsService service(RSAKey key, String baseUrl) {
        HealthConnectorsProperties.CdsClient c = new HealthConnectorsProperties.CdsClient();
        c.setIssuer(EHR);
        c.setJwks(new JWKSet(key.toPublicJWK()).toString());
        HealthConnectorsProperties.CdsService s = new HealthConnectorsProperties().getCdsService();
        s.setBaseUrl(baseUrl);
        s.getClients().put("ehr", c);
        return s;
    }

    static String token(RSAKey key, String aud) throws Exception {
        Instant now = Instant.now();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS384).type(JOSEObjectType.JWT)
                .keyID(key.getKeyID()).build(), new JWTClaimsSet.Builder().issuer(EHR).audience(aud)
                .jwtID(UUID.randomUUID().toString()).issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(120)))
                .build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    @Test
    void aClientTokenForThisEndpointIsVerified() throws Exception {
        RSAKey key = new RSAKeyGenerator(2048).keyID("k1").generate();
        CdsClientVerifier v = service(key, "https://sangam.example.org/").clientVerifier();
        assertEquals(EHR, v.verify(token(key, "https://sangam.example.org/cds-services/check"), "/cds-services/check"));
    }

    @Test
    void aTokenForAnotherEndpointIsRejectedWithTheReason() throws Exception {
        RSAKey key = new RSAKeyGenerator(2048).keyID("k1").generate();
        CdsClientVerifier v = service(key, "https://sangam.example.org").clientVerifier();
        CdsClientVerifier.Rejected e = assertThrows(CdsClientVerifier.Rejected.class, () ->
                v.verify(token(key, "https://sangam.example.org/cds-services/other"), "/cds-services/check"));
        assertEquals("WRONG_AUDIENCE", e.reason());
    }

    @Test
    void noClientMeansNoVerifier() {
        assertNull(new HealthConnectorsProperties().getCdsService().clientVerifier());
    }

    @Test
    void clientsWithoutABaseUrlFailAtStartUp() throws Exception {
        RSAKey key = new RSAKeyGenerator(2048).keyID("k1").generate();
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service(key, " ").clientVerifier());
        assertTrue(e.getMessage().contains("base-url"));
    }

    @Test
    void aBadClientEntryNamesItsKey() {
        HealthConnectorsProperties.CdsService s = new HealthConnectorsProperties().getCdsService();
        s.setBaseUrl("https://sangam.example.org");
        HealthConnectorsProperties.CdsClient c = new HealthConnectorsProperties.CdsClient();
        c.setIssuer(EHR);
        c.setAlgorithms(List.of("RS384"));
        s.getClients().put("ehr-a", c);
        IllegalStateException e = assertThrows(IllegalStateException.class, s::clientVerifier);
        assertTrue(e.getMessage().contains("cds-service.clients.ehr-a"));
    }

    @Test
    void audienceIsTheConfiguredUrlNotTheRequest() {
        HealthConnectorsProperties.CdsService s = new HealthConnectorsProperties().getCdsService();
        s.setBaseUrl("https://sangam.example.org//");
        assertEquals("https://sangam.example.org/cds-services/check", s.audience("/cds-services/check"));
    }
}

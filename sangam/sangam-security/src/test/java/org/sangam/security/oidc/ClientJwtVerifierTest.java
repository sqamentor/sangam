// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.oidc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sangam.security.oidc.ClientJwtVerifier.Client;
import org.sangam.security.oidc.ClientJwtVerifier.Reason;
import org.sangam.security.oidc.ClientJwtVerifier.Rejected;

/** M-006: client JWTs verified against a per-client JWKS registry (iss allow-list, aud, exp/iat, jti). Negative cases. */
class ClientJwtVerifierTest {

    static final String EHR = "https://ehr.example.org";
    static final String OTHER = "https://other-ehr.example.org";
    static final String AUD = "https://sangam.example.org/cds-services/check";
    static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");

    static RSAKey rsa;
    static RSAKey stranger;
    static ECKey ec;

    final AtomicReference<Instant> now = new AtomicReference<>(NOW);
    final Clock clock = new Clock() {
        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };

    @BeforeAll
    static void keys() throws Exception {
        rsa = new RSAKeyGenerator(2048).keyID("ehr-1").generate();
        stranger = new RSAKeyGenerator(2048).keyID("ehr-1").generate();
        ec = new ECKeyGenerator(Curve.P_384).keyID("other-1").generate();
    }

    ClientJwtVerifier verifier() {
        return new ClientJwtVerifier(List.of(
                new Client(EHR, null, new JWKSet(rsa.toPublicJWK()).toString(), null),
                new Client(OTHER, null, new JWKSet(ec.toPublicJWK()).toString(), Set.of("ES384"))),
                Duration.ofSeconds(30), Duration.ofMinutes(5), clock, null);
    }

    static JWTClaimsSet.Builder claims(String iss, String aud, String jti) {
        return new JWTClaimsSet.Builder().issuer(iss).subject("client-app").audience(aud).jwtID(jti)
                .issueTime(Date.from(NOW)).expirationTime(Date.from(NOW.plusSeconds(240)));
    }

    static String sign(JWSSigner signer, JWSAlgorithm alg, String kid, URI jku, JWTClaimsSet claims) throws Exception {
        JWSHeader.Builder h = new JWSHeader.Builder(alg).type(JOSEObjectType.JWT).keyID(kid);
        if (jku != null) {
            h.jwkURL(jku);
        }
        SignedJWT jwt = new SignedJWT(h.build(), claims);
        jwt.sign(signer);
        return jwt.serialize();
    }

    static String rs384(RSAKey key, JWTClaimsSet claims) throws Exception {
        return sign(new RSASSASigner(key), JWSAlgorithm.RS384, key.getKeyID(), null, claims);
    }

    static Reason reason(ClientJwtVerifier v, String token, String aud) {
        return assertThrows(Rejected.class, () -> v.verify(token, aud)).reason();
    }

    @Test
    void aRegisteredClientsTokenIsVerified() throws Exception {
        ClientJwtVerifier.Verified ok = verifier().verify(rs384(rsa, claims(EHR, AUD, "j-1").build()), AUD);
        assertEquals(EHR, ok.issuer());
        assertEquals("client-app", ok.subject());
        assertEquals("j-1", ok.jti());
    }

    @Test
    void es384ClientIsVerifiedWithItsOwnKeySet() throws Exception {
        String token = sign(new ECDSASigner(ec), JWSAlgorithm.ES384, "other-1", null, claims(OTHER, AUD, "e-1").build());
        assertEquals(OTHER, verifier().verify(token, AUD).issuer());
    }

    @Test
    void unknownIssuerIsRefused() throws Exception {
        assertEquals(Reason.UNKNOWN_CLIENT, reason(verifier(),
                rs384(rsa, claims("https://evil.example", AUD, "u-1").build()), AUD));
    }

    @Test
    void aTokenSignedWithAnotherKeyIsRefused() throws Exception {
        assertEquals(Reason.BAD_SIGNATURE, reason(verifier(), rs384(stranger, claims(EHR, AUD, "s-1").build()), AUD));
    }

    @Test
    void aClientCannotUseAnotherClientsIssuer() throws Exception {
        // signed with OTHER's key but claiming EHR: verified against EHR's key set only
        String token = sign(new ECDSASigner(ec), JWSAlgorithm.ES384, "other-1", null, claims(EHR, AUD, "x-1").build());
        assertEquals(Reason.BAD_SIGNATURE, reason(verifier(), token, AUD));
    }

    @Test
    void anAlgorithmTheClientMayNotUseIsRefused() throws Exception {
        String token = sign(new RSASSASigner(rsa), JWSAlgorithm.RS256, "ehr-1", null, claims(EHR, AUD, "a-1").build());
        assertEquals(Reason.BAD_SIGNATURE, reason(verifier(), token, AUD));
    }

    @Test
    void wrongAudienceIsRefused() throws Exception {
        String token = rs384(rsa, claims(EHR, "https://sangam.example.org/cds-services/other", "w-1").build());
        assertEquals(Reason.WRONG_AUDIENCE, reason(verifier(), token, AUD));
    }

    @Test
    void expiredTokenIsRefused() throws Exception {
        String token = rs384(rsa, claims(EHR, AUD, "x-2").build());
        now.set(NOW.plusSeconds(240 + 31));
        assertEquals(Reason.EXPIRED, reason(verifier(), token, AUD));
    }

    @Test
    void tokenIssuedInTheFutureIsRefused() throws Exception {
        JWTClaimsSet c = claims(EHR, AUD, "f-1").issueTime(Date.from(NOW.plusSeconds(120)))
                .expirationTime(Date.from(NOW.plusSeconds(200))).build();
        assertEquals(Reason.NOT_YET_VALID, reason(verifier(), rs384(rsa, c), AUD));
    }

    @Test
    void overlongLifetimeIsRefused() throws Exception {
        JWTClaimsSet c = claims(EHR, AUD, "l-1").expirationTime(Date.from(NOW.plus(Duration.ofHours(1)))).build();
        assertEquals(Reason.LIFETIME_TOO_LONG, reason(verifier(), rs384(rsa, c), AUD));
    }

    @Test
    void missingJtiOrExpOrIatIsRefused() throws Exception {
        assertEquals(Reason.MISSING_CLAIM, reason(verifier(), rs384(rsa, claims(EHR, AUD, null).build()), AUD));
        assertEquals(Reason.MISSING_CLAIM, reason(verifier(),
                rs384(rsa, claims(EHR, AUD, "m-1").expirationTime(null).build()), AUD));
        assertEquals(Reason.MISSING_CLAIM, reason(verifier(),
                rs384(rsa, claims(EHR, AUD, "m-2").issueTime(null).build()), AUD));
    }

    @Test
    void aReplayedJtiIsRefused() throws Exception {
        ClientJwtVerifier v = verifier();
        String token = rs384(rsa, claims(EHR, AUD, "r-1").build());
        v.verify(token, AUD);
        assertEquals(Reason.REPLAYED, reason(v, token, AUD));
    }

    @Test
    void aRefusedTokenDoesNotUseUpItsJti() throws Exception {
        ClientJwtVerifier v = verifier();
        String token = rs384(rsa, claims(EHR, AUD, "k-1").build());
        assertEquals(Reason.WRONG_AUDIENCE, reason(v, token, "https://sangam.example.org/cds-services/other"));
        assertEquals(EHR, v.verify(token, AUD).issuer());
    }

    @Test
    void sameJtiFromTwoClientsIsNotAReplay() throws Exception {
        ClientJwtVerifier v = verifier();
        v.verify(rs384(rsa, claims(EHR, AUD, "same").build()), AUD);
        String other = sign(new ECDSASigner(ec), JWSAlgorithm.ES384, "other-1", null, claims(OTHER, AUD, "same").build());
        assertEquals(OTHER, v.verify(other, AUD).issuer());
    }

    @Test
    void aJkuOtherThanTheRegisteredKeySetIsRefused() throws Exception {
        String token = sign(new RSASSASigner(rsa), JWSAlgorithm.RS384, "ehr-1", URI.create("https://evil.example/jwks"),
                claims(EHR, AUD, "j-2").build());
        assertEquals(Reason.JKU_NOT_ALLOWED, reason(verifier(), token, AUD));
    }

    @Test
    void notAJwtOrAnUnsignedJwtIsRefused() {
        assertEquals(Reason.MALFORMED, reason(verifier(), "not-a-jwt", AUD));
        assertEquals(Reason.MALFORMED, reason(verifier(), "", AUD));
        String plain = new PlainJWT(claims(EHR, AUD, "p-1").build()).serialize();
        assertEquals(Reason.MALFORMED, reason(verifier(), plain, AUD));
    }

    @Test
    void clientRegistrationIsValidated() {
        assertThrows(IllegalArgumentException.class, () -> new Client(EHR, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new Client(EHR, "https://ehr.example.org/jwks", "{\"keys\":[]}", null));
        assertThrows(IllegalArgumentException.class, () -> new Client(" ", "https://ehr.example.org/jwks", null, null));
        assertThrows(IllegalArgumentException.class, () -> new Client(EHR, "https://ehr.example.org/jwks", null, Set.of("XS999")));
        assertThrows(IllegalArgumentException.class, () -> new ClientJwtVerifier(List.of(
                new Client(EHR, "https://a.example/jwks", null, null), new Client(EHR, "https://b.example/jwks", null, null)),
                null, null, clock, null));
        assertThrows(IllegalArgumentException.class, () -> new ClientJwtVerifier(List.of(
                new Client(EHR, null, "not json", null)), null, null, clock, null));
        assertEquals(ClientJwtVerifier.DEFAULT_ALGORITHMS, new Client(EHR, "https://ehr.example.org/jwks", null, null).algorithms());
    }

    @Test
    void inMemoryReplayGuardForgetsExpiredIds() {
        ClientJwtVerifier.InMemoryReplayGuard g = new ClientJwtVerifier.InMemoryReplayGuard(clock);
        assertTrue(g.firstUse(EHR, "a", NOW.plusSeconds(60)));
        assertFalse(g.firstUse(EHR, "a", NOW.plusSeconds(60)));
        now.set(NOW.plusSeconds(61));
        assertTrue(g.firstUse(EHR, "b", NOW.plusSeconds(120)));
        assertEquals(1, g.size(), "the expired id was purged");
    }
}

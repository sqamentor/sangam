// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.publish.cloud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sangam.integration.connector.Json;
import org.sangam.ports.events.EventRegistry;
import org.sangam.ports.events.InboundEventProcessor;
import org.sangam.ports.events.channel.ChannelAdapters;
import org.sangam.ports.events.model.ChannelDefinition;

/**
 * Owner decision 219: Microsoft Entra delivery tokens for inbound Event Grid (issuer, audience and signature against the
 * configured tenant; fail closed), and the endpoint's {@code authMode=entra}.
 */
class EntraTokenValidatorTest {

    private static final String TENANT = "00000000-0000-4000-8000-000000000001";
    private static final String OTHER_TENANT = "00000000-0000-4000-8000-000000000002";
    private static final String AUDIENCE = "api://sangam-eventgrid-hook";
    private static final String V2_ISS = "https://login.microsoftonline.com/" + TENANT + "/v2.0";
    private static final String V1_ISS = "https://sts.windows.net/" + TENANT + "/";
    private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");

    private static KeyPair signer;
    private static KeyPair stranger;

    private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
    private final List<URI> fetched = new ArrayList<>();
    private final Map<URI, String> jwks = new HashMap<>();

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        signer = g.generateKeyPair();
        stranger = g.generateKeyPair();
    }

    private Clock clock() {
        return new Clock() {
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
    }

    private EntraTokenValidator validator() {
        return new EntraTokenValidator(uri -> {
            fetched.add(uri);
            String body = jwks.get(uri);
            if (body == null) {
                throw new EntraTokenValidator.KeysUnavailable("no key set at " + uri);
            }
            return body;
        }, clock());
    }

    private static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String unsigned(BigInteger i) {
        byte[] b = i.toByteArray();
        return b64(b[0] == 0 ? java.util.Arrays.copyOfRange(b, 1, b.length) : b);
    }

    private static String jwkSet(String kid, KeyPair kp, String issuer) {
        RSAPublicKey pk = (RSAPublicKey) kp.getPublic();
        ObjectNode k = Json.MAPPER.createObjectNode();
        k.put("kty", "RSA");
        k.put("use", "sig");
        k.put("kid", kid);
        k.put("n", unsigned(pk.getModulus()));
        k.put("e", unsigned(pk.getPublicExponent()));
        if (issuer != null) {
            k.put("issuer", issuer);
        }
        ObjectNode doc = Json.MAPPER.createObjectNode();
        doc.putArray("keys").add(k);
        return Json.write(doc);
    }

    private void publishV2(String kid, KeyPair kp) {
        jwks.put(URI.create("https://login.microsoftonline.com/" + TENANT + "/discovery/v2.0/keys"),
                jwkSet(kid, kp, "https://login.microsoftonline.com/{tenantid}/v2.0"));
    }

    private static Map<String, Object> claims(String iss) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("iss", iss);
        c.put("aud", AUDIENCE);
        c.put("tid", TENANT);
        c.put("azp", "00000000-0000-4000-8000-0000000000bb");
        c.put("oid", "00000000-0000-4000-8000-0000000000aa");
        c.put("roles", List.of(EntraTokenValidator.DEFAULT_ROLE));
        c.put("nbf", NOW.minusSeconds(60).getEpochSecond());
        c.put("exp", NOW.plusSeconds(3600).getEpochSecond());
        c.put("ver", "2.0");
        return c;
    }

    private static String token(String alg, String kid, Map<String, Object> claims, KeyPair kp) throws Exception {
        ObjectNode h = Json.MAPPER.createObjectNode();
        h.put("typ", "JWT");
        h.put("alg", alg);
        h.put("kid", kid);
        String signingInput = b64(Json.write(h).getBytes(StandardCharsets.UTF_8)) + "."
                + b64(Json.MAPPER.writeValueAsBytes(claims));
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(kp.getPrivate());
        s.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return "Bearer " + signingInput + "." + b64(s.sign());
    }

    private static EntraTokenValidator.Expected expected() {
        return EntraTokenValidator.Expected.of(Map.of("entraTenantId", TENANT, "entraAudience", "other-app, " + AUDIENCE));
    }

    private static String rejection(Runnable r) {
        return assertThrows(SecurityException.class, r::run).getMessage();
    }

    @Test
    void aValidV2TokenFromTheConfiguredTenantIsAccepted() throws Exception {
        publishV2("k1", signer);
        EntraTokenValidator.Caller c = validator().validate(token("RS256", "k1", claims(V2_ISS), signer), expected());
        assertEquals(V2_ISS, c.issuer());
        assertEquals(TENANT, c.tenantId());
        assertTrue(c.roles().contains(EntraTokenValidator.DEFAULT_ROLE));
        assertEquals(List.of(URI.create("https://login.microsoftonline.com/" + TENANT + "/discovery/v2.0/keys")), fetched,
                "the key set comes from the configured tenant only");
    }

    @Test
    void aV1TokenUsesTheV1IssuerAndKeySet() throws Exception {
        jwks.put(URI.create("https://login.microsoftonline.com/" + TENANT + "/discovery/keys"), jwkSet("k1", signer, null));
        Map<String, Object> c = claims(V1_ISS);
        c.remove("azp");
        c.put("appid", "00000000-0000-4000-8000-0000000000bb");
        c.put("ver", "1.0");
        assertEquals("00000000-0000-4000-8000-0000000000bb",
                validator().validate(token("RS256", "k1", c, signer), expected()).appId());
    }

    @Test
    void wrongTenantIssuerAudienceOrRoleIsRejectedBeforeOrAfterTheSignature() throws Exception {
        publishV2("k1", signer);
        EntraTokenValidator v = validator();
        Map<String, Object> tenant = claims("https://login.microsoftonline.com/" + OTHER_TENANT + "/v2.0");
        tenant.put("tid", OTHER_TENANT);
        String t1 = token("RS256", "k1", tenant, signer);
        assertTrue(rejection(() -> v.validate(t1, expected())).contains("tenant"));
        assertTrue(fetched.isEmpty(), "no key set is fetched for a foreign tenant");

        String t2 = token("RS256", "k1", claims("https://evil.example/" + TENANT + "/v2.0"), signer);
        assertTrue(rejection(() -> v.validate(t2, expected())).contains("issuer"));

        Map<String, Object> aud = claims(V2_ISS);
        aud.put("aud", "api://someone-else");
        String t3 = token("RS256", "k1", aud, signer);
        assertTrue(rejection(() -> v.validate(t3, expected())).contains("audience"));

        Map<String, Object> role = claims(V2_ISS);
        role.put("roles", List.of("Reader"));
        String t4 = token("RS256", "k1", role, signer);
        assertTrue(rejection(() -> v.validate(t4, expected())).contains("role"));
        Map<String, Object> noRole = claims(V2_ISS);
        noRole.remove("roles");
        String t5 = token("RS256", "k1", noRole, signer);
        assertTrue(rejection(() -> v.validate(t5, expected())).contains("role"));
    }

    @Test
    void aForgedOrAlteredSignatureIsRejected() throws Exception {
        publishV2("k1", signer);
        EntraTokenValidator v = validator();
        String forged = token("RS256", "k1", claims(V2_ISS), stranger);
        assertTrue(rejection(() -> v.validate(forged, expected())).contains("signature"));
        String good = token("RS256", "k1", claims(V2_ISS), signer);
        String[] p = good.substring(7).split("\\.");
        Map<String, Object> more = claims(V2_ISS);
        more.put("roles", List.of(EntraTokenValidator.DEFAULT_ROLE, "Admin"));
        String swapped = "Bearer " + p[0] + "." + b64(Json.MAPPER.writeValueAsBytes(more)) + "." + p[2];
        assertTrue(rejection(() -> v.validate(swapped, expected())).contains("signature"));
    }

    @Test
    void onlyRs256WithAKidAndNoCritIsAccepted() throws Exception {
        publishV2("k1", signer);
        EntraTokenValidator v = validator();
        String none = "Bearer " + b64("{\"alg\":\"none\",\"kid\":\"k1\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + b64(Json.MAPPER.writeValueAsBytes(claims(V2_ISS))) + ".x";
        assertTrue(rejection(() -> v.validate(none, expected())).contains("RS256"));
        String hs = token("HS256", "k1", claims(V2_ISS), signer);
        assertTrue(rejection(() -> v.validate(hs, expected())).contains("RS256"));
        String noKid = token("RS256", "", claims(V2_ISS), signer);
        assertTrue(rejection(() -> v.validate(noKid, expected())).contains("key"));
        for (String bad : new String[]{null, "", "Basic abc", "Bearer ", "Bearer a.b", "Bearer a.b.c.d"}) {
            assertThrows(SecurityException.class, () -> v.validate(bad, expected()), String.valueOf(bad));
        }
    }

    @Test
    void expiryAndNotBeforeAllowFiveMinutesOfSkewOnly() throws Exception {
        publishV2("k1", signer);
        EntraTokenValidator v = validator();
        Map<String, Object> c = claims(V2_ISS);
        c.put("exp", NOW.minusSeconds(240).getEpochSecond());
        String within = token("RS256", "k1", c, signer);
        v.validate(within, expected());
        c.put("exp", NOW.minusSeconds(301).getEpochSecond());
        String expired = token("RS256", "k1", c, signer);
        assertTrue(rejection(() -> v.validate(expired, expected())).contains("expired"));
        Map<String, Object> early = claims(V2_ISS);
        early.put("nbf", NOW.plusSeconds(301).getEpochSecond());
        String notYet = token("RS256", "k1", early, signer);
        assertTrue(rejection(() -> v.validate(notYet, expected())).contains("not yet"));
        Map<String, Object> noExp = claims(V2_ISS);
        noExp.remove("exp");
        String forever = token("RS256", "k1", noExp, signer);
        assertThrows(SecurityException.class, () -> v.validate(forever, expected()));
    }

    @Test
    void aKeyOfAnotherIssuerOrAShortKeyIsRefused() throws Exception {
        jwks.put(URI.create("https://login.microsoftonline.com/" + TENANT + "/discovery/v2.0/keys"),
                jwkSet("k1", signer, "https://login.microsoftonline.com/" + OTHER_TENANT + "/v2.0"));
        String t = token("RS256", "k1", claims(V2_ISS), signer);
        assertTrue(rejection(() -> validator().validate(t, expected())).contains("issuer"));

        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(1024);
        KeyPair weak = g.generateKeyPair();
        publishV2("k1", weak);
        String w = token("RS256", "k1", claims(V2_ISS), weak);
        assertTrue(rejection(() -> validator().validate(w, expected())).contains("short"));
    }

    @Test
    void keysAreCachedAndAnUnknownKidRefetchesAtMostEveryFiveMinutes() throws Exception {
        publishV2("k1", signer);
        EntraTokenValidator v = validator();
        v.validate(token("RS256", "k1", claims(V2_ISS), signer), expected());
        v.validate(token("RS256", "k1", claims(V2_ISS), signer), expected());
        assertEquals(1, fetched.size(), "cached");

        publishV2("k2", stranger);   // rollover: the tenant now signs with k2
        String rolled = token("RS256", "k2", claims(V2_ISS), stranger);
        assertTrue(rejection(() -> v.validate(rolled, expected())).contains("key set"), "within 5 minutes: no refetch");
        assertEquals(1, fetched.size());
        now.set(NOW.plus(Duration.ofMinutes(6)));
        Map<String, Object> c = claims(V2_ISS);
        c.put("exp", NOW.plusSeconds(7200).getEpochSecond());
        v.validate(token("RS256", "k2", c, stranger), expected());
        assertEquals(2, fetched.size(), "an unknown kid refetches the key set");

        now.set(NOW.plus(Duration.ofHours(25)));
        Map<String, Object> late = claims(V2_ISS);
        late.put("nbf", now.get().getEpochSecond());
        late.put("exp", now.get().plusSeconds(600).getEpochSecond());
        v.validate(token("RS256", "k2", late, stranger), expected());
        assertEquals(3, fetched.size(), "a key set is kept for 24 hours at most");
    }

    @Test
    void keysThatCannotBeFetchedAreUnavailableNeverAccepted() throws Exception {
        String t = token("RS256", "k1", claims(V2_ISS), signer);
        assertThrows(EntraTokenValidator.KeysUnavailable.class, () -> validator().validate(t, expected()));
        jwks.put(URI.create("https://login.microsoftonline.com/" + TENANT + "/discovery/v2.0/keys"), "{\"no\":1}");
        assertThrows(EntraTokenValidator.KeysUnavailable.class, () -> validator().validate(t, expected()));
        EntraTokenValidator throwing = new EntraTokenValidator(uri -> {
            throw new IllegalStateException("network");
        }, clock());
        assertThrows(EntraTokenValidator.KeysUnavailable.class, () -> throwing.validate(t, expected()));
    }

    @Test
    void theConfigurationIsRequiredAndTheRoleCannotBeSwitchedOff() {
        assertThrows(IllegalArgumentException.class, () -> EntraTokenValidator.Expected.of(Map.of("entraAudience", "a")));
        assertThrows(IllegalArgumentException.class, () -> EntraTokenValidator.Expected.of(Map.of("entraTenantId", TENANT)));
        assertThrows(IllegalArgumentException.class, () -> EntraTokenValidator.Expected.of(Map.of("entraTenantId", TENANT,
                "entraAudience", "a", "entraRequiredRole", " ")));
        assertThrows(IllegalArgumentException.class, () -> EntraTokenValidator.Expected.of(Map.of("entraTenantId", TENANT,
                "entraAudience", "a", "entraAuthority", "http://login.example")));
        EntraTokenValidator.Expected e = EntraTokenValidator.Expected.of(Map.of("entraTenantId", TENANT.toUpperCase(),
                "entraAudience", " a , b ", "entraAuthority", "https://login.example/"));
        assertEquals(TENANT, e.tenantId());
        assertEquals(Set.of("a", "b"), e.audiences());
        assertEquals(EntraTokenValidator.DEFAULT_ROLE, e.requiredRole());
        assertEquals("https://login.example", e.authority());
    }

    // ---- the endpoint in authMode entra ----

    private EventGridInboundEndpoint endpoint(List<String> delivered) throws Exception {
        ChannelDefinition ch = Json.MAPPER.readValue("{\"key\":\"ege\",\"channelType\":\"eventgrid\",\"type\":\"inbound\","
                + "\"deserializerType\":\"cloudevents\",\"properties\":{\"authMode\":\"entra\",\"entraTenantId\":\""
                + TENANT + "\",\"entraAudience\":\"" + AUDIENCE + "\"}}", ChannelDefinition.class);
        EventRegistry registry = new EventRegistry(null, null, Duration.ZERO) {
            @Override
            public Optional<DeployedChannel> channel(String tenantId, String key) {
                return "ege".equals(key) ? Optional.of(new DeployedChannel(tenantId, 1, ch)) : Optional.empty();
            }
        };
        InboundEventProcessor p = new InboundEventProcessor(null, null, null, null) {
            @Override
            public Delivery deliver(String tenant, String channelKey, String raw, Map<String, String> h, String id) {
                delivered.add(id);
                return new Delivery(Disposition.ACK, new Result(Outcome.ACCEPTED, "E", id, null), null);
            }
        };
        return new EventGridInboundEndpoint(registry, p, ChannelAdapters.Environment.of(Map.of(), Set.of()), validator());
    }

    private static final String CE = "{\"specversion\":\"1.0\",\"id\":\"e-1\",\"type\":\"OrderPlaced\",\"source\":\"/o\"}";

    @Test
    void theEndpointAcceptsOnlyAValidEntraTokenAndAnswers503WithoutKeys() throws Exception {
        List<String> delivered = new ArrayList<>();
        EventGridInboundEndpoint e = endpoint(delivered);
        String good = token("RS256", "k1", claims(V2_ISS), signer);
        Map<String, String> h = Map.of("Content-Type", "application/cloudevents+json", "Authorization", good);

        EventGridInboundEndpoint.Answer noKeys = e.post("T1", "ege", h, Map.of(), CE);
        assertEquals(503, noKeys.status(), "keys unavailable: Event Grid retries");
        assertTrue(noKeys.headers().containsKey("Retry-After"));
        // code scanning #296: the answer names no key set URI and carries no exception text (that is logged)
        assertEquals(EventGridInboundEndpoint.CODE_AUTHENTICATION_UNAVAILABLE, noKeys.code());
        assertEquals("The delivery cannot be authenticated at the moment; send it again", noKeys.body());
        assertEquals(Integer.valueOf(60), noKeys.retryAfterSeconds());
        assertTrue(delivered.isEmpty());

        publishV2("k1", signer);
        assertEquals(200, endpoint(delivered).post("T1", "ege", h, Map.of(), CE).status());
        assertEquals(List.of("e-1"), delivered);

        EventGridInboundEndpoint e2 = endpoint(delivered);
        assertThrows(SecurityException.class, () -> e2.post("T1", "ege",
                Map.of("Content-Type", "application/cloudevents+json"), Map.of(), CE), "no token");
        String forged = token("RS256", "k1", claims(V2_ISS), stranger);
        assertThrows(SecurityException.class, () -> e2.post("T1", "ege",
                Map.of("Content-Type", "application/cloudevents+json", "Authorization", forged), Map.of(), CE));
        assertThrows(SecurityException.class, () -> e2.options("T1", "ege",
                Map.of("WebHook-Request-Origin", "eventgrid.azure.net"), Map.of()), "the handshake is authenticated too");
        assertEquals(200, e2.options("T1", "ege", Map.of("WebHook-Request-Origin", "eventgrid.azure.net",
                "authorization", good), Map.of()).status());
        assertEquals(List.of("e-1"), delivered, "nothing unauthenticated reaches the processor");
    }

    @Test
    void anEntraChannelIgnoresASharedSecretHeader() throws Exception {
        publishV2("k1", signer);
        EventGridInboundEndpoint e = endpoint(new ArrayList<>());
        assertThrows(SecurityException.class, () -> e.post("T1", "ege", Map.of("Content-Type",
                "application/cloudevents+json", "X-Sangam-Key", "anything"), Map.of(), CE));
    }
}

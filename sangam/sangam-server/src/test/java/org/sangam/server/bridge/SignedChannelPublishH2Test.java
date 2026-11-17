// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.sangam.ports.events.InboundEventProcessor;
import org.sangam.ports.events.channel.WebhookInboundEndpoint;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * SL-76, over real HTTP (h2 profile): a channel with a {@code secretRef} can be fed through the token-authenticated
 * events API, and its webhook ingress stays strictly HMAC-verified.
 *
 * <p>Before: {@code POST /api/v1/events/{channel}} rebuilt the CloudEvent body (with the client's {@code signature}
 * extension inside it) and verified the channel HMAC over that rebuilt body, so every authenticated publish to a
 * signed channel was refused with 403 "webhook signature invalid", and the only way to use the events API was the
 * global {@code sangam.ports.events.allow-unsigned-webhooks} switch.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "sangam.ports.secret-refs=acc-hook",
        "sangam.ports.secrets.acc-hook=" + SignedChannelPublishH2Test.SECRET})
@ActiveProfiles("h2")
@DirtiesContext
class SignedChannelPublishH2Test {

    static final String SECRET = "sl76-channel-secret";
    static final String DEV = "Bearer sangam-dev-token-change-me";
    static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    int port;

    @Autowired
    WebhookInboundEndpoint ingress;

    HttpResponse<String> send(String path, String authorization, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(60)).header("Accept", "application/json")
                .header("Content-Type", "application/json").header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (authorization != null) {
            b.header("Authorization", authorization);
        }
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static String hmac(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aSignedChannelIsFedThroughTheEventsApiAndItsIngressStaysStrict() throws Exception {
        String n = Long.toString(System.nanoTime(), 36);
        String def = "sig-def-" + n;
        String channel = "sig-ch-" + n;
        assertEquals(201, send("/api/v1/events/definitions", DEV, "{\"key\":\"" + def
                + "\",\"name\":\"signed channel\",\"messageName\":\"sangam.test.signed\"}").statusCode());
        HttpResponse<String> ch = send("/api/v1/events/channels", DEV, "{\"key\":\"" + channel + "\",\"name\":\"signed\","
                + "\"direction\":\"INBOUND\",\"transport\":\"REST\",\"eventDefinitionKeys\":[\"" + def + "\"],"
                + "\"active\":true,\"config\":{\"secretRef\":\"acc-hook\"}}");
        assertEquals(201, ch.statusCode(), ch.body());

        // the token-authenticated events API: the principal's token and tenant authenticate the event
        String event = "{\"id\":\"e-" + n + "\",\"source\":\"acc-sl76\",\"type\":\"" + def
                + "\",\"specversion\":\"1.0\",\"data\":{\"amount\":5}}";
        HttpResponse<String> published = send("/api/v1/events/by-channel?channel=" + channel, DEV, event);
        assertEquals(202, published.statusCode(), published.body());
        assertEquals("ACCEPTED", JSON.readTree(published.body()).path("status").asText(), published.body());
        JsonNode again = JSON.readTree(send("/api/v1/events/by-channel?channel=" + channel, DEV, event).body());
        assertEquals("DUPLICATE", again.path("status").asText(), "the inbox still de-duplicates: " + again);
        // a client-supplied signature extension is not a credential on this path (and does not break it)
        String withSig = "{\"id\":\"s-" + n + "\",\"source\":\"acc-sl76\",\"type\":\"" + def
                + "\",\"specversion\":\"1.0\",\"data\":{\"amount\":6},\"extensions\":{\"signature\":\"sha256=00\"}}";
        assertEquals(202, send("/api/v1/events/by-channel?channel=" + channel, DEV, withSig).statusCode());
        // no principal, no event
        assertEquals(401, send("/api/v1/events/by-channel?channel=" + channel, null, event.replace("e-" + n, "x-" + n)).statusCode());

        // the webhook ingress of the same channel, as the server wires it: HMAC over the raw bytes, strictly
        String raw = "{\"specversion\":\"1.0\",\"id\":\"w-" + n + "\",\"source\":\"acc-sl76\",\"type\":\"" + def
                + "\",\"datacontenttype\":\"application/json\",\"data\":{\"amount\":7}}";
        assertThrows(SecurityException.class, () -> ingress.receive("dev", channel, Map.of(), raw), "missing signature");
        assertThrows(SecurityException.class, () -> ingress.receive("dev", channel,
                Map.of("X-Sangam-Signature", hmac("not-the-secret", raw)), raw), "wrong secret");
        assertThrows(SecurityException.class, () -> ingress.receive("dev", channel,
                Map.of("X-Sangam-Signature", hmac(SECRET, raw)), raw.replace("7", "70")), "tampered body");
        InboundEventProcessor.Result ok = ingress.receive("dev", channel,
                Map.of("X-Sangam-Signature", hmac(SECRET, raw), "Idempotency-Key", "w-" + n), raw);
        assertEquals(InboundEventProcessor.Outcome.ACCEPTED, ok.outcome(),
                "the right signature: " + ok.code() + " " + ok.reason());
    }
}

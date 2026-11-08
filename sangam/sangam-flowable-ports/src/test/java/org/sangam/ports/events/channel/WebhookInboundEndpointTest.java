// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.events.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.ports.events.InboundEventProcessor;
import org.sangam.ports.events.InboundEventProcessor.Outcome;
import org.sangam.ports.fwt.events.FwtEventsFixture;
import org.sangam.ports.support.PortsProblem;

/** FC-03 inbound webhook: HMAC signature, idempotency headers, size guard, channel kind and the R38/FW-29 tenant rules. */
class WebhookInboundEndpointTest {

    private static final ChannelAdapters.Environment ENV = ChannelAdapters.Environment.of(Map.of("hook-secret", "k3y"), java.util.Set.of());

    private FwtEventsFixture f;
    private WebhookInboundEndpoint hook;

    @BeforeEach
    void setUp() {
        f = new FwtEventsFixture(2048);
        for (String t : new String[]{"acc", "ops"}) {
            f.registry.deployEvent(t, "{\"key\":\"order\",\"payload\":[{\"name\":\"qty\",\"type\":\"integer\",\"required\":true}]}", null);
        }
        f.registry.deployChannel("acc", FwtEventsFixture.webhookChannel("signed",
                "\"channelEventKeyDetection\":{\"fixedValue\":\"order\"},\"properties\":{\"secretRef\":\"hook-secret\"}"), null);
        f.registry.deployChannel("acc", FwtEventsFixture.webhookChannel("open", "\"channelEventKeyDetection\":{\"fixedValue\":\"order\"},"
                + "\"channelEventTenantJsonPointerExpression\":\"/tenant\""), null);
        f.registry.deployChannel("acc", FwtEventsFixture.webhookChannel("shared", "\"channelEventKeyDetection\":{\"fixedValue\":\"order\"},"
                + "\"channelEventTenantJsonPointerExpression\":\"/tenant\",\"allowPayloadTenant\":true,\"properties\":{\"allowedTenants\":\"ops\"}"), null);
        f.registry.deployChannel("acc", "{\"key\":\"out\",\"channelType\":\"webhook\",\"type\":\"outbound\",\"url\":\"https://x.example/in\"}", null);
        f.registry.deployChannel("acc", "{\"key\":\"jms-in\",\"channelType\":\"jms\",\"type\":\"inbound\",\"destination\":\"q\"}", null);
        hook = new WebhookInboundEndpoint(f.registry, f.processor, ENV);
    }

    @AfterEach
    void tearDown() {
        f.close();
    }

    private static Map<String, String> signed(String body, String... more) {
        Map<String, String> h = new HashMap<>();
        h.put("X-Sangam-Signature", "sha256=" + WebhookInboundEndpoint.hmacHex("k3y", body));
        for (int i = 0; i < more.length; i += 2) {
            h.put(more[i], more[i + 1]);
        }
        return h;
    }

    @Test
    void aCorrectlySignedBodyIsAcceptedAndARedeliveryIsADuplicate() {
        String body = "{\"id\":\"e-1\",\"qty\":3}";
        InboundEventProcessor.Result r = hook.receive("acc", "signed", signed(body), body);
        assertEquals(Outcome.ACCEPTED, r.outcome());
        assertEquals("e-1", r.messageId());
        assertEquals(Outcome.DUPLICATE, hook.receive("acc", "signed", signed(body), body).outcome());
        assertEquals(1, f.count("SELECT COUNT(*) FROM INBOX"));
    }

    @Test
    void headerNamesAreCaseInsensitiveAndIdempotencyKeyWinsOverMessageIdHeaderAndPointer() {
        String body = "{\"id\":\"from-body\",\"qty\":1}";
        Map<String, String> h = new HashMap<>();
        h.put("x-sangam-signature", "sha256=" + WebhookInboundEndpoint.hmacHex("k3y", body));
        h.put("IDEMPOTENCY-KEY", "idem-1");
        h.put("X-Sangam-Message-Id", "msg-1");
        h.put(null, "ignored");
        assertEquals("from-body", hook.receive("acc", "signed", h, body).messageId(), "the channel's messageIdJsonPointer wins");

        String noId = "{\"qty\":1}";
        Map<String, String> h2 = signed(noId, "Idempotency-Key", "idem-2", "X-Sangam-Message-Id", "msg-2");
        assertEquals("idem-2", hook.receive("acc", "signed", h2, noId).messageId());
        Map<String, String> h3 = signed(noId, "X-Sangam-Message-Id", "msg-3");
        assertEquals("msg-3", hook.receive("acc", "signed", h3, noId).messageId());
    }

    @Test
    void aMissingTamperedOrForeignSignatureIsRefusedBeforeAnythingIsStored() {
        String body = "{\"id\":\"e-2\",\"qty\":3}";
        assertThrows(SecurityException.class, () -> hook.receive("acc", "signed", Map.of(), body));
        assertThrows(SecurityException.class, () -> hook.receive("acc", "signed", signed(body), body.replace("3", "300")));
        Map<String, String> wrongKey = Map.of("X-Sangam-Signature", "sha256=" + WebhookInboundEndpoint.hmacHex("other", body));
        assertThrows(SecurityException.class, () -> hook.receive("acc", "signed", wrongKey, body));
        assertEquals(0, f.count("SELECT COUNT(*) FROM INBOX"));
        assertEquals(0, f.count("SELECT COUNT(*) FROM EVT_LOG"));
    }

    @Test
    void emptyAndOversizedBodiesAreProblemsAndOnlyInboundWebhookChannelsAccept() {
        PortsProblem empty = assertThrows(PortsProblem.class, () -> hook.receive("acc", "open", Map.of(), ""));
        assertEquals(422, empty.status());
        assertThrows(PortsProblem.class, () -> hook.receive("acc", "open", Map.of(), null));
        PortsProblem big = assertThrows(PortsProblem.class, () -> hook.receive("acc", "open", Map.of(),
                "{\"id\":\"x\",\"pad\":\"" + "a".repeat(3000) + "\"}"));
        assertEquals("PAYLOAD_TOO_LARGE", big.code());
        assertEquals(413, big.status());
        // multi-byte text under the char limit but over the byte limit: the processor's byte check rejects it
        InboundEventProcessor.Result mb = hook.receive("acc", "open", Map.of(), "{\"id\":\"y\",\"qty\":1,\"t\":\"" + "€".repeat(1000) + "\"}");
        assertEquals("PAYLOAD_TOO_LARGE", mb.code());

        assertThrows(IllegalArgumentException.class, () -> hook.receive("acc", "out", Map.of(), "{}"), "outbound channel");
        assertThrows(IllegalArgumentException.class, () -> hook.receive("acc", "jms-in", Map.of(), "{}"), "broker channel");
        assertThrows(IllegalArgumentException.class, () -> hook.receive("acc", "nope", Map.of(), "{}"));
    }

    @Test
    void aChannelExistsOnlyInTheTenantItWasDeployedInto() {
        // R38: tenant ops has no channel "open"; acc's channel is not reachable through another tenant's URL
        assertThrows(IllegalArgumentException.class, () -> hook.receive("ops", "open", Map.of(), "{\"id\":\"z\",\"qty\":1}"));
        assertEquals(0, f.count("SELECT COUNT(*) FROM INBOX"));
    }

    @Test
    void aPayloadTenantIsHonouredOnlyOnASharedChannelAndOnlyForAllowedTenants() {
        InboundEventProcessor.Result foreign = hook.receive("acc", "open", Map.of(), "{\"id\":\"t1\",\"qty\":1,\"tenant\":\"ops\"}");
        assertEquals("TENANT_MISMATCH", foreign.code(), "FW-29: a plain channel never lets the payload pick the tenant");
        assertEquals(Outcome.ACCEPTED, hook.receive("acc", "open", Map.of(), "{\"id\":\"t2\",\"qty\":1,\"tenant\":\" \"}").outcome(),
                "a blank detected tenant counts as not detected");

        assertEquals(Outcome.ACCEPTED, hook.receive("acc", "shared", Map.of(), "{\"id\":\"t3\",\"qty\":1,\"tenant\":\"ops\"}").outcome());
        assertEquals("TENANT_MISMATCH", hook.receive("acc", "shared", Map.of(), "{\"id\":\"t4\",\"qty\":1,\"tenant\":\"hr\"}").code(),
                "not in allowedTenants");
        assertEquals(List.of("acc|t2", "ops|t3"), f.strings("SELECT tenant_id || '|' || message_id FROM INBOX ORDER BY message_id"));
    }
}

// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.sangam.ports.events.EventRegistry;
import org.sangam.ports.events.InboundEventProcessor;
import org.sangam.ports.events.channel.WebhookInboundEndpoint;
import org.sangam.ports.events.model.ChannelDefinition;
import org.sangam.ports.events.model.EventDefinition;
import org.sangam.ports.support.Json;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.EventPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.RestProperties;

/**
 * SL-107: direct unit test of {@link PortsEventBridge} (no database: the registry and the webhook endpoint are
 * recording fakes). Covers the SL-80 naming rule as the bridge applies it when it declares an event definition to the
 * ports registry, and the unchanged SL-76 publish path (token-authenticated: {@code receiveAuthenticated}, never the
 * HMAC-checked {@code receive}; rejection codes mapped to their problems).
 */
class PortsEventBridgeTest {

    static final RequestContext CTX = new RequestContext("t1", "alice", Set.of("sangam-admin"), null, null, null, null,
            null, "TEST");

    /** Records every deployed definition JSON; serves one inbound channel. */
    static final class FakeRegistry extends EventRegistry {
        final List<String> deployed = new ArrayList<>();
        boolean channelActive = true;
        String channelType = "inbound";

        FakeRegistry() {
            super(null, null, Duration.ZERO);
        }

        @Override
        public DeployedEvent deployEvent(String tenantId, String json, String appDeploymentId) {
            deployed.add(json);
            return new DeployedEvent(tenantId, deployed.size(), Json.read(json, EventDefinition.class));
        }

        @Override
        public Optional<DeployedChannel> channel(String tenantId, String key) {
            String json = "{\"key\":\"" + key + "\",\"name\":\"" + key + "\",\"channelType\":\"webhook\",\"type\":\""
                    + channelType + "\",\"topics\":[],\"properties\":{\"active\":\"" + channelActive + "\"}}";
            return Optional.of(new DeployedChannel(tenantId, 1, Json.read(json, ChannelDefinition.class)));
        }

        JsonNode last() {
            return Json.MAPPER.valueToTree(Json.read(deployed.get(deployed.size() - 1), Map.class));
        }
    }

    /** Records which entry point the bridge used; answers with a scripted result. */
    static final class FakeWebhook extends WebhookInboundEndpoint {
        final List<String> calls = new ArrayList<>();
        InboundEventProcessor.Result answer = new InboundEventProcessor.Result(InboundEventProcessor.Outcome.ACCEPTED,
                "evt", "m-1", null);

        FakeWebhook(EventRegistry r) {
            super(r, null, null);
        }

        @Override
        public InboundEventProcessor.Result receive(String tenant, String channelKey, Map<String, String> headers,
                                                    String body) {
            calls.add("receive");
            return answer;
        }

        @Override
        public InboundEventProcessor.Result receiveAuthenticated(String tenant, String channelKey,
                                                                 Map<String, String> headers, String body) {
            calls.add("receiveAuthenticated:" + tenant + ":" + channelKey + ":" + headers.get("ce-id"));
            return answer;
        }
    }

    final List<String> audited = new ArrayList<>();
    final FakeRegistry registry = new FakeRegistry();
    final FakeWebhook webhook = new FakeWebhook(registry);
    final PortsEventBridge bridge = new PortsEventBridge(registry, webhook, null, audit(), new RestProperties());

    AuditPort audit() {
        return (AuditPort) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{AuditPort.class},
                (p, m, a) -> {
                    if (m.getName().equals("record")) {
                        audited.add((String) a[2]);
                        return "audit-id";
                    }
                    throw new UnsupportedOperationException(m.getName());
                });
    }

    static EventPort.EventDefinition def(List<String> corr, String bkPointer, boolean uniqueStart) {
        return new EventPort.EventDefinition("bill-in", null, "bill in", 0, null, corr, bkPointer, "bill-start", null,
                uniqueStart, 0);
    }

    static List<String> names(JsonNode d) {
        List<String> out = new ArrayList<>();
        d.path("correlationParameters").forEach(n -> out.add(n.path("name").asText()));
        return out;
    }

    // ---------------------------------------------------------------- SL-80: one naming rule through the bridge

    @Test
    void nestedCorrelationPointersAndANestedBusinessKeyGetTheSameDottedName() {
        bridge.createDefinition(CTX, def(List.of("/bill/head", "/payee"), "/bill/id", true));
        JsonNode d = registry.last();
        assertEquals(List.of("bill.head", "payee", "bill.id"), names(d));
        assertEquals("bill.id", d.path("startUniqueReference").asText(), "was bill/id before SL-80");
        assertEquals("bill/id", d.path("businessKeyField").asText(), "the business key is still read at its path");
        assertTrue(audited.contains("EVENT_DEFINITION_CHANGED"));
    }

    @Test
    void aBusinessKeyAlreadyDeclaredAsACorrelationParameterIsNotDeclaredTwice() {
        bridge.createDefinition(CTX, def(List.of("/bill/id"), "/bill/id", true));
        assertEquals(List.of("bill.id"), names(registry.last()));
    }

    @Test
    void withoutAUniqueStartTheBusinessKeyIsNotACorrelationParameter() {
        bridge.createDefinition(CTX, def(List.of("/a/b"), "/bill/id", false));
        JsonNode d = registry.last();
        assertEquals(List.of("a.b"), names(d));
        assertTrue(d.path("startUniqueReference").isMissingNode() || d.path("startUniqueReference").isNull());
    }

    @Test
    void ambiguousPointersAreRefusedBeforeAnythingIsDeployed() {
        ApiProblem corr = assertThrows(ApiProblem.class,
                () -> bridge.createDefinition(CTX, def(List.of("/bill.no"), null, false)));
        assertEquals(400, corr.status());
        assertEquals("correlationParameters", corr.extensions().get("field"));
        ApiProblem bk = assertThrows(ApiProblem.class,
                () -> bridge.createDefinition(CTX, def(List.of(), "/bill~1id", false)));
        assertEquals("businessKeyPointer", bk.extensions().get("field"));
        assertTrue(registry.deployed.isEmpty());
    }

    @Test
    void namesThatAreNotPointersStayAsGiven() {
        bridge.createDefinition(CTX, def(List.of("billId"), "billId", true));
        JsonNode d = registry.last();
        assertEquals(List.of("billId"), names(d));
        assertEquals("billId", d.path("startUniqueReference").asText());
    }

    // ---------------------------------------------------------------- SL-76: publish path unchanged

    static EventPort.CloudEvent event() {
        return new EventPort.CloudEvent("e-1", "urn:test", "bill.submitted", "1.0", null, null, null, null,
                Map.of("billId", "B1"), Map.of("signature", "ignored-by-the-authenticated-api"));
    }

    @Test
    void anAuthenticatedPublishUsesTheTokenAuthenticatedEntryPointNotTheHmacOne() {
        EventPort.PublishResult r = bridge.publish(CTX, "bills", event());
        assertEquals(List.of("receiveAuthenticated:t1:bills:e-1"), webhook.calls);
        assertFalse(webhook.calls.contains("receive"));
        assertEquals("ACCEPTED", r.status());
        assertEquals("m-1", r.eventId());
        assertTrue(audited.contains("EVENT_RECEIVED"));
    }

    @Test
    void rejectionsMapToTheirProblems() {
        webhook.answer = new InboundEventProcessor.Result(InboundEventProcessor.Outcome.REJECTED, null, null,
                "TENANT_MISMATCH: payload tenant differs");
        assertEquals(403, assertThrows(ApiProblem.class, () -> bridge.publish(CTX, "bills", event())).status());
        webhook.answer = new InboundEventProcessor.Result(InboundEventProcessor.Outcome.REJECTED, null, null,
                "PAYLOAD_TOO_LARGE: 2 MiB");
        assertEquals(413, assertThrows(ApiProblem.class, () -> bridge.publish(CTX, "bills", event())).status());
        webhook.answer = new InboundEventProcessor.Result(InboundEventProcessor.Outcome.REJECTED, null, null,
                "no event key detected");
        ApiProblem other = assertThrows(ApiProblem.class, () -> bridge.publish(CTX, "bills", event()));
        assertEquals(422, other.status());
        assertEquals("EVENT_REJECTED", other.code());
        assertEquals("INVALID_INPUT", other.extensions().get("rejectionCode"));
    }

    @Test
    void aDuplicateIsReportedAsDuplicate() {
        webhook.answer = new InboundEventProcessor.Result(InboundEventProcessor.Outcome.DUPLICATE, "evt", "m-1", null);
        assertEquals("DUPLICATE", bridge.publish(CTX, "bills", event()).status());
    }

    @Test
    void anInactiveOrOutboundChannelIsRefusedBeforeTheEndpoint() {
        registry.channelActive = false;
        assertEquals("CHANNEL_INACTIVE", assertThrows(ApiProblem.class,
                () -> bridge.publish(CTX, "bills", event())).code());
        registry.channelActive = true;
        registry.channelType = "outbound";
        assertEquals("CHANNEL_INACTIVE", assertThrows(ApiProblem.class,
                () -> bridge.publish(CTX, "bills", event())).code());
        assertTrue(webhook.calls.isEmpty());
    }
}

// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.publish.cloud;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.Json;
import org.sangam.integration.connector.http.GcpTokens;
import org.sangam.ports.events.InboundEventProcessor;
import org.sangam.ports.events.channel.ChannelAdapters;
import org.sangam.ports.events.channel.PollingInbound;
import org.sangam.ports.events.model.ChannelDefinition;

/**
 * Google Cloud Pub/Sub over its REST API (M-064; REST, not the gRPC client: no gRPC in the product). Properties: {@code project},
 * {@code topic} (out) or {@code subscription} (in), and the credentials: {@code serviceAccountKeyRef} (a secret
 * reference to the service-account key JSON; its token endpoint must be on the egress allow-list) or
 * {@code metadataUrl} (the workload's metadata server, normally {@code http://metadata.google.internal}); optional
 * {@code endpoint} (default {@value #ENDPOINT}). Access tokens come from the product's one Google token source
 * ({@link GcpTokens}, scope {@value #SCOPE}).
 *
 * <ul>
 *   <li><b>Out:</b> {@code topics/<topic>:publish} with one message: {@code data} = the body (base64), attributes
 *       {@code message-id}, {@code event-key}, {@code tenant-id}, {@code content-type}; CloudEvents binary mode uses the
 *       Pub/Sub binding's {@code ce-<attr>} attributes.</li>
 *   <li><b>In:</b> {@code subscriptions/<sub>:pull} ({@code batch} messages); {@code :acknowledge} the ones the processor
 *       said acknowledge; {@code :modifyAckDeadline} the others to {@code redeliverBackoffMs} (bounded 0..600 s).
 *       Message id: the {@code message-id} attribute, else the Pub/Sub {@code messageId}.</li>
 * </ul>
 */
final class PubSubChannels {

    static final String ENDPOINT = "https://pubsub.googleapis.com";
    static final String SCOPE = "https://www.googleapis.com/auth/pubsub";

    private PubSubChannels() {
    }

    static GcpTokens tokens(ChannelDefinition ch, CloudChannels.Http http) {
        String keyRef = CloudChannels.prop(ch, "serviceAccountKeyRef", null);
        if (keyRef != null) {
            return GcpTokens.serviceAccount(keyRef, http.secrets(), http.transport(), http.clock());
        }
        String metadata = CloudChannels.prop(ch, "metadataUrl", null);
        if (metadata != null) {
            return GcpTokens.metadata(URI.create(metadata), http.transport(), http.clock());
        }
        throw new IllegalArgumentException("pubsub channel " + ch.key() + " needs properties.serviceAccountKeyRef or properties.metadataUrl");
    }

    static String base(ChannelDefinition ch) {
        return CloudChannels.prop(ch, "endpoint", ENDPOINT).replaceAll("/+$", "") + "/v1/projects/"
                + CloudChannels.required(ch, "project");
    }

    static JsonNode call(ChannelDefinition ch, CloudChannels.Http http, GcpTokens tokens, String tenant, String url,
                         ObjectNode body, String what) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/json");
        h.put("Authorization", "Bearer " + tokens.accessToken(tenant == null ? "system" : tenant, SCOPE));
        return http.okJson(tenant, what, new HttpTransport.Call("POST", URI.create(url), h, Json.bytes(body), null, false));
    }

    static final class Outbound implements ChannelAdapters.OutboundChannelAdapter {
        private final ChannelDefinition ch;
        private final CloudChannels.Http http;
        private final GcpTokens tokens;
        private final String url;

        Outbound(ChannelDefinition ch, CloudChannels.Http http) {
            this.ch = ch;
            this.http = http;
            this.tokens = tokens(ch, http);
            this.url = base(ch) + "/topics/" + CloudChannels.required(ch, "topic") + ":publish";
        }

        @Override
        public void send(String tenantId, String eventKey, String messageId, String body) throws Exception {
            send(ChannelAdapters.Outgoing.json(tenantId, eventKey, messageId, body));
        }

        @Override
        public void send(ChannelAdapters.Outgoing m) {
            ObjectNode b = Json.MAPPER.createObjectNode();
            ObjectNode msg = b.putArray("messages").addObject();
            msg.put("data", Base64.getEncoder().encodeToString(m.body().getBytes(StandardCharsets.UTF_8)));
            ObjectNode attrs = msg.putObject("attributes");
            CloudChannels.metadata(m, "ce-").forEach(attrs::put);
            JsonNode r = call(ch, http, tokens, m.tenantId(), url, b, "Pub/Sub publish to " + ch.key());
            if (r.path("messageIds").size() != 1) {
                throw new IllegalStateException("Pub/Sub publish to " + ch.key() + " returned no message id");
            }
        }
    }

    static final class Inbound extends PollingInbound {
        private final CloudChannels.Http http;
        private final GcpTokens tokens;
        private final String sub;

        Inbound(ChannelDefinition ch, String tenant, InboundEventProcessor processor, CloudChannels.Http http) {
            super(ch, tenant, processor);
            this.http = http;
            this.tokens = tokens(ch, http);
            this.sub = base(ch) + "/subscriptions/" + CloudChannels.required(ch, "subscription");
        }

        @Override
        protected int pollOnce() {
            ObjectNode req = Json.MAPPER.createObjectNode();
            req.put("maxMessages", CloudChannels.intProp(ch, "batch", 10, 1, 1000));
            JsonNode res = call(ch, http, tokens, tenant, sub + ":pull", req, "Pub/Sub pull on " + ch.key());
            List<String> ack = new ArrayList<>();
            List<String> nack = new ArrayList<>();
            int n = 0;
            for (JsonNode rm : res.path("receivedMessages")) {
                n++;
                JsonNode msg = rm.path("message");
                Map<String, String> headers = new LinkedHashMap<>();
                msg.path("attributes").fields().forEachRemaining(e -> headers.put(e.getKey(), e.getValue().asText(null)));
                headers.put("pubsub-message-id", msg.path("messageId").asText());
                String id = headers.containsKey("message-id") ? headers.get("message-id") : msg.path("messageId").asText(null);
                String body = new String(Base64.getDecoder().decode(msg.path("data").asText("")), StandardCharsets.UTF_8);
                boolean ok;
                try {
                    ok = deliver(body, headers, id);
                } catch (RuntimeException e) {
                    ok = false;
                }
                (ok ? ack : nack).add(rm.path("ackId").asText());
            }
            if (!ack.isEmpty()) {
                ObjectNode a = Json.MAPPER.createObjectNode();
                ids(a, ack);
                call(ch, http, tokens, tenant, sub + ":acknowledge", a, "Pub/Sub acknowledge on " + ch.key());
            }
            if (!nack.isEmpty()) {
                ObjectNode a = Json.MAPPER.createObjectNode();
                ids(a, nack);
                a.put("ackDeadlineSeconds", Math.min(600, redeliverDelaySeconds()));
                call(ch, http, tokens, tenant, sub + ":modifyAckDeadline", a, "Pub/Sub modifyAckDeadline on " + ch.key());
            }
            return n;
        }

        private static void ids(ObjectNode a, List<String> ids) {
            ArrayNode arr = a.putArray("ackIds");
            ids.forEach(arr::add);
        }
    }
}

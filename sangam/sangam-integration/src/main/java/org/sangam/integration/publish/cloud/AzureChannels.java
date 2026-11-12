// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.publish.cloud;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.Json;
import org.sangam.ports.events.InboundEventProcessor;
import org.sangam.ports.events.channel.ChannelAdapters;
import org.sangam.ports.events.channel.PollingInbound;
import org.sangam.ports.events.model.ChannelDefinition;
import org.sangam.ports.events.pipeline.CloudEventsFormat;

/**
 * Azure channel families over their public REST APIs (M-064).
 *
 * <ul>
 *   <li><b>servicebus</b>: {@code namespace} (name or host), {@code entityPath} (queue, or
 *       {@code topic/subscriptions/name} for receiving), {@code sasKeyName}, {@code sasKeyRef} (secret reference); a SAS
 *       token ({@link AzureSas}) is signed per call. Out: {@code POST <entity>/messages} with the message's
 *       {@code Content-Type} and {@code BrokerProperties} {@code MessageId} (duplicate detection, when enabled on the
 *       entity, uses it) and {@code Label} = the event key. In: peek-lock {@code POST <entity>/messages/head}, complete
 *       with {@code DELETE} on the lock location once the processor said acknowledge; otherwise the lock is left to
 *       expire (the entity's lock duration is the redelivery delay). Plain or structured CloudEvents only: binary mode
 *       would need custom properties, whose REST encoding is an open owner question.</li>
 *   <li><b>eventgrid</b> (namespace topic, outbound): {@code endpoint} (the namespace host), {@code topic},
 *       {@code keyRef} (access key reference), optional {@code apiVersion} (default {@value #EVENT_GRID_API_VERSION});
 *       {@code POST https://<endpoint>/topics/<topic>:publish} with {@code Content-Type: application/cloudevents+json}
 *       and {@code Authorization: SharedAccessKey <key>}. CloudEvents structured only (enforced at deploy).</li>
 * </ul>
 */
final class AzureChannels {

    static final String EVENT_GRID_API_VERSION = "2024-06-01";
    static final Duration SAS_LIFETIME = Duration.ofMinutes(5);

    private AzureChannels() {
    }

    static String serviceBusEntity(ChannelDefinition ch) {
        String ns = CloudChannels.required(ch, "namespace");
        String host = ns.contains(".") ? ns : ns + ".servicebus.windows.net";
        // properties.endpoint: a full base URL instead (a private endpoint or a local emulator), still egress-checked
        String base = CloudChannels.prop(ch, "endpoint", "https://" + host).replaceAll("/+$", "");
        String path = CloudChannels.required(ch, "entityPath").replaceAll("^/+|/+$", "");
        return base + "/" + path;
    }

    static Map<String, String> sas(ChannelDefinition ch, CloudChannels.Http http, String entity) {
        String token = AzureSas.token(entity, CloudChannels.required(ch, "sasKeyName"),
                http.secret(CloudChannels.required(ch, "sasKeyRef")), http.clock().instant().plus(SAS_LIFETIME));
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", token);
        return h;
    }

    static final class ServiceBusOutbound implements ChannelAdapters.OutboundChannelAdapter {
        private final ChannelDefinition ch;
        private final CloudChannels.Http http;
        private final String entity;

        ServiceBusOutbound(ChannelDefinition ch, CloudChannels.Http http) {
            this.ch = ch;
            this.http = http;
            this.entity = serviceBusEntity(ch);
        }

        @Override
        public void send(String tenantId, String eventKey, String messageId, String body) throws Exception {
            send(ChannelAdapters.Outgoing.json(tenantId, eventKey, messageId, body));
        }

        @Override
        public void send(ChannelAdapters.Outgoing m) {
            if (m.binary()) {
                throw new IllegalStateException("servicebus channel " + ch.key() + " cannot send CloudEvents binary mode");
            }
            Map<String, String> h = sas(ch, http, entity);
            h.put("Content-Type", m.contentType());
            ObjectNode bp = Json.MAPPER.createObjectNode();
            bp.put("MessageId", m.messageId());
            bp.put("Label", m.eventKey());
            h.put("BrokerProperties", Json.write(bp));
            http.ok(m.tenantId(), "Service Bus send to " + ch.key(), new HttpTransport.Call("POST", URI.create(entity + "/messages"),
                    h, m.body().getBytes(StandardCharsets.UTF_8), null, false));
        }
    }

    static final class ServiceBusInbound extends PollingInbound {
        private final CloudChannels.Http http;
        private final String entity;

        ServiceBusInbound(ChannelDefinition ch, String tenant, InboundEventProcessor processor, CloudChannels.Http http) {
            super(ch, tenant, processor);
            this.http = http;
            this.entity = serviceBusEntity(ch);
        }

        @Override
        protected int pollOnce() {
            int batch = CloudChannels.intProp(ch, "batch", 10, 1, 100);
            int wait = CloudChannels.intProp(ch, "waitSeconds", 20, 0, 60);
            int n = 0;
            for (int i = 0; i < batch; i++) {
                HttpTransport.Outcome.Response r = http.ok(tenant, "Service Bus peek-lock on " + ch.key(),
                        new HttpTransport.Call("POST", URI.create(entity + "/messages/head?timeout=" + (i == 0 ? wait : 1)),
                                sas(ch, http, entity), null, Duration.ofSeconds(wait + 30L), false));
                if (r.status() == 204) {
                    break;
                }
                n++;
                settle(r);
            }
            return n;
        }

        private void settle(HttpTransport.Outcome.Response r) {
            JsonNode props = Json.tree(r.header("BrokerProperties").orElse("{}"));
            String lock = r.header("Location").orElseThrow(() -> new IllegalStateException("peek-lock answer has no Location"));
            Map<String, String> headers = new LinkedHashMap<>();
            r.header("Content-Type").ifPresent(v -> headers.put("content-type", v));
            String label = props == null ? null : props.path("Label").asText(null);
            if (label != null) {
                headers.put("servicebus-label", label);
            }
            String id = props == null ? null : props.path("MessageId").asText(null);
            boolean ack;
            try {
                ack = deliver(r.bodyString(), headers, id);
            } catch (RuntimeException e) {
                ack = false;
            }
            if (ack) {
                http.ok(tenant, "Service Bus complete on " + ch.key(), new HttpTransport.Call("DELETE", URI.create(lock),
                        sas(ch, http, entity), null, null, true));
            }
            // not acknowledged: the lock expires and Service Bus offers the message again (DeliveryCount + 1)
        }
    }

    static final class EventGridOutbound implements ChannelAdapters.OutboundChannelAdapter {
        private final ChannelDefinition ch;
        private final CloudChannels.Http http;
        private final URI publish;

        EventGridOutbound(ChannelDefinition ch, CloudChannels.Http http) {
            this.ch = ch;
            this.http = http;
            String endpoint = CloudChannels.required(ch, "endpoint").replaceAll("/+$", "");
            String base = endpoint.matches("(?i)^https?://.*") ? endpoint : "https://" + endpoint;
            this.publish = URI.create(base + "/topics/" + CloudChannels.required(ch, "topic") + ":publish?api-version="
                    + CloudChannels.prop(ch, "apiVersion", EVENT_GRID_API_VERSION));
        }

        @Override
        public void send(String tenantId, String eventKey, String messageId, String body) {
            throw new IllegalStateException("eventgrid channel " + ch.key() + " takes CloudEvents only (serializerType cloudevents)");
        }

        @Override
        public void send(ChannelAdapters.Outgoing m) {
            if (m.binary() || !m.contentType().startsWith(CloudEventsFormat.STRUCTURED_CONTENT_TYPE)) {
                throw new IllegalStateException("eventgrid channel " + ch.key() + " takes structured CloudEvents only");
            }
            Map<String, String> h = new LinkedHashMap<>();
            h.put("Content-Type", CloudEventsFormat.STRUCTURED_CONTENT_TYPE + "; charset=utf-8");
            h.put("Authorization", "SharedAccessKey " + http.secret(CloudChannels.required(ch, "keyRef")));
            http.ok(m.tenantId(), "Event Grid publish to " + ch.key(), new HttpTransport.Call("POST", publish, h,
                    m.body().getBytes(StandardCharsets.UTF_8), null, false));
        }
    }
}

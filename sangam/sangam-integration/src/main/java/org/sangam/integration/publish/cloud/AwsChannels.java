// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.publish.cloud;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.Json;
import org.sangam.integration.connector.http.AwsRequestSigner;
import org.sangam.ports.events.InboundEventProcessor;
import org.sangam.ports.events.channel.AwsSigV4;
import org.sangam.ports.events.channel.ChannelAdapters;
import org.sangam.ports.events.channel.PollingInbound;
import org.sangam.ports.events.model.ChannelDefinition;

/**
 * AWS channel families over the public HTTPS APIs, SigV4-signed (M-064). Properties: {@code region},
 * {@code accessKeyRef}, {@code secretKeyRef}, optional {@code sessionTokenRef} (secret references), optional
 * {@code endpoint} (a VPC endpoint or a compatible service; default the regional public endpoint).
 *
 * <ul>
 *   <li><b>sqs</b> (AWS JSON 1.0, {@code X-Amz-Target: AmazonSQS.<Operation>}): out = {@code SendMessage} to
 *       {@code queueUrl} (a FIFO queue gets {@code MessageGroupId} = {@code properties.messageGroupId} or the event key,
 *       and {@code MessageDeduplicationId} = the message id); in = {@code ReceiveMessage} long polling
 *       ({@code waitSeconds}, default 20; {@code batch} up to 10), {@code DeleteMessage} once the processor said
 *       acknowledge, otherwise {@code ChangeMessageVisibility} to {@code redeliverBackoffMs}. With
 *       {@code properties.snsEnvelope=true} a queue subscribed to an SNS topic without raw delivery is unwrapped
 *       (message and its attributes).</li>
 *   <li><b>sns</b> (Query API {@code Publish}, form encoded) to {@code topicArn}; FIFO topics as for SQS.</li>
 *   <li><b>eventbridge</b> (AWS JSON 1.1 {@code AWSEvents.PutEvents}): one entry, {@code Source} =
 *       {@code properties.source} (default {@code sangam}), {@code DetailType} = the event key, {@code Detail} = the
 *       body (a JSON object), {@code EventBusName} = {@code properties.eventBusName} (default {@code default}); a
 *       non-zero {@code FailedEntryCount} is a failure. EventBridge has no message metadata: plain or structured only.</li>
 * </ul>
 * Message attributes (SQS, SNS): {@code message-id}, {@code event-key}, {@code tenant-id}, {@code content-type}; in
 * CloudEvents binary mode the attributes are {@code ce-<attr>} (the CloudEvents HTTP naming; AWS has no official
 * binding) plus {@code content-type}, at most 10 as both services allow.
 */
final class AwsChannels {

    private AwsChannels() {
    }

    static String region(ChannelDefinition ch) {
        return CloudChannels.required(ch, "region");
    }

    static URI endpoint(ChannelDefinition ch, String service) {
        String base = CloudChannels.prop(ch, "endpoint", "https://" + service + "." + region(ch) + ".amazonaws.com");
        return URI.create(base.replaceAll("/+$", "") + "/");
    }

    static AwsSigV4.Credentials credentials(ChannelDefinition ch, CloudChannels.Http http) {
        String session = CloudChannels.prop(ch, "sessionTokenRef", null);
        return new AwsSigV4.Credentials(http.secret(CloudChannels.required(ch, "accessKeyRef")),
                http.secret(CloudChannels.required(ch, "secretKeyRef")), session == null ? null : http.secret(session));
    }

    /** Signs and sends one POST; returns the 2xx answer or throws. */
    static HttpTransport.Outcome.Response post(ChannelDefinition ch, CloudChannels.Http http, String tenant, String service,
                                               URI uri, Map<String, String> headers, byte[] body, String what) {
        Map<String, String> h = new LinkedHashMap<>(headers);
        h.putAll(AwsRequestSigner.sign("POST", uri, headers, body, region(ch), service, credentials(ch, http),
                http.clock().instant()));
        return http.ok(tenant, what, new HttpTransport.Call("POST", uri, h, body, null, false));
    }

    static JsonNode json(ChannelDefinition ch, CloudChannels.Http http, String tenant, String service, String target,
                         String version, ObjectNode body, String what) {
        URI uri = endpoint(ch, service);
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/x-amz-json-" + version);
        h.put("X-Amz-Target", target);
        HttpTransport.Outcome.Response r = post(ch, http, tenant, service, uri, h, Json.bytes(body), what);
        JsonNode n = r.body().length == 0 ? null : Json.tree(r.body());
        return n == null ? Json.MAPPER.createObjectNode() : n;
    }

    static boolean fifo(String queueOrTopic) {
        return queueOrTopic.endsWith(".fifo");
    }

    static String groupId(ChannelDefinition ch, ChannelAdapters.Outgoing m) {
        return CloudChannels.prop(ch, "messageGroupId", m.eventKey());
    }

    /** SQS: send to {@code queueUrl}. */
    static final class SqsOutbound implements ChannelAdapters.OutboundChannelAdapter {
        private final ChannelDefinition ch;
        private final CloudChannels.Http http;
        private final String queueUrl;

        SqsOutbound(ChannelDefinition ch, CloudChannels.Http http) {
            this.ch = ch;
            this.http = http;
            this.queueUrl = CloudChannels.required(ch, "queueUrl");
        }

        @Override
        public void send(String tenantId, String eventKey, String messageId, String body) throws Exception {
            send(ChannelAdapters.Outgoing.json(tenantId, eventKey, messageId, body));
        }

        @Override
        public void send(ChannelAdapters.Outgoing m) {
            ObjectNode b = Json.MAPPER.createObjectNode();
            b.put("QueueUrl", queueUrl);
            b.put("MessageBody", m.body());
            ObjectNode attrs = b.putObject("MessageAttributes");
            CloudChannels.metadata(m, "ce-").forEach((k, v) -> {
                ObjectNode a = attrs.putObject(k);
                a.put("DataType", "String");
                a.put("StringValue", v);
            });
            if (fifo(queueUrl)) {
                b.put("MessageGroupId", groupId(ch, m));
                b.put("MessageDeduplicationId", m.messageId());
            }
            json(ch, http, m.tenantId(), "sqs", "AmazonSQS.SendMessage", "1.0", b, "SQS SendMessage to " + ch.key());
        }
    }

    /** SQS: long-polling consumer. */
    static final class SqsInbound extends PollingInbound {
        private final CloudChannels.Http http;
        private final String queueUrl;

        SqsInbound(ChannelDefinition ch, String tenant, InboundEventProcessor processor, CloudChannels.Http http) {
            super(ch, tenant, processor);
            this.http = http;
            this.queueUrl = CloudChannels.required(ch, "queueUrl");
        }

        @Override
        protected int pollOnce() {
            ObjectNode req = Json.MAPPER.createObjectNode();
            req.put("QueueUrl", queueUrl);
            req.put("MaxNumberOfMessages", CloudChannels.intProp(ch, "batch", 10, 1, 10));
            req.put("WaitTimeSeconds", CloudChannels.intProp(ch, "waitSeconds", 20, 0, 20));
            req.putArray("MessageAttributeNames").add("All");
            JsonNode res = json(ch, http, tenant, "sqs", "AmazonSQS.ReceiveMessage", "1.0", req, "SQS ReceiveMessage on " + ch.key());
            int n = 0;
            for (JsonNode msg : res.path("Messages")) {
                n++;
                settle(msg);
            }
            return n;
        }

        private void settle(JsonNode msg) {
            String receipt = msg.path("ReceiptHandle").asText();
            Map<String, String> headers = new LinkedHashMap<>();
            msg.path("MessageAttributes").fields().forEachRemaining(e -> headers.put(e.getKey(), e.getValue().path("StringValue").asText(null)));
            String body = msg.path("Body").asText("");
            if ("true".equalsIgnoreCase(CloudChannels.prop(ch, "snsEnvelope", "false"))) {
                JsonNode env = Json.tree(body);
                if (env != null && "Notification".equals(env.path("Type").asText())) {
                    body = env.path("Message").asText("");
                    env.path("MessageAttributes").fields().forEachRemaining(e -> headers.put(e.getKey(), e.getValue().path("Value").asText(null)));
                }
            }
            headers.put("sqs-message-id", msg.path("MessageId").asText());
            String id = headers.getOrDefault("message-id", null);
            boolean ack;
            try {
                ack = deliver(body, headers, id);
            } catch (RuntimeException e) {
                ack = false;
            }
            ObjectNode req = Json.MAPPER.createObjectNode();
            req.put("QueueUrl", queueUrl);
            req.put("ReceiptHandle", receipt);
            if (ack) {
                json(ch, http, tenant, "sqs", "AmazonSQS.DeleteMessage", "1.0", req, "SQS DeleteMessage on " + ch.key());
            } else {
                req.put("VisibilityTimeout", redeliverDelaySeconds());
                json(ch, http, tenant, "sqs", "AmazonSQS.ChangeMessageVisibility", "1.0", req,
                        "SQS ChangeMessageVisibility on " + ch.key());
            }
        }
    }

    /** SNS: Publish to {@code topicArn}. */
    static final class SnsOutbound implements ChannelAdapters.OutboundChannelAdapter {
        private final ChannelDefinition ch;
        private final CloudChannels.Http http;
        private final String topicArn;

        SnsOutbound(ChannelDefinition ch, CloudChannels.Http http) {
            this.ch = ch;
            this.http = http;
            this.topicArn = CloudChannels.required(ch, "topicArn");
        }

        @Override
        public void send(String tenantId, String eventKey, String messageId, String body) throws Exception {
            send(ChannelAdapters.Outgoing.json(tenantId, eventKey, messageId, body));
        }

        @Override
        public void send(ChannelAdapters.Outgoing m) {
            Map<String, String> form = new LinkedHashMap<>();
            form.put("Action", "Publish");
            form.put("Version", "2010-03-31");
            form.put("TopicArn", topicArn);
            form.put("Message", m.body());
            int i = 0;
            for (Map.Entry<String, String> e : CloudChannels.metadata(m, "ce-").entrySet()) {
                i++;
                form.put("MessageAttributes.entry." + i + ".Name", e.getKey());
                form.put("MessageAttributes.entry." + i + ".Value.DataType", "String");
                form.put("MessageAttributes.entry." + i + ".Value.StringValue", e.getValue());
            }
            if (fifo(topicArn)) {
                form.put("MessageGroupId", groupId(ch, m));
                form.put("MessageDeduplicationId", m.messageId());
            }
            StringBuilder body = new StringBuilder();
            form.forEach((k, v) -> {
                if (!body.isEmpty()) {
                    body.append('&');
                }
                body.append(URLEncoder.encode(k, StandardCharsets.UTF_8)).append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8));
            });
            Map<String, String> h = new LinkedHashMap<>();
            h.put("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");
            post(ch, http, m.tenantId(), "sns", endpoint(ch, "sns"), h, body.toString().getBytes(StandardCharsets.UTF_8),
                    "SNS Publish to " + ch.key());
        }
    }

    /** EventBridge: PutEvents with one entry. */
    static final class EventBridgeOutbound implements ChannelAdapters.OutboundChannelAdapter {
        private final ChannelDefinition ch;
        private final CloudChannels.Http http;

        EventBridgeOutbound(ChannelDefinition ch, CloudChannels.Http http) {
            this.ch = ch;
            this.http = http;
        }

        @Override
        public void send(String tenantId, String eventKey, String messageId, String body) throws Exception {
            send(ChannelAdapters.Outgoing.json(tenantId, eventKey, messageId, body));
        }

        @Override
        public void send(ChannelAdapters.Outgoing m) {
            if (m.binary()) {
                throw new IllegalStateException("eventbridge channel " + ch.key() + " cannot send CloudEvents binary mode");
            }
            ObjectNode b = Json.MAPPER.createObjectNode();
            ObjectNode e = b.putArray("Entries").addObject();
            e.put("Source", CloudChannels.prop(ch, "source", "sangam"));
            e.put("DetailType", m.eventKey());
            e.put("Detail", m.body());
            e.put("EventBusName", CloudChannels.prop(ch, "eventBusName", "default"));
            JsonNode r = json(ch, http, m.tenantId(), "events", "AWSEvents.PutEvents", "1.1", b, "EventBridge PutEvents to " + ch.key());
            if (r.path("FailedEntryCount").asInt(0) > 0) {
                JsonNode first = r.path("Entries").path(0);
                throw new IllegalStateException("EventBridge refused the event on " + ch.key() + ": "
                        + first.path("ErrorCode").asText("") + " " + first.path("ErrorMessage").asText(""));
            }
        }
    }
}

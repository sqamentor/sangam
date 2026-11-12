// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.publish.cloud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.integration.connector.EgressPolicy;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.Json;
import org.sangam.integration.connector.RetryPolicy;
import org.sangam.ports.events.InboundEventProcessor;
import org.sangam.ports.events.channel.ChannelAdapters;
import org.sangam.ports.events.channel.PollingInbound;
import org.sangam.ports.events.model.ChannelDefinition;

/**
 * M-064 cloud channel families against a loopback HTTP stub: the request each provider API expects (operation,
 * signature scheme, body, attributes, CloudEvents binary attributes), failure answers that make the relay retry, and the
 * consumers' settle step (acknowledge only what the processor said to acknowledge; delay the rest).
 */
class CloudChannelsTest {

    static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");
    static final Map<String, String> SECRETS = Map.of("secret://aws/ak", "AKIDTEST", "secret://aws/sk", "sk-test-value",
            "secret://sb/key", "sb-key-value", "secret://eg/key", "eg-key-value");

    record Req(String method, String path, String query, Map<String, String> headers, String body) {
        JsonNode json() {
            return Json.tree(body);
        }

        String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name)).map(Map.Entry::getValue)
                    .findFirst().orElse(null);
        }
    }

    record Answer(int status, Map<String, String> headers, String body) {
        static Answer ok(String body) {
            return new Answer(200, Map.of("Content-Type", "application/json"), body);
        }
    }

    HttpServer server;
    final List<Req> requests = new CopyOnWriteArrayList<>();
    volatile Function<Req, Answer> handler = r -> Answer.ok("{}");
    String base;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", x -> {
            Map<String, String> h = new LinkedHashMap<>();
            x.getRequestHeaders().forEach((k, v) -> h.put(k, String.join(",", v)));
            Req r = new Req(x.getRequestMethod(), x.getRequestURI().getRawPath(), x.getRequestURI().getRawQuery(), h,
                    new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requests.add(r);
            Answer a = handler.apply(r);
            a.headers().forEach((k, v) -> x.getResponseHeaders().add(k, v));
            byte[] b = a.body().getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(a.status(), b.length == 0 ? -1 : b.length);
            if (b.length > 0) {
                x.getResponseBody().write(b);
            }
            x.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    CloudChannels provider() {
        HttpTransport t = new HttpTransport(EgressPolicy.of(List.of(EgressPolicy.Rule.parse("*", base, true)), 0, 1 << 20),
                null, Duration.ofSeconds(2), Duration.ofSeconds(5), RetryPolicy.none());
        return new CloudChannels(Clock.fixed(NOW, ZoneOffset.UTC), (ch, env) -> t);
    }

    static ChannelAdapters.Environment env(Map<String, String> secrets) {
        return ChannelAdapters.Environment.of(secrets, Set.of("127.0.0.1"));
    }

    static ChannelDefinition ch(String json) {
        try {
            return Json.MAPPER.readValue(json, ChannelDefinition.class);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    String aws(String type, String direction, String extra) {
        return "{\"key\":\"c1\",\"channelType\":\"" + type + "\",\"type\":\"" + direction + "\",\"properties\":{\"region\":\"eu-west-1\","
                + "\"endpoint\":\"" + base + "\",\"accessKeyRef\":\"secret://aws/ak\",\"secretKeyRef\":\"secret://aws/sk\"" + extra + "}}";
    }

    static ChannelAdapters.Outgoing plain() {
        return ChannelAdapters.Outgoing.json("T1", "OrderPlaced", "m-1", "{\"order\":7}");
    }

    static ChannelAdapters.Outgoing binary() {
        Map<String, String> ce = new LinkedHashMap<>();
        ce.put("specversion", "1.0");
        ce.put("id", "m-2");
        ce.put("source", "urn:sangam:channel:c1");
        ce.put("type", "OrderPlaced");
        ce.put("sangamtenant", "T1");
        return new ChannelAdapters.Outgoing("T1", "OrderPlaced", "m-2", "{\"order\":8}", "application/json", ce);
    }

    /** A processor whose broker entry answers from a script: message id -> acknowledge? */
    static final class ScriptedProcessor extends InboundEventProcessor {
        final Map<String, Boolean> acks;
        final List<String> seenBodies = new ArrayList<>();
        final List<Map<String, String>> seenHeaders = new ArrayList<>();

        ScriptedProcessor(Map<String, Boolean> acks) {
            super(null, null, null, null);
            this.acks = acks;
        }

        @Override
        public Delivery deliver(String tenant, String channelKey, String raw, Map<String, String> headers, String id) {
            seenBodies.add(raw);
            seenHeaders.add(headers);
            return acks.getOrDefault(id, false)
                    ? new Delivery(Disposition.ACK, new Result(Outcome.ACCEPTED, "OrderPlaced", id, null), null)
                    : new Delivery(Disposition.REDELIVER, null, "TENANT_NOT_ADMITTED: tenant " + tenant);
        }
    }

    /** Runs one poll of a pulling consumer without starting its thread. */
    static int pollOnce(ChannelAdapters.InboundChannelAdapter a) throws Exception {
        java.lang.reflect.Method m = PollingInbound.class.getDeclaredMethod("pollOnce");
        m.setAccessible(true);
        return (int) m.invoke(a);
    }

    // ------------------------------------------------------------------ SQS

    @Test
    void sqsSendIsASigV4SignedSendMessageWithIdAttributes() throws Exception {
        ChannelDefinition c = ch(aws("sqs", "outbound", ",\"queueUrl\":\"https://sqs.eu-west-1.amazonaws.com/1/orders\""));
        provider().outbound(c, env(SECRETS)).send(plain());

        Req r = requests.get(0);
        assertEquals("POST", r.method());
        assertEquals("AmazonSQS.SendMessage", r.header("X-Amz-Target"));
        assertEquals("application/x-amz-json-1.0", r.header("Content-Type"));
        assertTrue(r.header("Authorization").startsWith("AWS4-HMAC-SHA256 Credential=AKIDTEST/20261112/eu-west-1/sqs/aws4_request, SignedHeaders="),
                r.header("Authorization"));
        assertEquals("20261112T100000Z", r.header("X-Amz-Date"));
        assertFalse(r.header("Authorization").contains("sk-test-value"), "the secret key is never sent");
        JsonNode b = r.json();
        assertEquals("https://sqs.eu-west-1.amazonaws.com/1/orders", b.path("QueueUrl").asText());
        assertEquals("{\"order\":7}", b.path("MessageBody").asText());
        assertEquals("m-1", b.path("MessageAttributes").path("message-id").path("StringValue").asText());
        assertEquals("OrderPlaced", b.path("MessageAttributes").path("event-key").path("StringValue").asText());
        assertEquals("T1", b.path("MessageAttributes").path("tenant-id").path("StringValue").asText());
        assertTrue(b.path("MessageGroupId").isMissingNode(), "standard queue: no FIFO fields");
    }

    @Test
    void sqsFifoGetsGroupAndDeduplicationAndBinaryModeUsesCeAttributes() throws Exception {
        ChannelDefinition c = ch(aws("sqs", "outbound", ",\"queueUrl\":\"https://sqs.eu-west-1.amazonaws.com/1/orders.fifo\""));
        provider().outbound(c, env(SECRETS)).send(binary());
        JsonNode b = requests.get(0).json();
        assertEquals("OrderPlaced", b.path("MessageGroupId").asText());
        assertEquals("m-2", b.path("MessageDeduplicationId").asText());
        JsonNode a = b.path("MessageAttributes");
        assertEquals("1.0", a.path("ce-specversion").path("StringValue").asText());
        assertEquals("m-2", a.path("ce-id").path("StringValue").asText());
        assertEquals("T1", a.path("ce-sangamtenant").path("StringValue").asText());
        assertTrue(a.path("message-id").isMissingNode(), "binary mode carries the ids as CloudEvents attributes only");
        assertTrue(a.size() <= 10, "SQS allows at most 10 message attributes");
    }

    @Test
    void aRefusedSendThrowsSoTheRelayRetries() {
        handler = r -> new Answer(400, Map.of(), "{\"__type\":\"InvalidParameterValue\"}");
        ChannelDefinition c = ch(aws("sqs", "outbound", ",\"queueUrl\":\"https://sqs.eu-west-1.amazonaws.com/1/orders\""));
        ChannelAdapters.OutboundChannelAdapter o = provider().outbound(c, env(SECRETS));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> o.send(plain()));
        assertTrue(e.getMessage().contains("HTTP 400"));
    }

    @Test
    void anUnresolvableKeyIsNeverSent() {
        ChannelDefinition c = ch(aws("sqs", "outbound", ",\"queueUrl\":\"https://sqs.eu-west-1.amazonaws.com/1/orders\""));
        ChannelAdapters.OutboundChannelAdapter o = provider().outbound(c, env(Map.of()));
        assertThrows(IllegalStateException.class, () -> o.send(plain()));
        assertTrue(requests.isEmpty());
    }

    @Test
    void sqsConsumerDeletesAcknowledgedMessagesAndDelaysTheOthers() throws Exception {
        handler = r -> "AmazonSQS.ReceiveMessage".equals(r.header("X-Amz-Target"))
                ? Answer.ok("{\"Messages\":[{\"MessageId\":\"q-1\",\"ReceiptHandle\":\"rh-1\",\"Body\":\"{\\\"a\\\":1}\","
                + "\"MessageAttributes\":{\"message-id\":{\"DataType\":\"String\",\"StringValue\":\"m-1\"}}},"
                + "{\"MessageId\":\"q-2\",\"ReceiptHandle\":\"rh-2\",\"Body\":\"{\\\"a\\\":2}\","
                + "\"MessageAttributes\":{\"message-id\":{\"DataType\":\"String\",\"StringValue\":\"m-2\"}}}]}")
                : Answer.ok("{}");
        ChannelDefinition c = ch(aws("sqs", "inbound", ",\"queueUrl\":\"https://sqs.eu-west-1.amazonaws.com/1/orders\",\"redeliverBackoffMs\":\"7000\""));
        ScriptedProcessor p = new ScriptedProcessor(Map.of("m-1", true, "m-2", false));
        ChannelAdapters.InboundChannelAdapter in = provider().inbound(c, "T1", p, env(SECRETS));

        assertEquals(2, pollOnce(in));
        assertEquals(List.of("{\"a\":1}", "{\"a\":2}"), p.seenBodies);
        Req receive = requests.get(0);
        assertEquals(20, receive.json().path("WaitTimeSeconds").asInt());
        assertEquals(10, receive.json().path("MaxNumberOfMessages").asInt());
        Req delete = requests.get(1);
        assertEquals("AmazonSQS.DeleteMessage", delete.header("X-Amz-Target"));
        assertEquals("rh-1", delete.json().path("ReceiptHandle").asText());
        Req delay = requests.get(2);
        assertEquals("AmazonSQS.ChangeMessageVisibility", delay.header("X-Amz-Target"));
        assertEquals("rh-2", delay.json().path("ReceiptHandle").asText());
        assertEquals(7, delay.json().path("VisibilityTimeout").asInt());
        assertEquals(3, requests.size(), "the not-admitted message is never deleted");
    }

    @Test
    void sqsConsumerUnwrapsAnSnsEnvelopeWhenAsked() throws Exception {
        String envelope = "{\"Type\":\"Notification\",\"MessageId\":\"sns-1\",\"Message\":\"{\\\"a\\\":3}\","
                + "\"MessageAttributes\":{\"message-id\":{\"Type\":\"String\",\"Value\":\"m-3\"}}}";
        handler = r -> "AmazonSQS.ReceiveMessage".equals(r.header("X-Amz-Target"))
                ? Answer.ok("{\"Messages\":[{\"MessageId\":\"q-3\",\"ReceiptHandle\":\"rh-3\",\"Body\":" + Json.write(envelope) + "}]}")
                : Answer.ok("{}");
        ChannelDefinition c = ch(aws("sqs", "inbound", ",\"queueUrl\":\"https://sqs.eu-west-1.amazonaws.com/1/orders\",\"snsEnvelope\":\"true\""));
        ScriptedProcessor p = new ScriptedProcessor(Map.of("m-3", true));
        assertEquals(1, pollOnce(provider().inbound(c, "T1", p, env(SECRETS))));
        assertEquals(List.of("{\"a\":3}"), p.seenBodies);
        assertEquals("AmazonSQS.DeleteMessage", requests.get(1).header("X-Amz-Target"));
    }

    // ------------------------------------------------------------------ SNS, EventBridge

    @Test
    void snsPublishIsAFormEncodedQueryCall() throws Exception {
        ChannelDefinition c = ch(aws("sns", "outbound", ",\"topicArn\":\"arn:aws:sns:eu-west-1:1:orders\""));
        provider().outbound(c, env(SECRETS)).send(plain());
        Req r = requests.get(0);
        assertTrue(r.header("Authorization").contains("/eu-west-1/sns/aws4_request"));
        Map<String, String> form = form(r.body());
        assertEquals("Publish", form.get("Action"));
        assertEquals("2010-03-31", form.get("Version"));
        assertEquals("arn:aws:sns:eu-west-1:1:orders", form.get("TopicArn"));
        assertEquals("{\"order\":7}", form.get("Message"));
        assertEquals("message-id", form.get("MessageAttributes.entry.1.Name"));
        assertEquals("m-1", form.get("MessageAttributes.entry.1.Value.StringValue"));
    }

    @Test
    void eventBridgePutEventsFailsOnAFailedEntry() throws Exception {
        ChannelDefinition c = ch(aws("eventbridge", "outbound", ",\"eventBusName\":\"orders\",\"source\":\"app.orders\""));
        ChannelAdapters.OutboundChannelAdapter o = provider().outbound(c, env(SECRETS));
        handler = r -> Answer.ok("{\"FailedEntryCount\":0,\"Entries\":[{\"EventId\":\"e-1\"}]}");
        o.send(plain());
        Req r = requests.get(0);
        assertEquals("AWSEvents.PutEvents", r.header("X-Amz-Target"));
        assertEquals("application/x-amz-json-1.1", r.header("Content-Type"));
        assertTrue(r.header("Authorization").contains("/eu-west-1/events/aws4_request"));
        JsonNode e = r.json().path("Entries").path(0);
        assertEquals("app.orders", e.path("Source").asText());
        assertEquals("OrderPlaced", e.path("DetailType").asText());
        assertEquals("{\"order\":7}", e.path("Detail").asText());
        assertEquals("orders", e.path("EventBusName").asText());

        handler = r2 -> Answer.ok("{\"FailedEntryCount\":1,\"Entries\":[{\"ErrorCode\":\"InternalFailure\",\"ErrorMessage\":\"x\"}]}");
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> o.send(plain()));
        assertTrue(ex.getMessage().contains("InternalFailure"));
        assertThrows(IllegalStateException.class, () -> o.send(binary()), "EventBridge has no message metadata");
    }

    // ------------------------------------------------------------------ Azure

    String serviceBus(String direction) {
        return "{\"key\":\"c1\",\"channelType\":\"servicebus\",\"type\":\"" + direction + "\",\"properties\":{\"namespace\":\"ns1\","
                + "\"endpoint\":\"" + base + "\",\"entityPath\":\"orders\",\"sasKeyName\":\"send\",\"sasKeyRef\":\"secret://sb/key\"}}";
    }

    @Test
    void serviceBusSendCarriesASasTokenAndBrokerProperties() throws Exception {
        handler = r -> new Answer(201, Map.of(), "");
        provider().outbound(ch(serviceBus("outbound")), env(SECRETS)).send(plain());
        Req r = requests.get(0);
        assertEquals("/orders/messages", r.path());
        String expected = AzureSas.token(base + "/orders", "send", "sb-key-value", NOW.plus(AzureChannels.SAS_LIFETIME));
        assertEquals(expected, r.header("Authorization"));
        JsonNode bp = Json.tree(r.header("BrokerProperties"));
        assertEquals("m-1", bp.path("MessageId").asText());
        assertEquals("OrderPlaced", bp.path("Label").asText());
        assertEquals("application/json", r.header("Content-Type"));
        assertEquals("{\"order\":7}", r.body());
    }

    @Test
    void serviceBusConsumerCompletesOnlyAcknowledgedMessages() throws Exception {
        String lock1 = base + "/orders/messages/1/lock-1";
        String lock2 = base + "/orders/messages/2/lock-2";
        int[] served = {0};
        handler = r -> {
            if (r.path().endsWith("/messages/head")) {
                served[0]++;
                return switch (served[0]) {
                    case 1 -> new Answer(201, Map.of("BrokerProperties", "{\"MessageId\":\"m-1\",\"Label\":\"OrderPlaced\"}",
                            "Location", lock1), "{\"a\":1}");
                    case 2 -> new Answer(201, Map.of("BrokerProperties", "{\"MessageId\":\"m-2\"}", "Location", lock2), "{\"a\":2}");
                    default -> new Answer(204, Map.of(), "");
                };
            }
            return Answer.ok("");
        };
        ScriptedProcessor p = new ScriptedProcessor(Map.of("m-1", true, "m-2", false));
        assertEquals(2, pollOnce(provider().inbound(ch(serviceBus("inbound")), "T1", p, env(SECRETS))));
        List<Req> deletes = requests.stream().filter(r -> r.method().equals("DELETE")).toList();
        assertEquals(1, deletes.size(), "the not-admitted message keeps its lock until it expires");
        assertEquals("/orders/messages/1/lock-1", deletes.get(0).path());
        assertEquals("OrderPlaced", p.seenHeaders.get(0).get("servicebus-label"));
    }

    @Test
    void eventGridPublishesOneStructuredCloudEventWithTheAccessKey() throws Exception {
        String json = "{\"key\":\"c1\",\"channelType\":\"eventgrid\",\"type\":\"outbound\",\"serializerType\":\"cloudevents\","
                + "\"properties\":{\"endpoint\":\"" + base + "\",\"topic\":\"orders\",\"keyRef\":\"secret://eg/key\"}}";
        ChannelAdapters.OutboundChannelAdapter o = provider().outbound(ch(json), env(SECRETS));
        String event = "{\"specversion\":\"1.0\",\"id\":\"m-1\",\"source\":\"urn:sangam\",\"type\":\"OrderPlaced\",\"data\":{}}";
        o.send(new ChannelAdapters.Outgoing("T1", "OrderPlaced", "m-1", event, "application/cloudevents+json", Map.of()));
        Req r = requests.get(0);
        assertEquals("/topics/orders:publish", r.path());
        assertEquals("api-version=" + AzureChannels.EVENT_GRID_API_VERSION, r.query());
        assertEquals("SharedAccessKey eg-key-value", r.header("Authorization"));
        assertTrue(r.header("Content-Type").startsWith("application/cloudevents+json"));
        assertEquals(event, r.body());
        assertThrows(IllegalStateException.class, () -> o.send(plain()), "plain JSON is not a CloudEvent");
    }

    // ------------------------------------------------------------------ Pub/Sub

    String pubsub(String direction, String target) throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(kp.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----\n";
        Map<String, String> key = new LinkedHashMap<>();
        key.put("client_email", "svc@p1.iam.gserviceaccount.com");
        key.put("private_key", pem);
        key.put("private_key_id", "k1");
        key.put("token_uri", base + "/token");
        secrets.put("secret://gcp/key", Json.write(key));
        return "{\"key\":\"c1\",\"channelType\":\"pubsub\",\"type\":\"" + direction + "\",\"properties\":{\"project\":\"p1\","
                + "\"endpoint\":\"" + base + "\"," + target + ",\"serviceAccountKeyRef\":\"secret://gcp/key\"}}";
    }

    final Map<String, String> secrets = new java.util.concurrent.ConcurrentHashMap<>(SECRETS);

    Answer pubsubServer(Req r, String pullAnswer) {
        if (r.path().equals("/token")) {
            return Answer.ok("{\"access_token\":\"at-1\",\"expires_in\":3600}");
        }
        if (r.path().endsWith(":pull")) {
            return Answer.ok(pullAnswer);
        }
        if (r.path().endsWith(":publish")) {
            return Answer.ok("{\"messageIds\":[\"ps-1\"]}");
        }
        return Answer.ok("{}");
    }

    @Test
    void pubSubPublishSendsBase64DataWithAttributesAndABearerToken() throws Exception {
        ChannelDefinition c = ch(pubsub("outbound", "\"topic\":\"orders\""));
        handler = r -> pubsubServer(r, "{}");
        provider().outbound(c, env(secrets)).send(binary());
        Req token = requests.get(0);
        assertEquals("/token", token.path());
        assertTrue(token.body().startsWith("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion="));
        Req pub = requests.get(1);
        assertEquals("/v1/projects/p1/topics/orders:publish", pub.path());
        assertEquals("Bearer at-1", pub.header("Authorization"));
        JsonNode m = pub.json().path("messages").path(0);
        assertEquals("{\"order\":8}", new String(Base64.getDecoder().decode(m.path("data").asText()), StandardCharsets.UTF_8));
        assertEquals("m-2", m.path("attributes").path("ce-id").asText());
        assertEquals("OrderPlaced", m.path("attributes").path("ce-type").asText());
    }

    @Test
    void pubSubConsumerAcknowledgesAdmittedAndExtendsTheDeadlineOfTheRest() throws Exception {
        String data1 = Base64.getEncoder().encodeToString("{\"a\":1}".getBytes(StandardCharsets.UTF_8));
        String data2 = Base64.getEncoder().encodeToString("{\"a\":2}".getBytes(StandardCharsets.UTF_8));
        String pull = "{\"receivedMessages\":[{\"ackId\":\"ack-1\",\"message\":{\"data\":\"" + data1 + "\",\"messageId\":\"ps-1\","
                + "\"attributes\":{\"message-id\":\"m-1\"}}},{\"ackId\":\"ack-2\",\"message\":{\"data\":\"" + data2 + "\",\"messageId\":\"ps-2\"}}]}";
        ChannelDefinition c = ch(pubsub("inbound", "\"subscription\":\"orders-sub\",\"redeliverBackoffMs\":\"30000\""));
        handler = r -> pubsubServer(r, pull);
        ScriptedProcessor p = new ScriptedProcessor(Map.of("m-1", true, "ps-2", false));
        assertEquals(2, pollOnce(provider().inbound(c, "T1", p, env(secrets))));
        assertEquals(List.of("{\"a\":1}", "{\"a\":2}"), p.seenBodies);
        Req ack = requests.stream().filter(r -> r.path().endsWith(":acknowledge")).findFirst().orElseThrow();
        assertEquals("ack-1", ack.json().path("ackIds").path(0).asText());
        assertEquals(1, ack.json().path("ackIds").size());
        Req nack = requests.stream().filter(r -> r.path().endsWith(":modifyAckDeadline")).findFirst().orElseThrow();
        assertEquals("ack-2", nack.json().path("ackIds").path(0).asText());
        assertEquals(30, nack.json().path("ackDeadlineSeconds").asInt());
    }

    // ------------------------------------------------------------------ provider seam

    @Test
    void theProviderPlugsTheCloudFamiliesIntoTheChannelFactory() {
        ChannelAdapters.Environment withProvider = ChannelAdapters.Environment.of(SECRETS, Set.of("127.0.0.1"), List.of(provider()));
        for (String t : CloudChannels.TYPES) {
            assertTrue(ChannelAdapters.available(t, withProvider), t);
            assertFalse(ChannelAdapters.available(t, env(SECRETS)), t + " without the provider");
        }
    }

    static Map<String, String> form(String body) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String p : body.split("&")) {
            int i = p.indexOf('=');
            m.put(URLDecoder.decode(p.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(p.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }
}

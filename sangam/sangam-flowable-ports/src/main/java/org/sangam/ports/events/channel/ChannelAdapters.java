// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.events.channel;

import org.sangam.ports.events.InboundEventProcessor;
import org.sangam.ports.events.model.ChannelDefinition;
import org.sangam.ports.events.pipeline.CloudEventsFormat;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Channel adapter SPI and factory (FLOWABLE-INTO-OPERATON FC-03, M-064 event-gateway channel families). Every family
 * is classpath-optional: an adapter type is available only when its client library is on the class path
 * ({@link #available}), and a channel of a type is only started when a channel of that type is deployed. Air-gapped
 * default (ARCHITECTURE §1.9): outbound HTTP is allowed only to hosts in {@link Environment#egressHosts()}.
 *
 * <p>Families: {@code http}/{@code webhook}, {@code kafka}, {@code jms}, {@code rabbitmq} (FLOWABLE-INTO-OPERATON FC-03), and (M-064)
 * {@code mqtt} (Eclipse Paho, MQTT 3.1.1), {@code nats} (NATS JetStream, jnats), and the cloud families
 * {@code sqs}, {@code sns}, {@code eventbridge}, {@code servicebus}, {@code eventgrid}, {@code pubsub}. The cloud
 * families speak each provider's public HTTPS API (no vendor SDK) and are supplied by a {@link Provider} through the
 * {@link Environment} (the integration module's {@code CloudChannels}, built on its egress-checked HTTP transport and
 * request signers), so this module holds no second copy of any transport.
 *
 * <p>{@link #validate} is the single deploy-time rule per type (directions, required properties, CloudEvents mode).
 */
public final class ChannelAdapters {

    /** Built-in families (client library in this module, optional). */
    static final List<String> BUILT_IN = List.of("http", "webhook", "kafka", "jms", "rabbitmq", "mqtt", "nats");
    /** Families supplied by a {@link Provider}. */
    static final List<String> PROVIDED = List.of("sqs", "sns", "eventbridge", "servicebus", "eventgrid", "pubsub");
    /** Families with no inbound consumer in 0.1.0 (they push or have no pull API; subscribe an SQS queue, or use a webhook). */
    static final Set<String> OUTBOUND_ONLY = Set.of("http", "sns", "eventbridge");
    /**
     * Push families: no consumer; the REST layer hands each request to the family's endpoint ({@code webhook}:
     * {@link WebhookInboundEndpoint}; {@code eventgrid}: the integration module's {@code EventGridInboundEndpoint}).
     */
    static final Set<String> PUSH_INBOUND = Set.of("webhook", "eventgrid");
    /**
     * Families that send plain or structured CloudEvents only: no per-message metadata (MQTT 3.1.1, EventBridge), or a
     * structured-only service (Event Grid). Service Bus sends binary mode only when the channel opts in with
     * {@code properties.binaryMode=customProperties} (decision 189; off by default).
     */
    static final Set<String> NO_BINARY = Set.of("mqtt", "eventbridge", "servicebus", "eventgrid");
    /** Service Bus opt-in to CloudEvents binary mode: the attributes travel as {@code cloudEvents_*} custom properties. */
    public static final String SERVICE_BUS_BINARY_PROPERTY = "binaryMode";
    public static final String SERVICE_BUS_BINARY_CUSTOM_PROPERTIES = "customProperties";
    /** Seconds or milliseconds a consumer waits before a message it may not acknowledge yet is offered again. */
    static final String REDELIVER_BACKOFF_MS = "redeliverBackoffMs";
    /**
     * Owner decisions 189 and 219: how an inbound eventgrid channel authenticates deliveries. {@value #EVENT_GRID_SHARED_SECRET}
     * (the default) or {@value #EVENT_GRID_ENTRA} (a Microsoft Entra bearer token checked against the configured tenant).
     */
    public static final String EVENT_GRID_AUTH_MODE = "authMode";
    public static final String EVENT_GRID_SHARED_SECRET = "sharedSecret";
    public static final String EVENT_GRID_ENTRA = "entra";
    private static final java.util.regex.Pattern GUID = java.util.regex.Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private ChannelAdapters() {
    }

    /**
     * One message to send. {@code cloudEventsOrEmpty} holds the CloudEvents attributes (no prefix) when the channel
     * sends binary-mode CloudEvents; each transport writes them under its binding prefix. Otherwise it is empty and
     * {@code body} is the whole message (Sangam JSON or a structured CloudEvent).
     */
    public record Outgoing(String tenantId, String eventKey, String messageId, String body, String contentType,
                           Map<String, String> cloudEventsOrEmpty) {
        public Outgoing {
            contentType = contentType == null ? "application/json" : contentType;
            cloudEventsOrEmpty = cloudEventsOrEmpty == null ? Map.of() : Map.copyOf(cloudEventsOrEmpty);
        }

        public static Outgoing json(String tenantId, String eventKey, String messageId, String body) {
            return new Outgoing(tenantId, eventKey, messageId, body, "application/json", Map.of());
        }

        public boolean binary() {
            return !cloudEventsOrEmpty.isEmpty();
        }
    }

    /** Sends one serialized event. Must throw on failure so the relay retries (at-least-once; consumers dedupe by message id). */
    public interface OutboundChannelAdapter extends AutoCloseable {
        void send(String tenantId, String eventKey, String messageId, String body) throws Exception;

        /**
         * Sends one message with its content type and, in CloudEvents binary mode, its attributes. The default handles
         * the plain form only; adapters of transports with message metadata override it.
         */
        default void send(Outgoing m) throws Exception {
            if (m.binary()) {
                throw new IllegalStateException("this channel adapter cannot send CloudEvents binary mode");
            }
            send(m.tenantId(), m.eventKey(), m.messageId(), m.body());
        }

        @Override
        default void close() {
        }
    }

    /** A running consumer that feeds {@link InboundEventProcessor}. */
    public interface InboundChannelAdapter extends AutoCloseable {
        void start();

        @Override
        void close();
    }

    /** Supplies the adapters of channel families whose transport lives in another module (the cloud families). */
    public interface Provider {
        /** The channel types this provider builds. */
        Set<String> types();

        OutboundChannelAdapter outbound(ChannelDefinition ch, Environment env);

        InboundChannelAdapter inbound(ChannelDefinition ch, String tenant, InboundEventProcessor processor, Environment env);
    }

    /**
     * Deployment-provided resources: secrets (never stored in channel JSON, only {@code secretRef} names), JMS
     * connection factories by name, the egress allow-list, the node id and the channel {@link Provider}s.
     */
    public interface Environment {
        Optional<String> secret(String ref);

        default Optional<Object> jmsConnectionFactory(String name) {
            return Optional.empty();
        }

        Set<String> egressHosts();

        default String nodeId() {
            return "sangam";
        }

        /** Providers of the cloud channel families (none: those families are not available). */
        default List<Provider> providers() {
            return List.of();
        }

        default boolean egressAllowed(URI uri) {
            // host names are case-insensitive (RFC 4343): compare both sides case-folded, whatever the configured spelling
            String host = uri.getHost();
            return host != null && egressHosts().stream().anyMatch(h -> h != null && h.equalsIgnoreCase(host));
        }

        static Environment of(Map<String, String> secrets, Set<String> egressHosts) {
            return of(secrets, egressHosts, List.of());
        }

        static Environment of(Map<String, String> secrets, Set<String> egressHosts, List<Provider> providers) {
            return of(ref -> Optional.ofNullable(secrets.get(ref)), egressHosts, providers);
        }

        /** Secrets through one lookup (the server passes its platform secret resolver chain); an empty answer = unknown. */
        static Environment of(java.util.function.Function<String, Optional<String>> secrets, Set<String> egressHosts,
                              List<Provider> providers) {
            java.util.Objects.requireNonNull(secrets, "secrets");
            List<Provider> ps = List.copyOf(providers);
            return new Environment() {
                @Override
                public Optional<String> secret(String ref) {
                    Optional<String> v = ref == null ? Optional.empty() : secrets.apply(ref);
                    return v == null ? Optional.empty() : v;
                }

                @Override
                public Set<String> egressHosts() {
                    return egressHosts;
                }

                @Override
                public List<Provider> providers() {
                    return ps;
                }
            };
        }
    }

    /** True when a built-in adapter type's client library is on the class path (provider families need {@link #available(String, Environment)}). */
    public static boolean available(String adapterType) {
        return switch (adapterType) {
            case "http", "webhook" -> true;
            case "kafka" -> present("org.apache.kafka.clients.consumer.KafkaConsumer");
            case "jms" -> present("jakarta.jms.ConnectionFactory");
            case "rabbitmq" -> present("com.rabbitmq.client.ConnectionFactory");
            case "mqtt" -> present("org.eclipse.paho.client.mqttv3.MqttClient");
            case "nats" -> present("io.nats.client.Nats");
            default -> false;
        };
    }

    /**
     * Built-in families whose client library is optional and whose adapters therefore sit behind the {@link Provider}
     * seam, loaded by class name only when the library is present: this class never links a vendor class, so the
     * module compiles and runs without the jar (M-064 classpath-optional). Kafka, JMS and RabbitMQ predate the seam.
     */
    private static final Map<String, String> VENDOR_FAMILIES = Map.of(
            "mqtt", "org.sangam.ports.events.channel.MqttAdapters$Family",
            "nats", "org.sangam.ports.events.channel.NatsAdapters$Family");

    /** The provider of a vendor-optional built-in family, when its client library is on the class path. */
    static Optional<Provider> vendorFamily(String adapterType) {
        String cls = VENDOR_FAMILIES.get(adapterType);
        if (cls == null || !available(adapterType)) {
            return Optional.empty();
        }
        try {
            return Optional.of((Provider) Class.forName(cls, true, ChannelAdapters.class.getClassLoader())
                    .getDeclaredConstructor().newInstance());
        } catch (ReflectiveOperationException | LinkageError e) {
            // the library is present but the adapter cannot load (a broken or incompatible jar): a deployment error
            throw new IllegalStateException("channel adapter '" + adapterType + "' cannot be loaded: " + e, e);
        }
    }

    /** {@link #available(String)}, or a provider of {@code env} builds the type. */
    public static boolean available(String adapterType, Environment env) {
        return available(adapterType) || provider(adapterType, env).isPresent();
    }

    public static OutboundChannelAdapter outbound(ChannelDefinition ch, Environment env) {
        require(ch, env);
        return switch (ch.adapterType()) {
            case "http", "webhook" -> new HttpOutboundAdapter(ch, env);
            case "kafka" -> new KafkaAdapters.Outbound(ch);
            case "jms" -> new JmsAdapters.Outbound(ch, env);
            case "rabbitmq" -> new RabbitAdapters.Outbound(ch, env);
            default -> provider(ch.adapterType(), env).map(p -> p.outbound(ch, env))
                    .orElseThrow(() -> new IllegalArgumentException("no outbound adapter for " + ch.adapterType()));
        };
    }

    /**
     * Push channels ({@link #PUSH_INBOUND}: webhook, eventgrid) have no consumer: the REST layer calls their endpoint.
     * Returns null for them.
     */
    public static InboundChannelAdapter inbound(ChannelDefinition ch, String tenant, InboundEventProcessor processor, Environment env) {
        require(ch, env);
        if (OUTBOUND_ONLY.contains(ch.adapterType())) {
            throw new IllegalArgumentException("no inbound adapter for " + ch.adapterType());
        }
        if (PUSH_INBOUND.contains(ch.adapterType())) {
            return null;
        }
        return switch (ch.adapterType()) {
            case "kafka" -> new KafkaAdapters.Inbound(ch, tenant, processor, env);
            case "jms" -> new JmsAdapters.Inbound(ch, tenant, processor, env);
            case "rabbitmq" -> new RabbitAdapters.Inbound(ch, tenant, processor, env);
            default -> provider(ch.adapterType(), env).map(p -> p.inbound(ch, tenant, processor, env))
                    .orElseThrow(() -> new IllegalArgumentException("no inbound adapter for " + ch.adapterType()));
        };
    }

    /**
     * Deploy-time rules of a channel's family (called by {@code EventRegistry}): a known type, a supported direction,
     * the CloudEvents mode the transport can carry, and the properties the adapter needs. An error here is a deploy
     * error; at send time it would fail every delivery and the relay would retry it for ever (FW-41).
     */
    public static void validate(ChannelDefinition ch) {
        String t = ch.adapterType();
        if (!BUILT_IN.contains(t) && !PROVIDED.contains(t)) {
            throw new IllegalArgumentException("channel " + ch.key() + ": unknown channelType '" + ch.channelType() + "'");
        }
        boolean inbound = ch.direction() == ChannelDefinition.Direction.INBOUND;
        if (inbound && OUTBOUND_ONLY.contains(t)) {
            throw new IllegalArgumentException("channel " + ch.key() + ": channelType '" + t + "' is outbound only"
                    + ("http".equals(t) ? " (inbound HTTP channels use channelType 'webhook')"
                    : " (subscribe an SQS queue or a webhook channel to receive its events)"));
        }
        if (CloudEventsFormat.enabled(ch)) {
            CloudEventsFormat.Mode m = CloudEventsFormat.mode(ch);
            if (m == CloudEventsFormat.Mode.BINARY && NO_BINARY.contains(t) && !inbound && !serviceBusBinary(ch)) {
                throw new IllegalArgumentException("channel " + ch.key() + ": " + t + " has no message metadata, so it"
                        + " cannot send CloudEvents binary mode (use structured)");
            }
        } else if ("eventgrid".equals(t)) {
            throw new IllegalArgumentException("channel " + ch.key() + ": Event Grid namespace topics take CloudEvents only"
                    + " (serializerType cloudevents)");
        }
        for (String p : requiredProperties(t, inbound)) {
            String v = ch.properties().get(p);
            if (v == null || v.isBlank()) {
                throw new IllegalArgumentException("channel " + ch.key() + ": " + t + " needs properties." + p);
            }
        }
        if (inbound && "eventgrid".equals(t)) {
            validateEventGridAuth(ch);
        }
        String binaryMode = ch.properties().get(SERVICE_BUS_BINARY_PROPERTY);
        if (binaryMode != null && !("servicebus".equals(t) && SERVICE_BUS_BINARY_CUSTOM_PROPERTIES.equals(binaryMode.trim()))) {
            throw new IllegalArgumentException("channel " + ch.key() + ": properties." + SERVICE_BUS_BINARY_PROPERTY
                    + " is only for servicebus, value " + SERVICE_BUS_BINARY_CUSTOM_PROPERTIES);
        }
        boolean needsTarget = switch (t) {
            case "mqtt", "nats", "kafka", "rabbitmq", "jms" -> true;
            default -> false;
        };
        if (needsTarget && !inbound && ch.destination() == null && ch.topics().isEmpty() && ch.queue() == null
                && ch.routingKey() == null) {
            throw new IllegalArgumentException("channel " + ch.key() + ": outbound " + t + " needs destination or topics[0]");
        }
        if (inbound && ("mqtt".equals(t) || "kafka".equals(t)) && ch.topics().isEmpty()) {
            throw new IllegalArgumentException("channel " + ch.key() + ": inbound " + t + " channel needs topics");
        }
        if (inbound && "mqtt".equals(t) && (ch.messageIdJsonPointer() == null || ch.messageIdJsonPointer().isBlank())
                && !CloudEventsFormat.TYPE.equalsIgnoreCase(ch.deserializerType())) {
            // MQTT 3.1.1 has no message metadata and reuses packet ids: inbox de-duplication needs an id from the event
            throw new IllegalArgumentException("channel " + ch.key() + ": inbound mqtt needs messageIdJsonPointer or"
                    + " deserializerType cloudevents (the CloudEvents id)");
        }
        if ("pubsub".equals(t) && blank(ch.properties().get("serviceAccountKeyRef")) && blank(ch.properties().get("metadataUrl"))) {
            throw new IllegalArgumentException("channel " + ch.key() + ": pubsub needs properties.serviceAccountKeyRef or"
                    + " properties.metadataUrl");
        }
        String backoff = ch.properties().get(REDELIVER_BACKOFF_MS);
        if (backoff != null) {
            try {
                Long.parseLong(backoff.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("channel " + ch.key() + ": " + REDELIVER_BACKOFF_MS + " is not a number: " + backoff);
            }
        }
    }

    /**
     * Decisions 189 and 219: no unauthenticated push ingress. {@code sharedSecret}: {@code authSecretRef} plus exactly one
     * of {@code authHeader} or {@code authQueryParam}. {@code entra}: {@code entraTenantId} (a GUID) and
     * {@code entraAudience} (the webhook application ID or ID URI, comma-separated), optional {@code entraAuthority}
     * (https) and {@code entraRequiredRole}; the two modes are alternatives, so shared-secret properties are refused.
     */
    private static void validateEventGridAuth(ChannelDefinition ch) {
        Map<String, String> p = ch.properties();
        String mode = p.get(EVENT_GRID_AUTH_MODE) == null ? EVENT_GRID_SHARED_SECRET : p.get(EVENT_GRID_AUTH_MODE).trim();
        String where = "channel " + ch.key() + ": inbound eventgrid ";
        if (EVENT_GRID_ENTRA.equals(mode)) {
            String tenant = p.get("entraTenantId");
            if (tenant == null || !GUID.matcher(tenant.trim()).matches()) {
                throw new IllegalArgumentException(where + "with authMode entra needs properties.entraTenantId (a tenant GUID)");
            }
            if (blank(p.get("entraAudience"))) {
                throw new IllegalArgumentException(where + "with authMode entra needs properties.entraAudience");
            }
            String authority = p.get("entraAuthority");
            if (authority != null && !authority.trim().startsWith("https://")) {
                throw new IllegalArgumentException(where + "properties.entraAuthority must be an https URL");
            }
            if (p.containsKey("entraRequiredRole") && blank(p.get("entraRequiredRole"))) {
                throw new IllegalArgumentException(where + "properties.entraRequiredRole cannot be blank");
            }
            if (!blank(p.get("authSecretRef")) || !blank(p.get("authHeader")) || !blank(p.get("authQueryParam"))) {
                throw new IllegalArgumentException(where + "uses either authMode entra or the shared secret, not both");
            }
            return;
        }
        if (!EVENT_GRID_SHARED_SECRET.equals(mode)) {
            throw new IllegalArgumentException(where + "properties." + EVENT_GRID_AUTH_MODE + " is "
                    + EVENT_GRID_SHARED_SECRET + " or " + EVENT_GRID_ENTRA);
        }
        if (blank(p.get("authSecretRef"))) {
            throw new IllegalArgumentException("channel " + ch.key() + ": eventgrid needs properties.authSecretRef"
                    + " (or properties." + EVENT_GRID_AUTH_MODE + "=" + EVENT_GRID_ENTRA + ")");
        }
        if (blank(p.get("authHeader")) == blank(p.get("authQueryParam"))) {
            // the shared secret travels in one place only
            throw new IllegalArgumentException(where + "needs exactly one of"
                    + " properties.authHeader or properties.authQueryParam (with properties.authSecretRef)");
        }
    }

    /** Properties each family needs (credentials are secret references, never values). */
    static List<String> requiredProperties(String type, boolean inbound) {
        return switch (type) {
            case "mqtt" -> List.of("serverUri");
            case "nats" -> inbound ? List.of("serverUri", "stream", "consumer") : List.of("serverUri");
            case "sqs" -> List.of("region", "queueUrl", "accessKeyRef", "secretKeyRef");
            case "sns" -> List.of("region", "topicArn", "accessKeyRef", "secretKeyRef");
            case "eventbridge" -> List.of("region", "accessKeyRef", "secretKeyRef");
            case "servicebus" -> List.of("namespace", "entityPath", "sasKeyName", "sasKeyRef");
            // inbound: the authentication properties depend on authMode (validateEventGridAuth)
            case "eventgrid" -> inbound ? List.of() : List.of("endpoint", "topic", "keyRef");
            case "pubsub" -> inbound ? List.of("project", "subscription") : List.of("project", "topic");
            default -> List.of();
        };
    }

    /**
     * Broker entry for every inbound adapter (SL-142, C3-06): {@link InboundEventProcessor#deliver}. True means
     * acknowledge (stored, duplicate, rejected or parked); false means do not acknowledge, the broker redelivers.
     * Infrastructure failures throw, and the adapter treats them like false.
     */
    static boolean deliver(InboundEventProcessor processor, String tenant, ChannelDefinition ch, String raw,
                           Map<String, String> headers, String messageId) {
        return processor.deliver(tenant, ch.key(), raw, headers, messageId).ack();
    }

    /** Wait before a message that may not be acknowledged yet is offered again (default 5 s, at least 100 ms). */
    static long redeliverBackoffMs(ChannelDefinition ch) {
        return Math.max(100L, Long.parseLong(ch.property(REDELIVER_BACKOFF_MS, "5000").trim()));
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Optional<Provider> provider(String type, Environment env) {
        Optional<Provider> vendor = vendorFamily(type);
        if (vendor.isPresent()) {
            return vendor;
        }
        return env == null ? Optional.empty() : env.providers().stream().filter(p -> p.types().contains(type)).findFirst();
    }

    private static void require(ChannelDefinition ch, Environment env) {
        if (!available(ch.adapterType(), env)) {
            throw new IllegalStateException(unavailable(ch.key(), ch.adapterType()));
        }
    }

    /**
     * Owner decision 232: jnats is not in the server; a {@code nats} channel needs the NATS add-on, which provides
     * {@code io.nats.client.Nats}. Other families name their optional client dependency.
     */
    static String unavailable(String channelKey, String adapterType) {
        String need = "nats".equals(adapterType)
                ? "install the NATS add-on (io.nats:jnats in /opt/sangam/lib-ext, owner decision 232)"
                : "add its optional client dependency, see README";
        return "channel " + channelKey + ": adapter '" + adapterType + "' is not available (" + need + ")";
    }

    /** True when a servicebus channel opted in to CloudEvents binary mode as custom properties (decision 189). */
    public static boolean serviceBusBinary(ChannelDefinition ch) {
        String v = ch.properties().get(SERVICE_BUS_BINARY_PROPERTY);
        return "servicebus".equals(ch.adapterType()) && v != null && SERVICE_BUS_BINARY_CUSTOM_PROPERTIES.equals(v.trim());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean present(String cls) {
        try {
            Class.forName(cls, false, ChannelAdapters.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}

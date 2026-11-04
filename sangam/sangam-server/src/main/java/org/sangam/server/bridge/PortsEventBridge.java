// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.sangam.ports.events.EventRegistry;
import org.sangam.ports.events.InboundEventProcessor;
import org.sangam.ports.events.channel.InboundChannelManager;
import org.sangam.ports.events.channel.WebhookInboundEndpoint;
import org.sangam.ports.events.model.ChannelDefinition;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.EventPort;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.RestProperties;

/**
 * {@link EventPort} on sangam-flowable-ports' event registry (FC-02, FC-03, FC-04; R38, R83).
 * <ul>
 *   <li>Channels and event definitions are deployed into {@link EventRegistry} as Flowable-compatible
 *       {@code .channel} / {@code .event} JSON; each save is a new immutable version. Retire / restore / delete use the
 *       registry's version states ({@code ACTIVE} / {@code RETIRED}); delete retires first and then removes the
 *       retired versions; all three are refused with {@code 409 EVENT_IN_FLIGHT} while events of the key are buffered
 *       or undelivered.</li>
 *   <li>{@code POST /events/{channel}} is the {@link WebhookInboundEndpoint}: the CloudEvent (structured mode) is the
 *       raw body, {@code id} is the message id, so the inbox de-duplicates redeliveries (R38); dispatch to processes
 *       and cases (start, correlation, {@code uniqueStart}) happens asynchronously in the inbox loop.</li>
 * </ul>
 * REST transports map onto ports adapter types: REST/WEBHOOK → webhook (inbound) / http (outbound), KAFKA → kafka,
 * JMS → jms, AMQP → rabbitmq.
 */
public final class PortsEventBridge implements EventPort {

    /** The ports' size-limited mapper (audit X-2: document, nesting, string and number limits). */
    private static final ObjectMapper JSON = org.sangam.ports.support.Json.MAPPER;

    private final EventRegistry registry;
    private final WebhookInboundEndpoint webhook;
    private final InboundChannelManager channelManagerOrNull;
    private final AuditPort audit;
    private final RestProperties props;
    private final boolean allowUnsignedWebhooks;

    public PortsEventBridge(EventRegistry registry, WebhookInboundEndpoint webhook, InboundChannelManager channelManagerOrNull,
                            AuditPort audit, RestProperties props) {
        this(registry, webhook, channelManagerOrNull, audit, props, false);
    }

    /**
     * @param allowUnsignedWebhooks {@code sangam.ports.events.allow-unsigned-webhooks} (default false): an inbound
     *                              WEBHOOK/REST channel is reachable without a Sangam principal only through its
     *                              signature, so a deploy without {@code config.secretRef} is refused (audit PR-3, FW-47)
     */
    public PortsEventBridge(EventRegistry registry, WebhookInboundEndpoint webhook, InboundChannelManager channelManagerOrNull,
                            AuditPort audit, RestProperties props, boolean allowUnsignedWebhooks) {
        this.registry = registry;
        this.webhook = webhook;
        this.channelManagerOrNull = channelManagerOrNull;
        this.audit = audit;
        this.props = props;
        this.allowUnsignedWebhooks = allowUnsignedWebhooks;
    }

    // ---------------------------------------------------------------- channels

    @Override
    public Page<Channel> channels(RequestContext ctx, Page.PageRequest page) {
        List<Channel> out = new ArrayList<>();
        for (EventRegistry.DeployedChannel c : PortsErrors.call(registry::allChannels)) {
            if (ctx.tenantId().equals(c.tenantId())) {
                out.add(map(c));
            }
        }
        return Page.slice(out, page, props.getCountCap());
    }

    @Override
    public Channel channel(RequestContext ctx, String key) {
        return PortsErrors.call(() -> registry.channel(ctx.tenantId(), key)).map(this::map)
                .orElseThrow(() -> ApiProblem.notFound("channel", key));
    }

    @Override
    public Channel createChannel(RequestContext ctx, Channel c) {
        if (c == null || c.key() == null || c.key().isBlank()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "key is required");
        }
        if (c.key().equals("channels") || c.key().equals("definitions")) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "channel keys 'channels' and 'definitions' are reserved");
        }
        if (PortsErrors.call(() -> registry.channel(ctx.tenantId(), c.key())).isPresent()) {
            throw ApiProblem.conflict("ALREADY_EXISTS", "Channel " + c.key() + " exists; use PUT to deploy a new version");
        }
        return deployChannel(ctx, c.key(), c, "EVENT_CHANNEL_CREATED");
    }

    @Override
    public Channel updateChannel(RequestContext ctx, String key, Channel c) {
        channel(ctx, key);
        if (c.revision() > 0) {
            Channel current = channel(ctx, key);
            if (current.revision() != c.revision()) {
                throw ApiProblem.conflict("REVISION_CONFLICT", "Channel " + key + " is at version " + current.revision());
            }
        }
        return deployChannel(ctx, key, c, "EVENT_CHANNEL_CHANGED");
    }

    /** Delete = retire every active version, then delete every retired version (registry rule: retire first). */
    @Override
    public void deleteChannel(RequestContext ctx, String key, String reason) {
        PortsErrors.requireReason(reason);
        boolean known = !PortsErrors.call(() -> registry.channelVersions(ctx.tenantId(), key)).isEmpty();
        if (!known) {
            throw ApiProblem.notFound("channel", key);
        }
        PortsErrors.call(() -> registry.retireChannel(ctx.tenantId(), key, null, ctx.principalId()));
        int n = PortsErrors.call(() -> registry.deleteChannel(ctx.tenantId(), key, null));
        refreshChannels();
        audit.record(ctx, "sec:" + ctx.tenantId(), "EVENT_CHANNEL_DELETED",
                Map.of("key", key, "versions", n, "reason", reason));
    }

    @Override
    public Channel retireChannel(RequestContext ctx, String key, String reason) {
        PortsErrors.requireReason(reason);
        Channel current = channel(ctx, key);
        int n = PortsErrors.call(() -> registry.retireChannel(ctx.tenantId(), key, null, ctx.principalId()));
        refreshChannels();
        audit.record(ctx, "sec:" + ctx.tenantId(), "EVENT_CHANNEL_RETIRED",
                Map.of("key", key, "versions", n, "reason", reason));
        return PortsErrors.call(() -> registry.channel(ctx.tenantId(), key)).map(this::map)
                .orElse(new Channel(current.key(), current.tenantId(), current.name(), current.direction(),
                        current.transport(), current.destination(), current.eventDefinitionKeys(), current.keyDetector(),
                        current.tenantDetector(), current.dedupWindowSeconds(), current.config(), false,
                        current.revision()));
    }

    @Override
    public Channel restoreChannel(RequestContext ctx, String key, String reason) {
        PortsErrors.requireReason(reason);
        int version = newestRetired(PortsErrors.call(() -> registry.channelVersions(ctx.tenantId(), key)), "channel", key);
        PortsErrors.run(() -> registry.restoreChannel(ctx.tenantId(), key, version));
        refreshChannels();
        audit.record(ctx, "sec:" + ctx.tenantId(), "EVENT_CHANNEL_RESTORED",
                Map.of("key", key, "version", version, "reason", reason));
        return channel(ctx, key);
    }

    @Override
    public EventDefinition retireDefinition(RequestContext ctx, String key, String reason) {
        PortsErrors.requireReason(reason);
        EventDefinition current = definition(ctx, key);
        int n = PortsErrors.call(() -> registry.retireEvent(ctx.tenantId(), key, null, ctx.principalId()));
        audit.record(ctx, "sec:" + ctx.tenantId(), "EVENT_DEFINITION_RETIRED",
                Map.of("key", key, "versions", n, "reason", reason));
        return PortsErrors.call(() -> registry.event(ctx.tenantId(), key)).map(this::map).orElse(current);
    }

    @Override
    public EventDefinition restoreDefinition(RequestContext ctx, String key, String reason) {
        PortsErrors.requireReason(reason);
        int version = newestRetired(PortsErrors.call(() -> registry.eventVersions(ctx.tenantId(), key)),
                "event definition", key);
        PortsErrors.run(() -> registry.restoreEvent(ctx.tenantId(), key, version));
        audit.record(ctx, "sec:" + ctx.tenantId(), "EVENT_DEFINITION_RESTORED",
                Map.of("key", key, "version", version, "reason", reason));
        return definition(ctx, key);
    }

    private static int newestRetired(List<EventRegistry.VersionInfo> versions, String what, String key) {
        if (versions.isEmpty()) {
            throw ApiProblem.notFound(what, key);
        }
        return versions.stream().filter(v -> "RETIRED".equalsIgnoreCase(v.state()))
                .mapToInt(EventRegistry.VersionInfo::version).max()
                .orElseThrow(() -> ApiProblem.conflict("NOT_RETIRED", what + " " + key + " has no retired version to restore"));
    }

    private void refreshChannels() {
        if (channelManagerOrNull != null) {
            PortsErrors.run(channelManagerOrNull::refresh);
        }
    }

    // ---------------------------------------------------------------- event definitions

    @Override
    public Page<EventDefinition> definitions(RequestContext ctx, Page.PageRequest page) {
        List<EventDefinition> out = PortsErrors.call(() -> registry.events(ctx.tenantId())).stream().map(this::map).toList();
        return Page.slice(out, page, props.getCountCap());
    }

    @Override
    public EventDefinition definition(RequestContext ctx, String key) {
        return PortsErrors.call(() -> registry.event(ctx.tenantId(), key)).map(this::map)
                .orElseThrow(() -> ApiProblem.notFound("event definition", key));
    }

    @Override
    public EventDefinition createDefinition(RequestContext ctx, EventDefinition d) {
        if (d == null || d.key() == null || d.key().isBlank()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "key is required");
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("key", d.key());
        json.put("name", d.name() == null ? d.key() : d.name());
        json.put("payload", payloadFields(d.payloadSchema()));
        List<Map<String, Object>> corr = new ArrayList<>();
        for (String p : d.correlationParameters() == null ? List.<String>of() : d.correlationParameters()) {
            String name = p.startsWith("/") ? p.substring(1).replace('/', '.') : p;
            corr.add(Map.of("name", name, "type", "string"));
        }
        json.put("correlationParameters", corr);
        String bk = d.businessKeyPointer() == null ? null
                : d.businessKeyPointer().startsWith("/") ? d.businessKeyPointer().substring(1) : d.businessKeyPointer();
        if (bk != null) {
            json.put("businessKeyField", bk);
        }
        if (d.uniqueStart()) {
            String ref = bk != null ? bk : corr.isEmpty() ? null : String.valueOf(corr.get(0).get("name"));
            if (ref == null) {
                throw ApiProblem.badRequest("VALIDATION_FAILED", "uniqueStart needs a businessKeyPointer or a correlation parameter");
            }
            json.put("startUniqueReference", ref);
        }
        if (d.startProcessKey() != null) {
            json.put("startProcessKey", d.startProcessKey());
        }
        EventRegistry.DeployedEvent e = PortsErrors.call(() -> registry.deployEvent(ctx.tenantId(), write(json), null));
        audit.record(ctx, "sec:" + ctx.tenantId(), "EVENT_DEFINITION_CHANGED",
                Map.of("key", d.key(), "version", e.version()));
        return map(e);
    }

    /** Delete = retire every active version, then delete every retired version; refused while events are in flight. */
    @Override
    public void deleteDefinition(RequestContext ctx, String key, String reason) {
        PortsErrors.requireReason(reason);
        if (PortsErrors.call(() -> registry.eventVersions(ctx.tenantId(), key)).isEmpty()) {
            throw ApiProblem.notFound("event definition", key);
        }
        PortsErrors.call(() -> registry.retireEvent(ctx.tenantId(), key, null, ctx.principalId()));
        int n = PortsErrors.call(() -> registry.deleteEvent(ctx.tenantId(), key, null));
        audit.record(ctx, "sec:" + ctx.tenantId(), "EVENT_DEFINITION_DELETED",
                Map.of("key", key, "versions", n, "reason", reason));
    }

    // ---------------------------------------------------------------- publish

    @Override
    public PublishResult publish(RequestContext ctx, String channelKey, CloudEvent e) {
        if (e == null || e.id() == null || e.source() == null || e.type() == null) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "CloudEvent id, source and type are required");
        }
        if (e.specversion() != null && !"1.0".equals(e.specversion())) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "specversion must be 1.0");
        }
        Channel ch = channel(ctx, channelKey);
        if (!ch.active() || !"INBOUND".equals(ch.direction())) {
            throw ApiProblem.unprocessable("CHANNEL_INACTIVE", "Channel " + channelKey + " is not an active inbound channel");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("specversion", "1.0");
        body.put("id", e.id());
        body.put("source", e.source());
        body.put("type", e.type());
        if (e.subject() != null) {
            body.put("subject", e.subject());
        }
        if (e.time() != null) {
            body.put("time", e.time().toString());
        }
        body.put("datacontenttype", e.datacontenttype() == null ? "application/json" : e.datacontenttype());
        if (e.dataschema() != null) {
            body.put("dataschema", e.dataschema());
        }
        if (e.extensions() != null) {
            e.extensions().forEach(body::putIfAbsent);
        }
        body.put("data", e.data());
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Idempotency-Key", e.id());
        headers.put("ce-id", e.id());
        headers.put("ce-source", e.source());
        headers.put("ce-type", e.type());
        headers.put("ce-specversion", "1.0");
        if (e.extensions() != null && e.extensions().get("signature") instanceof String sig) {
            headers.put("X-Sangam-Signature", sig);
        }
        String raw = write(body);
        InboundEventProcessor.Result r = PortsErrors.call(() -> webhook.receive(ctx.tenantId(), channelKey, headers, raw));
        if (r.outcome() == InboundEventProcessor.Outcome.REJECTED) {
            // Audit PR-3: map by the processor's code, so a too-large or cross-tenant event is not a generic 422.
            String code = r.code();
            if ("TENANT_MISMATCH".equals(code)) {
                throw ApiProblem.forbidden("TENANT_MISMATCH", r.reason());
            }
            if ("PAYLOAD_TOO_LARGE".equals(code)) {
                throw new ApiProblem(413, "PAYLOAD_TOO_LARGE", r.reason());
            }
            throw ApiProblem.unprocessable("EVENT_REJECTED", r.reason()).with("rejectionCode", code);
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("channel", channelKey);
        p.put("event_id", e.id());
        p.put("event_key", r.eventKey());
        p.put("outcome", r.outcome().name());
        audit.record(ctx, "sec:" + ctx.tenantId(), "EVENT_RECEIVED", p);
        return new PublishResult(r.messageId() == null ? e.id() : r.messageId(),
                r.outcome() == InboundEventProcessor.Outcome.DUPLICATE ? "DUPLICATE" : "ACCEPTED", r.eventKey(), List.of(),
                List.of());
    }

    // ---------------------------------------------------------------- mapping

    private Channel deployChannel(RequestContext ctx, String key, Channel c, String auditType) {
        String direction = c.direction() == null ? "INBOUND" : c.direction().toUpperCase(Locale.ROOT);
        if (!direction.equals("INBOUND") && !direction.equals("OUTBOUND")) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "direction must be INBOUND or OUTBOUND");
        }
        String transport = c.transport() == null ? "WEBHOOK" : c.transport().toUpperCase(Locale.ROOT);
        String channelType = switch (transport) {
            case "REST", "WEBHOOK" -> direction.equals("INBOUND") ? "webhook" : "http";
            case "KAFKA" -> "kafka";
            case "JMS" -> "jms";
            case "AMQP" -> "rabbitmq";
            default -> throw ApiProblem.badRequest("VALIDATION_FAILED", "transport must be REST | WEBHOOK | KAFKA | JMS | AMQP");
        };
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("key", key);
        json.put("name", c.name() == null ? key : c.name());
        json.put("channelType", c.active() ? channelType : "webhook");
        json.put("type", direction.toLowerCase(Locale.ROOT));
        if (c.destination() != null) {
            if (channelType.equals("kafka")) {
                json.put("topics", List.of(c.destination()));
            } else if (channelType.equals("http")) {
                json.put("url", c.destination());
            } else if (channelType.equals("rabbitmq")) {
                json.put("queue", c.destination());
            } else {
                json.put("destination", c.destination());
            }
        }
        Map<String, Object> keyDetection = new LinkedHashMap<>();
        if (c.keyDetector() != null && !c.keyDetector().isBlank()) {
            if (c.keyDetector().startsWith("/")) {
                keyDetection.put("jsonPointerExpression", c.keyDetector());
            } else if (c.keyDetector().startsWith("header:")) {
                keyDetection.put("header", c.keyDetector().substring("header:".length()));
            } else {
                keyDetection.put("jsonField", c.keyDetector());
            }
        } else if (c.eventDefinitionKeys() != null && c.eventDefinitionKeys().size() == 1) {
            keyDetection.put("fixedValue", c.eventDefinitionKeys().get(0));
        } else {
            keyDetection.put("jsonField", "type");
        }
        json.put("channelEventKeyDetection", keyDetection);
        if (c.tenantDetector() != null) {
            json.put("channelEventTenantJsonPointerExpression", c.tenantDetector());
        }
        json.put("messageIdJsonPointer", "/id");
        json.put("payloadJsonPointer", "/data");
        Map<String, String> properties = new LinkedHashMap<>();
        if (c.config() != null) {
            c.config().forEach((k, v) -> properties.put(k, String.valueOf(v)));
        }
        if (channelType.equals("webhook") && direction.equals("INBOUND") && !allowUnsignedWebhooks
                && (properties.get("secretRef") == null || properties.get("secretRef").isBlank())) {
            throw ApiProblem.unprocessable("WEBHOOK_SECRET_REQUIRED",
                    "an inbound webhook channel needs config.secretRef (HMAC X-Sangam-Signature); unsigned public "
                            + "webhooks are refused unless sangam.ports.events.allow-unsigned-webhooks=true");
        }
        properties.put("active", Boolean.toString(c.active()));
        if (c.dedupWindowSeconds() != null) {
            properties.put("dedupWindowSeconds", c.dedupWindowSeconds().toString());
        }
        if (c.eventDefinitionKeys() != null) {
            properties.put("eventDefinitionKeys", String.join(",", c.eventDefinitionKeys()));
        }
        json.put("properties", properties);
        EventRegistry.DeployedChannel d = PortsErrors.call(() -> registry.deployChannel(ctx.tenantId(), write(json), null));
        if (channelManagerOrNull != null) {
            PortsErrors.run(channelManagerOrNull::refresh);
        }
        audit.record(ctx, "sec:" + ctx.tenantId(), auditType, Map.of("key", key, "version", d.version()));
        return map(d);
    }

    private Channel map(EventRegistry.DeployedChannel d) {
        ChannelDefinition c = d.definition();
        String transport = switch (c.adapterType()) {
            case "kafka" -> "KAFKA";
            case "jms" -> "JMS";
            case "rabbitmq" -> "AMQP";
            case "http" -> "REST";
            default -> "WEBHOOK";
        };
        String destination = c.url() != null ? c.url() : c.queue() != null ? c.queue()
                : !c.topics().isEmpty() ? c.topics().get(0) : c.destination();
        String keyDetector = null;
        List<String> keys = new ArrayList<>();
        if (c.channelEventKeyDetection() != null) {
            ChannelDefinition.KeyDetection k = c.channelEventKeyDetection();
            keyDetector = k.jsonPointerExpression() != null ? k.jsonPointerExpression()
                    : k.header() != null ? "header:" + k.header() : k.jsonField();
            if (k.fixedValue() != null) {
                keys.add(k.fixedValue());
            }
        }
        String listed = c.properties().get("eventDefinitionKeys");
        if (listed != null && !listed.isBlank()) {
            for (String s : listed.split(",")) {
                if (!keys.contains(s.trim())) {
                    keys.add(s.trim());
                }
            }
        }
        Map<String, Object> config = new LinkedHashMap<>(c.properties());
        config.remove("active");
        config.remove("eventDefinitionKeys");
        Object dedup = config.remove("dedupWindowSeconds");
        boolean active = !"false".equals(c.properties().get("active"));
        return new Channel(c.key(), d.tenantId(), c.name(), c.direction().name(), transport, destination, keys, keyDetector,
                c.tenantJsonPointer(), dedup == null ? null : Integer.valueOf(dedup.toString()), config, active, d.version());
    }

    private EventDefinition map(EventRegistry.DeployedEvent d) {
        org.sangam.ports.events.model.EventDefinition e = d.definition();
        List<Map<String, Object>> fields = new ArrayList<>();
        for (org.sangam.ports.events.model.EventDefinition.Field f : e.payload()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", f.name());
            m.put("type", f.type());
            m.put("required", f.isRequired());
            fields.add(m);
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("fields", fields);
        List<String> corr = e.correlationParameters().stream()
                .map(org.sangam.ports.events.model.EventDefinition.Field::name).toList();
        boolean starts = e.startProcessKey() != null || e.startCaseKey() != null;
        return new EventDefinition(e.key(), d.tenantId(), e.name(), d.version(), schema, corr,
                e.businessKeyField() == null ? null : "/" + e.businessKeyField(), e.startProcessKey(),
                starts ? null : e.key(), e.startUniqueReference() != null, d.version());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> payloadFields(Map<String, Object> schema) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (schema == null) {
            return out;
        }
        if (schema.get("fields") instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> m && m.get("name") != null) {
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("name", String.valueOf(m.get("name")));
                    f.put("type", m.get("type") == null ? "string" : String.valueOf(m.get("type")));
                    if (m.get("required") != null) {
                        f.put("required", Boolean.valueOf(String.valueOf(m.get("required"))));
                    }
                    out.add(f);
                }
            }
            return out;
        }
        // JSON Schema object: properties → fields (types integer/number/boolean/string/object/array)
        if (schema.get("properties") instanceof Map<?, ?> props) {
            List<Object> required = schema.get("required") instanceof List<?> r ? (List<Object>) r : List.of();
            props.forEach((k, v) -> {
                String type = "string";
                if (v instanceof Map<?, ?> m && m.get("type") != null) {
                    type = switch (String.valueOf(m.get("type"))) {
                        case "integer" -> "long";
                        case "number" -> "double";
                        case "boolean" -> "boolean";
                        case "object", "array" -> "json";
                        default -> "string";
                    };
                }
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("name", String.valueOf(k));
                f.put("type", type);
                f.put("required", required.contains(k));
                out.add(f);
            });
        }
        return out;
    }

    private static String write(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "cannot serialise: " + e.getOriginalMessage());
        }
    }
}

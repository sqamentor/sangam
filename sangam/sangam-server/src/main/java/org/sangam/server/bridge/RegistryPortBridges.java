// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

import org.operaton.bpm.engine.DecisionService;
import org.operaton.bpm.engine.ProcessEngineException;
import org.sangam.compliance.SangamCompliance;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.CanonicalJson;
import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.Outbox;
import org.sangam.core.layer.SqlPredicate;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.integration.connector.ConnectorInvoker;
import org.sangam.integration.connector.ConnectorRegistry;
import org.sangam.integration.relay.OutboxWriter;
import org.sangam.ports.content.ContentService;
import org.sangam.ports.content.OutputTemplateRenderer;
import org.sangam.registry.RegistryContext;
import org.sangam.registry.RegistryPorts;
import org.sangam.rest.port.RegistryPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.TxSupport;
import org.sangam.security.SangamSecurity;
import org.sangam.security.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * sangam-registry port bindings (registry README "Integration notes" §3). Every port that runs inside a registry
 * command uses the connection of the current Spring transaction ({@link TxSupport#connection()}), which is the same
 * connection the engine and the registry service use, so outbox rows, audit and engine state commit together (P-1).
 * Remote calls (document-locker push, system-of-record push) are never made inside the command: they are written to
 * the outbox and delivered after commit by {@link PublishDelivery}.
 */
public final class RegistryPortBridges {

    private static final Logger LOG = LoggerFactory.getLogger(RegistryPortBridges.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Outbox destination of registry domain events (unordered; subscribers via the integration subscriptions). */
    public static final String DEST_EVENTS = "sangam.registry.events";
    /** Outbox destination for document-locker pushes and system-of-record pushes (unordered, after commit). */
    public static final String DEST_PUBLISH = "sangam.registry.publish";
    public static final String EVT_PUBLISH = "REGISTRY_PUBLISH";
    public static final String EVT_SOR_PUSH = "REGISTRY_SOR_PUSH";

    private RegistryPortBridges() {
    }

    /** The security identity of a registry caller: the request's resolved identity when it is the same caller. */
    public static SecurityIdentity identity(RegistryContext ctx) {
        SecurityIdentity current = TenantContext.current().orElse(null);
        if (current != null && current.tenantId().equals(ctx.tenant())
                && (current.personId().equals(ctx.actor()) || current.subject().equals(ctx.actor()))) {
            return current;
        }
        if (ctx.actor().startsWith("system:")) {
            return SecurityIdentity.system(ctx.tenant(), ctx.onBehalfOf() != null ? ctx.onBehalfOf() : ctx.actor());
        }
        return new SecurityIdentity(ctx.tenant(), ctx.actor(), ctx.actor(), Set.of(), Set.of(), Set.of(), Set.of(),
                "REGISTRY", null, ctx.onBehalfOf(), false, Map.of());
    }

    /** The registry context of a REST caller (VI.4 rule 6: tenant from the authenticated identity only). */
    public static RegistryContext context(RequestContext ctx) {
        return new RegistryContext(ctx.tenantId(), ctx.principalId(), ctx.actingForSeatId(), null, ctx.idempotencyKey(),
                null);
    }

    // ---------------------------------------------------------------- EventPort → outbox (same transaction)

    /** Registry events to the integration outbox (subscriptions), else straight to {@value #DEST_EVENTS}. */
    public static RegistryPorts.EventPort events(OutboxWriter writer, TxSupport tx) {
        Outbox core = new Outbox(tx.dialect());
        return (c, ctx, rootId, eventType, payload) -> {
            Map<String, Object> p = new LinkedHashMap<>(payload == null ? Map.of() : payload);
            p.put("actor", ctx.actor());
            if (ctx.operationId() != null) {
                p.put("operationId", ctx.operationId());
            }
            if (writer != null) {
                writer.append(c, ctx.tenant(), rootId, eventType, p);
            } else {
                core.append(c, ctx.tenant(), rootId, eventType, CanonicalJson.write(jsonSafe(p)),
                        List.of(Outbox.Destination.unordered(DEST_EVENTS)));
            }
        };
    }

    // ---------------------------------------------------------------- VisibilityPort → VI.4 compiled predicate

    public static RegistryPorts.VisibilityPort visibility(SangamSecurity security, TxSupport tx) {
        return (ctx, resourceType, alias) -> {
            SqlPredicate p = security.authorization().visible(tx.connection(), identity(ctx), resourceType, "READ", alias);
            return p;
        };
    }

    // ---------------------------------------------------------------- PrivacyPort → security field masks

    public static RegistryPorts.PrivacyPort privacy(SangamSecurity security, TxSupport tx) {
        return (ctx, resourceType, view, sensitiveFields) -> {
            if (view == null || view.isEmpty()) {
                return view;
            }
            Object id = view.get("id");
            String scope = ctx.purpose();
            return security.masks().mask(tx.connection(), identity(ctx), resourceType,
                    id == null ? null : String.valueOf(id), scope, view);
        };
    }

    // ---------------------------------------------------------------- ConsentPort → compliance purpose binding

    /**
     * Consent and purpose checks through sangam-compliance's {@code PurposeBindingGuard}: a declared purpose must be
     * bound for the party (lawful basis / consent / legitimate use). Without a purpose the registry's own default
     * applies (permit). Merge and erasure notifications are recorded as compliance audit by the registry itself; the
     * compliance module has no merge-follow API yet (gap).
     */
    public static RegistryPorts.ConsentPort consent(SangamCompliance compliance, TxSupport tx) {
        return new RegistryPorts.ConsentPort() {
            @Override
            public RegistryPorts.ConsentDecision check(RegistryContext ctx, String partyId, String purpose,
                                                       String processing) {
                if (purpose == null || purpose.isBlank()) {
                    return RegistryPorts.ConsentDecision.permit(null);
                }
                Set<String> categories = processing == null ? Set.of() : Set.of(processing);
                boolean ok = compliance.purposeBinding.allowed(tx.connection(), ctx.tenant(), partyId, purpose, categories);
                return ok ? RegistryPorts.ConsentDecision.permit(purpose)
                        : RegistryPorts.ConsentDecision.deny("PURPOSE_NOT_BOUND");
            }
        };
    }

    // ---------------------------------------------------------------- DecisionPort → Operaton DMN

    public static RegistryPorts.DecisionPort decisions(DecisionService decisions) {
        return (tenant, key, vars) -> {
            try {
                return decisions.evaluateDecisionByKey(key).decisionDefinitionTenantId(tenant).variables(vars)
                        .evaluate().getResultList();
            } catch (ProcessEngineException tenantMiss) {
                // Shared (untenanted) decision tables serve every tenant that has no override (GP-37 pattern).
                return decisions.evaluateDecisionByKey(key).decisionDefinitionWithoutTenantId().variables(vars)
                        .evaluate().getResultList();
            }
        };
    }

    // ---------------------------------------------------------------- DocumentPort → ports renderer + content store

    /**
     * Template rendering with the flowable-ports {@code OutputTemplateRenderer} into the {@code ContentService}. The
     * template text comes from the REST template registry ({@code /admin/templates}, entry key = templateKey,
     * {@code body.<lang>} or {@code body}), the same templates the document-generation endpoint uses. HTML output; a
     * PDF/A rendering service is not bound (gap: rendered as HTML with content type text/html).
     */
    public static RegistryPorts.DocumentPort documents(ContentService content, RegistryPort templates) {
        OutputTemplateRenderer renderer = new OutputTemplateRenderer(content);
        return (ctx, templateKey, model) -> {
            RequestContext sys = RequestContext.system(ctx.tenant(), ctx.actor());
            var page = templates.list(sys, "templates", null, org.sangam.rest.port.Page.PageRequest.of(0, 500));
            Optional<Map<String, Object>> entry = page.items().stream()
                    .filter(e -> templateKey.equals(e.get("key"))).findFirst();
            if (entry.isEmpty()) {
                LOG.debug("registry document template {} not found for tenant {}", templateKey, ctx.tenant());
                return Optional.empty();
            }
            Object body = entry.get().get("body");
            String text = body instanceof Map<?, ?> m ? String.valueOf(m.values().stream().<Object>map(v -> v).findFirst().orElse(""))
                    : String.valueOf(body == null ? "" : body);
            String html = renderer.render(text, model == null ? Map.of() : model, true, false);
            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            Object objectId = model == null ? null : model.get("objectId");
            var item = content.create(ctx.tenant(), templateKey + ".html", "text/html", "registry-object",
                    objectId == null ? null : String.valueOf(objectId), null, new ByteArrayInputStream(bytes), ctx.actor());
            return Optional.of(new RegistryPorts.GeneratedDocument(item.id(), Hashes.hex(Hashes.sha256(bytes)),
                    "text/html"));
        };
    }

    // ---------------------------------------------------------------- PublishPort → outbox → locker connector

    /**
     * Document-locker push: written to the outbox in the registry command ({@value #DEST_PUBLISH}); the push itself
     * runs after commit in {@link PublishDelivery} through the integration connector, idempotent by object id. The
     * external URI is therefore not known inside the command ({@code Optional.empty()}); the connector outcome is in
     * the integration call journal and ledger.
     */
    public static RegistryPorts.PublishPort publisher(TxSupport tx, String connectorId) {
        Outbox outbox = new Outbox(tx.dialect());
        return (ctx, channel, objectId, documentRef, meta) -> {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("kind", "publish");
            p.put("channel", channel);
            p.put("connectorId", connectorId);
            p.put("objectId", objectId);
            p.put("documentRef", documentRef);
            p.put("meta", meta == null ? Map.of() : meta);
            p.put("actor", ctx.actor());
            outbox.append(tx.connection(), ctx.tenant(), objectId, EVT_PUBLISH, CanonicalJson.write(jsonSafe(p)),
                    List.of(Outbox.Destination.unordered(DEST_PUBLISH)));
            return Optional.empty();
        };
    }

    // ---------------------------------------------------------------- SystemOfRecordPort → connectors

    /**
     * Facade mode ({@code extensions.systemOfRecord = EXTERNAL}): the connector for a type comes from
     * {@code sangam.registry.system-of-record.<typeKey>}. {@code fetch} is a read-only connector call (operation
     * {@code fetch}); {@code push} goes through the outbox (operation {@code push}) after commit.
     */
    public static RegistryPorts.SystemOfRecordPort systemOfRecord(ConnectorRegistry connectors,
                                                                  Function<String, String> connectorForType,
                                                                  TxSupport tx) {
        Outbox outbox = new Outbox(tx.dialect());
        return new RegistryPorts.SystemOfRecordPort() {
            @Override
            public Optional<Map<String, Object>> fetch(RegistryContext ctx, String typeKey, String externalRef) {
                String id = connectorForType.apply(typeKey);
                if (id == null) {
                    return Optional.empty();
                }
                SangamConnector conn = connectors.require(ctx.tenant(), id);
                ConnectorResult r = conn.invoke(new ConnectorRequest(ctx.tenant(),
                        Hashes.uuidV5(UUID.nameUUIDFromBytes("sangam-registry-sor".getBytes(StandardCharsets.UTF_8)),
                                ctx.tenant() + ":" + typeKey + ":" + externalRef + ":" + Instant.now().toEpochMilli()),
                        null, 1, externalRef, null, "fetch", Map.of("typeKey", typeKey, "externalRef", externalRef),
                        Instant.now().plusSeconds(30)));
                if (r instanceof ConnectorResult.Success s) {
                    return Optional.of(s.output());
                }
                if (r instanceof ConnectorResult.BusinessFailure f && "NOT_FOUND".equals(f.code())) {
                    return Optional.empty();
                }
                throw new IllegalStateException("system of record " + id + " fetch failed: " + r);
            }

            @Override
            public void push(RegistryContext ctx, String typeKey, String objectId, String externalRef, String change,
                             Map<String, Object> view) {
                String id = connectorForType.apply(typeKey);
                if (id == null) {
                    return;
                }
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("kind", "sor-push");
                p.put("connectorId", id);
                p.put("typeKey", typeKey);
                p.put("objectId", objectId);
                p.put("externalRef", externalRef);
                p.put("change", change);
                p.put("view", view == null ? Map.of() : view);
                outbox.append(tx.connection(), ctx.tenant(), objectId, EVT_SOR_PUSH, CanonicalJson.write(jsonSafe(p)),
                        List.of(Outbox.Destination.unordered(DEST_PUBLISH)));
            }
        };
    }

    // ---------------------------------------------------------------- MetricsPort → Micrometer

    public static RegistryPorts.MetricsPort metrics(MeterRegistry registry) {
        return new RegistryPorts.MetricsPort() {
            @Override
            public void counterLockWait(String tenant, String seriesKey, long nanos) {
                registry.timer("sangam.registry.counter.lock.wait", "series", seriesKey)
                        .record(java.time.Duration.ofNanos(nanos));
            }

            @Override
            public void increment(String name, Map<String, String> tags) {
                Tags t = Tags.empty();
                if (tags != null) {
                    for (Map.Entry<String, String> e : tags.entrySet()) {
                        if (!"tenant".equals(e.getKey())) {
                            t = t.and(e.getKey(), e.getValue() == null ? "" : e.getValue());
                        }
                    }
                }
                registry.counter(name.startsWith("sangam.") ? name : "sangam.registry." + name, t).increment();
            }
        };
    }

    // ---------------------------------------------------------------- after-commit delivery of DEST_PUBLISH

    /**
     * Relay publisher for {@value #DEST_PUBLISH}: invokes the connector through the integration {@link ConnectorInvoker}
     * (operation ledger, R36 identity derived from the outbox event id, so a redelivery never pushes twice).
     * A business failure is permanent (the relay parks it); a transient failure is retried by the relay.
     */
    public static final class PublishDelivery implements org.sangam.core.layer.Publisher {
        private final ConnectorInvoker invoker;

        public PublishDelivery(ConnectorInvoker invoker) {
            this.invoker = invoker;
        }

        @Override
        public void publish(String destination, Outbox.OutboxEvent event) throws Exception {
            Map<String, Object> p = JSON.readValue(event.payload(), new TypeReference<LinkedHashMap<String, Object>>() { });
            String kind = String.valueOf(p.get("kind"));
            String connectorId = String.valueOf(p.get("connectorId"));
            Map<String, Object> input = new LinkedHashMap<>();
            String operation;
            if ("publish".equals(kind)) {
                operation = "push";
                Object meta = p.get("meta");
                if (meta instanceof Map<?, ?> m) {
                    m.forEach((k, v) -> input.put(String.valueOf(k), v));
                }
                input.putIfAbsent("uri", p.get("documentRef"));
                input.put("objectId", p.get("objectId"));
                input.put("channel", p.get("channel"));
            } else {
                operation = "push";
                input.putAll(p);
            }
            var outcome = invoker.invoke(new ConnectorInvoker.Invocation(event.tenantId(), event.rootId(),
                    "registry-" + kind, 0, event.eventId().toString(), null, connectorId, operation, input, null, 0,
                    Instant.now().plusSeconds(60)));
            if (outcome instanceof ConnectorInvoker.Failed f) {
                throw new org.sangam.integration.relay.PermanentPublishException(
                        "registry " + kind + " via " + connectorId + " failed: " + f.code() + " " + f.message());
            }
            if (outcome instanceof ConnectorInvoker.Retry r) {
                throw new IllegalStateException("registry " + kind + " via " + connectorId + " to retry: " + r.message());
            }
        }
    }

    @SuppressWarnings("unchecked")
    static Object jsonSafe(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            ((Map<Object, Object>) m).forEach((k, x) -> out.put(String.valueOf(k), jsonSafe(x)));
            return out;
        }
        if (v instanceof java.util.Collection<?> c) {
            return c.stream().map(RegistryPortBridges::jsonSafe).toList();
        }
        if (v instanceof Number n && !(v instanceof Integer || v instanceof Long || v instanceof Short
                || v instanceof Byte || v instanceof java.math.BigInteger)) {
            return new java.math.BigDecimal(n.toString()).toPlainString();
        }
        if (v instanceof java.time.temporal.TemporalAccessor || v instanceof java.util.Date) {
            return v.toString();
        }
        return v;
    }
}

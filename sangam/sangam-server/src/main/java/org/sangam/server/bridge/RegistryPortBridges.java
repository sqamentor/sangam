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
import org.sangam.core.security.SecurityIdentity;
import org.sangam.integration.connector.ConnectorInvoker;
import org.sangam.integration.connector.ConnectorRegistry;
import org.sangam.integration.document.pdf.PdfMarkup;
import org.sangam.integration.document.pdf.PdfRendering;
import org.sangam.integration.relay.OutboxWriter;
import org.sangam.ports.content.ContentService;
import org.sangam.ports.content.OutputTemplateRenderer;
import org.sangam.registry.RegistryContext;
import org.sangam.registry.RegistryPorts;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.RegistryPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.TxSupport;
import org.sangam.security.SangamSecurity;
import org.sangam.security.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * sangam-registry port bindings (registry README "Integration notes" §3). Every port that touches the database works
 * on the caller's connection, which the registry passes in (SL-169): visibility, privacy and consent read on it; the
 * event, publish and system-of-record ports write their outbox rows on it. None of them uses
 * {@link TxSupport#connection()}, which outside a Spring transaction borrowed a second, unclosed pool connection.
 * Outbox rows, audit and engine state therefore commit together (P-1).
 * Remote calls (document-locker push, system-of-record push) are never made inside the command: they are written to
 * the outbox and delivered after commit by {@link PublishDelivery}.
 */
public final class RegistryPortBridges {

    private static final Logger LOG = LoggerFactory.getLogger(RegistryPortBridges.class);
    // decision 381(4): fractions of a stored outbox payload are read exactly
    private static final ObjectMapper JSON = org.sangam.rest.support.ExactJson.mapper();

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

    /**
     * Registry events to the integration outbox through its subscriptions ({@link OutboxWriter}): written only to the
     * destinations subscribed to the event type, nothing when none is. SL-146 (owner decision 2026-11-12): without an
     * {@code OutboxWriter} (integration disabled) there is no relay and no subscriber, so nothing is written; the former
     * fallback wrote to a fixed {@code sangam.registry.events} destination that no publisher served.
     */
    public static RegistryPorts.EventPort events(OutboxWriter writer, TxSupport tx) {
        if (writer == null) {
            return (c, ctx, rootId, eventType, payload) -> LOG.trace("registry event {} not written: no outbox writer", eventType);
        }
        return (c, ctx, rootId, eventType, payload) -> {
            Map<String, Object> p = new LinkedHashMap<>(payload == null ? Map.of() : payload);
            p.put("actor", ctx.actor());
            if (ctx.operationId() != null) {
                p.put("operationId", ctx.operationId());
            }
            writer.append(c, ctx.tenant(), rootId, eventType, p);
        };
    }

    // ---------------------------------------------------------------- VisibilityPort → VI.4 compiled predicate

    /**
     * SL-169: the policy is compiled on the caller's connection {@code c}, never on {@link TxSupport#connection()}.
     * Outside a Spring transaction that call borrowed a second pool connection that nothing closed and that could not
     * see the caller's uncommitted writes. {@code tx} stays in the signature so the wiring is unchanged.
     */
    public static RegistryPorts.VisibilityPort visibility(SangamSecurity security, TxSupport tx) {
        return (c, ctx, resourceType, alias) -> security.authorization().visible(requireConnection(c), identity(ctx),
                resourceType, "READ", alias);
    }

    private static Connection requireConnection(Connection c) {
        if (c == null) {
            throw new IllegalStateException("a registry port was called without the caller's connection (SL-169)");
        }
        return c;
    }

    // ---------------------------------------------------------------- PrivacyPort → security field masks

    public static RegistryPorts.PrivacyPort privacy(SangamSecurity security, TxSupport tx) {
        return (c, ctx, resourceType, view, sensitiveFields) -> {
            if (view == null || view.isEmpty()) {
                return view;
            }
            Object id = view.get("id");
            String scope = ctx.purpose();
            return security.masks().mask(requireConnection(c), identity(ctx), resourceType,
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
            public RegistryPorts.ConsentDecision check(Connection c, RegistryContext ctx, String partyId, String purpose,
                                                       String processing) {
                if (purpose == null || purpose.isBlank()) {
                    return RegistryPorts.ConsentDecision.permit(null);
                }
                Set<String> categories = processing == null ? Set.of() : Set.of(processing);
                boolean ok = compliance.purposeBinding.allowed(requireConnection(c), ctx.tenant(), partyId, purpose,
                        categories);
                return ok ? RegistryPorts.ConsentDecision.permit(purpose)
                        : RegistryPorts.ConsentDecision.deny("PURPOSE_NOT_BOUND");
            }
        };
    }

    // ---------------------------------------------------------------- DecisionPort → Operaton DMN

    public static RegistryPorts.DecisionPort decisions(DecisionService decisions) {
        return (tenant, key, facts) -> {
            // decision 381(4): the facts reach the engine as variables through TypedVariables (no binary float)
            Map<String, Object> vars = org.sangam.rest.support.TypedVariables.fromJson(facts);
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
     * {@code body.<lang>} or {@code body}), the same templates the document-generation endpoint uses. An entry with
     * {@code format: PDFA} is written in the {@code PdfMarkup} text form and rendered to a validated PDF/A-2b + PDF/UA
     * document through {@link PdfRendering} (M-081). Owner decision 69: while PDF output is unavailable such an entry
     * is refused with {@code 422 FORMAT_NOT_AVAILABLE} naming the reason, the same as document generation and the
     * audit export; there is no HTML fallback. A rendering that fails validation is refused with
     * {@code 422 PDF_INVALID}. Other entries render as HTML.
     */
    public static RegistryPorts.DocumentPort documents(ContentService content, RegistryPort templates) {
        return documents(content, templates, PdfRendering.installed());
    }

    public static RegistryPorts.DocumentPort documents(ContentService content, RegistryPort templates, PdfRendering pdf) {
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
            Map<String, Object> vars = model == null ? Map.of() : model;
            Object objectId = model == null ? null : model.get("objectId");
            String scopeId = objectId == null ? null : String.valueOf(objectId);
            boolean wantsPdf = "PDFA".equalsIgnoreCase(String.valueOf(entry.get().get("format")));
            if (wantsPdf) {
                if (!pdf.available()) {
                    throw ApiProblem.unprocessable("FORMAT_NOT_AVAILABLE", "registry document template " + templateKey
                            + " asks for PDF/A, but PDF/A rendering is off: " + pdf.unavailableReason());
                }
                String language = body instanceof Map<?, ?> m && !m.isEmpty() ? String.valueOf(m.keySet().iterator().next()) : "en";
                String title = String.valueOf(entry.get().getOrDefault("name", templateKey));
                PdfRendering.Rendered pdfOut;
                try {
                    pdfOut = pdf.render(PdfMarkup.parse(title, language, text,
                            t -> renderer.render(t, vars, false, false)));
                } catch (PdfRendering.Unavailable e) {
                    throw ApiProblem.unprocessable("FORMAT_NOT_AVAILABLE", e.getMessage());
                } catch (PdfRendering.Invalid e) {
                    throw ApiProblem.unprocessable("PDF_INVALID", e.getMessage());
                }
                var item = content.create(ctx.tenant(), templateKey + ".pdf", PdfRendering.Rendered.CONTENT_TYPE,
                        "registry-object", scopeId, null, new ByteArrayInputStream(pdfOut.content()), ctx.actor());
                return Optional.of(new RegistryPorts.GeneratedDocument(item.id(), pdfOut.sha256(),
                        PdfRendering.Rendered.CONTENT_TYPE));
            }
            String html = renderer.render(text, vars, true, false);
            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            var item = content.create(ctx.tenant(), templateKey + ".html", "text/html", "registry-object",
                    scopeId, null, new ByteArrayInputStream(bytes), ctx.actor());
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
    /**
     * Publishes through the connector {@code sangam.registry.publish.connector}. SL-104 (ADR-D-20, generic: no
     * built-in default in code or config): there is no default connector; publishing without one fails in the
     * publishing transaction, naming the property.
     */
    public static RegistryPorts.PublishPort publisher(TxSupport tx, String connectorId) {
        boolean configured = connectorId != null && !connectorId.isBlank();
        Outbox outbox = configured ? new Outbox(tx.dialect()) : null;
        return (c, ctx, channel, objectId, documentRef, meta) -> {
            if (!configured) {
                throw new IllegalStateException("sangam.registry.publish.connector is not configured: cannot publish "
                        + objectId + " on " + channel + " (SL-104: there is no default publish connector)");
            }
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("kind", "publish");
            p.put("channel", channel);
            p.put("connectorId", connectorId);
            p.put("objectId", objectId);
            p.put("documentRef", documentRef);
            p.put("meta", meta == null ? Map.of() : meta);
            p.put("actor", ctx.actor());
            outbox.append(requireConnection(c), ctx.tenant(), objectId, EVT_PUBLISH, CanonicalJson.write(jsonSafe(p)),
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
            public void push(Connection c, RegistryContext ctx, String typeKey, String objectId, String externalRef,
                             String change, Map<String, Object> view) {
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
                outbox.append(requireConnection(c), ctx.tenant(), objectId, EVT_SOR_PUSH, CanonicalJson.write(jsonSafe(p)),
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
                    Instant.now().plusSeconds(60),
                    org.sangam.core.layer.OperationLedger.operationId(event.tenantId(), event.rootId(),
                            "registry-" + kind + "#" + event.eventId(), 0)));
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

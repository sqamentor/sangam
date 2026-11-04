// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.LookupResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.integration.connector.ConnectorCallJournal;
import org.sangam.integration.connector.ConnectorInvoker;
import org.sangam.integration.connector.ConnectorRegistry;
import org.sangam.integration.ledger.OperationLedgerService;
import org.sangam.integration.relay.DeadLetterStore;
import org.sangam.rest.adapter.operaton.OperatonOpsConsoleAdapter;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.OpsConsolePort;
import org.sangam.rest.port.OpsPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.TxSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Binds the operations console's reconciliation lookup and notification resend to sangam-integration (OJ-02, A-2,
 * R60). Applied to the default {@link OperatonOpsConsoleAdapter}; a module-provided console is left alone.
 *
 * <ul>
 *   <li><b>Lookup</b> (capability classes a/b): the item's operation is read from the ledger and its connector call
 *       journal ({@link OperationLedgerService#view}); the registered connector's {@code lookup} is called with the
 *       original operationId and request. It is read-only: the operator still resolves the item. Class (c) has no
 *       lookup → {@code 422 LOOKUP_NOT_SUPPORTED}.</li>
 *   <li><b>Notification failures</b>: open dead letters (INTEGRATION_DEAD_LETTER) whose destination or event type is a
 *       notification ({@code sangam.ops.notification-destinations}, default {@code notify*,email*,mail*,sms*,dlt*})
 *       are listed next to the failures recorded by the modules.</li>
 *   <li><b>Resend</b>: a dead-lettered delivery is requeued through the outbox relay (same event id, so the receiver
 *       can de-duplicate) and the dead letter is marked REQUEUED; a recorded failure whose id is a ledger operation is
 *       re-dispatched through {@link ConnectorInvoker} as the new operation {@code resend:<failureId>} under the same
 *       root and activity, so a repeated resend click sends once (R36 operation identity).</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sangam.modules.integration.enabled", havingValue = "true", matchIfMissing = true)
public class OpsIntegrationWiring {

    private static final Logger LOG = LoggerFactory.getLogger(OpsIntegrationWiring.class);
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    static final String DLQ_PREFIX = "dlq:";

    @Bean
    public SmartInitializingSingleton sangamOpsIntegrationBinder(ObjectProvider<OpsConsolePort> console,
            ObjectProvider<OpsPort> ops, ObjectProvider<OperationLedgerService> ledger,
            ObjectProvider<ConnectorRegistry> registry, ObjectProvider<ConnectorInvoker> invoker,
            ObjectProvider<DeadLetterStore> deadLetters, ObjectProvider<ConnectorCallJournal> journal, TxSupport tx,
            Environment env) {
        return () -> {
            OpsConsolePort port = console.getIfAvailable();
            if (!(port instanceof OperatonOpsConsoleAdapter adapter)) {
                LOG.info("Ops console is not the default adapter: lookup/resend binding skipped");
                return;
            }
            OperationLedgerService ledgerService = ledger.getIfAvailable();
            ConnectorRegistry connectors = registry.getIfAvailable();
            if (ledgerService != null && connectors != null) {
                adapter.setLookup((ctx, item) -> lookup(ctx, item, ledgerService, connectors));
            }
            DeadLetterStore dlq = deadLetters.getIfAvailable();
            Predicate<String> notification = notificationMatcher(env.getProperty(
                    "sangam.ops.notification-destinations", "notify*,email*,mail*,sms*,dlt*"));
            if (dlq != null) {
                adapter.setFailureSource(tenant -> deadLetterFailures(dlq, tenant, notification));
            }
            OpsPort opsPort = ops.getIfAvailable();
            ConnectorInvoker inv = invoker.getIfAvailable();
            adapter.setResender((ctx, failure) -> resend(ctx, failure, dlq, opsPort, ledgerService, inv, tx));
            LOG.info("Ops console bound to sangam-integration (lookup: {}, dead letters: {}, resend: {})",
                    ledgerService != null && connectors != null, dlq != null, inv != null || dlq != null);
        };
    }

    // ------------------------------------------------------------------ lookup (A-2 classes a/b)

    static OpsConsolePort.LookupResult lookup(RequestContext ctx, OpsPort.ReconciliationItem item,
                                              OperationLedgerService ledger, ConnectorRegistry registry) {
        UUID op = operationId(item.operationId());
        OperationLedgerService.OperationView view = ledger.view(op)
                .orElseThrow(() -> ApiProblem.notFound("ledger operation", item.operationId()));
        if (!ctx.tenantId().equals(view.entry().tenantId())) {
            throw ApiProblem.notFound("ledger operation", item.operationId());
        }
        ConnectorCallJournal.Call call = view.call();
        if (call == null) {
            throw ApiProblem.unprocessable("LOOKUP_NOT_SUPPORTED",
                    "operation " + op + " has no connector call (it was not dispatched through a connector)");
        }
        SangamConnector connector = registry.find(ctx.tenantId(), call.connectorId())
                .orElseThrow(() -> ApiProblem.unprocessable("LOOKUP_NOT_SUPPORTED",
                        "connector " + call.connectorId() + " is not registered for this tenant"));
        if (connector.capabilityClass() == CapabilityClass.C_NEITHER) {
            throw ApiProblem.unprocessable("LOOKUP_NOT_SUPPORTED",
                    "connector " + call.connectorId() + " is capability class (c): it offers no outcome lookup (A-2)");
        }
        ConnectorRequest request = new ConnectorRequest(ctx.tenantId(), op, view.entry().attemptId(),
                Math.max(1, call.attempts()), call.rootId(), call.businessKey(), call.operation(), input(call.requestJson()),
                Instant.now().plus(Duration.ofSeconds(30)));
        LookupResult r = connector.lookup(request);
        String text = switch (r) {
            case LookupResult.Found f when f.succeeded() -> "FOUND_SUCCEEDED" + (f.providerRef() == null ? ""
                    : " providerRef=" + f.providerRef());
            case LookupResult.Found f -> "FOUND_FAILED" + (f.failureCode() == null ? "" : " code=" + f.failureCode());
            case LookupResult.NotFound n -> "NOT_FOUND";
            case LookupResult.Unavailable u -> "UNAVAILABLE: " + u.reason();
            case LookupResult.NotSupported n -> throw ApiProblem.unprocessable("LOOKUP_NOT_SUPPORTED",
                    "connector " + call.connectorId() + " does not implement lookup");
        };
        return new OpsConsolePort.LookupResult(text, Instant.now());
    }

    // ------------------------------------------------------------------ notification failures and resend (R60)

    static List<OpsConsolePort.NotificationFailure> deadLetterFailures(DeadLetterStore dlq, String tenant,
                                                                       Predicate<String> notification) {
        List<OpsConsolePort.NotificationFailure> out = new ArrayList<>();
        for (DeadLetterStore.Entry e : dlq.listOpen(tenant, 500)) {
            if (!notification.test(e.destination()) && !(e.eventType() != null
                    && e.eventType().toUpperCase(Locale.ROOT).contains("NOTIFICATION"))) {
                continue;
            }
            Map<String, Object> payload = input(e.payload());
            out.add(new OpsConsolePort.NotificationFailure(DLQ_PREFIX + e.id(), e.createdAt(), e.destination(),
                    e.error(), str(payload.get("templateId")), str(payload.getOrDefault("businessKey", e.rootId())),
                    mask(str(firstOf(payload, "to", "recipient", "mobile", "email")))));
        }
        return out;
    }

    static void resend(RequestContext ctx, OpsConsolePort.NotificationFailure failure, DeadLetterStore dlq, OpsPort ops,
                       OperationLedgerService ledger, ConnectorInvoker invoker, TxSupport tx) {
        String id = failure.id();
        if (id.startsWith(DLQ_PREFIX)) {
            if (dlq == null || ops == null) {
                throw ApiProblem.notInstalled("dead-letter resend (R60)", "sangam-integration");
            }
            long dlqId = Long.parseLong(id.substring(DLQ_PREFIX.length()));
            DeadLetterStore.Entry e = tx.withConnection(c -> dlq.get(c, dlqId))
                    .orElseThrow(() -> ApiProblem.notFound("notification failure", id));
            if (!ctx.tenantId().equals(e.tenantId())) {
                throw ApiProblem.notFound("notification failure", id);
            }
            if (e.kind() != DeadLetterStore.Kind.OUTBOX || e.refId() == null) {
                throw ApiProblem.unprocessable("RESEND_NOT_SUPPORTED", "only dead-lettered outbox deliveries can be resent");
            }
            ops.requeueOutbox(ctx, e.refId(), new OpsPort.OutboxRepairRequest(e.destination(), "notification resend"));
            tx.withConnection(c -> dlq.resolve(c, dlqId, DeadLetterStore.State.REQUEUED, ctx.principalId(),
                    "notification resend"));
            return;
        }
        if (ledger == null || invoker == null) {
            throw ApiProblem.notInstalled("notification resend (R60)", "sangam-integration");
        }
        UUID op;
        try {
            op = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw ApiProblem.unprocessable("RESEND_NOT_SUPPORTED",
                    "the failure is not linked to a ledger operation; resend it from the originating module");
        }
        OperationLedgerService.OperationView view = ledger.view(op)
                .orElseThrow(() -> ApiProblem.unprocessable("RESEND_NOT_SUPPORTED", "no ledger operation " + id));
        ConnectorCallJournal.Call call = view.call();
        if (call == null || !ctx.tenantId().equals(call.tenantId())) {
            throw ApiProblem.unprocessable("RESEND_NOT_SUPPORTED", "no connector call recorded for operation " + id);
        }
        ConnectorInvoker.Outcome outcome = invoker.invoke(new ConnectorInvoker.Invocation(ctx.tenantId(), call.rootId(),
                view.entry().activityInstanceId(), view.entry().loopCounter(), "resend:" + id, call.businessKey(),
                call.connectorId(), call.operation(), input(call.requestJson()), UUID.randomUUID().toString(), 0L,
                Instant.now().plus(Duration.ofSeconds(30))));
        switch (outcome) {
            case ConnectorInvoker.Completed c -> {
                // delivered (or a replay of the earlier resend with the same operation identity)
            }
            case ConnectorInvoker.Failed f -> throw ApiProblem.unprocessable("RESEND_FAILED", f.code() + ": " + f.message());
            case ConnectorInvoker.Retry r -> throw ApiProblem.unavailable("RESEND_RETRY", r.message(),
                    (int) Math.max(1, r.retryAfter() == null ? 30 : r.retryAfter().toSeconds()));
            case ConnectorInvoker.Unknown u -> throw ApiProblem.conflict("RESEND_OUTCOME_UNKNOWN",
                    "the resend outcome is unknown; reconciliation item " + u.reconciliationItemId())
                    .with("reconciliationItemId", u.reconciliationItemId());
            case ConnectorInvoker.Stale s -> throw ApiProblem.conflict("STALE_LEASE", s.message());
        }
    }

    // ------------------------------------------------------------------ helpers

    static UUID operationId(String s) {
        if (s == null || s.isBlank()) {
            throw ApiProblem.unprocessable("LOOKUP_NOT_SUPPORTED", "the reconciliation item has no operationId");
        }
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            throw ApiProblem.unprocessable("LOOKUP_NOT_SUPPORTED", "operationId " + s + " is not a UUID");
        }
    }

    static Map<String, Object> input(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return JSON.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    static Predicate<String> notificationMatcher(String spec) {
        List<Predicate<String>> parts = new ArrayList<>();
        for (String p : spec.split(",")) {
            String s = p.trim().toLowerCase(Locale.ROOT);
            if (s.isEmpty()) {
                continue;
            }
            parts.add(s.endsWith("*") ? d -> d.toLowerCase(Locale.ROOT).startsWith(s.substring(0, s.length() - 1))
                    : d -> d.toLowerCase(Locale.ROOT).equals(s));
        }
        return d -> d != null && parts.stream().anyMatch(x -> x.test(d));
    }

    private static Object firstOf(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            if (m.get(k) != null) {
                return m.get(k);
            }
        }
        return null;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** Recipients are never shown in clear (DPDP): keep the first two and last two characters. */
    static String mask(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        if (s.length() <= 4) {
            return "****";
        }
        return s.substring(0, 2) + "*".repeat(Math.min(8, s.length() - 4)) + s.substring(s.length() - 2);
    }
}

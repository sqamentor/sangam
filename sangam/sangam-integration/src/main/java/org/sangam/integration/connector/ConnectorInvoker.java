// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector;

import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.LookupResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.CanonicalJson;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.OperationLedger;
import org.sangam.core.layer.Transactions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Exactly-once-by-contract execution of one connector call (ARCHITECTURE.md §1.3; SANGAM.md VI.3 rules 4–5, A-2, A-8,
 * R36, R37, R28).
 *
 * <ol>
 *   <li><b>Intent</b> (own short transaction): {@code OPERATION_LEDGER.recordIntent} with the one operationId minted by
 *       the engine (T35; derived only as a logged fallback), the request fingerprint, attemptId and lockVersion (fencing); {@code CONNECTOR_CALL} records the
 *       request and the attempt; {@code OPERATION_DISPATCHED} audit on the root's chain.</li>
 *   <li><b>Decide</b>: a settled operation is never dispatched again; its stored outcome is returned
 *       ({@link Outcome#replayed()}). A repeat attempt follows the capability class: (a) dispatch again with the same
 *       operationId (downstream dedups); (b) look the outcome up first; (c) only if the previous attempt was proven
 *       {@code NO_EFFECT}, otherwise UNKNOWN_OUTCOME and a reconciliation item (no blind retry, R28).</li>
 *   <li><b>Dispatch</b> outside any transaction.</li>
 *   <li><b>Outcome</b> (own short transaction, fenced by lockVersion): success / business failure into the ledger;
 *       an unknown outcome is looked up once for (a)/(b), otherwise {@code markUnknown} opens the reconciliation
 *       item.</li>
 * </ol>
 */
public final class ConnectorInvoker {

    private static final Logger LOG = LoggerFactory.getLogger(ConnectorInvoker.class);

    /** Engine identity and payload of one call. */
    public record Invocation(String tenantId, String rootId, String activityInstanceId, int loopCounter, String retrySafeKey,
                             String businessKey, String connectorId, String operation, Map<String, Object> input,
                             String attemptId, long lockVersion, Instant deadline, UUID explicitOperationId) {

        /** Without a minted id: the identity is derived, with a warning, because it can differ from the engine's (T35). */
        public Invocation(String tenantId, String rootId, String activityInstanceId, int loopCounter, String retrySafeKey,
                          String businessKey, String connectorId, String operation, Map<String, Object> input,
                          String attemptId, long lockVersion, Instant deadline) {
            this(tenantId, rootId, activityInstanceId, loopCounter, retrySafeKey, businessKey, connectorId, operation, input,
                    attemptId, lockVersion, deadline, null);
        }

        /**
         * The ledger key component: the activity instance id, extended by the retry-safe key when one is declared, so
         * the ledger row and the operationId always agree.
         */
        public String ledgerActivityId() {
            return retrySafeKey == null || retrySafeKey.isEmpty() ? activityInstanceId : activityInstanceId + "#" + retrySafeKey;
        }

        public OperationLedger.OperationRef ref() {
            return new OperationLedger.OperationRef(tenantId, rootId, ledgerActivityId(), loopCounter);
        }

        public UUID operationId() {
            return explicitOperationId != null ? explicitOperationId : ref().operationId();
        }
    }

    /** What the caller (worker bridge, delegate) must do next. */
    public sealed interface Outcome permits Completed, Failed, Retry, Unknown, Stale {
        UUID operationId();

        default boolean replayed() {
            return false;
        }
    }

    /** Complete the task with this output. */
    public record Completed(UUID operationId, Map<String, Object> output, String providerRef, boolean duplicate,
                            boolean replayed) implements Outcome {
    }

    /** Raise a BPMN error {@code code}: the effect definitely did not happen. */
    public record Failed(UUID operationId, String code, String message, boolean replayed) implements Outcome {
    }

    /** Report a retryable failure; the ledger stays INTENT and the next attempt reuses the operationId. */
    public record Retry(UUID operationId, String message, Duration retryAfter) implements Outcome {
    }

    /** Hold the work: UNKNOWN_OUTCOME with reconciliation item {@code reconciliationItemId} (null when it pre-existed). */
    public record Unknown(UUID operationId, String reason, Long reconciliationItemId) implements Outcome {
    }

    /** The caller's lease is stale (409 STALE_LEASE): change nothing, drop the work. */
    public record Stale(UUID operationId, String message) implements Outcome {
    }

    private final Transactions tx;
    private final OperationLedger ledger;
    private final ConnectorCallJournal journal;
    private final ConnectorRegistry registry;
    private final AuditChain audit;

    public ConnectorInvoker(Transactions tx, OperationLedger ledger, ConnectorCallJournal journal, ConnectorRegistry registry,
                            AuditChain auditOrNull) {
        this.tx = tx;
        this.ledger = ledger;
        this.journal = journal;
        this.registry = registry;
        this.audit = auditOrNull;
    }

    public ConnectorRegistry registry() {
        return registry;
    }

    /** Request fingerprint (A-2): SHA-256 of JCS({connector, operation, input}). */
    public static byte[] fingerprint(String connectorId, String operation, Map<String, Object> input) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("connector", connectorId);
        m.put("operation", operation);
        m.put("input", input == null ? Map.of() : input);
        return Hashes.sha256(CanonicalJson.write(m));
    }

    public Outcome invoke(Invocation inv) {
        SangamConnector connector = registry.require(inv.tenantId(), inv.connectorId());
        CapabilityClass cls = connector.capabilityClass();
        UUID op = inv.operationId();
        if (inv.explicitOperationId() == null) {
            LOG.warn("connector {} call on root {} has no minted operationId; using the derived id {} (it may differ from the"
                    + " engine's T35 id)", inv.connectorId(), inv.rootId(), op);
        }
        String requestJson = Json.write(inv.input() == null ? Map.of() : inv.input());
        byte[] fp = fingerprint(inv.connectorId(), inv.operation(), inv.input());

        record Decision(Outcome settled, boolean lookupFirst, int attemptNumber) {
        }
        Decision d;
        try {
            d = tx.inTransaction(c -> {
                if (audit != null) {
                    audit.append(c, inv.tenantId(), inv.rootId(), "OPERATION_DISPATCHED", auditPayload(inv, op, cls, null));
                }
                OperationLedger.Intent intent = ledger.recordIntent(c, op, inv.ref(), cls, fp, inv.attemptId(), inv.lockVersion());
                // the ledger key has no tenant part: never replay or settle another tenant's row (ids minted without a tenant)
                ledger.find(c, op).filter(e -> !inv.tenantId().equals(e.tenantId())).ifPresent(e -> {
                    throw new IllegalStateException("operationId " + op + " already belongs to another tenant");
                });
                ConnectorCallJournal.Call prior = journal.recordAttempt(c, op, inv.tenantId(), inv.connectorId(), inv.operation(),
                        inv.rootId(), inv.businessKey(), requestJson);
                if (!intent.mayDispatch()) {
                    return new Decision(stored(op, intent.state(), intent.storedOutcome()), false, intent.attempts());
                }
                if (intent.firstAttempt()) {
                    return new Decision(null, false, 1);
                }
                boolean provenNoEffect = prior.lastResult() == ConnectorCallJournal.LastResult.NO_EFFECT;
                return switch (cls) {
                    case A_IDEMPOTENT_WITH_LOOKUP -> new Decision(null, false, intent.attempts());
                    case B_LOOKUP_ONLY -> new Decision(null, !provenNoEffect, intent.attempts());
                    case C_NEITHER -> {
                        if (provenNoEffect) {
                            yield new Decision(null, false, intent.attempts());
                        }
                        long item = ledger.markUnknown(c, op, inv.lockVersion(),
                                "repeat attempt of a class (c) operation without proof of no effect (last: " + prior.lastResult() + ")");
                        journal.recordResult(c, op, ConnectorCallJournal.LastResult.UNKNOWN, null, "held for reconciliation");
                        yield new Decision(new Unknown(op, "class (c) repeat refused; reconciliation required", item), false,
                                intent.attempts());
                    }
                };
            });
        } catch (OperationLedger.StaleLeaseException e) {
            return new Stale(op, e.getMessage());
        }
        if (d.settled() != null) {
            return d.settled();
        }
        ConnectorRequest request = new ConnectorRequest(inv.tenantId(), op, inv.attemptId(), d.attemptNumber(), inv.rootId(),
                inv.businessKey(), inv.operation(), inv.input(), inv.deadline());

        if (d.lookupFirst()) {
            LookupResult lr = safeLookup(connector, request);
            switch (lr) {
                case LookupResult.Found f -> {
                    return settleFromLookup(inv, op, f);
                }
                case LookupResult.Unavailable u -> {
                    return new Retry(op, "outcome lookup unavailable before controlled retry: " + u.reason(), null);
                }
                case LookupResult.NotFound nf -> LOG.debug("operation {} not found downstream; controlled retry", op);
                case LookupResult.NotSupported ns -> {
                    return markUnknown(inv, op, "class (b) connector " + inv.connectorId() + " has no lookup");
                }
            }
        }

        ConnectorResult result;
        try {
            result = connector.invoke(request);
        } catch (RuntimeException e) {
            // A connector that throws broke the SPI contract; we cannot know whether the effect happened.
            LOG.warn("connector {} threw for operation {}", inv.connectorId(), op, e);
            result = new ConnectorResult.OutcomeUnknown("connector threw " + e.getClass().getSimpleName() + ": " + e.getMessage(), null);
        }
        return record(inv, connector, cls, op, request, result);
    }

    private Outcome record(Invocation inv, SangamConnector connector, CapabilityClass cls, UUID op, ConnectorRequest request,
                           ConnectorResult result) {
        try {
            return switch (result) {
                case ConnectorResult.Success s -> settleSuccess(inv, op, s.output(), s.providerRef(), s.duplicate());
                case ConnectorResult.BusinessFailure f -> settleFailure(inv, op, f.code(), f.message(), f.output());
                case ConnectorResult.TransientFailure t -> {
                    ConnectorCallJournal.LastResult lr = t.noEffect() ? ConnectorCallJournal.LastResult.NO_EFFECT
                            : ConnectorCallJournal.LastResult.MAYBE;
                    if (!t.noEffect() && cls == CapabilityClass.C_NEITHER) {
                        yield markUnknown(inv, op, "failure without proof of no effect on a class (c) connector: " + t.message());
                    }
                    tx.inTransaction(c -> {
                        journal.recordResult(c, op, lr, null, t.message());
                        return null;
                    });
                    yield new Retry(op, t.message(), t.retryAfter());
                }
                case ConnectorResult.OutcomeUnknown u -> {
                    if (cls == CapabilityClass.C_NEITHER) {
                        yield markUnknown(inv, op, u.reason());
                    }
                    LookupResult lr = safeLookup(connector, request);
                    yield switch (lr) {
                        case LookupResult.Found f -> settleFromLookup(inv, op, f);
                        case LookupResult.NotFound nf -> cls == CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP
                                ? retryAfterUnknown(op, u)
                                : markUnknown(inv, op, u.reason() + "; lookup: not found (may still settle)");
                        case LookupResult.Unavailable un -> cls == CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP
                                ? retryAfterUnknown(op, u)
                                : markUnknown(inv, op, u.reason() + "; lookup unavailable: " + un.reason());
                        case LookupResult.NotSupported ns -> markUnknown(inv, op, u.reason());
                    };
                }
            };
        } catch (OperationLedger.StaleLeaseException e) {
            return new Stale(op, e.getMessage());
        }
    }

    private Outcome retryAfterUnknown(UUID op, ConnectorResult.OutcomeUnknown u) {
        tx.inTransaction(c -> {
            journal.recordResult(c, op, ConnectorCallJournal.LastResult.MAYBE, u.providerRef(), u.reason());
            return null;
        });
        return new Retry(op, "outcome unknown; idempotent connector, retry with the same operationId: " + u.reason(), null);
    }

    private Outcome settleFromLookup(Invocation inv, UUID op, LookupResult.Found f) {
        return f.succeeded() ? settleSuccess(inv, op, f.output(), f.providerRef(), true)
                : settleFailure(inv, op, f.failureCode() == null ? "DOWNSTREAM_FAILED" : f.failureCode(), "confirmed by lookup",
                f.output());
    }

    private Outcome settleSuccess(Invocation inv, UUID op, Map<String, Object> output, String providerRef, boolean duplicate) {
        String outcome = successJson(output, providerRef, duplicate);
        tx.inTransaction(c -> {
            if (audit != null) {
                Map<String, Object> p = auditPayload(inv, op, null, "SUCCEEDED");
                p.put("providerRef", providerRef);
                audit.append(c, inv.tenantId(), inv.rootId(), "OPERATION_OUTCOME", p);
            }
            ledger.recordOutcome(c, op, inv.lockVersion(), true, outcome);
            journal.recordResult(c, op, ConnectorCallJournal.LastResult.SUCCESS, providerRef, null);
            return null;
        });
        return new Completed(op, output, providerRef, duplicate, false);
    }

    private Outcome settleFailure(Invocation inv, UUID op, String code, String message, Map<String, Object> output) {
        String outcome = failureJson(code, message, output);
        tx.inTransaction(c -> {
            if (audit != null) {
                Map<String, Object> p = auditPayload(inv, op, null, "FAILED");
                p.put("code", code);
                audit.append(c, inv.tenantId(), inv.rootId(), "OPERATION_OUTCOME", p);
            }
            ledger.recordOutcome(c, op, inv.lockVersion(), false, outcome);
            journal.recordResult(c, op, ConnectorCallJournal.LastResult.BUSINESS_FAILURE, null, code + ": " + message);
            return null;
        });
        return new Failed(op, code, message, false);
    }

    private Outcome markUnknown(Invocation inv, UUID op, String reason) {
        long item = tx.inTransaction(c -> {
            if (audit != null) {
                Map<String, Object> p = auditPayload(inv, op, null, "UNKNOWN_OUTCOME");
                p.put("reason", reason);
                audit.append(c, inv.tenantId(), inv.rootId(), "OPERATION_UNKNOWN", p);
            }
            long id = ledger.markUnknown(c, op, inv.lockVersion(), reason);
            journal.recordResult(c, op, ConnectorCallJournal.LastResult.UNKNOWN, null, reason);
            return id;
        });
        return new Unknown(op, reason, item);
    }

    private static LookupResult safeLookup(SangamConnector connector, ConnectorRequest request) {
        try {
            return connector.lookup(request);
        } catch (RuntimeException e) {
            return new LookupResult.Unavailable("lookup threw " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** Maps a stored ledger outcome back to an {@link Outcome} (replay; never re-dispatches). */
    static Outcome stored(UUID op, OperationLedger.State state, String outcomeJson) {
        Map<String, Object> m = Json.readMap(outcomeJson);
        return switch (state) {
            case SUCCEEDED -> new Completed(op, asMap(m.get("output")), (String) m.get("providerRef"),
                    Boolean.TRUE.equals(m.get("duplicate")), true);
            case FAILED -> new Failed(op, String.valueOf(m.getOrDefault("code", "FAILED")),
                    String.valueOf(m.getOrDefault("message", "")), true);
            case UNKNOWN_OUTCOME -> new Unknown(op, "awaiting reconciliation", null);
            case INTENT -> throw new IllegalStateException("INTENT is not a settled state");
        };
    }

    public static String successJson(Map<String, Object> output, String providerRef, boolean duplicate) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("output", output == null ? Map.of() : output);
        m.put("providerRef", providerRef);
        m.put("duplicate", duplicate);
        return Json.write(m);
    }

    public static String failureJson(String code, String message, Map<String, Object> output) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("message", message);
        m.put("output", output == null ? Map.of() : output);
        return Json.write(m);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> mm ? (Map<String, Object>) mm : Map.of();
    }

    private static Map<String, Object> auditPayload(Invocation inv, UUID op, CapabilityClass cls, String state) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("operationId", op.toString());
        p.put("connector", inv.connectorId());
        p.put("operation", inv.operation());
        p.put("attemptId", inv.attemptId());
        p.put("lockVersion", inv.lockVersion());
        if (cls != null) {
            p.put("capabilityClass", cls.name());
        }
        if (state != null) {
            p.put("state", state);
        }
        return p;
    }
}

// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.sangam.core.layer.CapabilityClass;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.OperationLedger;
import org.sangam.core.layer.ReconciliationQueue;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.WorkPort;

/**
 * The operation ledger on the worker API (SL-16; VI.3 rules 4–5, R37, A-2, A-8, A-14): every external worker (SDK, REST,
 * compatibility endpoints) gets its intent recorded when the server leases the work, and its outcome recorded with the
 * engine command that ends the work, keyed by the one engine operationId ({@code OperationIdentity.forWork}, SL-08).
 * A worker therefore never has to call a ledger API itself, and the A-10 expiry policy and reconciliation see the same
 * operation as for server-side connectors. Every method runs on the caller's (engine) transaction connection.
 *
 * <ul>
 *   <li>lease: {@code recordIntent} (attempt and lock version of the lease) and A-14 binding of the engine work id; a
 *       last attempt that ended in a reported technical failure is reopened (no effect happened);</li>
 *   <li>complete: SUCCEEDED with {@code {output, providerRef, duplicate}}; an operation that already SUCCEEDED (stored
 *       outcome) stays as it is;</li>
 *   <li>fail with no retries left: FAILED ({@value #TECHNICAL_FAILURE}); with retries left the ledger stays INTENT and
 *       the next attempt reuses the operationId; BPMN error: FAILED with the error code;</li>
 *   <li>outcome unknown: UNKNOWN_OUTCOME and a reconciliation item carrying the operationId.</li>
 * </ul>
 */
final class WorkLedger {

    /** Failure code of a worker's reported technical failure (the effect did not happen; a later attempt may run). */
    static final String TECHNICAL_FAILURE = "TECHNICAL_FAILURE";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Engine identity of one lease. */
    record Lease(UUID operationId, String tenantId, String rootId, String activityInstanceId, String capabilityClass,
                 String workId, String attemptId, long lockVersion) {
    }

    private final OperationLedger ledger;

    WorkLedger(Dialect dialect, ReconciliationQueue reconciliation) {
        this.ledger = new OperationLedger(dialect, reconciliation);
    }

    /** R37 on lease. Throws {@link OperationLedger.StaleLeaseException} / {@link IllegalStateException} on a refusal. */
    WorkPort.WorkOperation onLease(Connection c, Lease l) {
        CapabilityClass declared = CapabilityClass.fromDeclaration(l.capabilityClass());
        OperationLedger.OperationRef ref = new OperationLedger.OperationRef(l.tenantId(), l.rootId(), l.activityInstanceId(), 0);
        OperationLedger.Intent i = ledger.recordIntent(c, l.operationId(), ref, declared, null, l.attemptId(), l.lockVersion());
        OperationLedger.Entry e = ledger.find(c, l.operationId()).orElseThrow();
        if (l.tenantId() != null && !l.tenantId().equals(e.tenantId())) {
            // the ledger key has no tenant part: never hand out or settle another tenant's operation
            throw new IllegalStateException("operationId " + l.operationId() + " already belongs to another tenant");
        }
        if (i.state() == OperationLedger.State.FAILED && TECHNICAL_FAILURE.equals(code(i.storedOutcome()))) {
            i = ledger.reopenFailed(c, l.operationId(), l.attemptId(), l.lockVersion());
        }
        ledger.bindEngineWork(c, l.operationId(), l.workId());
        return new WorkPort.WorkOperation(i.state().name(), i.attempts(), e.capabilityClass().name(), i.mayDispatch(),
                parse(i.storedOutcome()));
    }

    /** Ledger outcome of a completion, in the completing transaction. No row: a lease granted before SL-16. */
    void onComplete(Connection c, UUID op, long lockVersion, String outcomeJson) {
        Optional<OperationLedger.Entry> e = op == null ? Optional.empty() : ledger.find(c, op);
        if (e.isEmpty() || e.get().state() == OperationLedger.State.SUCCEEDED) {
            return;
        }
        requireOpen(e.get());
        ledger.recordOutcome(c, op, lockVersion, true, outcomeJson);
    }

    /** Technical failure: FAILED only when the worker gives up (no retries left). */
    void onFail(Connection c, UUID op, long lockVersion, int retriesLeft, String message) {
        if (retriesLeft > 0) {
            return;
        }
        settleFailure(c, op, lockVersion, failureJson(TECHNICAL_FAILURE, message, null));
    }

    /** A BPMN error is a definite business failure of the operation. */
    void onBpmnError(Connection c, UUID op, long lockVersion, String code, String message, Map<String, Object> variables) {
        settleFailure(c, op, lockVersion, failureJson(code, message, variables));
    }

    /**
     * Outcome unknown: the ledger operation goes to UNKNOWN_OUTCOME with a reconciliation item carrying the operationId
     * (so resolution updates the ledger and releases the work). Null when the operation has no ledger row.
     */
    Long onUnknown(Connection c, UUID op, long lockVersion, String workId, Map<String, Object> details) {
        Optional<OperationLedger.Entry> e = op == null ? Optional.empty() : ledger.find(c, op);
        if (e.isEmpty()) {
            return null;
        }
        return ledger.markUnknown(c, op, lockVersion, workId, details);
    }

    /**
     * A repeated completion of work that already completed: the ledger holds this work's SUCCEEDED operation at the
     * same lock version with the identical outcome. Lets the caller answer the retry as the first call was answered.
     */
    boolean isRepeatedCompletion(Connection c, String workId, long lockVersion, String outcomeJson) {
        return ledger.findByEngineWork(c, workId).filter(e -> e.state() == OperationLedger.State.SUCCEEDED
                && e.lockVersion() == lockVersion && Objects.equals(e.outcome(), outcomeJson)).isPresent();
    }

    static String successJson(Map<String, Object> variables, WorkPort.WorkOutcome outcome) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("output", variables == null ? Map.of() : variables);
        m.put("providerRef", outcome == null ? null : outcome.reference());
        m.put("duplicate", false);
        if (outcome != null && outcome.details() != null) {
            m.put("details", outcome.details());
        }
        return PayloadHash.canonical(m);
    }

    static String failureJson(String code, String message, Map<String, Object> output) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("message", message);
        m.put("output", output == null ? Map.of() : output);
        return PayloadHash.canonical(m);
    }

    private void settleFailure(Connection c, UUID op, long lockVersion, String json) {
        Optional<OperationLedger.Entry> e = op == null ? Optional.empty() : ledger.find(c, op);
        if (e.isEmpty()) {
            return;
        }
        requireOpen(e.get());
        ledger.recordOutcome(c, op, lockVersion, false, json);
    }

    private static void requireOpen(OperationLedger.Entry e) {
        if (e.state() != OperationLedger.State.INTENT) {
            throw ApiProblem.conflict("OPERATION_" + e.state().name(), "operation " + e.operationId() + " is "
                    + e.state() + (e.state() == OperationLedger.State.UNKNOWN_OUTCOME ? "; reconciliation decides" : ""));
        }
    }

    private static String code(String json) {
        Object o = parse(json);
        return o instanceof Map<?, ?> m && m.get("code") != null ? String.valueOf(m.get("code")) : null;
    }

    private static Object parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(json, Object.class);
        } catch (java.io.IOException e) {
            return json;
        }
    }
}

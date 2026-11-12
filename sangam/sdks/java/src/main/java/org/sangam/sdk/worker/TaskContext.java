// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.sangam.sdk.SangamClient;
import org.sangam.sdk.log.SangamLogger;
import org.sangam.sdk.model.ExternalWork;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * What a handler sees while it holds the lease: the work item, its identities, the live lease state kept fresh
 * by the heartbeat, cooperative cancellation, a logger pre-filled with the work fields, and the client.
 */
public final class TaskContext {

    private final ExternalWork work;
    private final String workerId;
    private final SangamClient client;
    private final ObjectMapper json;
    private final SangamLogger logger;
    private final Map<String, Object> logFields;
    final AtomicLong lockVersion;
    final AtomicReference<Instant> lockExpiresAt;
    volatile boolean cancelRequested;
    volatile boolean leaseLost;
    volatile boolean shuttingDown;
    volatile String outcomeReference;

    TaskContext(ExternalWork work, String workerId, SangamClient client, ObjectMapper json, SangamLogger logger) {
        this.work = work;
        this.workerId = workerId;
        this.client = client;
        this.json = json;
        this.lockVersion = new AtomicLong(work.lockVersion());
        this.lockExpiresAt = new AtomicReference<>(work.lockExpiresAt());
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("workerId", workerId);
        f.put("workId", work.id());
        f.put("topic", work.topic());
        if (work.operationId() != null) f.put("operationId", work.operationId());
        if (work.attemptId() != null) f.put("attemptId", work.attemptId());
        f.put("lockVersion", work.lockVersion());
        if (work.businessKey() != null) f.put("businessKey", work.businessKey());
        if (work.processInstanceId() != null) f.put("processInstanceId", work.processInstanceId());
        this.logFields = Map.copyOf(f);
        SangamLogger base = logger;
        this.logger = (level, event, message, fields, error) -> {
            Map<String, Object> merged = new LinkedHashMap<>(logFields);
            if (fields != null) merged.putAll(fields);
            base.log(level, event, message, merged, error);
        };
    }

    public ExternalWork work() { return work; }
    public String workId() { return work.id(); }
    public String topic() { return work.topic(); }
    public String businessKey() { return work.businessKey(); }
    public String processInstanceId() { return work.processInstanceId(); }
    public String workerId() { return workerId; }

    /** R36 stable business-operation id; falls back to the work id when the server sent none. */
    public String operationId() {
        return work.operationId() != null ? work.operationId() : work.id();
    }

    /** Idempotency key to forward to downstream systems (= operationId, VI.3.2 rule 4). */
    public String idempotencyKey() {
        return operationId();
    }

    /**
     * W3C {@code traceparent} the engine stored when it created this task (ExternalWork.traceContext, M-062, R13), or
     * null when none was stored. Link the handler's span to it.
     */
    public String traceparent() {
        return work.traceContext() == null ? null : work.traceContext().traceparent();
    }

    /** W3C {@code tracestate} of the task's trace context, or null. */
    public String tracestate() {
        return work.traceContext() == null ? null : work.traceContext().tracestate();
    }

    /** Changes per lease (A-2). Useful for downstream request ids that must differ per attempt. */
    public String attemptId() { return work.attemptId(); }

    /** Current fencing token. */
    public long lockVersion() { return lockVersion.get(); }

    public Instant lockExpiresAt() { return lockExpiresAt.get(); }

    public Map<String, Object> variables() {
        return work.variables() == null ? Map.of() : work.variables();
    }

    /**
     * A variable converted to {@code type}; R33 TypedValue objects are unwrapped first
     * ({@code {"type":"Decimal","value":"12.50"}} with {@code BigDecimal.class} gives 12.50).
     */
    public <T> T variable(String name, Class<T> type) {
        Object v = variables().get(name);
        return org.sangam.sdk.model.TypedValue.unwrap(v, type, json);
    }

    /** True once a heartbeat reported {@code cancelRequested} (VI.3.1 CANCEL_REQUESTED). */
    public boolean isCancelRequested() { return cancelRequested; }

    /** True once the lease is gone (409 stale-lease, 410 gone, or expiry while unreachable); results are discarded. */
    public boolean isLeaseLost() { return leaseLost; }

    public boolean isShuttingDown() { return shuttingDown; }

    /** Cooperative checkpoint: throws {@link TaskCancelledException} if cancel was requested, the lease was lost or the worker stops. */
    public void throwIfCancelled() {
        if (cancelRequested) throw new TaskCancelledException("cancel requested by server");
        if (leaseLost) throw new TaskCancelledException("lease lost");
        if (shuttingDown) throw new TaskCancelledException("worker shutting down");
        if (Thread.currentThread().isInterrupted()) throw new TaskCancelledException("interrupted");
    }

    /** Logger whose lines always carry workerId, workId, topic, operationId, attemptId, lockVersion, businessKey. */
    /**
     * Downstream reference of this work's effect (e.g. a payment reference), sent as {@code outcome.reference} on
     * complete and recorded in the operation ledger (SL-16).
     */
    public void outcomeReference(String reference) { this.outcomeReference = reference; }

    public String outcomeReference() { return outcomeReference; }

    /** The server-recorded ledger entry of {@link #operationId()}; null from servers that do not send it. */
    public org.sangam.sdk.model.WorkOperation operation() { return work.operation(); }

    /**
     * Durable step (M-025, R37): runs {@code call} at most once per successful outcome of this work's operation, as
     * far as the step journal can tell. A step that an earlier attempt recorded {@code SUCCEEDED} is not called again;
     * its recorded result is decoded to {@code type} and returned. The call should send
     * {@link Step#idempotencyKey()} to the remote API.
     *
     * <p><b>Not exactly-once for remote APIs.</b> The journal is written before ({@code INTENT}) and after
     * ({@code SUCCEEDED}) the remote call; a crash between the two leaves {@code INTENT}. The next attempt then looks
     * the outcome up, runs again only when the call is idempotent, or reports outcome-unknown
     * ({@link StepOptions}), never re-issuing blindly. Default options are capability class (c).
     *
     * @param name step name, unique within the work's operation (1-128 printable ASCII)
     * @param type the result type for replay (records, maps, {@code BigDecimal} from R33 typed values); null/Void = none
     */
    public <T> T step(String name, Class<T> type, StepCall<T> call) throws Exception {
        return DurableSteps.run(this, name, type, StepOptions.defaults(), call);
    }

    /** {@link #step(String, Class, StepCall)} with the recovery options of the call's capability class. */
    public <T> T step(String name, Class<T> type, StepOptions<T> options, StepCall<T> call) throws Exception {
        return DurableSteps.run(this, name, type, options, call);
    }

    ObjectMapper json() { return json; }

    public SangamLogger logger() { return logger; }

    public Map<String, Object> logFields() { return logFields; }

    public SangamClient client() { return client; }
}

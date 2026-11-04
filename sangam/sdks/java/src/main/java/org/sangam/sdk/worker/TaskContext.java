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
    public SangamLogger logger() { return logger; }

    public Map<String, Object> logFields() { return logFields; }

    public SangamClient client() { return client; }
}

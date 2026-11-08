// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.worker;

import com.fasterxml.jackson.databind.JsonNode;
import org.sangam.core.api.EngineAdapter.ExternalWork;
import org.sangam.ports.support.Json;
import org.sangam.ports.support.PortsProblem;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * External-worker REST semantics parity (FLOWABLE-INTO-OPERATON FC-36; Flowable {@code flowable-external-job-rest}:
 * {@code POST /acquire/jobs}, {@code /acquire/jobs/{id}/complete|fail|bpmnError|cmmnTerminate}). Sangam's worker API
 * (ARCHITECTURE §4, {@code /api/v1/work}, owned by WS-REST) is the contract; this class translates Flowable's request
 * and response shapes onto it so existing Flowable external-worker clients (Java, JS, Python, .NET, Go) can be pointed
 * at a Sangam compatibility endpoint that WS-REST mounts (suggested {@code /api/v1/compat/flowable/external-job-api}).
 *
 * <p>Semantic mapping:
 * <table>
 *   <tr><th>Flowable</th><th>Sangam</th></tr>
 *   <tr><td>acquire {topic, lockDuration ISO, numberOfTasks, workerId}</td><td>fetch-and-lock {workerId, topics[{name, lockDurationMs}], maxTasks}</td></tr>
 *   <tr><td>job id</td><td>work id; the response carries {@code sangamLockVersion} and {@code sangamAttemptId}</td></tr>
 *   <tr><td>complete {workerId, variables[{name,type,value}]}</td><td>complete {workerId, lockVersion, variables{}}</td></tr>
 *   <tr><td>fail {workerId, errorMessage, errorDetails, retries, retryTimeout}</td><td>fail {workerId, lockVersion, errorMessage, retries, retryBackoffMs}</td></tr>
 *   <tr><td>bpmnError {workerId, errorCode, variables}</td><td>bpmn-error {workerId, lockVersion, errorCode, variables}</td></tr>
 *   <tr><td>cmmnTerminate</td><td>not applicable: CMMN external work runs as a BPMN process task (FLOWABLE-INTO-OPERATON FC-11 pattern); mapped to
 *       complete with variable {@code _cmmnTerminate=true}</td></tr>
 *   <tr><td>HTTP 409 on a stale lock</td><td>409 {@code stale-lease} (identical status)</td></tr>
 * </table>
 *
 * <p><b>Fencing (FW-07).</b> Flowable has no fencing token: its complete/fail compare only the worker id, so a worker
 * whose lease expired and that re-acquired the same job cannot be told apart from its stale self. The translation must
 * not re-create that weakness. {@link #fencingToken} therefore returns the echoed {@code sangamLockVersion} and, when a
 * legacy client does not echo it, throws {@code FENCING_TOKEN_REQUIRED} (409) unless the caller explicitly allows the
 * unfenced fallback ({@link #UNFENCED}); the "current lease of the same worker" fallback is then the caller's
 * documented, opt-in risk. The {@code toComplete/toFail} translations put {@link #UNFENCED} ({@code -1}) into
 * {@code lockVersion} when the client did not echo one, never a guessed version.
 *
 * <p><b>Input guards.</b> {@code workerId} and {@code topic} are required (≤ 255 chars); {@code lockDuration} must be an
 * ISO duration between {@value #MIN_LOCK_MS} ms and {@value #MAX_LOCK_MS} ms; {@code numberOfTasks} is clamped to
 * 1..{@value #MAX_TASKS}; at most {@value #MAX_VARIABLES} variables; reserved variable names ({@code _SANGAM_*},
 * {@code _FLOWABLE_*}) are refused ({@code RESERVED_VARIABLE}); malformed dates/durations are {@code INVALID_INPUT}.
 */
public final class FlowableWorkerCompat {

    /** {@code lockVersion} value meaning "the client sent no fencing token". */
    public static final long UNFENCED = -1L;
    public static final long MIN_LOCK_MS = 1_000L;
    public static final long MAX_LOCK_MS = 24L * 3600 * 1000;
    public static final int MAX_TASKS = 100;
    public static final int MAX_VARIABLES = 500;
    public static final int MAX_ERROR_CHARS = 4_000;

    private FlowableWorkerCompat() {
    }

    /** Flowable acquire request → Sangam fetch-and-lock request body. */
    public static Map<String, Object> toFetchAndLock(String flowableAcquireJson) {
        JsonNode n = Json.tree(flowableAcquireJson);
        long lockMs = n.hasNonNull("lockDuration") ? duration(n.get("lockDuration").asText(), "lockDuration") : 300_000L;
        if (lockMs < MIN_LOCK_MS || lockMs > MAX_LOCK_MS) {
            throw PortsProblem.invalid("lockDuration must be between " + MIN_LOCK_MS + " ms and " + MAX_LOCK_MS + " ms");
        }
        Map<String, Object> topic = new LinkedHashMap<>();
        topic.put("name", PortsProblem.requireText(n.path("topic").asText(null), "topic", 255));
        topic.put("lockDurationMs", lockMs);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("workerId", PortsProblem.requireText(n.path("workerId").asText(null), "workerId", 255));
        out.put("topics", List.of(topic));
        out.put("maxTasks", Math.max(1, Math.min(MAX_TASKS, n.path("numberOfTasks").asInt(1))));
        return out;
    }

    /** Sangam leased work → Flowable {@code AcquiredExternalWorkerJobResponse}. */
    public static Map<String, Object> toFlowableJob(ExternalWork w, String tenantId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", w.id());
        m.put("correlationId", w.operationId());
        m.put("processInstanceId", w.processInstanceId());
        m.put("executionId", w.activityInstanceId());
        m.put("elementId", w.activityId());
        m.put("scopeType", "bpmn");
        m.put("retries", w.retries());
        m.put("lockExpirationTime", w.lockExpiresAt() == null ? null : w.lockExpiresAt().toString());
        m.put("tenantId", tenantId);
        m.put("variables", toFlowableVariables(w.variables()));
        m.put("sangamLockVersion", w.lockVersion());
        m.put("sangamAttemptId", w.attemptId());
        return m;
    }

    /**
     * Flowable complete / bpmnError body → Sangam body. {@code lockVersion} is the echoed {@code sangamLockVersion},
     * else {@code fallbackLockVersion} when it is ≥ 0 (a version the caller knows from its own records), else
     * {@link #UNFENCED}; resolve it with {@link #fencingToken} before calling the worker API.
     */
    public static Map<String, Object> toComplete(String flowableJson, long fallbackLockVersion) {
        JsonNode n = Json.tree(flowableJson);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("workerId", PortsProblem.requireText(n.path("workerId").asText(null), "workerId", 255));
        out.put("lockVersion", echoedLockVersion(n, fallbackLockVersion));
        if (n.hasNonNull("sangamAttemptId")) {
            out.put("attemptId", n.get("sangamAttemptId").asText());
        }
        out.put("variables", fromFlowableVariables(n.path("variables")));
        if (n.hasNonNull("errorCode")) {
            out.put("errorCode", PortsProblem.requireText(n.get("errorCode").asText(), "errorCode", 255));
        }
        return out;
    }

    /** Flowable cmmnTerminate → Sangam complete with the terminate marker. */
    public static Map<String, Object> toCmmnTerminate(String flowableJson, long fallbackLockVersion) {
        Map<String, Object> out = toComplete(flowableJson, fallbackLockVersion);
        @SuppressWarnings("unchecked")
        Map<String, Object> vars = new LinkedHashMap<>((Map<String, Object>) out.get("variables"));
        vars.put("_cmmnTerminate", true);
        out.put("variables", vars);
        return out;
    }

    /** Flowable fail body → Sangam fail body. */
    public static Map<String, Object> toFail(String flowableJson, long fallbackLockVersion) {
        JsonNode n = Json.tree(flowableJson);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("workerId", PortsProblem.requireText(n.path("workerId").asText(null), "workerId", 255));
        out.put("lockVersion", echoedLockVersion(n, fallbackLockVersion));
        if (n.hasNonNull("sangamAttemptId")) {
            out.put("attemptId", n.get("sangamAttemptId").asText());
        }
        String msg = n.path("errorMessage").asText("");
        if (n.hasNonNull("errorDetails")) {
            msg = msg + "\n" + n.get("errorDetails").asText();
        }
        out.put("errorMessage", msg.length() > MAX_ERROR_CHARS ? msg.substring(0, MAX_ERROR_CHARS) : msg);
        int retries = n.has("retries") ? n.get("retries").asInt() : 0;
        out.put("retries", Math.max(0, Math.min(retries, 100)));
        long backoff = n.hasNonNull("retryTimeout") ? duration(n.get("retryTimeout").asText(), "retryTimeout") : 0L;
        out.put("retryBackoffMs", Math.max(0L, Math.min(backoff, MAX_LOCK_MS)));
        return out;
    }

    /**
     * The fencing token to pass to the worker API. Returns the translated {@code lockVersion} when the client echoed
     * one; otherwise throws {@code FENCING_TOKEN_REQUIRED} (409) unless {@code allowUnfencedFallback}, in which case it
     * returns {@link #UNFENCED} and the caller resolves the lease itself (opt-in legacy mode).
     */
    public static long fencingToken(Map<String, Object> translated, boolean allowUnfencedFallback) {
        Object v = translated.get("lockVersion");
        long lv = v instanceof Number num ? num.longValue() : UNFENCED;
        if (lv >= 0) {
            return lv;
        }
        if (allowUnfencedFallback) {
            return UNFENCED;
        }
        throw new PortsProblem("FENCING_TOKEN_REQUIRED", 409, "echo sangamLockVersion from the acquire response; unfenced "
                + "completion is disabled (a re-acquired job cannot be told apart from its stale holder, FW-07)");
    }

    private static long echoedLockVersion(JsonNode n, long fallback) {
        if (n.hasNonNull("sangamLockVersion")) {
            JsonNode v = n.get("sangamLockVersion");
            if (!v.canConvertToLong() || v.asLong() < 0) {
                throw PortsProblem.invalid("sangamLockVersion must be a non-negative integer");
            }
            return v.asLong();
        }
        return fallback >= 0 ? fallback : UNFENCED;
    }

    private static long duration(String iso, String field) {
        try {
            return Duration.parse(iso).toMillis();
        } catch (DateTimeParseException | ArithmeticException e) {
            throw PortsProblem.invalid(field + " is not an ISO-8601 duration: " + iso);
        }
    }

    static List<Map<String, Object>> toFlowableVariables(Map<String, Object> vars) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (vars == null) {
            return out;
        }
        vars.forEach((k, v) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", k);
            m.put("type", flowableType(v));
            m.put("value", v instanceof java.util.Date d ? d.toInstant().toString() : v);
            out.add(m);
        });
        return out;
    }

    static Map<String, Object> fromFlowableVariables(JsonNode arr) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (arr == null || !arr.isArray()) {
            return out;
        }
        if (arr.size() > MAX_VARIABLES) {
            throw new PortsProblem("LIMIT_EXCEEDED", 422, "more than " + MAX_VARIABLES + " variables");
        }
        for (JsonNode v : arr) {
            String name = PortsProblem.requireText(v.path("name").asText(null), "variable name", 255);
            if (PortsProblem.isReservedVariable(name)) {
                throw new PortsProblem("RESERVED_VARIABLE", 422, "variable " + name + " is reserved (FW-30)");
            }
            String type = v.path("type").asText("string");
            JsonNode value = v.get("value");
            boolean none = value == null || value.isNull();
            Object val;
            try {
                val = switch (type) {
                    case "integer", "short" -> none ? null : intValue(value, name);
                    case "long" -> none ? null : longValue(value, name);
                    case "double" -> none ? null : value.asDouble();
                    case "boolean" -> none ? null : value.asBoolean();
                    case "date" -> none ? null : java.util.Date.from(java.time.Instant.parse(value.asText()));
                    case "json" -> none ? null : Json.plain(value);
                    default -> none ? null : value.asText();
                };
            } catch (DateTimeParseException e) {
                throw PortsProblem.invalid("variable " + name + " is not an ISO-8601 instant");
            }
            out.put(name, val);
        }
        return out;
    }

    private static Object intValue(JsonNode value, String name) {
        if (value.isNumber() && !value.canConvertToInt()) {
            throw PortsProblem.invalid("variable " + name + " is out of the integer range");
        }
        return value.asInt();
    }

    private static Object longValue(JsonNode value, String name) {
        if (value.isNumber() && !value.canConvertToLong()) {
            throw PortsProblem.invalid("variable " + name + " is out of the long range");
        }
        return value.asLong();
    }

    static String flowableType(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof Integer || v instanceof Short) {
            return "integer";
        }
        if (v instanceof Long) {
            return "long";
        }
        if (v instanceof Double || v instanceof Float) {
            return "double";
        }
        if (v instanceof Boolean) {
            return "boolean";
        }
        if (v instanceof java.util.Date) {
            return "date";
        }
        if (v instanceof Map || v instanceof List) {
            return "json";
        }
        return "string";
    }
}

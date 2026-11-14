// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.temporal;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.sangam.core.integration.ConnectorDescriptor;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.LookupResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.Json;
import org.sangam.integration.connector.SecretResolver;

/**
 * Owner decision 163 (M-070): start a Temporal workflow and await its result over Temporal's HTTP API (server 1.22 or
 * later), with no Temporal SDK. Operation {@code start}: input {@code workflowType} (or the configured default; when
 * {@code allowedWorkflowTypes} is set only those run), {@code taskQueue} (or the default) and {@code args} (a list) or
 * {@code input} (one value).
 *
 * <ul>
 *   <li><b>Exactly once.</b> The workflow id is the operationId and so is the start {@code requestId}; the reuse policy
 *       is {@code REJECT_DUPLICATE} ("a new execution can't start if any previous execution has the same Workflow Id",
 *       for closed executions within the namespace retention period) and the conflict policy is the default
 *       {@code FAIL} for a running one. Either refusal is {@code ALREADY_EXISTS} (HTTP 409), which means "this
 *       operation already started it", so the connector goes on to await it. A retry later than the namespace
 *       retention period could start a second run. Capability class (a): idempotent, with a lookup.</li>
 *   <li><b>Start:</b> {@code POST /api/v1/namespaces/{ns}/workflows/{workflowId}} (the {@code additional_bindings} of
 *       {@code StartWorkflowExecution} in temporalio/api {@code workflowservice/v1/service.proto}; owner decision 216
 *       lookup, commit 8e0453c3 of 2026-04-30, read 2026-11-13).</li>
 *   <li><b>Await:</b> long polls of {@code GET /api/v1/namespaces/{ns}/workflows/{workflowId}/history} with
 *       {@code waitNewEvent=true} and the close-event filter, until the request deadline (at most
 *       {@code awaitTimeout}); a run that continued as new is followed. Completed is a success with {@code result};
 *       failed, timed out, cancelled and terminated are business failures; no close event before the deadline is a
 *       transient failure with an effect (the retry repeats the de-duplicated start and awaits again).</li>
 *   <li><b>Lookup:</b> the same history call without waiting: no workflow is {@code NotFound}, running is
 *       {@code Unavailable}, closed is {@code Found}.</li>
 *   <li><b>Payloads:</b> JSON values, sent as standard Payload messages ({@code {"payloads":[...]}},
 *       {@code metadata.encoding = json/plain}, Base64 {@code data}) or, with {@code shorthandPayloads}, as a JSON
 *       array of plain values (an argument shaped like a Payload is then refused, because the server would read it as
 *       one). Answers are read in either form ({@link #decodeFirst}); {@code binary/null} is no result, and a result
 *       that cannot be read is {@code TEMPORAL_RESULT_UNREADABLE}, never a success.</li>
 * </ul>
 *
 * Credentials: an optional API key by secret reference, sent as {@code Authorization: Bearer}. The HTTP transport
 * applies the tenant egress policy and the lock rule.
 */
public final class TemporalConnector implements SangamConnector {

    public static final String TYPE = "temporal";
    public static final String OP_START = "start";
    static final String JSON_PLAIN = "json/plain";
    static final String BINARY_NULL = "binary/null";
    private static final String SYSTEM = "Temporal";
    private static final Duration LONG_POLL = Duration.ofSeconds(30);

    /**
     * @param id                   connector instance id
     * @param baseUri              the Temporal HTTP API base (for example {@code https://temporal.example:7243})
     * @param namespace            Temporal namespace
     * @param transport            HTTP transport
     * @param secrets              secret resolver for {@code apiKeyRef}
     * @param apiKeyRef            optional API key reference (Bearer); null = no Authorization header
     * @param defaultWorkflowType  used when the input names none; may be null
     * @param defaultTaskQueue     used when the input names none; may be null
     * @param allowedWorkflowTypes when not empty, the only workflow types this connector starts
     * @param shorthandPayloads    send payloads as plain JSON values instead of Payload messages
     * @param timeout              timeout of one non-waiting call; null = 30 s
     * @param awaitTimeout         longest await of one invoke; null = 5 min
     * @param profile              live or simulator
     * @param clock                time source of the await loop; null = UTC system clock
     */
    public record Config(String id, URI baseUri, String namespace, HttpTransport transport, SecretResolver secrets,
                         String apiKeyRef, String defaultWorkflowType, String defaultTaskQueue,
                         Set<String> allowedWorkflowTypes, boolean shorthandPayloads, Duration timeout,
                         Duration awaitTimeout, ConnectorProfile profile, Clock clock) {
        public Config {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(baseUri, "baseUri");
            Objects.requireNonNull(transport, "transport");
            if (namespace == null || namespace.isBlank()) {
                throw new IllegalArgumentException("namespace is required");
            }
            if (apiKeyRef != null && secrets == null) {
                throw new IllegalArgumentException("apiKeyRef needs a secret resolver");
            }
            allowedWorkflowTypes = allowedWorkflowTypes == null ? Set.of() : Set.copyOf(allowedWorkflowTypes);
            timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
            awaitTimeout = awaitTimeout == null ? Duration.ofMinutes(5) : awaitTimeout;
            profile = profile == null ? ConnectorProfile.LIVE : profile;
            clock = clock == null ? Clock.systemUTC() : clock;
        }
    }

    private final Config config;
    private final ConnectorDescriptor descriptor;

    public TemporalConnector(Config config) {
        this.config = Objects.requireNonNull(config, "config");
        this.descriptor = new ConnectorDescriptor(config.id(), TYPE, CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP,
                List.of(OP_START), config.profile());
    }

    @Override
    public ConnectorDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ConnectorResult invoke(ConnectorRequest request) {
        if (!OP_START.equals(request.operation())) {
            return ConnectorResult.BusinessFailure.of("UNSUPPORTED_OPERATION",
                    "Temporal supports only '" + OP_START + "', got '" + request.operation() + "'");
        }
        ObjectNode body;
        try {
            body = startBody(request);
        } catch (IllegalArgumentException e) {
            return ConnectorResult.BusinessFailure.of("INVALID_REQUEST", e.getMessage());
        }
        String workflowId = request.operationId().toString();
        long started = config.clock().millis();
        Map<String, String> headers;
        try {
            headers = headers(true);
        } catch (RuntimeException e) {
            return ConnectorResult.TransientFailure.noEffect(SYSTEM + ": not sent: " + e.getMessage());
        }
        HttpTransport.Outcome o = config.transport().execute(request.tenantId(), new HttpTransport.Call("POST",
                workflowUri(workflowId, ""), headers, Json.bytes(body), callTimeout(request), true),
                deadline(request));
        if (!(o instanceof HttpTransport.Outcome.Response r)) {
            return transportFailure(o);
        }
        if (!r.is2xx() && r.status() != 409) {
            return startFailure(r);
        }
        return await(request, workflowId, started);
    }

    @Override
    public LookupResult lookup(ConnectorRequest request) {
        String workflowId = request.operationId().toString();
        Map<String, String> headers;
        try {
            headers = headers(false);
        } catch (RuntimeException e) {
            return new LookupResult.Unavailable(SYSTEM + ": " + e.getMessage());
        }
        HttpTransport.Outcome o = config.transport().execute(request.tenantId(), new HttpTransport.Call("GET",
                historyUri(workflowId, false, null), headers, null, callTimeout(request), true), deadline(request));
        if (!(o instanceof HttpTransport.Outcome.Response r)) {
            return new LookupResult.Unavailable(SYSTEM + ": " + describe(o));
        }
        if (r.status() == 404) {
            return new LookupResult.NotFound();
        }
        if (!r.is2xx()) {
            return new LookupResult.Unavailable(SYSTEM + " lookup answered HTTP " + r.status());
        }
        JsonNode close = closeEvent(Json.tree(r.body()));
        if (close == null) {
            return new LookupResult.Unavailable(SYSTEM + ": workflow " + workflowId + " is still running");
        }
        ConnectorResult c = fromClose(workflowId, close);
        if (c instanceof ConnectorResult.Success s) {
            return LookupResult.Found.success(s.output(), s.providerRef());
        }
        if (c instanceof ConnectorResult.BusinessFailure f) {
            return LookupResult.Found.failed(f.code(), f.output());
        }
        return new LookupResult.Unavailable(SYSTEM + ": workflow " + workflowId + " continued as new");
    }

    // ---- start -----------------------------------------------------------------------------------

    ObjectNode startBody(ConnectorRequest request) {
        String type = firstNonBlank(request.str("workflowType"), config.defaultWorkflowType());
        if (type == null) {
            throw new IllegalArgumentException("workflowType is required");
        }
        if (!config.allowedWorkflowTypes().isEmpty() && !config.allowedWorkflowTypes().contains(type)) {
            throw new IllegalArgumentException("workflow type '" + type + "' is not allowed for this connector");
        }
        String queue = firstNonBlank(request.str("taskQueue"), config.defaultTaskQueue());
        if (queue == null) {
            throw new IllegalArgumentException("taskQueue is required");
        }
        List<Object> args = new ArrayList<>();
        Object a = request.input().get("args");
        if (a instanceof List<?> l) {
            args.addAll(l);
        } else if (a != null) {
            throw new IllegalArgumentException("args must be a list");
        } else if (request.input().containsKey("input")) {
            args.add(request.input().get("input"));
        }
        ObjectNode b = Json.MAPPER.createObjectNode();
        b.putObject("workflowType").put("name", type);
        b.putObject("taskQueue").put("name", queue);
        if (!args.isEmpty()) {
            b.set("input", payloads(args));
        }
        b.put("requestId", request.operationId().toString());
        b.put("workflowIdReusePolicy", "WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE");
        b.put("identity", "sangam/" + config.id());
        return b;
    }

    private JsonNode payloads(List<Object> values) {
        ArrayNode list = Json.MAPPER.createArrayNode();
        for (Object v : values) {
            JsonNode json = Json.MAPPER.valueToTree(v);
            if (config.shorthandPayloads()) {
                if (json.isObject() && (json.has("_protoMessageType") || json.has("metadata")
                        && json.size() == (json.has("data") ? 2 : 1))) {
                    // the server could read it as a Payload or a protobuf message, not as this JSON value
                    throw new IllegalArgumentException("an argument object shaped like a Payload or carrying"
                            + " '_protoMessageType' cannot travel as a shorthand payload (turn shorthandPayloads off)");
                }
                list.add(json);
            } else {
                ObjectNode p = list.addObject();
                p.putObject("metadata").put("encoding", b64(JSON_PLAIN.getBytes(StandardCharsets.UTF_8)));
                p.put("data", b64(Json.bytes(json)));
            }
        }
        if (config.shorthandPayloads()) {
            return list;
        }
        ObjectNode wrapper = Json.MAPPER.createObjectNode();
        wrapper.set("payloads", list);
        return wrapper;
    }

    private ConnectorResult startFailure(HttpTransport.Outcome.Response r) {
        int s = r.status();
        String msg = SYSTEM + " start answered HTTP " + s + message(r);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("httpStatus", s);
        return switch (s) {
            case 400 -> new ConnectorResult.BusinessFailure("TEMPORAL_INVALID_REQUEST", msg, out);
            case 401, 403 -> new ConnectorResult.BusinessFailure("TEMPORAL_AUTH", msg, out);
            case 404 -> new ConnectorResult.BusinessFailure("TEMPORAL_NAMESPACE_NOT_FOUND", msg, out);
            case 408, 425, 429, 503 -> new ConnectorResult.TransientFailure(msg, true, r.retryAfter().orElse(null));
            default -> s >= 500 ? new ConnectorResult.TransientFailure(msg, false, r.retryAfter().orElse(null))
                    : new ConnectorResult.BusinessFailure("HTTP_" + s, msg, out);
        };
    }

    // ---- await -----------------------------------------------------------------------------------

    private ConnectorResult await(ConnectorRequest request, String workflowId, long startedMillis) {
        long until = startedMillis + config.awaitTimeout().toMillis();
        if (request.deadline() != null) {
            until = Math.min(until, request.deadline().toEpochMilli());
        }
        String pageToken = null;
        while (config.clock().millis() < until) {
            long left = until - config.clock().millis();
            Duration wait = Duration.ofMillis(Math.max(1, Math.min(left, LONG_POLL.toMillis())));
            HttpTransport.Outcome o;
            try {
                o = config.transport().execute(request.tenantId(), new HttpTransport.Call("GET",
                        historyUri(workflowId, true, pageToken), headers(false), null, wait, true), until);
            } catch (RuntimeException e) {
                return new ConnectorResult.TransientFailure(SYSTEM + ": started workflow " + workflowId
                        + " could not be awaited: " + e.getMessage(), false, Duration.ofSeconds(5));
            }
            if (!(o instanceof HttpTransport.Outcome.Response r) || !r.is2xx()) {
                return new ConnectorResult.TransientFailure(SYSTEM + ": started workflow " + workflowId
                        + " could not be awaited: " + describe(o), false, Duration.ofSeconds(5));
            }
            JsonNode page = Json.tree(r.body());
            JsonNode close = closeEvent(page);
            if (close != null) {
                ConnectorResult c = fromClose(workflowId, close);
                if (c != null) {
                    return c;
                }
                pageToken = null;   // continued as new: await the new run
                continue;
            }
            String next = page == null ? null : text(page, "nextPageToken", "next_page_token");
            pageToken = next == null || next.isBlank() ? null : next;
        }
        return new ConnectorResult.TransientFailure(SYSTEM + ": workflow " + workflowId + " is still running",
                false, Duration.ofSeconds(5));
    }

    /** The close event of a history page, or null. */
    static JsonNode closeEvent(JsonNode page) {
        if (page == null) {
            return null;
        }
        JsonNode events = page.path("history").path("events");
        if (!events.isArray()) {
            return null;
        }
        for (JsonNode e : events) {
            if (kind(e) != null) {
                return e;
            }
        }
        return null;
    }

    /** Normalised close-event kind: COMPLETED, FAILED, TIMEDOUT, CANCELED, TERMINATED, CONTINUEDASNEW, or null. */
    static String kind(JsonNode event) {
        String t = text(event, "eventType", "event_type");
        if (t == null) {
            return null;
        }
        String n = t.toUpperCase(Locale.ROOT).replace("EVENT_TYPE_", "").replace("_", "");
        if (!n.startsWith("WORKFLOWEXECUTION")) {
            return null;
        }
        String k = n.substring("WORKFLOWEXECUTION".length());
        return Set.of("COMPLETED", "FAILED", "TIMEDOUT", "CANCELED", "TERMINATED", "CONTINUEDASNEW").contains(k) ? k : null;
    }

    /** Maps a close event; null for continued-as-new (the caller follows the new run). */
    static ConnectorResult fromClose(String workflowId, JsonNode e) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("workflowId", workflowId);
        String kind = kind(e);
        return switch (kind) {
            case "COMPLETED" -> {
                JsonNode attrs = attributes(e, "workflowExecutionCompletedEventAttributes");
                Object result;
                try {
                    result = decodeFirst(attrs.get("result"));
                } catch (IllegalArgumentException bad) {
                    // completed, but the result cannot be read: never a success without its result
                    yield new ConnectorResult.BusinessFailure("TEMPORAL_RESULT_UNREADABLE",
                            "workflow completed but its result is not a readable Payload: " + bad.getMessage(), out);
                }
                if (result != null) {
                    out.put("result", result);
                }
                yield new ConnectorResult.Success(out, workflowId, false);
            }
            case "FAILED" -> {
                JsonNode attrs = attributes(e, "workflowExecutionFailedEventAttributes");
                String m = attrs.path("failure").path("message").asText("workflow failed");
                out.put("failure", m);
                yield new ConnectorResult.BusinessFailure("TEMPORAL_WORKFLOW_FAILED", m, out);
            }
            case "TIMEDOUT" -> new ConnectorResult.BusinessFailure("TEMPORAL_WORKFLOW_TIMED_OUT", "workflow timed out", out);
            case "CANCELED" -> new ConnectorResult.BusinessFailure("TEMPORAL_WORKFLOW_CANCELED", "workflow cancelled", out);
            case "TERMINATED" -> new ConnectorResult.BusinessFailure("TEMPORAL_WORKFLOW_TERMINATED", "workflow terminated", out);
            default -> null;
        };
    }

    private static JsonNode attributes(JsonNode e, String camel) {
        JsonNode a = e.get(camel);
        if (a == null) {
            a = e.get(camel.replaceAll("([A-Z])", "_$1").toLowerCase(Locale.ROOT));
        }
        return a == null ? Json.MAPPER.createObjectNode() : a;
    }

    /**
     * The first JSON value of a Payloads message, or null (owner decision 216; rules of the server's JSON form,
     * go.temporal.io/api internal/temporalcommonv1/payload_json.go, read 2026-11-13). The HTTP API answers in shorthand
     * unless the request has {@code noPayloadShorthand}: shorthand Payloads are a JSON array whose elements are the plain
     * values ({@code json/plain}), objects carrying {@code _protoMessageType} ({@code json/protobuf}) or, for
     * {@code binary/null}, a metadata-only Payload object; when any payload cannot be shorthand the whole message is the
     * standard object {@code {"payloads":[{"metadata":{..base64..},"data":base64}]}}. So an array element is a value
     * unless it is exactly a metadata-only {@code binary/null} Payload, and an object form is always standard Payloads.
     */
    static Object decodeFirst(JsonNode payloads) {
        if (payloads == null || payloads.isNull()) {
            return null;
        }
        if (payloads.isArray()) {
            if (payloads.isEmpty()) {
                return null;
            }
            JsonNode v = payloads.get(0);
            if (v.isNull() || BINARY_NULL.equals(encodingOf(v)) && v.size() == 1) {
                return null;
            }
            return Json.MAPPER.convertValue(v, Object.class);
        }
        JsonNode list = payloads.path("payloads");
        if (!list.isArray() || list.isEmpty()) {
            return null;
        }
        JsonNode p = list.get(0);
        String encoding = encodingOf(p);
        if (encoding == null) {
            throw new IllegalArgumentException("a standard Payload without metadata.encoding");
        }
        if (BINARY_NULL.equals(encoding)) {
            return null;
        }
        JsonNode data = p.get("data");
        String b64 = data != null && data.isTextual() ? data.asText() : "";
        if (!JSON_PLAIN.equals(encoding)) {
            return Map.of("encoding", encoding, "data", b64);
        }
        JsonNode v = Json.tree(Base64.getDecoder().decode(b64));
        return v == null ? null : Json.MAPPER.convertValue(v, Object.class);
    }

    /** The decoded {@code metadata.encoding} of a standard Payload object, or null. */
    private static String encodingOf(JsonNode p) {
        JsonNode enc = p == null || !p.isObject() ? null : p.path("metadata").get("encoding");
        if (enc == null || !enc.isTextual()) {
            return null;
        }
        try {
            return new String(Base64.getDecoder().decode(enc.asText()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------

    URI workflowUri(String workflowId, String suffix) {
        String base = config.baseUri().toString().replaceAll("/+$", "");
        return URI.create(base + "/api/v1/namespaces/" + enc(config.namespace()) + "/workflows/" + enc(workflowId) + suffix);
    }

    URI historyUri(String workflowId, boolean wait, String pageToken) {
        String q = "?historyEventFilterType=HISTORY_EVENT_FILTER_TYPE_CLOSE_EVENT" + (wait ? "&waitNewEvent=true" : "")
                + (pageToken == null ? "" : "&nextPageToken=" + enc(pageToken));
        return workflowUri(workflowId, "/history" + q);
    }

    private Map<String, String> headers(boolean json) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Accept", "application/json");
        if (json) {
            h.put("Content-Type", "application/json");
        }
        if (config.apiKeyRef() != null) {
            String key = config.secrets().require(config.apiKeyRef());
            if (key == null || key.isBlank()) {
                throw new IllegalStateException("Temporal API key " + config.apiKeyRef() + " resolves to nothing");
            }
            h.put("Authorization", "Bearer " + key);
        }
        return h;
    }

    private Duration callTimeout(ConnectorRequest request) {
        long left = request.remainingMillis(config.timeout().toMillis());
        return Duration.ofMillis(Math.max(1, Math.min(config.timeout().toMillis(), left)));
    }

    private static long deadline(ConnectorRequest request) {
        return request.deadline() == null ? 0 : request.deadline().toEpochMilli();
    }

    private static ConnectorResult transportFailure(HttpTransport.Outcome o) {
        return switch (o) {
            case HttpTransport.Outcome.NoEffect ne ->
                    new ConnectorResult.TransientFailure(SYSTEM + ": not sent: " + ne.reason(), true, null);
            case HttpTransport.Outcome.Unknown u -> new ConnectorResult.OutcomeUnknown(SYSTEM + ": " + u.reason(), null);
            case HttpTransport.Outcome.Response r -> new ConnectorResult.OutcomeUnknown(SYSTEM + ": HTTP " + r.status(), null);
        };
    }

    private static String describe(HttpTransport.Outcome o) {
        return switch (o) {
            case HttpTransport.Outcome.NoEffect ne -> ne.reason();
            case HttpTransport.Outcome.Unknown u -> u.reason();
            case HttpTransport.Outcome.Response r -> "HTTP " + r.status();
        };
    }

    private static String message(HttpTransport.Outcome.Response r) {
        JsonNode n = Json.tree(r.body());
        return n != null && n.path("message").isTextual() ? ": " + n.path("message").asText() : "";
    }

    private static String text(JsonNode n, String camel, String snake) {
        JsonNode v = n.has(camel) ? n.get(camel) : n.get(snake);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a.trim() : b != null && !b.isBlank() ? b.trim() : null;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String b64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }
}

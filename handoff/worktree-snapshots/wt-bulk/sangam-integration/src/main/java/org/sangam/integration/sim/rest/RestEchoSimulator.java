// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.sim.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.sangam.core.layer.Hashes;
import org.sangam.integration.sim.SimHttpServer;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Generic REST endpoint simulator with {@code Idempotency-Key} semantics (A-2 class (a), R36), used by the generic
 * HTTP/REST connector in the SIMULATOR profile.
 *
 * <ul>
 *   <li>{@code POST /api/operations} with header {@code Idempotency-Key} (the operationId) and any JSON body:
 *       <ul>
 *         <li>new key: the effect happens ({@code REST_CALL}, key = Idempotency-Key) → 200
 *             {@code {"id","status":"DONE","duplicate":false,"echo":<body>,"receivedAt"}};</li>
 *         <li>same key and same body (by SHA-256 of the canonical JSON tree): no new effect → 200 with the stored
 *             result and {@code "duplicate":true};</li>
 *         <li>same key, different body → 409 {@code IDEMPOTENCY_KEY_REUSED};</li>
 *         <li>missing key → 400 {@code MISSING_IDEMPOTENCY_KEY}; body not a JSON object → 400 {@code BAD_REQUEST}.</li>
 *       </ul></li>
 *   <li>{@code GET /api/operations/{id}}: outcome lookup → 200 with the stored result, or 404
 *       {@code OPERATION_NOT_FOUND}.</li>
 * </ul>
 * Error bodies follow the other simulators: {@code {"status":"ERROR","errorCode","message"}}.
 */
public final class RestEchoSimulator extends SimHttpServer {

    public static final String OPERATIONS_PATH = "/api/operations";
    public static final String OPERATION_PREFIX = OPERATIONS_PATH + "/";
    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    public static final String EFFECT_REST_CALL = "REST_CALL";

    /** One stored operation. */
    public record StoredOperation(String id, String bodyHash, String resultJson, Instant at) { }

    private static final ObjectMapper JSON = new ObjectMapper();
    /** Sorted object keys, so a body hash does not depend on member order. */
    private static final ObjectMapper CANONICAL = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private final Map<String, StoredOperation> byKey = new LinkedHashMap<>();

    public RestEchoSimulator() {
        super("rest-echo");
        route("POST", OPERATIONS_PATH, this::create);
        prefixRoute("GET", OPERATION_PREFIX, this::get);
    }

    public Optional<StoredOperation> find(String id) {
        synchronized (byKey) {
            return Optional.ofNullable(byKey.get(id));
        }
    }

    public int size() {
        synchronized (byKey) {
            return byKey.size();
        }
    }

    private Response create(Request req) throws Exception {
        String key = req.header(IDEMPOTENCY_HEADER);
        if (key == null || key.isBlank()) {
            return error(400, "MISSING_IDEMPOTENCY_KEY", IDEMPOTENCY_HEADER + " header is required");
        }
        key = key.trim();
        JsonNode body;
        try {
            body = JSON.readTree(req.body());
        } catch (Exception e) {
            body = null;
        }
        if (body == null || !body.isObject()) {
            return error(400, "BAD_REQUEST", "body must be a JSON object");
        }
        String hash = Hashes.hex(Hashes.sha256(CANONICAL.writeValueAsBytes(JSON.treeToValue(body, Object.class))));
        synchronized (byKey) {
            StoredOperation existing = byKey.get(key);
            if (existing != null) {
                if (!existing.bodyHash().equals(hash)) {
                    return error(409, "IDEMPOTENCY_KEY_REUSED", "Idempotency-Key already used with a different body");
                }
                ObjectNode dup = (ObjectNode) JSON.readTree(existing.resultJson());
                dup.put("duplicate", true);
                return Response.json(200, dup.toString());
            }
            Instant now = Instant.now();
            ObjectNode out = JSON.createObjectNode();
            out.put("id", key);
            out.put("status", "DONE");
            out.put("duplicate", false);
            out.set("echo", body);
            out.put("receivedAt", now.toString());
            byKey.put(key, new StoredOperation(key, hash, out.toString(), now));
            ledger().record(EFFECT_REST_CALL, key, Map.of("bodyHash", hash));
            return Response.json(200, out.toString());
        }
    }

    private Response get(Request req) {
        String id = req.pathAfter(OPERATION_PREFIX);
        if (id == null || id.isBlank() || id.contains("/")) {
            return error(404, "OPERATION_NOT_FOUND", "unknown operation");
        }
        Optional<StoredOperation> op = find(id);
        if (op.isEmpty()) {
            return error(404, "OPERATION_NOT_FOUND", "unknown operation " + id);
        }
        return Response.json(200, op.get().resultJson());
    }

    private static Response error(int status, String code, String msg) {
        ObjectNode out = JSON.createObjectNode();
        out.put("status", "ERROR");
        out.put("errorCode", code);
        out.put("message", msg);
        return Response.json(status, out.toString());
    }
}

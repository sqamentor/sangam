// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.contract;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.sangam.integration.connector.Json;

/**
 * M-068: the operations of a stored contract, for the modeller picker and for binding a task to one.
 * <ul>
 *   <li>OpenAPI 3.x: every {@code paths.<path>.<method>} with an {@code operationId}; the request schema is the
 *       {@code application/json} (or {@code +json}) body schema, the response schemas by status code.</li>
 *   <li>AsyncAPI 2.x: {@code channels.<channel>.publish|subscribe} with an {@code operationId}; the message payload.</li>
 *   <li>AsyncAPI 3.x: {@code operations.<id>} (key or {@code operationId}), action send/receive, its channel address
 *       and the payload of its first message.</li>
 * </ul>
 * Only references inside the document ({@code #/...}) are followed; an external reference is refused, so a stored
 * contract never causes an outbound fetch. Schemas are returned with their local references inlined; a recursive schema
 * keeps the recursive {@code $ref} and {@link #document} is supplied for the validator to resolve it.
 */
public final class ContractOperations {

    private static final List<String> METHODS = List.of("get", "put", "post", "delete", "options", "head", "patch", "trace");
    private static final int MAX_DEPTH = 64;

    /** One operation. {@code method}/{@code path} for OpenAPI; {@code action}/{@code channel} for AsyncAPI. */
    public record Operation(String operationId, ApiContractStore.Format format, String method, String path,
                            String action, String channel, String summary, JsonNode requestSchema,
                            Map<String, JsonNode> responseSchemas, List<String> servers, JsonNode document) {
    }

    private ContractOperations() {
    }

    /** All operations of a contract, in document order. */
    public static List<Operation> list(ApiContractStore.ApiContract c) {
        return c.format() == ApiContractStore.Format.OPENAPI ? openApi(c.document())
                : c.specVersion().startsWith("2.") ? asyncApi2(c.document()) : asyncApi3(c.document());
    }

    /** The operation {@code operationId}, if the contract has it. */
    public static Optional<Operation> find(ApiContractStore.ApiContract c, String operationId) {
        return list(c).stream().filter(o -> o.operationId().equals(operationId)).findFirst();
    }

    /** Resolves {@code ref} against {@code store} for the package: the contract version and the operation, or an error. */
    public static Operation resolve(ApiContractStore store, String tenantId, String packageId, OperationRef ref) {
        ApiContractStore.ApiContract c = store.find(tenantId, packageId, ref.contractId(), ref.version())
                .orElseThrow(() -> new IllegalArgumentException("CONTRACT_NOT_FOUND: " + ref.contractId()
                        + (ref.version() == null ? "" : "@" + ref.version())));
        return find(c, ref.operationId()).orElseThrow(() ->
                new IllegalArgumentException("OPERATION_NOT_FOUND: " + ref.operationId() + " in " + ref.contractId()
                        + "@" + c.version()));
    }

    private static List<Operation> openApi(JsonNode doc) {
        List<Operation> out = new ArrayList<>();
        List<String> servers = servers(doc.path("servers"), "url");
        JsonNode paths = doc.path("paths");
        paths.fieldNames().forEachRemaining(path -> {
            JsonNode item = deref(doc, paths.get(path));
            for (String m : METHODS) {
                JsonNode op = item.get(m);
                if (op == null || !op.path("operationId").isTextual()) {
                    continue;
                }
                JsonNode body = deref(doc, op.path("requestBody"));
                JsonNode req = jsonSchema(doc, body.path("content"));
                Map<String, JsonNode> responses = new LinkedHashMap<>();
                JsonNode rs = op.path("responses");
                rs.fieldNames().forEachRemaining(code -> {
                    JsonNode s = jsonSchema(doc, deref(doc, rs.get(code)).path("content"));
                    if (s != null) {
                        responses.put(code, s);
                    }
                });
                List<String> opServers = servers(op.path("servers"), "url");
                out.add(new Operation(op.get("operationId").asText(), ApiContractStore.Format.OPENAPI,
                        m.toUpperCase(Locale.ROOT), path, null, null, text(op, "summary"), req, responses,
                        opServers.isEmpty() ? servers : opServers, doc));
            }
        });
        return out;
    }

    private static List<Operation> asyncApi2(JsonNode doc) {
        List<Operation> out = new ArrayList<>();
        List<String> servers = new ArrayList<>();
        doc.path("servers").forEach(s -> {
            if (s.path("url").isTextual()) {
                servers.add(s.get("url").asText());
            }
        });
        JsonNode channels = doc.path("channels");
        channels.fieldNames().forEachRemaining(ch -> {
            JsonNode item = deref(doc, channels.get(ch));
            for (String action : List.of("publish", "subscribe")) {
                JsonNode op = item.get(action);
                if (op == null || !op.path("operationId").isTextual()) {
                    continue;
                }
                JsonNode msg = deref(doc, op.path("message"));
                if (msg.has("oneOf")) {
                    msg = deref(doc, msg.get("oneOf").path(0));
                }
                out.add(new Operation(op.get("operationId").asText(), ApiContractStore.Format.ASYNCAPI, null, null,
                        action, ch, text(op, "summary"), schema(doc, msg.get("payload")), Map.of(), servers, doc));
            }
        });
        return out;
    }

    private static List<Operation> asyncApi3(JsonNode doc) {
        List<Operation> out = new ArrayList<>();
        List<String> servers = new ArrayList<>();
        doc.path("servers").forEach(s -> {
            if (s.path("host").isTextual()) {
                servers.add(s.path("protocol").asText("") + "://" + s.get("host").asText()
                        + s.path("pathname").asText(""));
            }
        });
        JsonNode ops = doc.path("operations");
        ops.fieldNames().forEachRemaining(key -> {
            JsonNode op = deref(doc, ops.get(key));
            String id = op.path("operationId").isTextual() ? op.get("operationId").asText() : key;
            JsonNode channel = deref(doc, op.path("channel"));
            JsonNode msgs = op.path("messages").isArray() && !op.path("messages").isEmpty() ? op.get("messages")
                    : channel.path("messages");
            JsonNode first = msgs.isArray() ? msgs.path(0) : msgs.isObject() && msgs.fieldNames().hasNext()
                    ? msgs.get(msgs.fieldNames().next()) : null;
            JsonNode msg = first == null ? null : deref(doc, first);
            out.add(new Operation(id, ApiContractStore.Format.ASYNCAPI, null, null, op.path("action").asText(null),
                    channel.path("address").asText(null), text(op, "summary"),
                    msg == null ? null : schema(doc, msg.get("payload")), Map.of(), servers, doc));
        });
        return out;
    }

    private static JsonNode jsonSchema(JsonNode doc, JsonNode content) {
        if (content == null || !content.isObject()) {
            return null;
        }
        if (content.has("application/json")) {
            return schema(doc, content.get("application/json").get("schema"));
        }
        var it = content.fieldNames();
        while (it.hasNext()) {
            String type = it.next();
            if (type.toLowerCase(Locale.ROOT).endsWith("+json")) {
                return schema(doc, content.get(type).get("schema"));
            }
        }
        return null;
    }

    /** {@code node} with every local {@code $ref} inlined; a recursive reference is left as {@code $ref}. */
    static JsonNode schema(JsonNode doc, JsonNode node) {
        return node == null || node.isMissingNode() ? null : inline(doc, node, new IdentityHashMap<>(), 0);
    }

    private static JsonNode inline(JsonNode doc, JsonNode node, Map<JsonNode, Boolean> stack, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("schema is nested too deeply");
        }
        if (node.isObject() && node.path("$ref").isTextual()) {
            JsonNode target = pointer(doc, node.get("$ref").asText());
            if (stack.containsKey(target)) {
                return node.deepCopy();
            }
            stack.put(target, Boolean.TRUE);
            JsonNode r = inline(doc, target, stack, depth + 1);
            stack.remove(target);
            return r;
        }
        if (node.isObject()) {
            ObjectNode o = Json.MAPPER.createObjectNode();
            node.properties().forEach(e -> o.set(e.getKey(), inline(doc, e.getValue(), stack, depth + 1)));
            return o;
        }
        if (node.isArray()) {
            var a = Json.MAPPER.createArrayNode();
            node.forEach(n -> a.add(inline(doc, n, stack, depth + 1)));
            return a;
        }
        return node;
    }

    /** Follows {@code $ref} chains of {@code node} (not its children). */
    static JsonNode deref(JsonNode doc, JsonNode node) {
        JsonNode n = node;
        Set<String> seen = new java.util.HashSet<>();
        while (n != null && n.isObject() && n.path("$ref").isTextual()) {
            String ref = n.get("$ref").asText();
            if (!seen.add(ref)) {
                throw new IllegalArgumentException("circular $ref " + ref);
            }
            n = pointer(doc, ref);
        }
        return n == null ? Json.MAPPER.missingNode() : n;
    }

    private static JsonNode pointer(JsonNode doc, String ref) {
        if (!ref.startsWith("#/") && !ref.equals("#")) {
            throw new IllegalArgumentException("only references inside the document are supported: " + ref);
        }
        String ptr = java.net.URLDecoder.decode(ref.substring(1).replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8);
        JsonNode target = doc.at(ptr);
        if (target.isMissingNode()) {
            throw new IllegalArgumentException("unresolved $ref " + ref);
        }
        return target;
    }

    private static List<String> servers(JsonNode servers, String field) {
        List<String> out = new ArrayList<>();
        servers.forEach(s -> {
            if (s.path(field).isTextual()) {
                out.add(s.get(field).asText());
            }
        });
        return out;
    }

    private static String text(JsonNode n, String field) {
        return n.path(field).isTextual() ? n.get(field).asText() : null;
    }
}

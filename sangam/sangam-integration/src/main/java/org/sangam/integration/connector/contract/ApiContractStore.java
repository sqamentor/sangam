// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.contract;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import org.sangam.integration.connector.Json;

/**
 * M-068: OpenAPI / AsyncAPI documents stored per package, versioned like element templates: a stored version is
 * immutable, {@link #put} of a changed document is the next version, and {@link #put} of an identical document (same
 * SHA-256) returns the stored version. {@link JdbcApiContractStore} persists them in {@code API_CONTRACT} and admits a
 * document only through {@link #accept} (every operation and schema usable); {@link InMemory} serves tests, the dev
 * profile and the simulator and checks the format only ({@link #parse}).
 */
public interface ApiContractStore {

    enum Format { OPENAPI, ASYNCAPI }

    /** One stored version. {@code document} is the parsed JSON document. */
    record ApiContract(String tenantId, String packageId, String contractId, int version, Format format,
                       String specVersion, JsonNode document, String sha256) {
    }

    /** Stores {@code json} (an OpenAPI 3.x or AsyncAPI 2.x/3.x document in JSON) as the next version, or returns the
     *  stored version with the same content. */
    ApiContract put(String tenantId, String packageId, String contractId, String json);

    /** {@link #put(String, String, String, String)} recording who stored a new version (a store may ignore it). */
    default ApiContract put(String tenantId, String packageId, String contractId, String json, String createdBy) {
        return put(tenantId, packageId, contractId, json);
    }

    /**
     * {@link #put(String, String, String, String, String)} that calls {@code onStored} with a NEW version (never for an
     * identical re-send that answers the stored version). A persistent store calls it in the same transaction as the
     * insert, so an exception from it (an audit write that failed) leaves nothing stored. This default, for stores without
     * transactions ({@link InMemory}), calls it after the put.
     */
    default ApiContract put(String tenantId, String packageId, String contractId, String json, String createdBy,
                            java.util.function.Consumer<ApiContract> onStored) {
        Optional<ApiContract> before = find(tenantId, packageId, contractId, null);
        ApiContract stored = put(tenantId, packageId, contractId, json, createdBy);
        if (before.isEmpty() || before.get().version() != stored.version()) {
            onStored.accept(stored);
        }
        return stored;
    }

    /** A version, or the latest when {@code version} is null. */
    Optional<ApiContract> find(String tenantId, String packageId, String contractId, Integer version);

    /** The latest version of each contract of a package, by contract id. */
    List<ApiContract> list(String tenantId, String packageId);

    /** Parses and classifies a document; refuses anything that is not OpenAPI 3.x or AsyncAPI 2.x/3.x JSON. */
    static ApiContract parse(String tenantId, String packageId, String contractId, int version, String json) {
        if (contractId == null || !contractId.matches("[A-Za-z0-9_.-]{1,120}")) {
            throw new IllegalArgumentException("contract id is invalid");
        }
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("contract document is empty");
        }
        JsonNode doc;
        try {
            doc = Json.MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("contract document is not JSON: " + e.getOriginalMessage());
        }
        if (doc == null || !doc.isObject()) {
            throw new IllegalArgumentException("contract document must be a JSON object");
        }
        Format format;
        String spec;
        if (doc.path("openapi").isTextual()) {
            format = Format.OPENAPI;
            spec = doc.get("openapi").asText();
            if (!spec.startsWith("3.")) {
                throw new IllegalArgumentException("only OpenAPI 3.x is supported, not " + spec);
            }
        } else if (doc.path("asyncapi").isTextual()) {
            format = Format.ASYNCAPI;
            spec = doc.get("asyncapi").asText();
            if (!spec.startsWith("2.") && !spec.startsWith("3.")) {
                throw new IllegalArgumentException("only AsyncAPI 2.x and 3.x are supported, not " + spec);
            }
        } else {
            throw new IllegalArgumentException("not an OpenAPI or AsyncAPI document (no openapi/asyncapi field)");
        }
        return new ApiContract(tenantId, packageId, contractId, version, format, spec, doc, sha256(json));
    }

    /**
     * {@link #parse} plus {@link JsonSchemaValidator#checkContract}: a contract a persistent store admits has operations
     * that list and schemas that compile without leaving the document. Any refusal is an {@link IllegalArgumentException}.
     */
    static ApiContract accept(String tenantId, String packageId, String contractId, int version, String json) {
        ApiContract c = parse(tenantId, packageId, contractId, version, json);
        JsonSchemaValidator.checkContract(c);
        return c;
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** In-memory store (tests, dev profile, simulators). Thread-safe. */
    final class InMemory implements ApiContractStore {

        private final Map<String, List<ApiContract>> versions = new LinkedHashMap<>();

        private static String key(String tenantId, String packageId, String contractId) {
            return tenantId + '\u0000' + packageId + '\u0000' + contractId;
        }

        @Override
        public synchronized ApiContract put(String tenantId, String packageId, String contractId, String json) {
            List<ApiContract> list = versions.computeIfAbsent(key(tenantId, packageId, contractId), k -> new ArrayList<>());
            ApiContract next = parse(tenantId, packageId, contractId, list.size() + 1, json);
            if (!list.isEmpty() && list.get(list.size() - 1).sha256().equals(next.sha256())) {
                return list.get(list.size() - 1);
            }
            list.add(next);
            return next;
        }

        @Override
        public synchronized Optional<ApiContract> find(String tenantId, String packageId, String contractId, Integer version) {
            List<ApiContract> list = versions.get(key(tenantId, packageId, contractId));
            if (list == null || list.isEmpty()) {
                return Optional.empty();
            }
            if (version == null) {
                return Optional.of(list.get(list.size() - 1));
            }
            return version >= 1 && version <= list.size() ? Optional.of(list.get(version - 1)) : Optional.empty();
        }

        @Override
        public synchronized List<ApiContract> list(String tenantId, String packageId) {
            String prefix = tenantId + '\u0000' + packageId + '\u0000';
            return versions.entrySet().stream().filter(e -> e.getKey().startsWith(prefix) && !e.getValue().isEmpty())
                    .map(e -> e.getValue().get(e.getValue().size() - 1))
                    .sorted(java.util.Comparator.comparing(ApiContract::contractId)).toList();
        }
    }
}

// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.contract;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.sangam.integration.connector.Json;

/**
 * M-068: {@link SchemaValidator} over networknt json-schema-validator (Apache-2.0).
 * <ul>
 *   <li>Dialect from the contract ({@link #forContract}): OpenAPI 3.0 schemas are draft 4 based, OpenAPI 3.1 uses
 *       2020-12, AsyncAPI's default schema format is a draft 7 superset.</li>
 *   <li>A reference left in the schema (a recursive one, see {@link ContractOperations}) is resolved inside the contract:
 *       the document's top-level members are added to the schema root, so {@code #/components/...} points at the same
 *       node as in the contract.</li>
 *   <li>Nothing is ever fetched: a schema that holds a reference outside the document, an absolute {@code $id} (or
 *       draft 4 {@code id}), or a {@code $schema} other than a built-in json-schema.org dialect is refused before the
 *       library sees it ({@link IllegalArgumentException}).</li>
 *   <li>Format keywords are assertions ({@code date}, {@code email}, ...), so a malformed value is a violation.</li>
 * </ul>
 * Violations are {@code <instance location>: <message>}, sorted, so the answer is stable. Compiled schemas are cached
 * (at most {@value #MAX_CACHED}).
 */
public final class JsonSchemaValidator implements SchemaValidator {

    static final int MAX_CACHED = 256;
    private static final List<String> SCHEMA_KEYWORDS = List.of("type", "properties", "items", "$ref", "allOf", "anyOf",
            "oneOf", "required", "definitions", "$schema");

    private final SpecVersion.VersionFlag dialect;
    private final JsonSchemaFactory factory;
    private final SchemaValidatorsConfig config;
    private final Map<String, JsonSchema> cache = new ConcurrentHashMap<>();

    public JsonSchemaValidator(SpecVersion.VersionFlag dialect) {
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        this.factory = JsonSchemaFactory.getInstance(dialect);
        this.config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(Boolean.TRUE).build();
    }

    /** The validator for the schema dialect of {@code contract}. */
    public static JsonSchemaValidator forContract(ApiContractStore.ApiContract contract) {
        return new JsonSchemaValidator(dialectOf(contract.format(), contract.specVersion()));
    }

    static SpecVersion.VersionFlag dialectOf(ApiContractStore.Format format, String specVersion) {
        if (format == ApiContractStore.Format.OPENAPI) {
            return specVersion != null && specVersion.startsWith("3.0") ? SpecVersion.VersionFlag.V4
                    : SpecVersion.VersionFlag.V202012;
        }
        return SpecVersion.VersionFlag.V7;
    }

    public SpecVersion.VersionFlag dialect() {
        return dialect;
    }

    @Override
    public List<String> validate(JsonNode schema, JsonNode document, JsonNode instance) {
        if (schema == null || schema.isMissingNode() || schema.isNull()) {
            return List.of();   // the operation declares no schema: nothing to check
        }
        JsonSchema compiled = compiled(schema, document);
        List<String> out = new ArrayList<>();
        for (ValidationMessage m : compiled.validate(instance == null ? Json.MAPPER.nullNode() : instance)) {
            out.add(m.getInstanceLocation() + ": " + m.getMessage());
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    /** The compiled (validators initialised) and cached schema; refuses one that refers outside the contract. */
    private JsonSchema compiled(JsonNode schema, JsonNode document) {
        JsonNode root = rooted(schema, document);
        refuseOutsideReferences(root);
        String key = Json.write(root);
        JsonSchema compiled = cache.get(key);
        if (compiled == null) {
            compiled = factory.getSchema(root, config);
            compiled.initializeValidators();
            if (cache.size() >= MAX_CACHED) {
                cache.clear();
            }
            cache.put(key, compiled);
        }
        return compiled;
    }

    /**
     * M-068: what a persistent store checks before it admits a contract. The operations must list
     * ({@link ContractOperations#list}: no reference outside the document, none unresolved or circular), operation ids
     * must be unique (a ref names exactly one), and every request and response schema must stay inside the document and
     * compile in the contract's dialect. Answers normally when the contract is usable; otherwise an
     * {@link IllegalArgumentException} naming the operation and the reason.
     */
    public static void checkContract(ApiContractStore.ApiContract contract) {
        List<ContractOperations.Operation> ops;
        try {
            ops = ContractOperations.list(contract);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("the contract's operations cannot be read: " + e.getMessage(), e);
        }
        JsonSchemaValidator v = forContract(contract);
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (ContractOperations.Operation op : ops) {
            if (!ids.add(op.operationId())) {
                throw new IllegalArgumentException("operation id " + op.operationId() + " occurs more than once");
            }
            v.usable("operation " + op.operationId() + " request schema", op.requestSchema(), contract.document());
            for (Map.Entry<String, JsonNode> r : op.responseSchemas().entrySet()) {
                v.usable("operation " + op.operationId() + " response " + r.getKey() + " schema", r.getValue(),
                        contract.document());
            }
        }
    }

    private void usable(String what, JsonNode schema, JsonNode document) {
        if (schema == null || schema.isMissingNode() || schema.isNull()) {
            return;
        }
        try {
            compiled(schema, document);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(what + ": " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(what + " does not compile as " + dialect + ": " + e.getMessage(), e);
        }
    }

    /** {@code schema} with the document's top-level members it lacks, so a local {@code $ref} resolves as in the contract. */
    static JsonNode rooted(JsonNode schema, JsonNode document) {
        if (!schema.isObject() || document == null || !document.isObject() || !hasRef(schema)) {
            return schema;
        }
        ObjectNode root = ((ObjectNode) schema).deepCopy();
        document.properties().forEach(e -> {
            if (!root.has(e.getKey()) && !e.getKey().equals("$schema") && !e.getKey().equals("$id")) {
                root.set(e.getKey(), e.getValue());
            }
        });
        return root;
    }

    private static boolean hasRef(JsonNode n) {
        if (n.isObject()) {
            if (n.path("$ref").isTextual()) {
                return true;
            }
            for (Map.Entry<String, JsonNode> e : n.properties()) {
                if (hasRef(e.getValue())) {
                    return true;
                }
            }
        } else if (n.isArray()) {
            for (JsonNode c : n) {
                if (hasRef(c)) {
                    return true;
                }
            }
        }
        return false;
    }

    static void refuseOutsideReferences(JsonNode n) {
        if (n.isObject()) {
            JsonNode ref = n.get("$ref");
            if (ref != null && ref.isTextual() && !ref.asText().startsWith("#")) {
                throw new IllegalArgumentException("schema reference outside the contract is not followed: " + ref.asText());
            }
            // an absolute base would make a "#/..." reference point at a remote document; draft 4 spells it "id",
            // counted only next to a schema keyword so a data property or an example named "id" is not mistaken for it
            JsonNode id = n.get("$id");
            JsonNode legacyId = n.get("id");
            if ((id != null && id.isTextual() && id.asText().contains("://")) || (legacyId != null && legacyId.isTextual()
                    && legacyId.asText().contains("://") && SCHEMA_KEYWORDS.stream().anyMatch(n::has))) {
                throw new IllegalArgumentException("schema with an absolute id is not accepted");
            }
            JsonNode meta = n.get("$schema");
            if (meta != null && meta.isTextual() && !meta.asText().matches("https?://json-schema\\.org/.*")) {
                throw new IllegalArgumentException("schema dialect " + meta.asText() + " is not a built-in one");
            }
            n.properties().forEach(e -> refuseOutsideReferences(e.getValue()));
        } else if (n.isArray()) {
            n.forEach(JsonSchemaValidator::refuseOutsideReferences);
        }
    }
}

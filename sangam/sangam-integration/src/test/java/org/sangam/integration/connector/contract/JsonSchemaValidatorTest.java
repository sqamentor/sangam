// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.Test;
import org.sangam.integration.connector.Json;

/** M-068: JSON-Schema validation of a contract operation's request body. */
class JsonSchemaValidatorTest {

    private static final String OPENAPI = """
            {"openapi":"3.0.3","info":{"title":"t","version":"1"},
             "paths":{"/pets":{"post":{"operationId":"addPet",
               "requestBody":{"content":{"application/json":{"schema":{"$ref":"#/components/schemas/Pet"}}}},
               "responses":{"201":{"description":"ok"}}}}},
             "components":{"schemas":{"Pet":{"type":"object","required":["name"],"additionalProperties":false,
               "properties":{"name":{"type":"string","minLength":1},"born":{"type":"string","format":"date"},
                 "parent":{"$ref":"#/components/schemas/Pet"}}}}}}
            """;

    private static JsonNode json(String s) throws Exception {
        return Json.MAPPER.readTree(s);
    }

    private static ContractOperations.Operation addPet(ApiContractStore store) {
        store.put("t1", "p1", "pets", OPENAPI);
        return ContractOperations.resolve(store, "t1", "p1", OperationRef.parse("pets#addPet"));
    }

    @Test
    void aValidBodyHasNoViolations() throws Exception {
        ApiContractStore store = new ApiContractStore.InMemory();
        ContractOperations.Operation op = addPet(store);
        JsonSchemaValidator v = JsonSchemaValidator.forContract(store.find("t1", "p1", "pets", null).orElseThrow());
        assertEquals(SpecVersion.VersionFlag.V4, v.dialect(), "OpenAPI 3.0 schemas are draft 4 based");
        assertEquals(List.of(), v.validate(op.requestSchema(), op.document(), json("{\"name\":\"Rex\",\"born\":\"2020-01-31\"}")));
    }

    @Test
    void violationsAreReportedWithTheirLocationAndSorted() throws Exception {
        ApiContractStore store = new ApiContractStore.InMemory();
        ContractOperations.Operation op = addPet(store);
        JsonSchemaValidator v = JsonSchemaValidator.forContract(store.find("t1", "p1", "pets", null).orElseThrow());
        List<String> errors = v.validate(op.requestSchema(), op.document(), json("{\"born\":\"31-01-2020\",\"x\":1}"));
        assertTrue(errors.size() >= 2, errors.toString());   // name missing, x not allowed (draft 4 has no "date" format)
        assertEquals(errors.stream().sorted().toList(), errors);
        assertTrue(errors.stream().anyMatch(e -> e.contains("name")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("x")), errors.toString());
    }

    @Test
    void aRecursiveReferenceIsResolvedInsideTheContract() throws Exception {
        ApiContractStore store = new ApiContractStore.InMemory();
        ContractOperations.Operation op = addPet(store);
        JsonSchemaValidator v = new JsonSchemaValidator(SpecVersion.VersionFlag.V4);
        assertEquals(List.of(), v.validate(op.requestSchema(), op.document(),
                json("{\"name\":\"a\",\"parent\":{\"name\":\"b\"}}")));
        assertFalse(v.validate(op.requestSchema(), op.document(), json("{\"name\":\"a\",\"parent\":{\"name\":\"\"}}"))
                .isEmpty(), "the nested Pet is checked by the same rules");
    }

    @Test
    void noSchemaMeansNothingToCheck() throws Exception {
        assertEquals(List.of(), new JsonSchemaValidator(SpecVersion.VersionFlag.V202012).validate(null, null, json("{}")));
    }

    @Test
    void aReferenceOutsideTheContractIsRefusedNotFetched() throws Exception {
        JsonSchemaValidator v = new JsonSchemaValidator(SpecVersion.VersionFlag.V202012);
        assertThrows(IllegalArgumentException.class, () -> v.validate(json("{\"$ref\":\"https://evil.example/s.json\"}"),
                null, json("{}")));
        assertThrows(IllegalArgumentException.class, () -> v.validate(
                json("{\"$id\":\"https://evil.example/base\",\"properties\":{\"a\":{\"$ref\":\"#/x\"}}}"), null, json("{}")));
        assertThrows(IllegalArgumentException.class, () -> v.validate(
                json("{\"$schema\":\"https://evil.example/meta\",\"type\":\"object\"}"), null, json("{}")));
    }

    @Test
    void aDataPropertyNamedIdIsNotMistakenForASchemaId() throws Exception {
        JsonSchemaValidator v = new JsonSchemaValidator(SpecVersion.VersionFlag.V4);
        JsonNode schema = json("{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}},"
                + "\"example\":{\"id\":\"https://example.org/x\"}}");
        assertEquals(List.of(), v.validate(schema, null, json("{\"id\":\"https://example.org/x\"}")));
    }

    @Test
    void theDialectFollowsTheContract() {
        assertEquals(SpecVersion.VersionFlag.V202012,
                JsonSchemaValidator.dialectOf(ApiContractStore.Format.OPENAPI, "3.1.0"));
        assertEquals(SpecVersion.VersionFlag.V7, JsonSchemaValidator.dialectOf(ApiContractStore.Format.ASYNCAPI, "3.0.0"));
    }
}

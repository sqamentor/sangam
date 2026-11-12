// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** M-068: operation references, the contract store and operation resolution. */
class ContractOperationsTest {

    private static final String OPENAPI = """
            {"openapi":"3.0.3","info":{"title":"t","version":"1"},
             "servers":[{"url":"https://api.example.org/v1"}],
             "paths":{"/pets/{id}":{
               "get":{"operationId":"getPet","responses":{"200":{"description":"ok",
                 "content":{"application/json":{"schema":{"$ref":"#/components/schemas/Pet"}}}}}},
               "put":{"operationId":"updatePet","requestBody":{"$ref":"#/components/requestBodies/PetBody"},
                 "responses":{"204":{"description":"done"}}}}},
             "components":{
               "requestBodies":{"PetBody":{"content":{"application/merge-patch+json":{"schema":{"$ref":"#/components/schemas/Pet"}}}}},
               "schemas":{"Pet":{"type":"object","required":["name"],
                 "properties":{"name":{"type":"string"},"parent":{"$ref":"#/components/schemas/Pet"}}}}}}
            """;

    private static final String ASYNCAPI2 = """
            {"asyncapi":"2.6.0","info":{"title":"e","version":"1"},
             "channels":{"orders/created":{"publish":{"operationId":"orderCreated",
               "message":{"payload":{"type":"object","properties":{"id":{"type":"string"}}}}}}}}
            """;

    private static final String ASYNCAPI3 = """
            {"asyncapi":"3.0.0","info":{"title":"e","version":"1"},
             "channels":{"created":{"address":"orders.created","messages":{"m":{"$ref":"#/components/messages/Created"}}}},
             "operations":{"sendCreated":{"action":"send","channel":{"$ref":"#/channels/created"}}},
             "components":{"messages":{"Created":{"payload":{"type":"object"}}}}}
            """;

    @Test
    void anOperationRefIsParsedStrictly() {
        OperationRef r = OperationRef.parse("pets@3#getPet");
        assertEquals("pets", r.contractId());
        assertEquals(3, r.version());
        assertEquals("getPet", r.operationId());
        assertNull(OperationRef.parse("pets#getPet").version());
        assertEquals("pets@2#getPet", OperationRef.parse("pets#getPet").pinned(2).toString());
        for (String bad : List.of("pets", "#getPet", "pets@0#x", "pets@x#y", "pe ts#x", "a#b#c")) {
            assertThrows(IllegalArgumentException.class, () -> OperationRef.parse(bad), bad);
        }
    }

    @Test
    void theStoreVersionsChangesAndKeepsAnIdenticalDocument() {
        ApiContractStore store = new ApiContractStore.InMemory();
        ApiContractStore.ApiContract v1 = store.put("t1", "p1", "pets", OPENAPI);
        assertSame(v1, store.put("t1", "p1", "pets", OPENAPI));
        ApiContractStore.ApiContract v2 = store.put("t1", "p1", "pets", OPENAPI.replace("\"t\"", "\"t2\""));
        assertEquals(1, v1.version());
        assertEquals(2, v2.version());
        assertEquals(2, store.find("t1", "p1", "pets", null).orElseThrow().version());
        assertEquals(1, store.find("t1", "p1", "pets", 1).orElseThrow().version());
        assertTrue(store.find("t2", "p1", "pets", null).isEmpty(), "another tenant does not see it");
        assertEquals(1, store.list("t1", "p1").size());
    }

    @Test
    void onlyOpenApi3AndAsyncApi2Or3AreAccepted() {
        ApiContractStore store = new ApiContractStore.InMemory();
        assertThrows(IllegalArgumentException.class, () -> store.put("t", "p", "s", "{\"swagger\":\"2.0\"}"));
        assertThrows(IllegalArgumentException.class, () -> store.put("t", "p", "s", "{\"openapi\":\"2.0\"}"));
        assertThrows(IllegalArgumentException.class, () -> store.put("t", "p", "s", "not json"));
        assertThrows(IllegalArgumentException.class, () -> store.put("t", "p", "bad id", OPENAPI));
    }

    @Test
    void openApiOperationsResolveMethodPathAndInlinedSchemas() {
        ApiContractStore store = new ApiContractStore.InMemory();
        store.put("t1", "p1", "pets", OPENAPI);
        ContractOperations.Operation get = ContractOperations.resolve(store, "t1", "p1", OperationRef.parse("pets#getPet"));
        assertEquals("GET", get.method());
        assertEquals("/pets/{id}", get.path());
        assertEquals(List.of("https://api.example.org/v1"), get.servers());
        assertEquals("object", get.responseSchemas().get("200").path("type").asText());
        ContractOperations.Operation put = ContractOperations.resolve(store, "t1", "p1", OperationRef.parse("pets#updatePet"));
        assertEquals("name", put.requestSchema().path("required").path(0).asText(), "+json body and $ref inlined");
        assertEquals("#/components/schemas/Pet",
                put.requestSchema().path("properties").path("parent").path("$ref").asText(),
                "a recursive reference stays a $ref");
    }

    @Test
    void anUnknownContractOrOperationIsNamed() {
        ApiContractStore store = new ApiContractStore.InMemory();
        store.put("t1", "p1", "pets", OPENAPI);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ContractOperations.resolve(store, "t1", "p1",
                OperationRef.parse("cats#getCat"))).getMessage().startsWith("CONTRACT_NOT_FOUND"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ContractOperations.resolve(store, "t1", "p1",
                OperationRef.parse("pets#deletePet"))).getMessage().startsWith("OPERATION_NOT_FOUND"));
    }

    @Test
    void anExternalReferenceIsRefusedNeverFetched() {
        ApiContractStore store = new ApiContractStore.InMemory();
        store.put("t1", "p1", "ext", OPENAPI.replace("#/components/requestBodies/PetBody", "https://evil.example/b.json"));
        assertThrows(IllegalArgumentException.class, () -> ContractOperations.resolve(store, "t1", "p1",
                OperationRef.parse("ext#updatePet")));
    }

    @Test
    void asyncApiOperationsOfBothMajorVersions() {
        ApiContractStore store = new ApiContractStore.InMemory();
        store.put("t1", "p1", "ev2", ASYNCAPI2);
        store.put("t1", "p1", "ev3", ASYNCAPI3);
        ContractOperations.Operation a2 = ContractOperations.resolve(store, "t1", "p1", OperationRef.parse("ev2#orderCreated"));
        assertEquals("publish", a2.action());
        assertEquals("orders/created", a2.channel());
        assertEquals("object", a2.requestSchema().path("type").asText());
        ContractOperations.Operation a3 = ContractOperations.resolve(store, "t1", "p1", OperationRef.parse("ev3#sendCreated"));
        assertEquals("send", a3.action());
        assertEquals("orders.created", a3.channel());
        assertEquals("object", a3.requestSchema().path("type").asText());
    }
}

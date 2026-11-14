// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.arazzo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.integration.ConnectorDescriptor;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.LookupResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.integration.connector.contract.ApiContractStore;

/**
 * Owner decision 159: an Arazzo step runs one pinned OpenAPI operation of the contract store through the contract's
 * HTTP connector, then checks the success criteria and writes {@code steps.<stepId>.outputs.<name>}.
 */
class ArazzoOperationStepTest {

    private static final String PETS = """
            {"openapi":"3.0.3","info":{"title":"t","version":"1"},
             "paths":{"/pets/{id}":{
               "get":{"operationId":"getPet","responses":{"200":{"description":"ok"}}},
               "put":{"operationId":"updatePet","responses":{"204":{"description":"done"}}}}}}
            """;
    private static final String EVENTS = """
            {"asyncapi":"2.6.0","info":{"title":"e","version":"1"},
             "channels":{"orders":{"publish":{"operationId":"orderCreated","message":{"payload":{"type":"object"}}}}}}
            """;
    private static final UUID OP = UUID.fromString("00000000-0000-0000-0000-000000000159");

    /** Records the HTTP connector input and answers with a fixed result. */
    static final class Http implements SangamConnector {
        final List<ConnectorRequest> calls = new ArrayList<>();
        ConnectorResult answer = ConnectorResult.Success.of(Map.of("status", 200, "body", Map.of("id", "p-7",
                "owner", Map.of("name", "Asha"))), "ref-1");

        @Override
        public ConnectorDescriptor descriptor() {
            return new ConnectorDescriptor("pets-http", "http", null, List.of(), null);
        }

        @Override
        public ConnectorResult invoke(ConnectorRequest request) {
            calls.add(request);
            return answer;
        }
    }

    private ApiContractStore store;
    private Http http;
    private ArazzoOperationStep step;

    @BeforeEach
    void setUp() {
        store = new ApiContractStore.InMemory();
        store.put("t1", "p1", "pets", PETS);
        store.put("t1", "p1", "events", EVENTS);
        http = new Http();
        step = new ArazzoOperationStep("arazzo", store, id -> "pets".equals(id) ? http : null, null);
    }

    private static ConnectorRequest request(Map<String, Object> input) {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("packageId", "p1");
        in.put("stepId", "find");
        in.putAll(input);
        return new ConnectorRequest("t1", OP, "a1", 1, "root", "bk", "invoke", in, null);
    }

    @Test
    void theOperationRunsWithItsParametersAndOutputsBecomeStepVariables() {
        ConnectorResult r = step.invoke(request(Map.of(
                "operationRef", "pets@1#getPet",
                "arazzo.request", """
                        {"parameters":[{"name":"id","in":"path","value":"$inputs.petId"},
                          {"name":"expand","in":"query","value":"owner"},
                          {"name":"X-Trace","in":"header","value":"t-{$inputs.petId}"},
                          {"name":"session","in":"cookie","value":"s1"},{"name":"lang","in":"cookie","value":"en"}]}
                        """,
                "arazzo.successCriteria", "[{\"condition\":\"$statusCode == 200\"}]",
                "arazzo.outputs", "{\"owner\":\"$response.body#/owner/name\",\"id\":\"$response.body#/id\"}",
                "variables", Map.of("petId", "a b/7"))));
        ConnectorResult.Success s = assertInstanceOf(ConnectorResult.Success.class, r);
        ConnectorRequest call = http.calls.get(0);
        assertEquals(OP, call.operationId(), "the step's operationId is the HTTP call's idempotency key");
        assertEquals("GET", call.input().get("method"));
        assertEquals("/pets/a%20b%2F7", call.input().get("path"));
        assertEquals(Map.of("expand", "owner"), call.input().get("query"));
        assertEquals(Map.of("X-Trace", "t-a b/7", "Cookie", "session=s1; lang=en"), call.input().get("headers"));
        assertEquals(200, s.output().get("statusCode"));
        assertEquals(Map.of("owner", "Asha", "id", "p-7"), s.output().get("outputs"));
        assertEquals(Map.of("steps.find.outputs.owner", "Asha", "steps.find.outputs.id", "p-7"),
                s.output().get("variables"));
        assertEquals("ref-1", s.providerRef());
    }

    @Test
    void theBodyIsResolvedAndReplacementsApplied() {
        http.answer = ConnectorResult.Success.of(Map.of("status", 204), null);
        ConnectorResult r = step.invoke(request(Map.of(
                "operationRef", "pets@1#updatePet",
                "arazzo.request", """
                        {"parameters":[{"name":"id","in":"path","value":"7"}],
                         "requestBody":{"contentType":"application/merge-patch+json",
                           "payload":{"name":"$inputs.name","tags":["a","b"]},
                           "replacements":[{"target":"/tags/1","value":"$steps.find.outputs.id"},
                                           {"target":"/status","value":"sold"}]}}
                        """,
                "variables", Map.of("name", "Rex", "steps.find.outputs.id", "p-7"))));
        assertInstanceOf(ConnectorResult.Success.class, r, "no criteria: 2xx is success");
        Map<String, Object> in = http.calls.get(0).input();
        assertEquals("PUT", in.get("method"));
        assertEquals(Map.of("name", "Rex", "tags", List.of("a", "p-7"), "status", "sold"), in.get("body"));
        assertEquals(Map.of("Content-Type", "application/merge-patch+json"), in.get("headers"));
    }

    @Test
    void aFourHundredAnswerIsJudgedByTheCriteria() {
        http.answer = new ConnectorResult.BusinessFailure("HTTP_404", "not found",
                Map.of("status", 404, "body", Map.of("error", "none")));
        Map<String, Object> base = Map.of("operationRef", "pets@1#getPet",
                "arazzo.request", "{\"parameters\":[{\"name\":\"id\",\"in\":\"path\",\"value\":\"9\"}]}");
        Map<String, Object> expectMissing = new LinkedHashMap<>(base);
        expectMissing.put("arazzo.successCriteria", "[{\"condition\":\"$statusCode == 404\"}]");
        assertInstanceOf(ConnectorResult.Success.class, step.invoke(request(expectMissing)));

        ConnectorResult.BusinessFailure f = assertInstanceOf(ConnectorResult.BusinessFailure.class,
                step.invoke(request(base)));
        assertEquals("ARAZZO_CRITERIA_NOT_MET", f.code());
        assertEquals(404, f.output().get("statusCode"));
        assertEquals(Map.of("error", "none"), f.output().get("body"));
    }

    @Test
    void transientAndUnknownOutcomesArePassedOnUnchanged() {
        Map<String, Object> in = Map.of("operationRef", "pets@1#getPet",
                "arazzo.request", "{\"parameters\":[{\"name\":\"id\",\"in\":\"path\",\"value\":\"9\"}]}");
        http.answer = ConnectorResult.TransientFailure.noEffect("refused");
        assertEquals(http.answer, step.invoke(request(in)));
        http.answer = ConnectorResult.OutcomeUnknown.of("timeout after send");
        assertEquals(http.answer, step.invoke(request(in)));
        http.answer = ConnectorResult.BusinessFailure.of("HTTP_EGRESS", "blocked");
        assertEquals(http.answer, step.invoke(request(in)), "a failure without an HTTP status is not a response");
    }

    @Test
    void refusalsHappenBeforeAnyCall() {
        assertCode("OPERATION_REF_NOT_PINNED", Map.of("operationRef", "pets#getPet"));
        assertCode("OPERATION_REF_INVALID", Map.of("operationRef", "pets@1#nope"));
        assertCode("OPERATION_REF_INVALID", Map.of("operationRef", "pets@9#getPet"));
        assertCode("OPERATION_REF_INVALID", Map.of());
        assertCode("ARAZZO_UNSUPPORTED", Map.of("operationRef", "events@1#orderCreated"));
        store.put("t1", "p1", "other", PETS);
        assertCode("CONNECTOR_NOT_CONFIGURED", Map.of("operationRef", "other@1#getPet"));
        assertCode("INVALID_REQUEST", Map.of("operationRef", "pets@1#getPet", "arazzo.request", "{}"));
        assertCode("INVALID_REQUEST", Map.of("operationRef", "pets@1#getPet", "arazzo.request",
                "{\"parameters\":[{\"name\":\"id\",\"in\":\"body\",\"value\":\"1\"}]}"));
        assertCode("INVALID_REQUEST", Map.of("operationRef", "pets@1#getPet", "arazzo.request", "[1]"));
        assertCode("ARAZZO_UNSUPPORTED", Map.of("operationRef", "pets@1#getPet", "arazzo.request",
                "{\"parameters\":[{\"name\":\"id\",\"in\":\"path\",\"value\":\"$request.header.x\"}]}"));
        assertTrue(http.calls.isEmpty(), "nothing is sent for a refused step");
    }

    private void assertCode(String code, Map<?, ?> input) {
        Map<String, Object> in = new LinkedHashMap<>();
        input.forEach((k, v) -> in.put(String.valueOf(k), v));
        ConnectorResult.BusinessFailure f = assertInstanceOf(ConnectorResult.BusinessFailure.class,
                step.invoke(request(in)), code);
        assertEquals(code, f.code(), f::message);
    }

    @Test
    void anUnsupportedCriterionOrOutputIsRefusedAfterTheCall() {
        Map<String, Object> in = new LinkedHashMap<>(Map.of("operationRef", "pets@1#getPet",
                "arazzo.request", "{\"parameters\":[{\"name\":\"id\",\"in\":\"path\",\"value\":\"9\"}]}"));
        in.put("arazzo.outputs", "{\"loc\":\"$response.header.Location\"}");
        assertEquals("ARAZZO_UNSUPPORTED", assertInstanceOf(ConnectorResult.BusinessFailure.class,
                step.invoke(request(in))).code());
    }

    @Test
    void withoutAStepIdNoVariablesAreNamed() {
        Map<String, Object> in = new LinkedHashMap<>(Map.of("operationRef", "pets@1#getPet",
                "arazzo.request", "{\"parameters\":[{\"name\":\"id\",\"in\":\"path\",\"value\":\"9\"}]}",
                "arazzo.outputs", "{\"id\":\"$response.body#/id\"}"));
        in.put("stepId", "");
        ConnectorResult.Success s = assertInstanceOf(ConnectorResult.Success.class, step.invoke(request(in)));
        assertEquals(Map.of("id", "p-7"), s.output().get("outputs"));
        assertEquals(Map.of(), s.output().get("variables"));
    }

    @Test
    void pathTemplatesAreFilledStrictly() {
        assertEquals("/a/1/b/x%3Fy", ArazzoOperationStep.fillPath("/a/{p}/b/{q}", Map.of("p", "1", "q", "x?y")));
        assertThrows(IllegalArgumentException.class, () -> ArazzoOperationStep.fillPath("/a/{p}", Map.of()));
        assertInstanceOf(LookupResult.NotSupported.class, step.lookup(request(Map.of())));
    }
}

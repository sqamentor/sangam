// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.graphql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.integration.connector.Json;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.integration.connector.http.LiveHttpConnectors;

/**
 * M-105 GraphQL connector: one POST of {query, variables, operationName} to the endpoint; with reviewed documents only
 * they run (a free query is refused before anything is sent); an {@code errors} list in a 2xx answer is the business
 * failure {@code GRAPHQL_ERRORS} with the partial data kept; a 2xx without a JSON object is
 * {@code GRAPHQL_INVALID_RESPONSE}.
 */
class GraphQlConnectorTest {

    HttpServer server;
    String base;
    final List<String> bodies = new CopyOnWriteArrayList<>();
    volatile String answer = "{\"data\":{\"order\":{\"id\":\"O-1\"}}}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", x -> {
            bodies.add(new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] b = answer.getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().add("Content-Type", "application/json");
            x.sendResponseHeaders(200, b.length);
            x.getResponseBody().write(b);
            x.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/graphql";
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    GraphQlConnector connector(Map<String, String> documents) {
        LiveHttpConnectors.Spec spec = new LiveHttpConnectors.Spec("orders", base, "POST", "c", null, null, null, null,
                Duration.ofSeconds(2), Duration.ofSeconds(5), 1, List.of(), true, Map.of(), Map.of(), null);
        return new GraphQlConnector(LiveHttpConnectors.build("t1", spec, SecretResolver.of(Map.of()), false), documents);
    }

    static ConnectorRequest req(Map<String, Object> input) {
        UUID op = UUID.randomUUID();
        return new ConnectorRequest("t1", op, op.toString(), 1, null, null, "invoke", new LinkedHashMap<>(input),
                Instant.now().plusSeconds(10));
    }

    @Test
    void aQueryIsPostedWithItsVariablesAndTheDataComesBack() {
        ConnectorResult r = connector(Map.of()).invoke(req(Map.of("query", "query($id: ID!) { order(id: $id) { id } }",
                "variables", Map.of("id", "O-1"), "operationName", "Order")));
        ConnectorResult.Success s = assertInstanceOf(ConnectorResult.Success.class, r);
        assertEquals(Map.of("order", Map.of("id", "O-1")), s.output().get("data"));

        JsonNode sent = Json.tree(bodies.get(0));
        assertTrue(sent.path("query").asText().startsWith("query($id"));
        assertEquals("O-1", sent.path("variables").path("id").asText());
        assertEquals("Order", sent.path("operationName").asText());
    }

    @Test
    void withReviewedDocumentsOnlyTheyRunAndAFreeQueryIsRefusedUnsent() {
        GraphQlConnector c = connector(Map.of("order", "query($id: ID!) { order(id: $id) { id } }"));
        assertEquals(List.of("order"), c.descriptor().operations());

        assertInstanceOf(ConnectorResult.Success.class, c.invoke(req(Map.of("document", "order",
                "variables", Map.of("id", "O-1")))));
        assertEquals("query($id: ID!) { order(id: $id) { id } }", Json.tree(bodies.get(0)).path("query").asText());

        ConnectorResult free = c.invoke(req(Map.of("query", "mutation { dropAll }")));
        assertEquals("INVALID_REQUEST", assertInstanceOf(ConnectorResult.BusinessFailure.class, free).code());
        ConnectorResult unknown = c.invoke(req(Map.of("document", "other")));
        assertEquals("INVALID_REQUEST", assertInstanceOf(ConnectorResult.BusinessFailure.class, unknown).code());
        ConnectorResult badVars = c.invoke(req(Map.of("document", "order", "variables", List.of(1))));
        assertEquals("INVALID_REQUEST", assertInstanceOf(ConnectorResult.BusinessFailure.class, badVars).code());
        assertEquals(1, bodies.size(), "refused calls are never sent");
        assertThrows(IllegalArgumentException.class, () -> connector(Map.of("empty", " ")));
    }

    @Test
    void errorsInA2xxAnswerAreABusinessFailureThatKeepsThePartialData() {
        answer = "{\"data\":{\"order\":null},\"errors\":[{\"message\":\"not allowed\",\"path\":[\"order\"]}]}";
        ConnectorResult r = connector(Map.of()).invoke(req(Map.of("query", "{ order(id: \"x\") { id } }")));
        ConnectorResult.BusinessFailure f = assertInstanceOf(ConnectorResult.BusinessFailure.class, r);
        assertEquals("GRAPHQL_ERRORS", f.code());
        assertTrue(f.message().contains("not allowed"));
        assertTrue(f.output().containsKey("errors"));
        assertTrue(f.output().containsKey("data"));
    }

    @Test
    void anAnswerThatIsNotAJsonObjectIsInvalidAndOtherResultsPassThrough() {
        ConnectorResult notObject = GraphQlConnector.answer(ConnectorResult.Success.of(Map.of("status", 200, "body", "<html/>"),
                null));
        assertEquals("GRAPHQL_INVALID_RESPONSE",
                assertInstanceOf(ConnectorResult.BusinessFailure.class, notObject).code());
        ConnectorResult transientFailure = ConnectorResult.TransientFailure.noEffect("down");
        assertEquals(transientFailure, GraphQlConnector.answer(transientFailure));

        ConnectorResult noData = GraphQlConnector.answer(ConnectorResult.Success.of(Map.of("status", 200,
                "body", Map.of("errors", List.of())), null));
        ConnectorResult.Success s = assertInstanceOf(ConnectorResult.Success.class, noData);
        assertFalse(s.output().containsKey("data"), "an empty errors list is not a failure; absent data stays absent");
    }
}

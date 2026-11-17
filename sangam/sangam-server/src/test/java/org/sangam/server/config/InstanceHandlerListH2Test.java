// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.sangam.integration.connector.Json;
import org.sangam.server.acceptance.AcceptanceIdp;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * M-059 discovery: {@code GET /process-definitions/by-id/handlers} lists the sangam:query and sangam:update handlers a
 * deployed model declares, so a client learns the names it can call on {@code /process-instances/{id}/queries/{name}}
 * and {@code /updates/{name}}. Before the fix the operation was in the contract and every SDK but no controller served
 * it, so the server answered 404 "No such endpoint".
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
@DirtiesContext
class InstanceHandlerListH2Test {

    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry r) {
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> AcceptanceIdp.get().issuer());
        r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> AcceptanceIdp.get().jwksUri());
    }

    @LocalServerPort
    int port;
    @Autowired
    Environment env;
    @Autowired
    ProcessEngine engine;

    static String bpmn(String key) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:sangam="http://sangam.org/schema/1.0/bpmn" targetNamespace="t">
                  <process id="%s" isExecutable="true">
                    <extensionElements>
                      <sangam:update name="correctAddress" activities="scrutiny">
                        <sangam:argument name="address" type="string" required="true"/>
                        <sangam:argument name="note"/>
                        <sangam:set variable="address" expression="args.address"/>
                      </sangam:update>
                      <sangam:query name="feeDue" expression="vars.fee - vars.paid"/>
                    </extensionElements>
                    <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="scrutiny"/>
                    <userTask id="scrutiny"/>
                    <sequenceFlow id="f2" sourceRef="scrutiny" targetRef="e"/><endEvent id="e"/>
                  </process>
                </definitions>
                """.formatted(key);
    }

    String deploy(String tenant) {
        String key = "m059list" + UUID.randomUUID().toString().substring(0, 8);
        engine.getRepositoryService().createDeployment().tenantId(tenant).name(key)
                .addString(key + ".bpmn", bpmn(key)).deploy();
        return engine.getRepositoryService().createProcessDefinitionQuery().processDefinitionKey(key).singleResult()
                .getId();
    }

    HttpResponse<String> list(String definitionId) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/api/v1/process-definitions/by-id/handlers?id="
                        + URLEncoder.encode(definitionId, StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + env.getRequiredProperty("sangam.security.dev-token.token"))
                .header("Accept", "application/json").GET().build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void theDeclaredQueriesThenUpdatesAreListedWithTheirArguments() throws Exception {
        String id = deploy("dev");
        HttpResponse<String> r = list(id);
        assertEquals(200, r.statusCode(), r.body());
        JsonNode a = Json.tree(r.body());
        assertEquals(2, a.size(), r.body());

        JsonNode q = a.get(0);
        assertEquals("feeDue", q.path("name").asText(), r.body());
        assertEquals("QUERY", q.path("kind").asText(), r.body());
        assertEquals(0, q.path("parameters").size(), r.body());

        JsonNode u = a.get(1);
        assertEquals("correctAddress", u.path("name").asText(), r.body());
        assertEquals("UPDATE", u.path("kind").asText(), r.body());
        assertEquals("address", u.path("parameters").get(0).path("name").asText(), r.body());
        assertEquals("string", u.path("parameters").get(0).path("type").asText(), r.body());
        assertEquals(true, u.path("parameters").get(0).path("required").asBoolean(), r.body());
        assertEquals("note", u.path("parameters").get(1).path("name").asText(), r.body());
        assertEquals("any", u.path("parameters").get(1).path("type").asText(), r.body());
        assertEquals(false, u.path("parameters").get(1).path("required").asBoolean(), r.body());
        assertEquals(List.of("address"), Json.MAPPER.convertValue(u.path("changes"), List.class), r.body());
    }

    @Test
    void anUnknownDefinitionOrOneOfAnotherTenantIsNotFound() throws Exception {
        HttpResponse<String> unknown = list("m059list:1:" + UUID.randomUUID());
        assertEquals(404, unknown.statusCode(), unknown.body());
        assertEquals("NOT_FOUND", Json.tree(unknown.body()).path("code").asText(), unknown.body());

        HttpResponse<String> other = list(deploy("other-tenant"));
        assertEquals(404, other.statusCode(), other.body());
    }
}

// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.enginerest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.ws.rs.core.Response;

import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.exception.NotFoundException;
import org.operaton.bpm.engine.exception.NullValueException;
import org.operaton.bpm.engine.rest.exception.InvalidRequestException;
import org.sangam.rest.port.SangamPrincipal;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * SL-72: for a tenant identity a missing id and another tenant's id get the same answer on {@code /engine-rest}: 404,
 * one body, no engine code, no id. Platform identities keep Operaton's answers. The cross-tenant exception is
 * Operaton's {@code ENGINE-13034} text as {@code CommandLogger#exceptionCommandWithUnauthorizedTenant} formats it.
 */
class EngineRestNotFoundTest {

    static final String ID = "T-4711";
    static final ProcessEngineException CROSS_TENANT = new ProcessEngineException(
            "ENGINE-13034 Cannot get the task '" + ID + "' because it belongs to no authenticated tenant.");

    static MockHttpServletRequest request(Set<String> groups) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/engine-rest/task/" + ID + "/variables");
        r.setAttribute(SangamPrincipal.ATTRIBUTE, new SangamPrincipal("tenant-a", "alice", groups, "OIDC", Map.of()));
        return r;
    }

    static Response engine(MockHttpServletRequest r, ProcessEngineException e) {
        EngineRestProblemMappers.Engine m = new EngineRestProblemMappers.Engine();
        m.request = r;
        return m.toResponse(e);
    }

    static Response rest(MockHttpServletRequest r, InvalidRequestException e) {
        EngineRestProblemMappers.Rest m = new EngineRestProblemMappers.Rest();
        m.request = r;
        return m.toResponse(e);
    }

    static Response any(MockHttpServletRequest r, Throwable e) {
        EngineRestProblemMappers.Any m = new EngineRestProblemMappers.Any();
        m.request = r;
        return m.toResponse(e);
    }

    @Test
    void aTenantIdentityGetsTheSameAnswerForAMissingIdAndAnotherTenantsId() {
        MockHttpServletRequest tenant = request(Set.of("sangam-engine-admin"));
        List<Response> answers = List.of(
                engine(tenant, CROSS_TENANT),
                any(tenant, new RuntimeException("wrapped", CROSS_TENANT)),
                engine(tenant, new NullValueException("task " + ID + " doesn't exist: task is null")),
                engine(tenant, new NotFoundException("No task found for id " + ID)),
                rest(tenant, new InvalidRequestException(Response.Status.NOT_FOUND, "No matching task with id " + ID)),
                rest(tenant, new InvalidRequestException(Response.Status.INTERNAL_SERVER_ERROR, CROSS_TENANT, "x " + ID)));
        byte[] first = (byte[]) answers.get(0).getEntity();
        for (Response r : answers) {
            assertEquals(404, r.getStatus());
            assertEquals("application/problem+json", r.getMediaType().toString());
            assertArrayEquals(first, (byte[]) r.getEntity(), "one body for every not-found");
        }
        String body = new String(first, StandardCharsets.UTF_8);
        assertFalse(body.contains(ID), "no id echo: " + body);
        assertFalse(body.contains("ENGINE-"), "no engine code: " + body);
        assertTrue(body.contains("\"NOT_FOUND\""), body);
    }

    @Test
    void aRequestWithoutAPrincipalIsTreatedAsATenantIdentity() {
        assertEquals(404, engine(new MockHttpServletRequest("GET", "/engine-rest/task/" + ID), CROSS_TENANT).getStatus());
    }

    @Test
    void platformIdentitiesKeepOperatonsAnswers() {
        MockHttpServletRequest platform = request(Set.of("sangam-admin", "sangam-platform"));
        Response cross = engine(platform, CROSS_TENANT);
        assertEquals(500, cross.getStatus(), "Operaton's own status for the refusal");
        assertNotEquals("application/problem+json", String.valueOf(cross.getMediaType()));
        assertEquals(404, rest(platform, new InvalidRequestException(Response.Status.NOT_FOUND, "No matching task with id "
                + ID)).getStatus());
        assertEquals(400, engine(platform, new NotFoundException("No task found for id " + ID)).getStatus(),
                "Operaton maps NotFoundException (a BadUserRequestException) to 400 for platform callers, unchanged");
    }

    @Test
    void otherErrorsOfATenantIdentityAreUnchanged() {
        MockHttpServletRequest tenant = request(Set.of("sangam-engine-admin"));
        assertEquals(500, engine(tenant, new ProcessEngineException("ENGINE-03004 some other failure")).getStatus());
        assertEquals(400, rest(tenant, new InvalidRequestException(Response.Status.BAD_REQUEST, "bad")).getStatus());
    }
}

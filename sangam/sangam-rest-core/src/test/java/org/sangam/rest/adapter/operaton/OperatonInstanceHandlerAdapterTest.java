// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sangam.engine.handlers.InstanceHandlerException;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.InstanceHandlerPort;
import org.sangam.rest.port.RequestContext;

/** M-059 adapter rules that hold before the engine is touched. */
class OperatonInstanceHandlerAdapterTest {

    private static final RequestContext CTX = new RequestContext("t1", "u1", Set.of("b", "a"), "seat-1", null,
            null, null, null, null);

    @Test
    void handlersSeeBusinessVariablesOnly() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("fee", 10L);
        vars.put("sangamCaseVersion", 3L);
        vars.put("sangamPackageHash", "abc");
        assertEquals(Map.of("fee", 10L), OperatonInstanceHandlerAdapter.business(vars));
    }

    @Test
    void identityIsTheCallerWithSortedGroups() {
        Map<String, Object> id = OperatonInstanceHandlerAdapter.identity(CTX);
        assertEquals("t1", id.get("tenantId"));
        assertEquals("u1", id.get("principalId"));
        assertEquals("seat-1", id.get("seatId"));
        assertEquals(List.of("a", "b"), id.get("groups"));
    }

    @Test
    void handlerErrorsAre422WithTheirCode() {
        ApiProblem p = OperatonInstanceHandlerAdapter.problem(new InstanceHandlerException("VALIDATION_FAILED", "bad"));
        assertEquals(422, p.status());
        assertEquals("VALIDATION_FAILED", p.code());
    }

    @Test
    void anUpdateWithoutAReasonIsRefusedBeforeAnyEngineCall() {
        OperatonInstanceHandlerAdapter a = new OperatonInstanceHandlerAdapter(null, null, null, null, null, null);
        ApiProblem p = assertThrows(ApiProblem.class, () -> a.update(CTX, "pi", "u",
                new InstanceHandlerPort.UpdateRequest(Map.of(), " ", null, null, null)));
        assertEquals(400, p.status());
        assertEquals("REASON_REQUIRED", p.code());
    }
}

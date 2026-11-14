// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.ProcessContinuationPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * Decision 149 controller: the endpoint permission is process.continue for the command and process.read for the
 * chain, checked before anything reaches the engine; request id and reason are required; a host without the engine
 * port answers 501 CAPABILITY_NOT_INSTALLED.
 */
class ProcessContinuationControllerTest {

    static final RequestContext CTX = new RequestContext("t1", "alice", Set.of(), null, null, null, null, "127.0.0.1",
            "OIDC");

    final List<String> calls = new ArrayList<>();

    final ProcessContinuationPort.ContinueAsNewResult result = new ProcessContinuationPort.ContinueAsNewResult("c-1",
            "pi-1", "pi-2", "pi-1", 1, "case-7", "def:1", "CREATED", "alice", "year end",
            Instant.parse("2026-11-13T00:00:00Z"), List.of(new ProcessContinuationPort.ContinuationEnded("USER_TASK", 1)));

    final ProcessContinuationPort.ProcessContinuationChain chain = new ProcessContinuationPort.ProcessContinuationChain(
            "pi-1", List.of("pi-1", "pi-2"), List.of());

    final ProcessContinuationPort port = new ProcessContinuationPort() {
        @Override
        public ContinueAsNewResult continueAsNew(RequestContext ctx, String id, ContinueAsNewRequest request) {
            calls.add("continue:" + id + ":" + request.continuationRequestId());
            return result;
        }

        @Override
        public ProcessContinuationChain chain(RequestContext ctx, String id) {
            calls.add("chain:" + id);
            return chain;
        }
    };

    static PermissionPort holding(String... granted) {
        Set<String> set = Set.of(granted);
        return new PermissionPort() {
            @Override
            public boolean has(RequestContext ctx, String permission) {
                return set.contains(permission);
            }

            @Override
            public Set<String> permissions(RequestContext ctx) {
                return set;
            }

            @Override
            public boolean mayReplay(RequestContext ctx, String commandType, String resourceRef) {
                return false;
            }
        };
    }

    ProcessContinuationController controller(ProcessContinuationPort p, PermissionPort permissions) {
        StaticListableBeanFactory bf = new StaticListableBeanFactory();
        if (p != null) {
            bf.addBean("continuations", p);
        }
        return new ProcessContinuationController(bf.getBeanProvider(ProcessContinuationPort.class), permissions);
    }

    static ProcessContinuationPort.ContinueAsNewRequest req(String id, String reason) {
        return new ProcessContinuationPort.ContinueAsNewRequest(id, reason, null, List.of("limit"), Map.of());
    }

    @Test
    void continueAsNewNeedsProcessContinueAndReachesThePort() {
        assertEquals("process.continue", ProcessContinuationController.PROCESS_CONTINUE);
        ApiProblem denied = assertThrows(ApiProblem.class, () -> controller(port, holding(Permissions.PROCESS_MODIFY,
                Permissions.PROCESS_READ)).continueAsNew(CTX, "pi-1", req("c-1", "year end")));
        assertEquals(403, denied.status());
        assertEquals("ACTION_NOT_PERMITTED", denied.code());
        assertTrue(calls.isEmpty(), "nothing reaches the engine without the permission");

        assertSame(result, controller(port, holding("process.continue")).continueAsNew(CTX, "pi-1",
                req("c-1", "year end")));
        assertEquals(List.of("continue:pi-1:c-1"), calls);
    }

    @Test
    void theRequestIdAndTheReasonAreRequired() {
        ProcessContinuationController c = controller(port, holding("process.continue"));
        assertEquals("VALIDATION_FAILED", assertThrows(ApiProblem.class,
                () -> c.continueAsNew(CTX, "pi-1", null)).code());
        for (String id : new String[] {null, " ", "x".repeat(256)}) {
            ApiProblem p = assertThrows(ApiProblem.class, () -> c.continueAsNew(CTX, "pi-1", req(id, "why")));
            assertEquals(400, p.status());
            assertEquals("VALIDATION_FAILED", p.code());
        }
        ApiProblem noReason = assertThrows(ApiProblem.class, () -> c.continueAsNew(CTX, "pi-1", req("c-1", " ")));
        assertEquals(400, noReason.status());
        assertEquals("REASON_REQUIRED", noReason.code());
        assertTrue(calls.isEmpty());
        c.continueAsNew(CTX, "pi-1", req("x".repeat(255), "why"));
        assertEquals(1, calls.size(), "255 characters is the contract maximum");
    }

    @Test
    void theChainNeedsProcessRead() {
        ApiProblem denied = assertThrows(ApiProblem.class,
                () -> controller(port, holding("process.continue")).chain(CTX, "pi-2"));
        assertEquals(403, denied.status());
        assertSame(chain, controller(port, holding(Permissions.PROCESS_READ)).chain(CTX, "pi-2"));
        assertEquals(List.of("chain:pi-2"), calls);
    }

    @Test
    void aHostWithoutTheEnginePortAnswers501() {
        ProcessContinuationController c = controller(null, holding("process.continue", Permissions.PROCESS_READ));
        ApiProblem p = assertThrows(ApiProblem.class, () -> c.continueAsNew(CTX, "pi-1", req("c-1", "why")));
        assertEquals(501, p.status());
        assertEquals("CAPABILITY_NOT_INSTALLED", p.code());
        assertEquals(501, assertThrows(ApiProblem.class, () -> c.chain(CTX, "pi-1")).status());
    }
}

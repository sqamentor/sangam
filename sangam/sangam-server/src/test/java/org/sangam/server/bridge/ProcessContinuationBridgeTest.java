// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.exception.NotFoundException;
import org.sangam.core.security.SangamSecurityException;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.engine.continuation.ContinuationNotAllowedException;
import org.sangam.engine.continuation.ProcessContinuations;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.ProcessContinuationPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.server.config.ProcessContinuationWiring;

/**
 * Decision 149 bridge: the engine result maps field for field onto the contract shapes, the caller's identity reaches
 * the engine, and each engine refusal is the contract's problem (409 CONTINUATION_NOT_ALLOWED with reason and
 * successorId, 404 for an instance or a named definition, 403, 400 VALIDATION_FAILED); anything else passes unchanged.
 */
class ProcessContinuationBridgeTest {

    static final RequestContext CTX = new RequestContext("t1", "alice", Set.of(), null, null, null, null, "127.0.0.1",
            "OIDC");
    static final Instant AT = Instant.parse("2026-11-13T00:00:00Z");

    final AtomicReference<SecurityIdentity> actor = new AtomicReference<>();
    final AtomicReference<ProcessContinuations.Request> seen = new AtomicReference<>();

    ProcessContinuationBridge bridge(RuntimeException failure) {
        return new ProcessContinuationBridge(new ProcessContinuationBridge.Continuations() {
            @Override
            public ProcessContinuations.Result continueAsNew(SecurityIdentity a, String id, ProcessContinuations.Request r) {
                actor.set(a);
                seen.set(r);
                if (failure != null) {
                    throw failure;
                }
                return new ProcessContinuations.Result(r.continuationRequestId(), id, "pi-2", "pi-0", 2, "case-7",
                        "def:3", ProcessContinuations.Outcome.CREATED, "alice", r.reason(), AT,
                        List.of(new ProcessContinuations.Ended(ProcessContinuations.EndedKind.USER_TASK, 1),
                                new ProcessContinuations.Ended(ProcessContinuations.EndedKind.TIMER, 2)));
            }

            @Override
            public ProcessContinuations.Chain chain(SecurityIdentity a, String id) {
                actor.set(a);
                if (failure != null) {
                    throw failure;
                }
                return new ProcessContinuations.Chain("pi-0", List.of("pi-0", "pi-1"), List.of(
                        new ProcessContinuations.Link("pi-0", "pi-1", 1, "c-0", "bob", "first", AT)));
            }
        });
    }

    static ProcessContinuationPort.ContinueAsNewRequest req(String definitionId) {
        return new ProcessContinuationPort.ContinueAsNewRequest("c-1", "year end", definitionId, List.of("limit"),
                Map.of("note", "carried over"));
    }

    @Test
    void theResultMapsOntoTheContractAndTheCallerReachesTheEngine() {
        ProcessContinuationPort.ContinueAsNewResult r = bridge(null).continueAsNew(CTX, "pi-1", req(null));
        assertEquals("t1", actor.get().tenantId());
        assertEquals("alice", actor.get().personId());
        assertEquals(List.of("limit"), seen.get().carryVariables());
        assertEquals("carried over", seen.get().variables().get("note"));
        assertNull(seen.get().processDefinitionId());
        assertEquals("c-1", r.requestId());
        assertEquals("pi-1", r.predecessorId());
        assertEquals("pi-2", r.successorId());
        assertEquals("pi-0", r.chainId());
        assertEquals(2, r.generation());
        assertEquals("case-7", r.businessKey());
        assertEquals("def:3", r.processDefinitionId());
        assertEquals("CREATED", r.outcome());
        assertEquals("alice", r.actor());
        assertEquals("year end", r.reason());
        assertEquals(AT, r.continuedAt());
        assertEquals(List.of(new ProcessContinuationPort.ContinuationEnded("USER_TASK", 1),
                new ProcessContinuationPort.ContinuationEnded("TIMER", 2)), r.ended());

        ProcessContinuationPort.ProcessContinuationChain c = bridge(null).chain(CTX, "pi-1");
        assertEquals("pi-0", c.chainId());
        assertEquals(List.of("pi-0", "pi-1"), c.instanceIds());
        assertEquals(List.of(new ProcessContinuationPort.ProcessContinuationLink("pi-0", "pi-1", 1, "c-0", "bob",
                "first", AT)), c.links());
    }

    @Test
    void aRefusedContinuationIs409WithReasonAndSuccessor() {
        ApiProblem p = assertThrows(ApiProblem.class, () -> bridge(new ContinuationNotAllowedException(
                ContinuationNotAllowedException.Reason.ALREADY_CONTINUED, "pi-1", "pi-9")).continueAsNew(CTX, "pi-1",
                req(null)));
        assertEquals(409, p.status());
        assertEquals("CONTINUATION_NOT_ALLOWED", p.code());
        assertEquals("ALREADY_CONTINUED", p.extensions().get("reason"));
        assertEquals("pi-9", p.extensions().get("successorId"));

        for (ContinuationNotAllowedException.Reason reason : List.of(ContinuationNotAllowedException.Reason.SUB_INSTANCE,
                ContinuationNotAllowedException.Reason.SUSPENDED, ContinuationNotAllowedException.Reason.ENDED,
                ContinuationNotAllowedException.Reason.REQUEST_ID_REUSED)) {
            ApiProblem q = assertThrows(ApiProblem.class, () -> bridge(new ContinuationNotAllowedException(reason,
                    "pi-1", null)).continueAsNew(CTX, "pi-1", req(null)));
            assertEquals(409, q.status());
            assertEquals(reason.name(), q.extensions().get("reason"));
            assertFalse(q.extensions().containsKey("successorId"), reason.name());
        }
    }

    @Test
    void notFoundForbiddenAndValidationMapToTheContract() {
        ApiProblem instance = assertThrows(ApiProblem.class, () -> bridge(new NotFoundException(
                "no running process instance pi-1")).continueAsNew(CTX, "pi-1", req(null)));
        assertEquals(404, instance.status());
        assertTrue(instance.detail().contains("pi-1"), instance.detail());

        ApiProblem definition = assertThrows(ApiProblem.class, () -> bridge(new NotFoundException(
                "no process definition def:9")).continueAsNew(CTX, "pi-1", req("def:9")));
        assertEquals(404, definition.status());
        assertTrue(definition.detail().contains("def:9"), definition.detail());

        ApiProblem denied = assertThrows(ApiProblem.class, () -> bridge(new SangamSecurityException.AccessDenied(
                "PROCESS_INSTANCE MODIFY denied", 3)).continueAsNew(CTX, "pi-1", req(null)));
        assertEquals(403, denied.status());
        assertEquals("ACTION_NOT_PERMITTED", denied.code());

        ApiProblem invalid = assertThrows(ApiProblem.class, () -> bridge(new IllegalArgumentException(
                "VALIDATION_FAILED: carry variable 'limit' does not exist on process instance pi-1"))
                .continueAsNew(CTX, "pi-1", req(null)));
        assertEquals(400, invalid.status());
        assertEquals("VALIDATION_FAILED", invalid.code());
        assertTrue(invalid.detail().startsWith("carry variable 'limit'"), invalid.detail());

        ApiProblem chainMissing = assertThrows(ApiProblem.class, () -> bridge(new NotFoundException("gone"))
                .chain(CTX, "pi-1"));
        assertEquals(404, chainMissing.status());
    }

    @Test
    void aWrappedRefusalMapsTheSameAndAnythingElsePassesUnchanged() {
        ApiProblem wrapped = assertThrows(ApiProblem.class, () -> bridge(new RuntimeException("retrier",
                new ContinuationNotAllowedException(ContinuationNotAllowedException.Reason.SUSPENDED, "pi-1", null)))
                .continueAsNew(CTX, "pi-1", req(null)));
        assertEquals(409, wrapped.status());

        IllegalStateException other = new IllegalStateException("database down");
        assertSame(other, assertThrows(IllegalStateException.class,
                () -> bridge(other).continueAsNew(CTX, "pi-1", req(null))));
        IllegalArgumentException plain = new IllegalArgumentException("not a validation refusal");
        assertSame(plain, assertThrows(IllegalArgumentException.class,
                () -> bridge(plain).continueAsNew(CTX, "pi-1", req(null))));
    }

    @Test
    void withoutSangamSecurityTheWiringPassesNoAuthorizationSoTheEngineRefuses() {
        assertNull(ProcessContinuationWiring.authorization(null),
                "fail closed: no tenant-only fallback for continue-as-new");
    }
}

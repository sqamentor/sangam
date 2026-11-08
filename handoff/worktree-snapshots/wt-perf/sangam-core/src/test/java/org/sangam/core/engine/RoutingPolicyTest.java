// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/** ADR-D-25: the shared exclusive-routing rule, independent of any engine. */
class RoutingPolicyTest {

    private static RoutingPolicy.Candidate c(String id, boolean v) {
        return new RoutingPolicy.Candidate(id, null, () -> v);
    }

    private static RoutingPolicy.Candidate p(String id, int prio, boolean v) {
        return new RoutingPolicy.Candidate(id, prio, () -> v);
    }

    @Test
    void strictTakesTheSingleTrueFlow() {
        RouteDecision d = RoutingPolicy.decide("g", RoutingPolicy.Mode.STRICT, List.of(c("a", false), c("b", true)), null);
        assertEquals("b", d.flowId());
    }

    @Test
    void strictNeverPicksByOrderWhenSeveralAreTrue() {
        RouteDecision d = RoutingPolicy.decide("g", RoutingPolicy.Mode.STRICT,
                List.of(c("a", true), c("b", true), c("d", false)), "d");
        assertEquals("AMBIGUOUS_ROUTE", d.code());
        assertEquals(List.of("a", "b"), d.matchedFlows());
        assertTrue(d.message().startsWith("AMBIGUOUS_ROUTE"));
    }

    @Test
    void noneTrueTakesDefaultElseNoMatchingRoute() {
        assertEquals("d", RoutingPolicy.decide("g", RoutingPolicy.Mode.STRICT,
                List.of(c("a", false), c("d", false)), "d").flowId());
        assertEquals("NO_MATCHING_ROUTE", RoutingPolicy.decide("g", RoutingPolicy.Mode.STRICT,
                List.of(c("a", false)), null).code());
    }

    @Test
    void evaluationErrorPropagatesAndNeverTakesTheDefault() {
        RoutingPolicy.Candidate boom = new RoutingPolicy.Candidate("a", null, () -> {
            throw new IllegalStateException("missing variable");
        });
        assertThrows(IllegalStateException.class, () -> RoutingPolicy.decide("g", RoutingPolicy.Mode.STRICT,
                List.of(boom, c("d", false)), "d"));
    }

    @Test
    void strictEvaluatesEveryCondition() {
        AtomicInteger calls = new AtomicInteger();
        RoutingPolicy.Candidate counted = new RoutingPolicy.Candidate("b", null, () -> {
            calls.incrementAndGet();
            return false;
        });
        RoutingPolicy.decide("g", RoutingPolicy.Mode.STRICT, List.of(c("a", true), counted), null);
        assertEquals(1, calls.get(), "a later condition is still evaluated (its errors surface)");
    }

    @Test
    void priorityTakesLowestPriorityNumberRegardlessOfListOrder() {
        RouteDecision d = RoutingPolicy.decide("g", RoutingPolicy.Mode.PRIORITY,
                List.of(p("a", 2, true), p("b", 1, true)), null);
        assertEquals("b", d.flowId());
    }

    @Test
    void validationRejectsIncompleteDuplicateAndMisplacedPriorities() {
        var f = List.of(new RoutingPolicy.FlowSpec("a", true, "1"), new RoutingPolicy.FlowSpec("b", true, null));
        assertEquals(1, RoutingPolicy.validate("g", "priority", null, f, null).size(), "missing priority");
        var dup = List.of(new RoutingPolicy.FlowSpec("a", true, "1"), new RoutingPolicy.FlowSpec("b", true, "1"));
        assertEquals(1, RoutingPolicy.validate("g", "priority", null, dup, null).size(), "duplicate");
        assertEquals(2, RoutingPolicy.validate("g", null, null, dup, null).size(),
                "strict gateway with priorities: one problem per flow");
        assertEquals(1, RoutingPolicy.validate("g", "first", null, f, null).size(), "unknown policy");
        var ok = List.of(new RoutingPolicy.FlowSpec("a", true, "1"), new RoutingPolicy.FlowSpec("b", true, "2"),
                new RoutingPolicy.FlowSpec("d", false, null));
        assertEquals(List.of(), RoutingPolicy.validate("g", "priority", "1", ok, "d"));
    }
}

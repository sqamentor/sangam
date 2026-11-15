// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** M-128 / NG-18: each move says how the token got there, so the diagram can tell a flow from an event. */
class TokenWalkViaTest {

    private static TokenWalk.Move to(TokenWalk.Result r, String node) {
        return r.moves().stream().filter(m -> node.equals(m.toNode())).findFirst().orElseThrow();
    }

    @Test
    void movesNameTheirKind() {
        TokenWalk.Result b = TokenWalk.walk(TokenWalkEventsTest.BOUNDARY, null, Map.of("task", List.of("late")), 100);
        assertEquals(TokenWalk.Via.FLOW, to(b, "task").via());
        assertEquals("f1", to(b, "task").flowId());
        assertEquals(TokenWalk.Via.BOUNDARY, to(b, "late").via());
        assertNull(to(b, "late").flowId());

        TokenWalk.Result e = TokenWalk.walk(TokenWalkEventsTest.EVENT_SUB, null, Map.of("ms", List.of("t2")), 100);
        assertEquals(TokenWalk.Via.EVENT_SUB_PROCESS, to(e, "ms").via());

        TokenWalk.Result c = TokenWalk.walk(TokenWalkEventsTest.COMPENSATION, null, Map.of(), 100);
        assertEquals(TokenWalk.Via.COMPENSATION, to(c, "refund").via());
        assertEquals("cb2", to(c, "refund").fromNode());
    }
}

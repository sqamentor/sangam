// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.simulation;

import java.util.List;
import java.util.Map;

/**
 * Token-flow simulation as a pure model walk: no engine, no variables, no effects. The client passes choices and the
 * walk returns ordered token moves for the diagram to animate, or the element waiting for a choice. A walk stops at
 * {@code maxSteps}, so loops terminate.
 *
 * <p>Choices ({@code choices}: element id to the values to take, consumed in order for repeated visits):
 * <ul>
 *   <li>a gateway with more than one outgoing flow (exclusive, event-based, inclusive): the outgoing flow id to take;</li>
 *   <li>an activity with boundary events (every kind but compensation): the activity's own id when it completes
 *       normally, or the id of the boundary event that fires. An interrupting boundary event ends the activity; a
 *       non-interrupting one starts a token on its outgoing flows and the activity waits for its next choice;</li>
 *   <li>the start event of an event sub-process: the ids of the flow nodes at which its event occurs, in order. When a
 *       token reaches such a node in the event sub-process's scope (or a scope inside it), the event sub-process
 *       starts; an interrupting one first cancels everything else running in that scope. These are never asked for:
 *       without them the event does not occur.</li>
 * </ul>
 *
 * <p>Model-thrown events follow BPMN: an error end event is caught by the innermost matching error event
 * sub-process or error boundary event on an enclosing sub-process (code or error element, or a catch-all), which
 * interrupts; an escalation is caught the same way and interrupts only when its catcher does; an uncaught one is
 * warned and the thrower carries on as a none event. A compensation throw event runs, newest first and each once, the
 * handlers of the activities completed in its scope (or the one named by {@code activityRef}); a completed sub-process
 * without a handler is compensated through its compensation event sub-process or its inner activities; the thrower
 * waits for all handlers. A terminate end event ends its scope. A sub-process is entered at its start event and
 * continues on its outgoing flows when nothing runs in it any more; parallel joins wait for all incoming tokens.
 *
 * <p>Limits: an inclusive join continues per token instead of computing reachability; conditions are not evaluated;
 * multi-instance activities, call activities and link events are walked as single plain activities or events.
 */
public final class TokenWalk {

    /** How a token got from one node to the next. */
    public enum Via {
        /** Along the sequence flow {@code flowId}. */
        FLOW,
        /** From the activity (or the event thrown inside it) to one of its boundary events; {@code flowId} is null. */
        BOUNDARY,
        /** From the node a token was at to the start event of a triggered event sub-process; {@code flowId} is null. */
        EVENT_SUB_PROCESS,
        /** From a compensation boundary event to its handler ({@code flowId}: the association), or into a compensation event sub-process. */
        COMPENSATION
    }

    public record Move(int step, String flowId, String fromNode, String toNode, Via via) {
    }

    /** {@code gatewayId}: the gateway or activity waiting; {@code options}: the values {@code choices} accepts for it. */
    public record Choice(String gatewayId, List<String> options) {
    }

    public enum Status { COMPLETED, NEEDS_CHOICE, STEP_LIMIT }

    public record Result(Status status, List<Move> moves, List<String> visited, Choice pending, List<String> warnings) {
    }

    private TokenWalk() {
    }

    /**
     * Walks the process {@code processIdOrNull} (the first process when null). An unknown process, a process without a
     * start event, a choice that names no outgoing flow or boundary event, and an event sub-process trigger that names
     * no flow node are {@link IllegalArgumentException}s.
     */
    public static Result walk(String bpmnXml, String processIdOrNull, Map<String, List<String>> choices, int maxSteps) {
        return new WalkRun(WalkModel.read(bpmnXml, processIdOrNull), choices, maxSteps).run();
    }
}

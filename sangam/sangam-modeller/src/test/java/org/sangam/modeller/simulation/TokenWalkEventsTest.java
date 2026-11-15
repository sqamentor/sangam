// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * M-128 / NG-18: the token walk follows boundary events, event sub-processes and compensation (they were not walked
 * before: the walk took an activity's outgoing flows only, never entered an event sub-process and ignored throw
 * events). Assertions are on visited nodes and moves, the shape the diagram animates.
 */
class TokenWalkEventsTest {

    static String bpmn(String body) {
        return "<definitions xmlns='http://www.omg.org/spec/BPMN/20100524/MODEL' targetNamespace='t'>" + body + "</definitions>";
    }

    static boolean moved(TokenWalk.Result r, String from, String to) {
        return r.moves().stream().anyMatch(m -> from.equals(m.fromNode()) && to.equals(m.toNode()));
    }

    static int index(TokenWalk.Result r, String node) {
        return r.visited().indexOf(node);
    }

    /** A user task with an interrupting timer and a non-interrupting message boundary event. */
    static final String BOUNDARY = bpmn("<process id='p' isExecutable='true'>"
            + "<startEvent id='s'/><sequenceFlow id='f1' sourceRef='s' targetRef='task'/>"
            + "<userTask id='task'/><sequenceFlow id='f2' sourceRef='task' targetRef='done'/><endEvent id='done'/>"
            + "<boundaryEvent id='late' attachedToRef='task'><timerEventDefinition/></boundaryEvent>"
            + "<sequenceFlow id='f3' sourceRef='late' targetRef='expired'/><endEvent id='expired'/>"
            + "<boundaryEvent id='nudge' attachedToRef='task' cancelActivity='false'><messageEventDefinition/></boundaryEvent>"
            + "<sequenceFlow id='f4' sourceRef='nudge' targetRef='note'/><serviceTask id='note'/>"
            + "<sequenceFlow id='f5' sourceRef='note' targetRef='noted'/><endEvent id='noted'/>"
            + "</process>");

    @Test
    void anActivityWithBoundaryEventsAsksWhichEventHappens() {
        TokenWalk.Result r = TokenWalk.walk(BOUNDARY, null, Map.of(), 100);
        assertEquals(TokenWalk.Status.NEEDS_CHOICE, r.status());
        assertEquals("task", r.pending().gatewayId());
        assertEquals(List.of("task", "late", "nudge"), r.pending().options());
    }

    @Test
    void anInterruptingBoundaryEventEndsTheActivity() {
        TokenWalk.Result r = TokenWalk.walk(BOUNDARY, null, Map.of("task", List.of("late")), 100);
        assertEquals(TokenWalk.Status.COMPLETED, r.status());
        assertTrue(moved(r, "task", "late"), r.moves().toString());
        assertTrue(r.visited().contains("expired"));
        assertFalse(r.visited().contains("done"), "the activity's own outgoing flow is not taken");
    }

    @Test
    void aNonInterruptingBoundaryEventAddsATokenAndTheActivityWaitsAgain() {
        TokenWalk.Result r = TokenWalk.walk(BOUNDARY, null, Map.of("task", List.of("nudge")), 100);
        assertEquals(TokenWalk.Status.NEEDS_CHOICE, r.status());
        assertEquals("task", r.pending().gatewayId());
        r = TokenWalk.walk(BOUNDARY, null, Map.of("task", List.of("nudge", "nudge", "task")), 100);
        assertEquals(TokenWalk.Status.COMPLETED, r.status());
        assertEquals(2, r.visited().stream().filter("noted"::equals).count(), "one token per firing");
        assertTrue(r.visited().contains("done"));
        assertThrows(IllegalArgumentException.class, () -> TokenWalk.walk(BOUNDARY, null, Map.of("task", List.of("nope")), 100));
    }

    /** An error end event inside a sub-process, caught by an error boundary event on it; a parallel branch inside. */
    static final String ERROR = bpmn("<error id='err' errorCode='E1'/><error id='other' errorCode='E2'/>"
            + "<process id='p' isExecutable='true'>"
            + "<startEvent id='s'/><sequenceFlow id='f1' sourceRef='s' targetRef='sub'/>"
            + "<subProcess id='sub'>"
            + "  <startEvent id='ss'/><sequenceFlow id='g1' sourceRef='ss' targetRef='fork'/><parallelGateway id='fork'/>"
            + "  <sequenceFlow id='g2' sourceRef='fork' targetRef='check'/><serviceTask id='check'/>"
            + "  <sequenceFlow id='g3' sourceRef='check' targetRef='fail'/><endEvent id='fail'><errorEventDefinition errorRef='err'/></endEvent>"
            + "  <sequenceFlow id='g4' sourceRef='fork' targetRef='a1'/><userTask id='a1'/><sequenceFlow id='g5' sourceRef='a1' targetRef='a2'/>"
            + "  <userTask id='a2'/><sequenceFlow id='g6' sourceRef='a2' targetRef='a3'/><userTask id='a3'/>"
            + "</subProcess>"
            + "<sequenceFlow id='f2' sourceRef='sub' targetRef='ok'/><endEvent id='ok'/>"
            + "<boundaryEvent id='wrong' attachedToRef='sub'><errorEventDefinition errorRef='other'/></boundaryEvent>"
            + "<sequenceFlow id='f3' sourceRef='wrong' targetRef='wrongEnd'/><endEvent id='wrongEnd'/>"
            + "<boundaryEvent id='caught' attachedToRef='sub'><errorEventDefinition errorRef='err'/></boundaryEvent>"
            + "<sequenceFlow id='f4' sourceRef='caught' targetRef='repair'/><userTask id='repair'/>"
            + "<sequenceFlow id='f5' sourceRef='repair' targetRef='repaired'/><endEvent id='repaired'/>"
            + "</process>");

    @Test
    void anErrorEndEventIsCaughtByTheMatchingBoundaryEventAndCancelsTheSubProcess() {
        TokenWalk.Result r = TokenWalk.walk(ERROR, null, Map.of("sub", List.of("sub")), 100);
        assertEquals(TokenWalk.Status.COMPLETED, r.status(), r.toString());
        assertTrue(moved(r, "fail", "caught"), r.moves().toString());
        assertTrue(r.visited().contains("repaired"));
        assertFalse(r.visited().contains("wrongEnd"), "a boundary event for another error code does not catch it");
        assertFalse(r.visited().contains("ok"), "the sub-process does not complete normally");
        assertFalse(r.visited().contains("a3"), "the parallel branch inside the sub-process is cancelled");
    }

    /** A sub-process continues on its outgoing flow only after everything inside it has ended. */
    @Test
    void aSubProcessCompletesBeforeItsOutgoingFlowIsTaken() {
        String model = bpmn("<process id='p' isExecutable='true'><startEvent id='s'/><sequenceFlow id='f1' sourceRef='s' targetRef='sub'/>"
                + "<subProcess id='sub'><startEvent id='ss'/><sequenceFlow id='g1' sourceRef='ss' targetRef='t1'/><userTask id='t1'/>"
                + "<sequenceFlow id='g2' sourceRef='t1' targetRef='t2'/><userTask id='t2'/><sequenceFlow id='g3' sourceRef='t2' targetRef='se'/>"
                + "<endEvent id='se'/></subProcess><sequenceFlow id='f2' sourceRef='sub' targetRef='after'/><userTask id='after'/>"
                + "<sequenceFlow id='f3' sourceRef='after' targetRef='e'/><endEvent id='e'/></process>");
        TokenWalk.Result r = TokenWalk.walk(model, null, Map.of(), 100);
        assertEquals(TokenWalk.Status.COMPLETED, r.status());
        assertTrue(index(r, "se") < index(r, "after"), r.visited().toString());
    }

    /** An interrupting error event sub-process of the process and a non-interrupting message event sub-process. */
    static final String EVENT_SUB = bpmn("<error id='err' errorCode='E1'/><process id='p' isExecutable='true'>"
            + "<startEvent id='s'/><sequenceFlow id='f1' sourceRef='s' targetRef='fork'/><parallelGateway id='fork'/>"
            + "<sequenceFlow id='f2' sourceRef='fork' targetRef='t1'/><userTask id='t1'/><sequenceFlow id='f3' sourceRef='t1' targetRef='t2'/>"
            + "<userTask id='t2'/><sequenceFlow id='f4' sourceRef='t2' targetRef='e1'/><endEvent id='e1'/>"
            + "<sequenceFlow id='f5' sourceRef='fork' targetRef='u1'/><userTask id='u1'/><sequenceFlow id='f6' sourceRef='u1' targetRef='boom'/>"
            + "<endEvent id='boom'><errorEventDefinition/></endEvent>"
            + "<subProcess id='onError' triggeredByEvent='true'><startEvent id='es'><errorEventDefinition errorRef='err'/></startEvent>"
            + "  <sequenceFlow id='h1' sourceRef='es' targetRef='handle'/><userTask id='handle'/><sequenceFlow id='h2' sourceRef='handle' targetRef='he'/>"
            + "  <endEvent id='he'/></subProcess>"
            + "<subProcess id='onMessage' triggeredByEvent='true'><startEvent id='ms' isInterrupting='false'><messageEventDefinition/></startEvent>"
            + "  <sequenceFlow id='m1' sourceRef='ms' targetRef='reply'/><userTask id='reply'/><sequenceFlow id='m2' sourceRef='reply' targetRef='me'/>"
            + "  <endEvent id='me'/></subProcess>"
            + "</process>");

    @Test
    void anErrorThrownWithoutCodeIsNotCaughtByAnEventSubProcessForAnotherCode() {
        TokenWalk.Result r = TokenWalk.walk(EVENT_SUB, null, Map.of(), 100);
        assertEquals(TokenWalk.Status.COMPLETED, r.status());
        assertFalse(r.visited().contains("handle"), "the thrown error has no code; the catcher wants E1");
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("boom") && w.contains("not caught")), r.warnings().toString());
        assertTrue(r.visited().contains("e1"));
    }

    @Test
    void aCatchAllErrorEventSubProcessInterruptsEverythingInItsScope() {
        String model = EVENT_SUB.replace("<errorEventDefinition errorRef='err'/></startEvent>", "<errorEventDefinition/></startEvent>");
        TokenWalk.Result r = TokenWalk.walk(model, null, Map.of(), 100);
        assertEquals(TokenWalk.Status.COMPLETED, r.status());
        assertTrue(moved(r, "boom", "es"), r.moves().toString());
        assertTrue(r.visited().contains("he"));
        assertFalse(r.visited().contains("e1"), "the other branch is cancelled by the interrupting event sub-process");
    }

    @Test
    void anEventSubProcessStartsWhereTheClientSaysItsEventOccurs() {
        TokenWalk.Result r = TokenWalk.walk(EVENT_SUB, null, Map.of("ms", List.of("t2")), 100);
        assertEquals(TokenWalk.Status.COMPLETED, r.status());
        assertTrue(moved(r, "t2", "ms"), r.moves().toString());
        assertTrue(r.visited().contains("me"));
        assertTrue(r.visited().contains("e1"), "a non-interrupting event sub-process leaves the token where it was");

        TokenWalk.Result never = TokenWalk.walk(EVENT_SUB, null, Map.of("ms", List.of("he")), 100);
        assertFalse(never.visited().contains("reply"));
        assertTrue(never.warnings().stream().anyMatch(w -> w.contains("ms") && w.contains("he")), never.warnings().toString());
        assertThrows(IllegalArgumentException.class, () -> TokenWalk.walk(EVENT_SUB, null, Map.of("ms", List.of("nowhere")), 100));
    }

    @Test
    void anInterruptingEventSubProcessTriggerCancelsTheScope() {
        String model = EVENT_SUB.replace("isInterrupting='false'", "");
        TokenWalk.Result r = TokenWalk.walk(model, null, Map.of("ms", List.of("t1")), 100);
        assertEquals(TokenWalk.Status.COMPLETED, r.status());
        assertTrue(r.visited().contains("me"));
        assertFalse(r.visited().contains("t2"));
        assertFalse(r.visited().contains("boom"), "the other branch is cancelled too");
    }

    /** Two booked steps with compensation handlers, then a compensation throw. */
    static final String COMPENSATION = bpmn("<process id='p' isExecutable='true'>"
            + "<startEvent id='s'/><sequenceFlow id='f1' sourceRef='s' targetRef='book'/><serviceTask id='book'/>"
            + "<sequenceFlow id='f2' sourceRef='book' targetRef='charge'/><serviceTask id='charge'/>"
            + "<sequenceFlow id='f3' sourceRef='charge' targetRef='undo'/>"
            + "<intermediateThrowEvent id='undo'><compensateEventDefinition/></intermediateThrowEvent>"
            + "<sequenceFlow id='f4' sourceRef='undo' targetRef='e'/><endEvent id='e'/>"
            + "<boundaryEvent id='cb1' attachedToRef='book'><compensateEventDefinition/></boundaryEvent>"
            + "<serviceTask id='cancelBooking' isForCompensation='true'/>"
            + "<boundaryEvent id='cb2' attachedToRef='charge'><compensateEventDefinition/></boundaryEvent>"
            + "<serviceTask id='refund' isForCompensation='true'/>"
            + "<association id='as1' sourceRef='cb1' targetRef='cancelBooking'/><association id='as2' sourceRef='cb2' targetRef='refund'/>"
            + "</process>");

    @Test
    void aCompensationThrowRunsTheHandlersNewestFirstAndThenContinues() {
        TokenWalk.Result r = TokenWalk.walk(COMPENSATION, null, Map.of(), 100);
        assertEquals(TokenWalk.Status.COMPLETED, r.status(), r.toString());
        assertTrue(index(r, "refund") >= 0 && index(r, "cancelBooking") > index(r, "refund"), r.visited().toString());
        assertTrue(index(r, "e") > index(r, "cancelBooking"), "the throw event waits for its handlers");
        assertTrue(r.moves().stream().anyMatch(m -> "as2".equals(m.flowId()) && "refund".equals(m.toNode())), r.moves().toString());
    }

    @Test
    void aCompensationThrowWithAnActivityRefCompensatesThatActivityOnly() {
        String model = COMPENSATION.replace("<intermediateThrowEvent id='undo'><compensateEventDefinition/>",
                "<intermediateThrowEvent id='undo'><compensateEventDefinition activityRef='book'/>");
        TokenWalk.Result r = TokenWalk.walk(model, null, Map.of(), 100);
        assertTrue(r.visited().contains("cancelBooking"));
        assertFalse(r.visited().contains("refund"));
    }

    @Test
    void aCompletedSubProcessIsCompensatedThroughItsInnerActivities() {
        String model = bpmn("<process id='p' isExecutable='true'><startEvent id='s'/><sequenceFlow id='f1' sourceRef='s' targetRef='sub'/>"
                + "<subProcess id='sub'><startEvent id='ss'/><sequenceFlow id='g1' sourceRef='ss' targetRef='book'/><serviceTask id='book'/>"
                + "<sequenceFlow id='g2' sourceRef='book' targetRef='se'/><endEvent id='se'/>"
                + "<boundaryEvent id='cb' attachedToRef='book'><compensateEventDefinition/></boundaryEvent>"
                + "<serviceTask id='cancelBooking' isForCompensation='true'/><association id='as' sourceRef='cb' targetRef='cancelBooking'/>"
                + "</subProcess><sequenceFlow id='f2' sourceRef='sub' targetRef='undo'/>"
                + "<endEvent id='undo'><compensateEventDefinition/></endEvent></process>");
        TokenWalk.Result r = TokenWalk.walk(model, null, Map.of(), 100);
        assertEquals(TokenWalk.Status.COMPLETED, r.status());
        assertTrue(r.visited().contains("cancelBooking"), r.visited().toString());
    }

    @Test
    void aCompensationWithNothingCompletedIsWarnedAndContinues() {
        String model = COMPENSATION.replace("targetRef='book'/><serviceTask id='book'/>", "targetRef='undo'/><serviceTask id='book'/>");
        TokenWalk.Result r = TokenWalk.walk(model, null, Map.of(), 100);
        assertEquals(TokenWalk.Status.COMPLETED, r.status());
        assertFalse(r.visited().contains("refund"));
        assertTrue(r.visited().contains("e"));
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("undo")), r.warnings().toString());
    }

    @Test
    void aNonInterruptingEscalationBoundaryLetsTheThrowerContinue() {
        String model = bpmn("<escalation id='esc' escalationCode='LATE'/><process id='p' isExecutable='true'>"
                + "<startEvent id='s'/><sequenceFlow id='f1' sourceRef='s' targetRef='sub'/>"
                + "<subProcess id='sub'><startEvent id='ss'/><sequenceFlow id='g1' sourceRef='ss' targetRef='raise'/>"
                + "<intermediateThrowEvent id='raise'><escalationEventDefinition escalationRef='esc'/></intermediateThrowEvent>"
                + "<sequenceFlow id='g2' sourceRef='raise' targetRef='se'/><endEvent id='se'/></subProcess>"
                + "<sequenceFlow id='f2' sourceRef='sub' targetRef='e'/><endEvent id='e'/>"
                + "<boundaryEvent id='tell' attachedToRef='sub' cancelActivity='false'><escalationEventDefinition escalationRef='esc'/></boundaryEvent>"
                + "<sequenceFlow id='f3' sourceRef='tell' targetRef='inform'/><userTask id='inform'/>"
                + "<sequenceFlow id='f4' sourceRef='inform' targetRef='ie'/><endEvent id='ie'/></process>");
        TokenWalk.Result r = TokenWalk.walk(model, null, Map.of("sub", List.of("sub")), 100);
        assertEquals(TokenWalk.Status.COMPLETED, r.status());
        assertTrue(moved(r, "raise", "tell"), r.moves().toString());
        assertTrue(r.visited().contains("ie"));
        assertTrue(r.visited().contains("se") && r.visited().contains("e"), "the thrower and the sub-process carry on");
    }

    @Test
    void aTerminateEndEventEndsItsScope() {
        String model = bpmn("<process id='p' isExecutable='true'><startEvent id='s'/><sequenceFlow id='f1' sourceRef='s' targetRef='fork'/>"
                + "<parallelGateway id='fork'/><sequenceFlow id='f2' sourceRef='fork' targetRef='stop'/>"
                + "<endEvent id='stop'><terminateEventDefinition/></endEvent>"
                + "<sequenceFlow id='f3' sourceRef='fork' targetRef='t1'/><userTask id='t1'/><sequenceFlow id='f4' sourceRef='t1' targetRef='t2'/>"
                + "<userTask id='t2'/><sequenceFlow id='f5' sourceRef='t2' targetRef='e'/><endEvent id='e'/></process>");
        TokenWalk.Result r = TokenWalk.walk(model, null, Map.of(), 100);
        assertEquals(TokenWalk.Status.COMPLETED, r.status());
        assertFalse(r.visited().contains("t2"), r.visited().toString());
    }
}

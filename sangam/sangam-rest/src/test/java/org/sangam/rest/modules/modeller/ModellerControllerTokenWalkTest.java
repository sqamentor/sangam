// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.modeller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.sangam.modeller.simulation.TokenWalk;

/**
 * M-128 / NG-18 {@code POST /models/token-walk} step by step (the console's form: {@code state} from the previous
 * answer plus one {@code choice}). Each step walks again from the start with all choices, so an element asked again
 * (a loop back to a gateway, an activity after a non-interrupting boundary event) must keep its earlier choices: the
 * step used to replace them with the new one, which lost every choice the user had made there before.
 */
class ModellerControllerTokenWalkTest {

    static final String LOOP = "<definitions xmlns='http://www.omg.org/spec/BPMN/20100524/MODEL' targetNamespace='t'>"
            + "<process id='p' isExecutable='true'><startEvent id='s'/><sequenceFlow id='f0' sourceRef='s' targetRef='x'/>"
            + "<exclusiveGateway id='x'/><sequenceFlow id='again' sourceRef='x' targetRef='work'/><userTask id='work'/>"
            + "<sequenceFlow id='back' sourceRef='work' targetRef='x'/><sequenceFlow id='exit' sourceRef='x' targetRef='e'/>"
            + "<endEvent id='e'/></process></definitions>";

    static final String NUDGE = "<definitions xmlns='http://www.omg.org/spec/BPMN/20100524/MODEL' targetNamespace='t'>"
            + "<process id='p' isExecutable='true'><startEvent id='s'/><sequenceFlow id='f1' sourceRef='s' targetRef='task'/>"
            + "<userTask id='task'/><sequenceFlow id='f2' sourceRef='task' targetRef='done'/><endEvent id='done'/>"
            + "<boundaryEvent id='nudge' attachedToRef='task' cancelActivity='false'><messageEventDefinition/></boundaryEvent>"
            + "<sequenceFlow id='f3' sourceRef='nudge' targetRef='noted'/><endEvent id='noted'/></process></definitions>";

    private static ModellerController.TokenWalkView step(String xml, ModellerController.TokenWalkView previous, String choice) {
        return ModellerController.walk(new ModellerController.TokenWalkRequest(null, xml, "f.bpmn", null, null, null,
                previous == null ? null : previous.state(), choice));
    }

    @Test
    void aGatewayVisitedAgainKeepsItsEarlierChoice() {
        var v = step(LOOP, null, null);
        assertEquals(TokenWalk.Status.NEEDS_CHOICE, v.status());
        v = step(LOOP, v, "again");
        assertEquals(TokenWalk.Status.NEEDS_CHOICE, v.status(), "the loop comes back to the gateway");
        v = step(LOOP, v, "exit");
        assertEquals(TokenWalk.Status.COMPLETED, v.status());
        assertEquals(1, v.visited().stream().filter("work"::equals).count(), "the loop the user chose first is still walked");
        assertTrue(v.done());
    }

    @Test
    void anActivityAskedAgainAfterANonInterruptingBoundaryEventKeepsItsEarlierChoice() {
        var v = step(NUDGE, null, null);
        assertEquals(List.of("task", "nudge"), v.choices().stream().map(ModellerController.TokenWalkChoice::flowId).toList());
        v = step(NUDGE, v, "nudge");
        assertEquals(TokenWalk.Status.NEEDS_CHOICE, v.status());
        v = step(NUDGE, v, "task");
        assertEquals(TokenWalk.Status.COMPLETED, v.status());
        assertTrue(v.visited().contains("noted"), v.visited().toString());
        assertTrue(v.visited().contains("done"));
    }
}

// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.contracts;

import org.operaton.bpm.model.bpmn.Bpmn;
import org.operaton.bpm.model.bpmn.BpmnModelInstance;

/**
 * The Stage B corpus workflows (spike/corpus MODELS.md) reduced to the elements each contract exercises, built with the
 * Operaton model API under run-unique keys and topics. Generic names only.
 */
final class Models {

    private Models() {
    }

    static String xml(BpmnModelInstance m) {
        return Bpmn.convertToString(m);
    }

    /** One user task (the E2 task population: WF-1 / WF-2 starts each leave one open task). */
    static String singleTask(String key, String taskId) {
        return xml(Bpmn.createExecutableProcess(key).operatonHistoryTimeToLive(30)
                .startEvent().userTask(taskId).name("Review").endEvent().done());
    }

    /**
     * WF-1 checker step: {@code checkerReview} then a gateway on {@code checkerDecision} ({@code forward} → approve);
     * a missing {@code checkerDecision} fails the gateway inside the completing command (E5-3 engine failure).
     */
    static String checkerFlow(String key) {
        return xml(Bpmn.createExecutableProcess(key).operatonHistoryTimeToLive(30)
                .startEvent()
                .userTask("checkerReview").name("Checker review")
                .exclusiveGateway("checkerGw")
                .condition("forward", "${checkerDecision == 'forward'}")
                .userTask("approve").name("Approve").endEvent("approved")
                .moveToLastGateway()
                .condition("return", "${checkerDecision == 'return'}")
                .endEvent("returned")
                .done());
    }

    /** WF-1 payment: an external task on {@code topic} (the IFMS payment worker), then the end. */
    static String payment(String key, String topic) {
        return xml(Bpmn.createExecutableProcess(key).operatonHistoryTimeToLive(30)
                .startEvent()
                .serviceTask("payment").name("Pay").operatonExternalTask(topic)
                .endEvent("paid")
                .done());
    }

    /**
     * WF-3 clarification loop: {@code scrutiny}; {@code clarify} waits at a message catch {@code message} and returns
     * to scrutiny; {@code accept} ends.
     */
    static String clarification(String key, String message) {
        return xml(Bpmn.createExecutableProcess(key).operatonHistoryTimeToLive(30)
                .startEvent()
                .userTask("scrutiny").name("Scrutiny")
                .exclusiveGateway("scrutinyGw")
                .condition("clarify", "${scrutinyDecision == 'clarify'}")
                .intermediateCatchEvent("awaitApplicantResponse").message(message)
                .connectTo("scrutiny")
                .moveToNode("scrutinyGw")
                .condition("accept", "${scrutinyDecision == 'accept'}")
                .endEvent("accepted")
                .done());
    }
}

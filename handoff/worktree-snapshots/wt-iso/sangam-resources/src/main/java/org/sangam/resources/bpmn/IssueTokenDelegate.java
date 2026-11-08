// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources.bpmn;

import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.sangam.resources.queue.QueueModel;
import org.sangam.resources.queue.QueueService;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Issues a queue token from a process (N07), typically the check-in of a confirmed booking.
 *
 * <p>Inputs: {@code servicePointId} (required), {@code bookingId}, {@code partyId}, {@code skill},
 * {@code priorityClass}, {@code routingKey}, {@code channel} (default APPOINTMENT with a booking, else WALK_IN).
 * Outputs: {@code tokenId}, {@code displayNo}, {@code tokenSkill}, {@code tokenPriorityLevel}.
 */
public class IssueTokenDelegate implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) {
        QueueService queue = ResourcesRuntime.service(QueueService.class);
        String channel = ResourcesRuntime.str(execution, "channel");
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("processInstanceId", execution.getProcessInstanceId());
        if (execution.getProcessBusinessKey() != null) {
            attrs.put("businessKey", execution.getProcessBusinessKey());
        }
        QueueModel.IssueRequest req = new QueueModel.IssueRequest(
                ResourcesRuntime.required(execution, "servicePointId"),
                channel == null ? null : QueueModel.Channel.valueOf(channel),
                ResourcesRuntime.str(execution, "bookingId"),
                ResourcesRuntime.str(execution, "partyId"),
                ResourcesRuntime.str(execution, "priorityClass"),
                ResourcesRuntime.str(execution, "skill"),
                ResourcesRuntime.str(execution, "routingKey"),
                attrs);
        QueueModel.Token t = queue.issue(ResourcesRuntime.connection(), ResourcesRuntime.identity(execution), req);
        execution.setVariable("tokenId", t.tokenId());
        execution.setVariable("displayNo", t.displayNo());
        execution.setVariable("tokenSkill", t.skill());
        execution.setVariable("tokenPriorityLevel", t.priorityLevel());
    }
}

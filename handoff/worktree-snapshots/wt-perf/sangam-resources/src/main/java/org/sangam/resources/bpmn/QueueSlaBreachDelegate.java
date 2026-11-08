// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources.bpmn;

import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.resources.queue.QueueModel;
import org.sangam.resources.queue.QueueService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lists the waiting tokens of a service point whose SLA is breached (for a timer-driven escalation process) and closes
 * stale calls with the no-show sweep when {@code sweepNoShows} is true.
 *
 * <p>Inputs: {@code servicePointId} (required), {@code sweepNoShows} (optional boolean). Outputs: {@code slaBreaches}
 * (list of maps: tokenId, displayNo, skill, priorityClass, queuedAt, slaDueAt), {@code slaBreachCount},
 * {@code noShowsClosed}.
 */
public class QueueSlaBreachDelegate implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) {
        QueueService queue = ResourcesRuntime.service(QueueService.class);
        SecurityIdentity actor = ResourcesRuntime.identity(execution);
        String sp = ResourcesRuntime.required(execution, "servicePointId");
        if (Boolean.parseBoolean(String.valueOf(execution.getVariable("sweepNoShows")))) {
            execution.setVariable("noShowsClosed", queue.noShowSweep(ResourcesRuntime.connection(), actor, sp));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (QueueModel.Token t : queue.slaBreaches(ResourcesRuntime.connection(), actor, sp)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tokenId", t.tokenId());
            m.put("displayNo", t.displayNo());
            m.put("skill", t.skill());
            m.put("priorityClass", t.priorityClass());
            m.put("queuedAt", t.queuedAt() == null ? null : t.queuedAt().toString());
            m.put("slaDueAt", t.slaDueAt() == null ? null : t.slaDueAt().toString());
            out.add(m);
        }
        execution.setVariable("slaBreaches", out);
        execution.setVariable("slaBreachCount", out.size());
    }
}

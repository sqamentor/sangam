// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.bridge.operaton;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.externaltask.ExternalTask;
import org.operaton.bpm.engine.runtime.Job;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.sangam.integration.reconciliation.HeldWorkReleaser;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Operaton release of held work after reconciliation (A-10 HOLD on Operaton = retries 0 + incident): external tasks of
 * the operation's activity instance get {@code setRetries(1)} (which resolves the incident), and failed jobs of a
 * {@link SangamConnectorDelegate} get {@code setJobRetries(1)}. The released attempt then reads the settled ledger.
 */
public final class OperatonHeldWorkReleaser implements HeldWorkReleaser {

    private final ProcessEngine engine;

    public OperatonHeldWorkReleaser(ProcessEngine engine) {
        this.engine = engine;
    }

    @Override
    public int release(String tenantId, String rootId, UUID operationId, String activityInstanceId) {
        List<String> instances = new ArrayList<>();
        instances.add(rootId);
        for (ProcessInstance pi : engine.getRuntimeService().createProcessInstanceQuery().rootProcessInstanceId(rootId).list()) {
            if (!instances.contains(pi.getId())) {
                instances.add(pi.getId());
            }
        }
        int released = 0;
        for (String pid : instances) {
            for (ExternalTask t : engine.getExternalTaskService().createExternalTaskQuery().processInstanceId(pid).noRetriesLeft().list()) {
                if (activityInstanceId == null || activityInstanceId.equals(t.getActivityInstanceId())) {
                    engine.getExternalTaskService().setRetries(t.getId(), 1);
                    released++;
                }
            }
            for (Job j : engine.getManagementService().createJobQuery().processInstanceId(pid).noRetriesLeft().list()) {
                String msg = j.getExceptionMessage();
                if (msg != null && msg.contains(operationId.toString())) {
                    engine.getManagementService().setJobRetries(j.getId(), 1);
                    released++;
                }
            }
        }
        return released;
    }
}

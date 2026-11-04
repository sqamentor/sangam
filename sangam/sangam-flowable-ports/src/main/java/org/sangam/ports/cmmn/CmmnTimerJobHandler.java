// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.cmmn;

import org.operaton.bpm.engine.impl.cmmn.entity.runtime.CaseExecutionEntity;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.operaton.bpm.engine.impl.jobexecutor.JobHandler;
import org.operaton.bpm.engine.impl.jobexecutor.JobHandlerConfiguration;
import org.operaton.bpm.engine.impl.persistence.entity.ExecutionEntity;

/**
 * Job handler for CMMN timer event listeners (FC-55; registered through {@code customJobHandlers}). The configuration
 * is the case execution id. If the listener is still available when the job runs, it occurs; if the case or the
 * listener ended first (exit, terminate, completion), the job does nothing.
 */
public class CmmnTimerJobHandler implements JobHandler<CmmnTimerJobHandler.Config> {

    public static final String TYPE = "sangam-cmmn-timer-listener";

    public record Config(String caseExecutionId) implements JobHandlerConfiguration {
        @Override
        public String toCanonicalString() {
            return caseExecutionId;
        }
    }

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public void execute(Config configuration, ExecutionEntity execution, CommandContext commandContext, String tenantId) {
        CaseExecutionEntity ce = commandContext.getCaseExecutionManager().findCaseExecutionById(configuration.caseExecutionId());
        if (ce != null && ce.isAvailable()) {
            ce.occur();
        }
    }

    @Override
    public Config newConfiguration(String canonicalString) {
        return new Config(canonicalString);
    }
}

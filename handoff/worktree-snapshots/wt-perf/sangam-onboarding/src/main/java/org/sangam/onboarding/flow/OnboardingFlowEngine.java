// SPDX-License-Identifier: Apache-2.0
package org.sangam.onboarding.flow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.runtime.Job;
import org.operaton.bpm.engine.task.Task;

/** The BPMN process of a run on the cell engine, through the public engine API (ADR-D-16). */
public final class OnboardingFlowEngine {

    public static final String PROCESS_KEY = "sangam-onboarding";
    public static final String RESOURCE = "META-INF/sangam/onboarding/sangam-onboarding.bpmn";
    public static final String TASK_FIX = "task_readiness_fix";
    public static final String TASK_APPROVAL = "task_go_live_approval";

    private final ProcessEngine engine;

    public OnboardingFlowEngine(ProcessEngine engine) {
        this.engine = engine;
    }

    /** Deploys the shared definition (duplicate filtering keeps restarts cheap). */
    public void deploy() {
        engine.getRepositoryService().createDeployment().name("sangam-onboarding").enableDuplicateFiltering(true)
                .addClasspathResource(RESOURCE).deploy();
    }

    public String start(String runId) {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("runId", runId);
        return engine.getRuntimeService().startProcessInstanceByKey(PROCESS_KEY, runId, vars).getId();
    }

    /** Completes the waiting user task; false when the process is not waiting there. */
    public boolean complete(String processInstanceId, String taskKey, Map<String, Object> vars) {
        Task t = engine.getTaskService().createTaskQuery().processInstanceId(processInstanceId)
                .taskDefinitionKey(taskKey).singleResult();
        if (t == null) {
            return false;
        }
        engine.getTaskService().complete(t.getId(), vars);
        return true;
    }

    /** Gives every failed job of the run one more try; returns how many. */
    public int retryFailed(String processInstanceId) {
        List<Job> failed = engine.getManagementService().createJobQuery().processInstanceId(processInstanceId)
                .noRetriesLeft().list();
        failed.forEach(j -> engine.getManagementService().setJobRetries(j.getId(), 1));
        return failed.size();
    }

    public void cancel(String processInstanceId, String reason) {
        engine.getRuntimeService().deleteProcessInstance(processInstanceId, reason);
    }
}

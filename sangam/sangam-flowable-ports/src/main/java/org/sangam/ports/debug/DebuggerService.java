// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.debug;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.bpmn.parser.BpmnParseListener;
import org.operaton.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.operaton.bpm.engine.impl.pvm.process.ActivityImpl;
import org.operaton.bpm.engine.impl.pvm.process.ScopeImpl;
import org.operaton.bpm.engine.impl.util.xml.Element;
import org.operaton.bpm.engine.runtime.Job;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Process debugger back end (FC-44 Flowable {@code DebugFlowableEngineAgenda}, EC-13 Inspect; R80). The UI is WS-UI.
 * Breakpoints ({@code DEBUG_BREAKPOINT}) exist only for <b>sandbox tenants</b>: at parse time an activity with a
 * breakpoint becomes {@code asyncBefore}, so execution stops there as a job; the debugger then steps (executes that
 * one job), continues, fires timers, sends messages and edits variables through the normal services.
 *
 * <p>Safety: the service refuses to exist outside a sandbox profile ({@code sandboxProfile=false} throws), and the
 * parse listener ignores non-sandbox tenants, so a production deployment is never altered. In the sandbox engine the
 * job executor must be off (breakpoint jobs would otherwise run). Breakpoints apply to deployments made after they
 * were set (asyncBefore changes transaction boundaries; R80 accepts this in sandboxes only).
 */
public class DebuggerService {

    private final ProcessEngine engine;
    private final Dialect dialect;
    private final Transactions tx;
    private final Set<String> sandboxTenants;

    public DebuggerService(ProcessEngine engine, Dialect dialect, Transactions tx, Set<String> sandboxTenants, boolean sandboxProfile) {
        if (!sandboxProfile) {
            throw new IllegalStateException("the debugger is available only in a sandbox profile (R80)");
        }
        this.engine = engine;
        this.dialect = dialect;
        this.tx = tx;
        this.sandboxTenants = Set.copyOf(sandboxTenants);
    }

    public void addBreakpoint(String tenant, String definitionKey, String activityId) {
        requireSandbox(tenant);
        tx.inTransaction(c -> Jdbc.insertIfAbsent(c, dialect, "INSERT INTO DEBUG_BREAKPOINT(tenant_id, definition_key, activity_id) VALUES (?,?,?)",
                tenant, definitionKey, activityId));
    }

    public void removeBreakpoint(String tenant, String definitionKey, String activityId) {
        requireSandbox(tenant);
        tx.inTransaction(c -> Jdbc.update(c, "DELETE FROM DEBUG_BREAKPOINT WHERE tenant_id = ? AND definition_key = ? AND activity_id = ?",
                tenant, definitionKey, activityId));
    }

    public Set<String> breakpoints(String tenant, String definitionKey) {
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT activity_id FROM DEBUG_BREAKPOINT WHERE tenant_id = ? AND definition_key = ?",
                rs -> rs.getString(1), tenant, definitionKey)).stream().collect(Collectors.toSet());
    }

    /** Upper bound of {@link #resume} steps (a looping model must not spin the debugger for ever). */
    public static final int MAX_RESUME_STEPS = 1_000;

    /** Jobs of an instance waiting at a breakpoint (or any async/timer job). */
    public List<Job> pausedAt(String processInstanceId) {
        requireSandboxInstance(processInstanceId);
        return engine.getManagementService().createJobQuery().processInstanceId(processInstanceId).list();
    }

    /** Executes one job: one step of the debugged instance. */
    public void step(String jobId) {
        requireSandboxJob(jobId);
        engine.getManagementService().executeJob(jobId);
    }

    /** Runs every job of the instance until none is left or {@code maxSteps} (at most {@value #MAX_RESUME_STEPS}) is reached. */
    public int resume(String processInstanceId, int maxSteps) {
        int limit = Math.max(0, Math.min(maxSteps, MAX_RESUME_STEPS));
        int n = 0;
        List<Job> jobs;
        while (n < limit && !(jobs = pausedAt(processInstanceId)).isEmpty()) {
            step(jobs.get(0).getId());
            n++;
        }
        return n;
    }

    /** Fires a timer job now (sets the due date to now and executes it). */
    public void fireTimer(String jobId) {
        requireSandboxJob(jobId);
        engine.getManagementService().setJobDuedate(jobId, new java.util.Date());
        engine.getManagementService().executeJob(jobId);
    }

    public void setVariables(String executionId, Map<String, Object> vars) {
        var ex = engine.getRuntimeService().createExecutionQuery().executionId(executionId).singleResult();
        if (ex == null) {
            throw new IllegalArgumentException("execution " + executionId + " not found");
        }
        requireSandboxInstance(ex.getProcessInstanceId());
        engine.getRuntimeService().setVariables(executionId, vars);
    }

    public void sendMessage(String messageName, String processInstanceId, Map<String, Object> vars) {
        requireSandboxInstance(processInstanceId);
        engine.getRuntimeService().createMessageCorrelation(messageName).processInstanceId(processInstanceId).setVariables(vars).correlate();
    }

    /**
     * FW-38 (Flowable Inspect "not for production"): every debugger action on a job, execution or instance first checks
     * that it belongs to a sandbox tenant, so a debugger reachable in a mixed engine cannot step, fire, edit or message
     * a non-sandbox instance.
     */
    private void requireSandboxInstance(String processInstanceId) {
        var pi = engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(processInstanceId).singleResult();
        if (pi == null) {
            throw new IllegalArgumentException("process instance " + processInstanceId + " not found");
        }
        requireSandboxTenant(pi.getTenantId());
    }

    private void requireSandboxJob(String jobId) {
        Job j = engine.getManagementService().createJobQuery().jobId(jobId).singleResult();
        if (j == null) {
            throw new IllegalArgumentException("job " + jobId + " not found");
        }
        requireSandboxTenant(j.getTenantId());
    }

    private void requireSandboxTenant(String tenant) {
        if (tenant == null || !sandboxTenants.contains(tenant)) {
            throw new org.sangam.ports.support.PortsProblem("NOT_SANDBOX", 403, "the debugger acts only on sandbox tenants (R80)");
        }
    }

    /** Parse listener: sandbox tenants only. */
    public BpmnParseListener parseListener() {
        return new BpmnParseListener() {
            @Override
            public void parseUserTask(Element e, ScopeImpl s, ActivityImpl a) {
                apply(a);
            }

            @Override
            public void parseServiceTask(Element e, ScopeImpl s, ActivityImpl a) {
                apply(a);
            }

            @Override
            public void parseTask(Element e, ScopeImpl s, ActivityImpl a) {
                apply(a);
            }

            @Override
            public void parseBusinessRuleTask(Element e, ScopeImpl s, ActivityImpl a) {
                apply(a);
            }

            @Override
            public void parseSendTask(Element e, ScopeImpl s, ActivityImpl a) {
                apply(a);
            }

            @Override
            public void parseReceiveTask(Element e, ScopeImpl s, ActivityImpl a) {
                apply(a);
            }

            @Override
            public void parseSubProcess(Element e, ScopeImpl s, ActivityImpl a) {
                apply(a);
            }

            @Override
            public void parseCallActivity(Element e, ScopeImpl s, ActivityImpl a) {
                apply(a);
            }

            @Override
            public void parseExclusiveGateway(Element e, ScopeImpl s, ActivityImpl a) {
                apply(a);
            }

            @Override
            public void parseIntermediateThrowEvent(Element e, ScopeImpl s, ActivityImpl a) {
                apply(a);
            }

            @Override
            public void parseEndEvent(Element e, ScopeImpl s, ActivityImpl a) {
                apply(a);
            }
        };
    }

    void apply(ActivityImpl a) {
        if (!(a.getProcessDefinition() instanceof ProcessDefinitionEntity pd) || pd.getTenantId() == null
                || !sandboxTenants.contains(pd.getTenantId())) {
            return;
        }
        if (breakpoints(pd.getTenantId(), pd.getKey()).contains(a.getId())) {
            a.setAsyncBefore(true);
            a.setProperty("sangam.breakpoint", true);
        }
    }

    private void requireSandbox(String tenant) {
        if (!sandboxTenants.contains(tenant)) {
            throw new IllegalArgumentException("tenant " + tenant + " is not a sandbox tenant");
        }
    }
}

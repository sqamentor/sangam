// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.persistence.entity.JobEntity;
import org.operaton.bpm.engine.repository.ProcessDefinition;
import org.operaton.bpm.engine.runtime.Job;
import org.sangam.ports.debug.DebuggerService;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.DebugPort;
import org.sangam.rest.port.ProcessPort;
import org.sangam.rest.port.RequestContext;

/**
 * {@link DebugPort} on sangam-flowable-ports' debugger (FLOWABLE-INTO-OPERATON FC-44, EC-13, R80). Breakpoints are per process definition key
 * in {@code DEBUG_BREAKPOINT}; the ports parse listener turns a breakpoint activity into {@code asyncBefore} for
 * <b>new deployments of sandbox tenants</b>, so execution stops there as a job and {@code step} executes it. This bean
 * exists only when {@code sangam.debug.enabled=true} on a non-production profile; elsewhere the default adapter
 * answers {@code 403 DEBUGGER_DISABLED}.
 */
public final class PortsDebugBridge implements DebugPort {

    private final DebuggerService debugger;
    private final ProcessEngine engine;
    private final ProcessPort processes;
    private final AuditPort audit;
    private final String profile;
    private final Set<String> sandboxTenants;

    public PortsDebugBridge(DebuggerService debugger, ProcessEngine engine, ProcessPort processes, AuditPort audit,
                            String profile, Set<String> sandboxTenants) {
        this.debugger = debugger;
        this.engine = engine;
        this.processes = processes;
        this.audit = audit;
        this.profile = profile;
        this.sandboxTenants = Set.copyOf(sandboxTenants);
    }

    @Override
    public Status status(RequestContext ctx) {
        boolean enabled = sandboxTenants.isEmpty() || sandboxTenants.contains(ctx.tenantId());
        return new Status(enabled, profile, sandboxTenants.isEmpty() ? null : String.join(",", sandboxTenants));
    }

    @Override
    public List<String> breakpoints(RequestContext ctx, String processInstanceId) {
        requireSandbox(ctx);
        String key = definitionKey(ctx, processInstanceId);
        return new ArrayList<>(PortsErrors.call(() -> debugger.breakpoints(ctx.tenantId(), key)));
    }

    @Override
    public List<String> setBreakpoints(RequestContext ctx, String processInstanceId, BreakpointsRequest request) {
        requireSandbox(ctx);
        String key = definitionKey(ctx, processInstanceId);
        Set<String> wanted = new HashSet<>(request == null || request.activityIds() == null ? List.of() : request.activityIds());
        Set<String> current = PortsErrors.call(() -> debugger.breakpoints(ctx.tenantId(), key));
        for (String a : current) {
            if (!wanted.contains(a)) {
                PortsErrors.run(() -> debugger.removeBreakpoint(ctx.tenantId(), key, a));
            }
        }
        for (String a : wanted) {
            if (!current.contains(a)) {
                PortsErrors.run(() -> debugger.addBreakpoint(ctx.tenantId(), key, a));
            }
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("definition_key", key);
        p.put("activity_ids", List.copyOf(wanted));
        p.put("note", "breakpoints take effect for deployments made after they were set");
        audit.record(ctx, processInstanceId, "DEBUG_BREAKPOINTS_SET", p);
        return breakpoints(ctx, processInstanceId);
    }

    @Override
    public StepResult step(RequestContext ctx, String processInstanceId) {
        requireSandbox(ctx);
        processes.get(ctx, processInstanceId);
        List<Job> jobs = PortsErrors.call(() -> debugger.pausedAt(processInstanceId));
        if (jobs.isEmpty()) {
            return new StepResult(null, null, active(processInstanceId));
        }
        Job job = jobs.get(0);
        String activity = job instanceof JobEntity je ? je.getActivityId() : null;
        PortsErrors.run(() -> debugger.step(job.getId()));
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("job_id", job.getId());
        p.put("activity_id", activity);
        audit.record(ctx, processInstanceId, "DEBUG_STEP", p);
        return new StepResult(activity, job.getId(), active(processInstanceId));
    }

    private List<String> active(String processInstanceId) {
        if (engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(processInstanceId).count() == 0) {
            return List.of();
        }
        return engine.getRuntimeService().getActiveActivityIds(processInstanceId);
    }

    private String definitionKey(RequestContext ctx, String processInstanceId) {
        ProcessPort.ProcessInstance pi = processes.get(ctx, processInstanceId);
        if (pi.processDefinitionKey() != null) {
            return pi.processDefinitionKey();
        }
        ProcessDefinition d = engine.getRepositoryService().getProcessDefinition(pi.processDefinitionId());
        return d.getKey();
    }

    private void requireSandbox(RequestContext ctx) {
        if (!sandboxTenants.isEmpty() && !sandboxTenants.contains(ctx.tenantId())) {
            throw ApiProblem.forbidden("DEBUGGER_DISABLED", "The debugger is available only in sandbox tenants (R80)");
        }
    }
}

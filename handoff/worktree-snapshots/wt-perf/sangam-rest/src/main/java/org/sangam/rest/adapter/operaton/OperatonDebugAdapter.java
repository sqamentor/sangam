// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.operaton.bpm.engine.ManagementService;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.runtime.Job;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.DebugPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.core.layer.Jdbc;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;

/**
 * Default model debugger (R80, FLOWABLE-INTO-OPERATON FC-44). It can never run on a production profile: when it is disabled, when the active
 * profile contains "prod", or when the caller is outside the sandbox tenant, every call except {@link #status} returns
 * {@code 403 DEBUGGER_DISABLED}.
 *
 * <p>Stepping executes the next due job of the instance. Breakpoints are honoured at job boundaries only: a step whose
 * next job belongs to a breakpoint activity is refused with a "paused at breakpoint" note. Turning an arbitrary
 * activity into a stop point needs an {@code asyncBefore} injected at parse time by the sangam-engine debug parse
 * listener (FLOWABLE-INTO-OPERATON FC-44); until that is wired, breakpoints on activities without a job are never reached.
 *
 * <p>Breakpoints are persisted in {@code DEBUG_BREAKPOINT} (sandbox tenants only): one row per activity with
 * {@code process_instance_id} set (changeset 0016-06). Because the table's primary key is
 * {@code (tenant_id, definition_key, activity_id)}, instance-scoped rows use {@code definition_key =
 * <processDefinitionKey>@<processInstanceId>}, so two instances of one definition can break at the same activity and
 * never collide with definition-wide breakpoints of sangam-flowable-ports' debugger.
 */
public class OperatonDebugAdapter implements DebugPort {

    private final RuntimeService runtime;
    private final ManagementService management;
    private final RestProperties props;
    private final boolean enabled;
    private final String profile;
    private final String sandboxTenant;
    private final TxSupport tx;

    public OperatonDebugAdapter(RuntimeService runtime, ManagementService management, RestProperties props,
                                boolean enabled, String profile, String sandboxTenant, TxSupport tx) {
        this.tx = tx;
        this.runtime = runtime;
        this.management = management;
        this.props = props;
        this.profile = profile == null ? "" : profile;
        this.enabled = enabled && !this.profile.toLowerCase(Locale.ROOT).contains("prod");
        this.sandboxTenant = sandboxTenant == null || sandboxTenant.isBlank() ? null : sandboxTenant;
    }

    @Override
    public Status status(RequestContext ctx) {
        boolean usable = enabled && (sandboxTenant == null || sandboxTenant.equals(ctx.tenantId()));
        return new Status(usable, profile, sandboxTenant);
    }

    @Override
    public List<String> breakpoints(RequestContext ctx, String processInstanceId) {
        guard(ctx);
        instance(ctx, processInstanceId);
        return sorted(stored(ctx.tenantId(), processInstanceId));
    }

    @Override
    public List<String> setBreakpoints(RequestContext ctx, String processInstanceId, BreakpointsRequest request) {
        guard(ctx);
        ProcessInstance pi = instance(ctx, processInstanceId);
        Set<String> set = new java.util.TreeSet<>();
        if (request != null && request.activityIds() != null) {
            request.activityIds().stream().filter(a -> a != null && !a.isBlank()).forEach(set::add);
        }
        String definitionKey = definitionKey(pi) + "@" + processInstanceId;
        tx.withConnection(c -> {
            // replace-all in one transaction
            Jdbc.update(c, "DELETE FROM DEBUG_BREAKPOINT WHERE tenant_id=? AND process_instance_id=?",
                    ctx.tenantId(), processInstanceId);
            for (String a : set) {
                Jdbc.update(c, "INSERT INTO DEBUG_BREAKPOINT(tenant_id, definition_key, activity_id, process_instance_id)"
                        + " VALUES (?,?,?,?)", ctx.tenantId(), definitionKey, a, processInstanceId);
            }
            return null;
        });
        return sorted(set);
    }

    @Override
    public StepResult step(RequestContext ctx, String processInstanceId) {
        guard(ctx);
        instance(ctx, processInstanceId);
        List<Job> due = OperatonSupport.call(() -> management.createJobQuery().processInstanceId(processInstanceId)
                .executable().orderByJobDuedate().asc().listPage(0, 1));
        if (due.isEmpty()) {
            return new StepResult(null, null, active(processInstanceId));
        }
        Job job = due.get(0);
        String activity = activityOf(job);
        if (activity != null && stored(ctx.tenantId(), processInstanceId).contains(activity)) {
            return new StepResult("paused at breakpoint " + activity, job.getId(), active(processInstanceId));
        }
        OperatonSupport.run(() -> management.executeJob(job.getId()));
        return new StepResult(activity != null ? activity : job.getId(), job.getId(), active(processInstanceId));
    }

    private String activityOf(Job job) {
        if (job instanceof org.operaton.bpm.engine.impl.persistence.entity.JobEntity je) {
            return je.getActivityId();
        }
        return null;
    }

    private List<String> active(String processInstanceId) {
        ProcessInstance pi = runtime.createProcessInstanceQuery().processInstanceId(processInstanceId).singleResult();
        if (pi == null) {
            return List.of();
        }
        return OperatonSupport.call(() -> runtime.getActiveActivityIds(processInstanceId));
    }

    private void guard(RequestContext ctx) {
        if (!enabled) {
            throw ApiProblem.forbidden("DEBUGGER_DISABLED",
                    "The debugger is disabled on this server (R80: never available on a production profile)");
        }
        if (sandboxTenant != null && !sandboxTenant.equals(ctx.tenantId())) {
            throw ApiProblem.forbidden("DEBUGGER_DISABLED", "The debugger is available only in the sandbox tenant");
        }
    }

    private ProcessInstance instance(RequestContext ctx, String id) {
        ProcessInstance pi = OperatonSupport.call(() ->
                runtime.createProcessInstanceQuery().processInstanceId(id).singleResult());
        if (pi == null) {
            throw ApiProblem.notFound("process instance", id);
        }
        OperatonSupport.checkTenant(ctx, pi.getTenantId(), "process instance", id);
        return pi;
    }

    private Set<String> stored(String tenant, String processInstanceId) {
        List<String> ids = tx.withConnection(c -> Jdbc.query(c,
                "SELECT activity_id FROM DEBUG_BREAKPOINT WHERE tenant_id=? AND process_instance_id=?",
                rs -> rs.getString(1), tenant, processInstanceId));
        return new java.util.TreeSet<String>(ids);
    }

    /** Operaton definition ids are {@code key:version:uuid}. */
    private static String definitionKey(ProcessInstance pi) {
        String def = pi.getProcessDefinitionId();
        int i = def == null ? -1 : def.indexOf(':');
        return i > 0 ? def.substring(0, i) : String.valueOf(def);
    }

    private static List<String> sorted(Set<String> s) {
        List<String> l = new ArrayList<>(s);
        l.sort(String::compareTo);
        return l;
    }

    /** Exposed for configuration checks. */
    public boolean enabled() {
        return enabled && props != null;
    }
}

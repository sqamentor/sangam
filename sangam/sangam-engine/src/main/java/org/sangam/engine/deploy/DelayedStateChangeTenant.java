// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.deploy;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.operaton.bpm.engine.impl.context.Context;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.operaton.bpm.engine.impl.interceptor.Session;
import org.operaton.bpm.engine.impl.interceptor.SessionFactory;
import org.operaton.bpm.engine.impl.jobexecutor.TimerActivateJobDefinitionHandler;
import org.operaton.bpm.engine.impl.jobexecutor.TimerActivateProcessDefinitionHandler;
import org.operaton.bpm.engine.impl.jobexecutor.TimerSuspendJobDefinitionHandler;
import org.operaton.bpm.engine.impl.jobexecutor.TimerSuspendProcessDefinitionHandler;
import org.operaton.bpm.engine.impl.persistence.entity.JobDefinitionEntity;
import org.operaton.bpm.engine.impl.persistence.entity.JobManager;
import org.operaton.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.operaton.bpm.engine.impl.persistence.entity.TimerEntity;
import org.operaton.bpm.engine.repository.ProcessDefinition;
import org.sangam.core.tenant.ExecutionTenantPolicy;
import org.sangam.engine.tenant.WorkTenants;

/**
 * Decision 331: the engine's timer of a delayed suspension or activation belongs to the tenant of the definition it
 * will change.
 *
 * <p>Operaton's {@code AbstractSetStateCmd.scheduleSuspensionStateUpdate} creates that timer with the definition's
 * deployment and no tenant ({@code repositoryService.suspendProcessDefinitionById(id, includeInstances, date)}, the
 * activate twin, the same by key, and the job-definition twins of the management service). A job without a tenant is
 * resolved by decision 144 / 197 ({@link WorkTenants}): it runs only when its deployment is a registered shared
 * deployment, so the delayed change of a tenant-owned definition was refused when its timer came due
 * ({@code TENANT_CONTEXT_MISSING}).
 *
 * <p>The tenant is stamped <b>when the timer is scheduled</b>, from the definition the command addresses, and never
 * derived from the deployment when the job is resolved (that would let untenanted batches over tenant instances run,
 * which SL-45 refuses). With the tenant on the job Operaton runs it with that tenant's authentication, so its tenant
 * check applies to the nested state change as for the synchronous call, and {@link WorkTenants} resolves it as
 * tenant-owned work. The owner is taken from the command's own job handler configuration:
 * <ul>
 *   <li>by process definition id, or by job definition id: the tenant of that definition;</li>
 *   <li>by process definition key with a tenant: that tenant; with "without tenant": none (the shared definitions);</li>
 *   <li>by process definition key alone (every tenant's definitions of the key): the tenant only when every
 *       definition of the key belongs to the same one tenant.</li>
 * </ul>
 * In every other case (a shared definition, definitions of several tenants, an unknown definition, a configuration
 * that cannot be read) the timer is left as Operaton made it, without a tenant, and is resolved as before: it runs for
 * a registered shared deployment and is refused otherwise (fail closed). A timer that already has a tenant, and every
 * other timer and job, is not touched.
 *
 * <p><b>Who schedules.</b> The timer gets the owner's tenant only when the scheduling command is not tenant-scoped
 * (platform code, an Operaton admin) or acts for that very tenant ({@link TenantScopedCreation#callerTenants}).
 * Operaton checks the caller's tenant for a change by definition id, but its {@code TenantCommandChecker} has no check
 * for a change by key ({@code checkUpdateProcessDefinitionSuspensionStateByKey} is empty, javap 2.2.0), so an identity
 * of tenant B can schedule a change of tenant A's definitions by key. Such a timer stays without a tenant and is
 * refused when it comes due, exactly as before this class: a timer scheduled by tenant B never runs as tenant A.
 *
 * <p>Installed as a session factory that replaces Operaton's {@link JobManager} (the {@code customSessionFactories}
 * extension point, PATCHES.md B-46 / I-27); only {@link JobManager#schedule} is overridden, and it always delegates.
 */
public final class DelayedStateChangeTenant {

    /** The handler types of a delayed change of process definitions. */
    static final Set<String> PROCESS_DEFINITION_TYPES = Set.of(TimerSuspendProcessDefinitionHandler.TYPE,
            TimerActivateProcessDefinitionHandler.TYPE);
    /** The handler types of a delayed change of job definitions. */
    static final Set<String> JOB_DEFINITION_TYPES = Set.of(TimerSuspendJobDefinitionHandler.TYPE,
            TimerActivateJobDefinitionHandler.TYPE);

    // the keys of Operaton's canonical handler configuration (pinned by EngineInternalsPinTest i27)
    static final String BY = "by";
    static final String JOB_DEFINITION_ID = "jobDefinitionId";
    static final String PROCESS_DEFINITION_ID = "processDefinitionId";
    static final String PROCESS_DEFINITION_KEY = "processDefinitionKey";
    static final String PROCESS_DEFINITION_TENANT_ID = "processDefinitionTenantId";

    private static final ObjectMapper JSON = new ObjectMapper();

    private DelayedStateChangeTenant() {
    }

    /** The session factory that replaces Operaton's job manager. */
    public static SessionFactory sessionFactory() {
        return new SessionFactory() {
            @Override
            public Class<?> getSessionType() {
                return JobManager.class;
            }

            @Override
            public Session openSession() {
                return new Jobs();
            }
        };
    }

    /** Operaton's job manager; a delayed state change timer gets its definition's tenant before it is inserted. */
    static final class Jobs extends JobManager {
        @Override
        public void schedule(TimerEntity timer) {
            stamp(Context.getCommandContext(), timer);
            super.schedule(timer);
        }
    }

    static void stamp(CommandContext cc, TimerEntity timer) {
        if (timer == null || timer.getTenantId() != null) {
            return;
        }
        String type = timer.getJobHandlerType();
        boolean jobDefinitions = JOB_DEFINITION_TYPES.contains(type);
        if (!jobDefinitions && !PROCESS_DEFINITION_TYPES.contains(type)) {
            return;
        }
        String owner = owner(cc, jobDefinitions, timer.getJobHandlerConfigurationRaw());
        List<String> caller = TenantScopedCreation.callerTenants();
        if (owner != null && (caller == null || caller.contains(owner))) {
            timer.setTenantId(owner);
        }
    }

    /** The one tenant that owns every definition the configuration addresses, or null (see the class comment). */
    static String owner(CommandContext cc, boolean jobDefinitions, String configuration) {
        JsonNode cfg;
        try {
            cfg = configuration == null ? null : JSON.readTree(configuration);
        } catch (java.io.IOException e) {
            return null;
        }
        if (cfg == null || !cfg.isObject()) {
            return null;
        }
        String by = cfg.path(BY).asText("");
        if (jobDefinitions && JOB_DEFINITION_ID.equals(by)) {
            JobDefinitionEntity d = cc.getJobDefinitionManager().findById(cfg.path(JOB_DEFINITION_ID).asText(null));
            return d == null ? null : ExecutionTenantPolicy.normalise(d.getTenantId());
        }
        if (PROCESS_DEFINITION_ID.equals(by)) {
            String id = cfg.path(PROCESS_DEFINITION_ID).asText(null);
            ProcessDefinitionEntity d = id == null ? null
                    : cc.getProcessDefinitionManager().findLatestProcessDefinitionById(id);
            return d == null ? null : ExecutionTenantPolicy.normalise(d.getTenantId());
        }
        if (PROCESS_DEFINITION_KEY.equals(by)) {
            if (cfg.has(PROCESS_DEFINITION_TENANT_ID)) {
                JsonNode tenant = cfg.get(PROCESS_DEFINITION_TENANT_ID);
                return tenant.isNull() ? null : ExecutionTenantPolicy.normalise(tenant.asText());
            }
            String key = cfg.path(PROCESS_DEFINITION_KEY).asText(null);
            List<? extends ProcessDefinition> all = key == null ? List.<ProcessDefinition>of()
                    : cc.getProcessDefinitionManager().findLatestProcessDefinitionsByKey(key);
            Set<String> owners = new LinkedHashSet<>();
            all.forEach(d -> owners.add(ExecutionTenantPolicy.normalise(d.getTenantId())));
            return owners.size() == 1 ? owners.iterator().next() : null;
        }
        return null;
    }
}
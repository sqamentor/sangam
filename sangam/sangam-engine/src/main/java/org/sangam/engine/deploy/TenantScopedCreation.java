// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.deploy;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.operaton.bpm.engine.impl.batch.BatchEntity;
import org.operaton.bpm.engine.impl.context.Context;
import org.operaton.bpm.engine.impl.identity.Authentication;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.operaton.bpm.engine.impl.interceptor.Session;
import org.operaton.bpm.engine.impl.interceptor.SessionFactory;
import org.operaton.bpm.engine.impl.persistence.entity.BatchManager;
import org.operaton.bpm.engine.impl.persistence.entity.DeploymentEntity;
import org.operaton.bpm.engine.impl.persistence.entity.DeploymentManager;
import org.operaton.bpm.engine.impl.persistence.entity.ResourceEntity;
import org.operaton.bpm.engine.impl.persistence.entity.ResourceManager;
import org.operaton.bpm.engine.impl.persistence.entity.TaskEntity;
import org.operaton.bpm.engine.impl.persistence.entity.TaskManager;

/**
 * SL-45: what an identity of one tenant creates in the engine belongs to that tenant.
 *
 * <p>Operaton's tenant check is active while an engine authentication with tenant ids is set: on {@code /engine-rest}
 * ({@code EngineRestTenantFilter} sets exactly the token's tenant), for A2A starts, and for every job of a tenant
 * (Operaton's {@code ExecuteJobsCmd} runs a job of tenant T with the authentication {@code [T]}). It filters queries and
 * refuses commands on another tenant's objects, but it has no check for the two things such an identity creates from
 * scratch, so tenant A changed tenant B through both:
 * <ul>
 *   <li><b>Deployments.</b> {@code TenantCommandChecker#checkCreateDeployment} is empty: a deployment naming another
 *       tenant was created in that tenant, and one naming no tenant became a shared definition that every tenant
 *       resolves. With duplicate filtering, the lookup of the named tenant's latest deployment of that name even
 *       returned the other tenant's deployment. A tenant-scoped identity now deploys only into its own tenant; the
 *       creation ({@link DeploymentManager#insertDeployment}) and the duplicate lookup
 *       ({@link ResourceManager#findLatestResourcesByDeploymentName}) refuse anything else.</li>
 *   <li><b>Batches.</b> Operaton creates a batch of the given ids without a tenant (only batches over one definition
 *       name one), and runs a job of no tenant without any authentication, so the batch's per-element commands
 *       (delete, suspend, set variables, correlate, ...) ran unchecked on another tenant's instances. A batch created by
 *       a tenant-scoped identity now belongs to that tenant ({@link BatchManager#insertBatch}, before Operaton derives
 *       the seed, monitor and execution job definitions from it); its jobs inherit the tenant, and Operaton runs them
 *       with that tenant's authentication, so every element is checked exactly like the synchronous call. The batch is
 *       also visible to and controllable by its own tenant only (it was shared before).</li>
 *   <li><b>Standalone tasks</b> (SL-73). A task created without a process or case ({@code TaskService#saveTask},
 *       {@code POST /engine-rest/task/create}) had no tenant unless the caller named one, and Operaton's tenant check
 *       shows rows without a tenant to every tenant, so tenant A's standalone task was visible and usable by tenant B.
 *       A standalone task of a tenant-scoped identity now belongs to the caller's tenant
 *       ({@link TaskManager#insertTask}); naming another tenant is refused. Tasks of process or case instances keep the
 *       tenant Operaton gives them from their instance.</li>
 * </ul>
 * A refusal is a {@link TenantIsolationException} (403 {@code TENANT_ISOLATION_VIOLATION} on {@code /api} and
 * {@code /engine-rest}), and the command rolls back. Without a tenant-scoped engine authentication (platform code, jobs
 * of shared definitions, an Operaton admin, a command with its tenant check switched off) nothing changes: the
 * predicate is Operaton's own {@code TenantManager#isTenantCheckEnabled}. Installed as session factories (Operaton's
 * {@code customSessionFactories} extension point) by {@code SangamProcessEnginePlugin}.
 */
public final class TenantScopedCreation {

    private TenantScopedCreation() {
    }

    /** The session factories that replace Operaton's batch, deployment, resource and task managers. */
    public static List<SessionFactory> sessionFactories() {
        return List.of(factory(BatchManager.class, Batches::new), factory(DeploymentManager.class, Deployments::new),
                factory(ResourceManager.class, Resources::new), factory(TaskManager.class, Tasks::new));
    }

    /**
     * The tenants the current command acts for, or {@code null} when it is not tenant-scoped: no command, no engine
     * authentication, an Operaton admin, or the tenant check switched off (Operaton's own predicate).
     */
    static List<String> callerTenants() {
        // Operaton 2.2: Context.getCommandContext() throws outside a command (ENGINE-09025), it never answers null
        CommandContext cc = Context.hasActiveCommandContext() ? Context.getCommandContext() : null;
        if (cc == null || !cc.getTenantManager().isTenantCheckEnabled()) {
            return null;
        }
        Authentication a = cc.getAuthentication();
        return a == null || a.getTenantIds() == null ? List.of() : a.getTenantIds();
    }

    /** Refuses creating {@code element} in {@code target} (null: shared) unless the caller acts for that tenant. */
    static void requireCallersTenant(String element, String target) {
        List<String> caller = callerTenants();
        if (caller == null || (target != null && caller.contains(target))) {
            return;
        }
        String callers = String.join(",", caller);
        throw new TenantIsolationException(element, callers, target, element + " of tenant " + callers
                + " may not be created " + (target == null ? "without a tenant (shared by every tenant)" : "in tenant "
                + target) + "; an identity of one tenant creates only in its own tenant");
    }

    /** Creation of a deployment: only into the caller's own tenant. */
    static final class Deployments extends DeploymentManager {
        @Override
        public void insertDeployment(DeploymentEntity deployment) {
            requireCallersTenant("deployment '" + deployment.getName() + "'", deployment.getTenantId());
            super.insertDeployment(deployment);
        }
    }

    /**
     * Duplicate filtering looks up the latest deployment of the name in the deployment's tenant, and a duplicate returns
     * that deployment: refused for another tenant before the lookup, so nothing of it is read or returned.
     */
    static final class Resources extends ResourceManager {
        @Override
        public Map<String, ResourceEntity> findLatestResourcesByDeploymentName(String deploymentName,
                                                                               Set<String> resourcesToFind,
                                                                               String source, String tenantId) {
            requireCallersTenant("deployment '" + deploymentName + "'", tenantId);
            return super.findLatestResourcesByDeploymentName(deploymentName, resourcesToFind, source, tenantId);
        }
    }

    /** A batch of a tenant-scoped identity belongs to its tenant, so its jobs run (and are checked) as that tenant. */
    static final class Batches extends BatchManager {
        @Override
        public void insertBatch(BatchEntity batch) {
            List<String> caller = callerTenants();
            if (caller != null) {
                String element = "batch '" + batch.getType() + "'";
                if (batch.getTenantId() != null) {
                    requireCallersTenant(element, batch.getTenantId());
                } else if (caller.size() == 1) {
                    batch.setTenantId(caller.get(0));
                } else {
                    // fail closed: an unowned batch would run its elements without any tenant check
                    String callers = String.join(",", caller);
                    throw new TenantIsolationException(element, callers, null, element + " needs exactly one tenant of"
                            + " the caller to belong to, the identity has [" + callers + "]");
                }
            }
            super.insertBatch(batch);
        }
    }

    /**
     * SL-73: a standalone task (no execution, no case execution) of a tenant-scoped identity belongs to the caller's
     * tenant, like a batch: Operaton's {@code SaveTaskCmd} inserts it through {@link TaskManager#insertTask} after its
     * own checks ({@code checkCreateTask}) and after taking a parent task's tenant.
     */
    static final class Tasks extends TaskManager {
        @Override
        public void insertTask(TaskEntity task) {
            List<String> caller = callerTenants();
            if (caller != null && task.getExecutionId() == null && task.getCaseExecutionId() == null) {
                String element = "standalone task '" + task.getId() + "'";
                if (task.getTenantId() != null) {
                    requireCallersTenant(element, task.getTenantId());
                } else if (caller.size() == 1) {
                    task.setTenantId(caller.get(0));
                } else {
                    // fail closed: a task without a tenant is visible to every tenant
                    String callers = String.join(",", caller);
                    throw new TenantIsolationException(element, callers, null, element + " needs exactly one tenant of"
                            + " the caller to belong to, the identity has [" + callers + "]");
                }
            }
            super.insertTask(task);
        }

        // SG-03(c): task suspension events. Operaton suspends and activates tasks only through these bulk UPDATEs
        // (single instance, by definition, by definition key, batch and timer jobs all end here; nested commands are
        // executed directly, so no command interceptor sees them), which fire no task listener. Each override reads the
        // tasks of its scope that are in the opposite state, runs the UPDATE, and reports exactly those tasks to
        // TaskSuspensionEvents in the same command: one event per task whose state changed, none when nothing changed.

        @Override
        public void updateTaskSuspensionStateByProcessInstanceId(String processInstanceId,
                                                                 org.operaton.bpm.engine.impl.persistence.entity.SuspensionState state) {
            var before = changing(q -> q.processInstanceId(processInstanceId), state, false);
            super.updateTaskSuspensionStateByProcessInstanceId(processInstanceId, state);
            report(before, state);
        }

        @Override
        public void updateTaskSuspensionStateByProcessDefinitionId(String processDefinitionId,
                                                                   org.operaton.bpm.engine.impl.persistence.entity.SuspensionState state) {
            var before = changing(q -> q.processDefinitionId(processDefinitionId), state, false);
            super.updateTaskSuspensionStateByProcessDefinitionId(processDefinitionId, state);
            report(before, state);
        }

        @Override
        public void updateTaskSuspensionStateByProcessDefinitionKey(String processDefinitionKey,
                                                                    org.operaton.bpm.engine.impl.persistence.entity.SuspensionState state) {
            var before = changing(q -> q.processDefinitionKey(processDefinitionKey), state, true);
            super.updateTaskSuspensionStateByProcessDefinitionKey(processDefinitionKey, state);
            report(before, state);
        }

        @Override
        public void updateTaskSuspensionStateByProcessDefinitionKeyAndTenantId(String processDefinitionKey,
                String processDefinitionTenantId, org.operaton.bpm.engine.impl.persistence.entity.SuspensionState state) {
            var before = changing(q -> {
                q.processDefinitionKey(processDefinitionKey);
                if (processDefinitionTenantId == null) {
                    q.withoutTenantId();
                } else {
                    q.tenantIdIn(processDefinitionTenantId);
                }
            }, state, true);
            super.updateTaskSuspensionStateByProcessDefinitionKeyAndTenantId(processDefinitionKey,
                    processDefinitionTenantId, state);
            report(before, state);
        }

        @Override
        public void updateTaskSuspensionStateByCaseExecutionId(String caseExecutionId,
                                                               org.operaton.bpm.engine.impl.persistence.entity.SuspensionState state) {
            var before = changing(q -> q.caseExecutionId(caseExecutionId), state, false);
            super.updateTaskSuspensionStateByCaseExecutionId(caseExecutionId, state);
            report(before, state);
        }

        private static String engineName() {
            var cfg = Context.getProcessEngineConfiguration();
            return cfg == null ? null : cfg.getProcessEngineName();
        }

        private static boolean suspending(org.operaton.bpm.engine.impl.persistence.entity.SuspensionState state) {
            return state.getStateCode()
                    == org.operaton.bpm.engine.impl.persistence.entity.SuspensionState.SUSPENDED.getStateCode();
        }

        /**
         * The tasks of the scope in the opposite state (they are the ones the UPDATE changes), read with the command's
         * authorization check off (the command has already been authorised) and with the tenant check exactly as the
         * UPDATE has it, so the events cover exactly the rows it changes. Empty when the engine has no listener.
         *
         * <p>{@code tenantChecked}: Operaton's UPDATE by process definition key (with or without a tenant id) carries
         * the caller's tenant check in its statement ({@code Task.xml updateTaskSuspensionStateByParametersSql},
         * {@code queryTenantCheckWithPrefix} in the {@code processDefinitionKey} branch), so the read keeps the
         * command's tenant check: an identity of tenant B changing a key by key changes none of tenant A's tasks, and
         * none of them may be reported (SL-45). The UPDATEs by instance, definition id and case execution have no tenant
         * check of their own (Operaton's {@code TenantCommandChecker} checked the addressed object first), so the read
         * has none either.
         */
        private List<org.sangam.core.engine.TaskSuspensionEvents.ChangedTask> changing(
                java.util.function.Consumer<org.operaton.bpm.engine.impl.TaskQueryImpl> scope,
                org.operaton.bpm.engine.impl.persistence.entity.SuspensionState state, boolean tenantChecked) {
            if (!org.sangam.core.engine.TaskSuspensionEvents.hasListeners(engineName())) {
                return List.of();
            }
            org.operaton.bpm.engine.impl.TaskQueryImpl q = new org.operaton.bpm.engine.impl.TaskQueryImpl();
            scope.accept(q);
            if (suspending(state)) {
                q.active();
            } else {
                q.suspended();
            }
            CommandContext cc = Context.getCommandContext();
            boolean auth = cc.isAuthorizationCheckEnabled();
            boolean tenant = cc.isTenantCheckEnabled();
            cc.disableAuthorizationCheck();
            if (!tenantChecked) {
                cc.disableTenantCheck();
            }
            try {
                List<org.sangam.core.engine.TaskSuspensionEvents.ChangedTask> out = new java.util.ArrayList<>();
                for (org.operaton.bpm.engine.task.Task t : findTasksByQueryCriteria(q)) {
                    out.add(new org.sangam.core.engine.TaskSuspensionEvents.ChangedTask(t.getId(), t.getTenantId(),
                            t.getProcessInstanceId(), t.getCaseInstanceId(), t.getTaskDefinitionKey(), t.getAssignee(),
                            t.getOwner()));
                }
                return out;
            } finally {
                cc.setAuthorizationCheckEnabled(auth);
                cc.setTenantCheckEnabled(tenant);
            }
        }

        private static void report(List<org.sangam.core.engine.TaskSuspensionEvents.ChangedTask> tasks,
                                   org.operaton.bpm.engine.impl.persistence.entity.SuspensionState state) {
            org.sangam.core.engine.TaskSuspensionEvents.fire(engineName(), tasks, suspending(state));
        }
    }

    private static SessionFactory factory(Class<? extends Session> type, java.util.function.Supplier<Session> manager) {
        return new SessionFactory() {
            @Override
            public Class<?> getSessionType() {
                return type;
            }

            @Override
            public Session openSession() {
                return manager.get();
            }
        };
    }
}

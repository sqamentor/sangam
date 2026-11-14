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
        CommandContext cc = Context.getCommandContext();
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

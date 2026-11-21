// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.sangam.packs.spi.PackDeployer;
import org.sangam.packs.spi.PackRegistry;
import org.sangam.packs.spi.ProfileTableDeployer;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deploys the discovered packs (sangam-packs §2) for one tenant: one engine deployment per pack
 * ({@code pack:<id>@<version>}, duplicate filtering on, {@code ModelGuard} checks), so repeated calls are idempotent.
 * Called at start-up for {@code sangam.packs.tenants} and, from WS-REST, when a tenant is created or activated
 * ({@code POST/PUT /admin/tenants}, {@code POST /admin/tenants/{id}/activate}) or before a tenant's first package
 * deploy. Each call is audited
 * ({@code PACKS_DEPLOYED} on {@code sec:<tenant>}).
 *
 * <p>DEC-225-4 ({@link #useProfileTables}): with the packs, the tenant's own delegation tables from its configuration
 * profile ({@code delegation.tables}, {@link ProfileTableDeployer}), checked before any pack is deployed; a refused set
 * is 422 with the refusal code and audited ({@code PROFILE_TABLES_REFUSED}), a deployed one is audited
 * ({@code PROFILE_TABLES_DEPLOYED}). {@link #syncTables} re-deploys them after a profile change.
 */
public final class PacksTenantDeployer {

    private static final Logger LOG = LoggerFactory.getLogger(PacksTenantDeployer.class);

    private final PackDeployer deployer;
    private final PackRegistry registry;
    private final AuditPort audit;
    private final Set<String> deployed = ConcurrentHashMap.newKeySet();
    private final Map<String, Set<String>> selections = new ConcurrentHashMap<>();

    public PacksTenantDeployer(PackDeployer deployer, PackRegistry registry, AuditPort audit) {
        this.deployer = deployer;
        this.registry = registry;
        this.audit = audit;
    }

    /** The packs an onboarded tenant chose: later automatic provisioning deploys only these. */
    public void select(String tenantId, Set<String> packIds) {
        selections.put(tenantId, Set.copyOf(packIds));
    }

    /** Deploys the tenant's selection (every pack when it has none) for {@code tenantId}; returns {@code packId@version → deploymentId}. */
    public Map<String, String> deploy(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "tenant id is required");
        }
        // DEC-225-4: the profile's own tables are checked before any pack is deployed, so a refused set leaves the
        // tenant as it was
        long began = System.nanoTime();
        ProfileTableDeployer.Plan plan = planTables(tenantId);
        List<PackDeployer.Result> results;
        try {
            Set<String> selection = selections.get(tenantId);
            results = selection == null ? deployer.deployAll(tenantId) : deployer.deploy(selection, tenantId);
        } catch (PackDeployer.PackRejectedException e) {
            throw ApiProblem.unprocessable("PACK_REJECTED", e.getMessage()).with("violations", e.violations());
        } catch (PackDeployer.PackLifecycleException e) {
            throw lifecycleProblem(e);
        }
        Map<String, String> out = new LinkedHashMap<>();
        List<String> packs = new ArrayList<>();
        for (PackDeployer.Result r : results) {
            out.put(r.packId() + "@" + r.version(), r.deploymentId());
            packs.add(r.packId() + "@" + r.version());
        }
        if (plan != null) {
            for (ProfileTableDeployer.Result r : applyTables(plan).tables()) {
                out.put(ProfileTableDeployer.NAME_PREFIX + r.key() + "@" + r.version(), r.deploymentId());
            }
        }
        deployed.add(tenantId);
        try {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("packs", packs);
            p.put("count", packs.size());
            audit.record(RequestContext.system(tenantId, "packs-deployer"), "sec:" + tenantId, "PACKS_DEPLOYED", p);
        } catch (RuntimeException e) {
            LOG.warn("PACKS_DEPLOYED audit for tenant {} failed: {}", tenantId, e.toString());
        }
        // register E-33 (8): what a start pays for this tenant; "new" is 0 when every pack was already deployed
        LOG.info("Deployed {} packs for tenant {} in {} ms ({} new deployments)", packs.size(), tenantId,
                (System.nanoTime() - began) / 1_000_000, results.stream().filter(PackDeployer.Result::newDeployment).count());
        return out;
    }

    /**
     * A refused pack lifecycle step as the pack lifecycle API renders it ({@code PackLifecycleController.call}):
     * {@code PACK_NOT_INSTALLED} 404, every other code 409 (SL-116: {@code PACK_OPERATION_IN_PROGRESS} when another
     * deploy, rollback or uninstall of the same pack and tenant held its lock too long), with {@code blockers} when any.
     */
    static ApiProblem lifecycleProblem(PackDeployer.PackLifecycleException e) {
        ApiProblem p = "PACK_NOT_INSTALLED".equals(e.code())
                ? new ApiProblem(404, e.code(), e.getMessage())
                : ApiProblem.conflict(e.code(), e.getMessage());
        return e.blockers().isEmpty() ? p : p.with("blockers", e.blockers());
    }

    /** DEC-225-4: where a tenant's own delegation tables come from (its configuration profile). */
    @FunctionalInterface
    public interface TenantTables {
        /**
         * The tenant's {@code delegation.tables}; null when the tenant has no profile. Throws
         * {@link ProfileTableDeployer.ProfileTableException} when its profile cannot be used.
         */
        List<Map<String, Object>> of(String tenantId);
    }

    /** DEC-225-4: deploy each tenant's own delegation tables with its packs, and on {@link #syncTables}. */
    public void useProfileTables(ProfileTableDeployer deployer, TenantTables source) {
        this.tableDeployer = deployer;
        this.tableSource = source;
    }

    private volatile ProfileTableDeployer tableDeployer;
    private volatile TenantTables tableSource;

    /** The checked tables of the tenant's profile; null when profile tables are not wired or the tenant has none. */
    private ProfileTableDeployer.Plan planTables(String tenantId) {
        ProfileTableDeployer d = tableDeployer;
        TenantTables s = tableSource;
        if (d == null || s == null) {
            return null;
        }
        try {
            List<Map<String, Object>> tables = s.of(tenantId);
            return tables == null ? null : d.plan(tenantId, tables);
        } catch (ProfileTableDeployer.ProfileTableException e) {
            auditTables(tenantId, "PROFILE_TABLES_REFUSED", Map.of("code", e.code(), "reason", e.getMessage()));
            throw ApiProblem.unprocessable(e.code(), e.getMessage());
        }
    }

    private ProfileTableDeployer.Sync applyTables(ProfileTableDeployer.Plan plan) {
        ProfileTableDeployer.Sync sync = tableDeployer.apply(plan);
        if (sync.tables().isEmpty() && sync.removed().isEmpty()) {
            return sync;   // a tenant without own tables, before and after: nothing to record
        }
        List<String> tables = new ArrayList<>();
        for (ProfileTableDeployer.Result r : sync.tables()) {
            tables.add(r.key() + "@" + r.version() + " " + r.file());
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("tables", tables);
        p.put("removed", sync.removed());
        auditTables(plan.tenantId(), "PROFILE_TABLES_DEPLOYED", p);
        LOG.info("Profile tables of tenant {}: {} in force, {} removed", plan.tenantId(), tables.size(),
                sync.removed().size());
        return sync;
    }

    /**
     * Re-deploys the tenant's own delegation tables after a profile change (activation, retirement, import), without
     * its packs. Refused tables leave the tenant's tables as they were; the refusal is audited
     * ({@code PROFILE_TABLES_REFUSED}) and thrown as a 422 problem.
     */
    public void syncTables(String tenantId) {
        ProfileTableDeployer.Plan plan = planTables(tenantId);
        if (plan != null) {
            applyTables(plan);
        } else if (tableDeployer != null && tableSource != null) {
            // no profile any more: withdraw every table it had deployed
            applyTables(tableDeployer.plan(tenantId, List.of()));
        }
    }

    /** Tenants whose tables a profile change re-syncs: deployed by this node or holding pack or table deployments. */
    public Set<String> tenantsToSync() {
        Set<String> out = new java.util.TreeSet<>(deployed);
        ProfileTableDeployer d = tableDeployer;
        if (d != null) {
            out.addAll(d.deployedTenants());
        }
        return out;
    }

    private void auditTables(String tenantId, String event, Map<String, Object> payload) {
        try {
            audit.record(RequestContext.system(tenantId, "packs-deployer"), "sec:" + tenantId, event, payload);
        } catch (RuntimeException e) {
            LOG.warn("{} audit for tenant {} failed: {}", event, tenantId, e.toString());
        }
    }

    /** Deploys once per tenant per node lifetime (the engine's duplicate filtering keeps later calls cheap anyway). */
    public void ensureDeployed(String tenantId) {
        if (!deployed.contains(tenantId)) {
            deploy(tenantId);
        }
    }

    public int packCount() {
        return registry.all().size();
    }
}

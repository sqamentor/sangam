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
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deploys the discovered packs (sangam-packs §2) for one tenant: one engine deployment per pack
 * ({@code pack:<id>@<version>}, duplicate filtering on, {@code ModelGuard} checks), so repeated calls are idempotent.
 * Called at start-up for {@code sangam.packs.tenants} and, from WS-REST, when a tenant is created or activated
 * ({@code POST/PUT /admin/tenants}) or before a tenant's first package deploy. Each call is audited
 * ({@code PACKS_DEPLOYED} on {@code sec:<tenant>}).
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
        List<PackDeployer.Result> results;
        try {
            Set<String> selection = selections.get(tenantId);
            results = selection == null ? deployer.deployAll(tenantId) : deployer.deploy(selection, tenantId);
        } catch (PackDeployer.PackRejectedException e) {
            throw ApiProblem.unprocessable("PACK_REJECTED", e.getMessage()).with("violations", e.violations());
        }
        Map<String, String> out = new LinkedHashMap<>();
        List<String> packs = new ArrayList<>();
        for (PackDeployer.Result r : results) {
            out.put(r.packId() + "@" + r.version(), r.deploymentId());
            packs.add(r.packId() + "@" + r.version());
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
        LOG.info("Deployed {} packs for tenant {}", packs.size(), tenantId);
        return out;
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

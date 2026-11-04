// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.tenancy;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.repository.Deployment;
import org.operaton.bpm.engine.repository.DeploymentBuilder;
import org.operaton.bpm.engine.repository.ProcessDefinition;
import org.operaton.bpm.engine.runtime.ActivityInstance;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.runtime.ProcessInstantiationBuilder;
import org.sangam.ports.batch.ProductBatchService;
import org.sangam.ports.links.InstanceLinkService;
import org.sangam.ports.support.EngineTx;

import java.io.InputStream;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Change-tenant tooling (FC-35; Flowable {@code ChangeTenantIdBuilder}). Route 2 with no direct engine-table update:
 * <ol>
 *   <li>{@link #copyDefinition}: redeploys the source definition's deployment resources into the target tenant
 *       (duplicate filtering, so it is idempotent).</li>
 *   <li>{@link #moveInstance}: Operaton migration refuses to cross tenants, so a running instance is <em>cut over</em>:
 *       a new instance of the target-tenant definition is started at the same active activities
 *       ({@code startBeforeActivity} per leaf activity instance) with the instance's variables and business key; the
 *       old instance is cancelled; a {@code change-tenant} link joins them; the {@link ProductRowRewriter} rewrites
 *       product rows (ledger, outbox roots, documents, links) in the same transaction.</li>
 * </ol>
 * Limits (documented): history of the old instance stays in the source tenant; task, job and external-task ids are
 * new (open leases are cancelled; workers get {@code stale-lease}); multi-instance and async-before states restart
 * their activity. Batched by {@link #asBatchHandler} for whole tenants.
 */
public class ChangeTenantService {

    /** Rewrites product-layer rows that reference the old instance (implemented by each owning module). */
    @FunctionalInterface
    public interface ProductRowRewriter {
        void rewrite(Connection c, String oldInstanceId, String newInstanceId, String fromTenant, String toTenant);
    }

    private final ProcessEngine engine;
    private final InstanceLinkService links;
    private final List<ProductRowRewriter> rewriters;

    public ChangeTenantService(ProcessEngine engine, InstanceLinkService links, List<ProductRowRewriter> rewriters) {
        this.engine = engine;
        this.links = links;
        this.rewriters = rewriters == null ? List.of() : List.copyOf(rewriters);
    }

    /** Returns the id of the equivalent definition in {@code targetTenant}. */
    public String copyDefinition(String processDefinitionId, String targetTenant) {
        if (targetTenant == null || targetTenant.isBlank() || "null".equals(targetTenant)) {
            // FW-46: a missing batch config value must not become the literal tenant "null"
            throw new IllegalArgumentException("targetTenant is required");
        }
        ProcessDefinition src = engine.getRepositoryService().getProcessDefinition(processDefinitionId);
        if (targetTenant.equals(src.getTenantId())) {
            return src.getId();
        }
        DeploymentBuilder db = engine.getRepositoryService().createDeployment().name(src.getKey() + "@" + targetTenant)
                .source("sangam-change-tenant").tenantId(targetTenant).enableDuplicateFiltering(false);
        for (String res : engine.getRepositoryService().getDeploymentResourceNames(src.getDeploymentId())) {
            try (InputStream in = engine.getRepositoryService().getResourceAsStream(src.getDeploymentId(), res)) {
                db.addInputStream(res, new java.io.ByteArrayInputStream(in.readAllBytes()));
            } catch (java.io.IOException e) {
                throw new ProcessEngineException("cannot read resource " + res, e);
            }
        }
        Deployment d = db.deploy();
        ProcessDefinition copy = engine.getRepositoryService().createProcessDefinitionQuery().processDefinitionKey(src.getKey())
                .tenantIdIn(targetTenant).latestVersion().singleResult();
        if (copy == null) {
            throw new ProcessEngineException("definition " + src.getKey() + " not found in tenant " + targetTenant + " after deployment " + d.getId());
        }
        return copy.getId();
    }

    /** Cuts one running instance over to {@code targetTenant}; returns the new process instance id. */
    public String moveInstance(String processInstanceId, String targetTenant, String user) {
        ProcessInstance pi = engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(processInstanceId).singleResult();
        if (pi == null) {
            throw new ProcessEngineException("process instance " + processInstanceId + " not found");
        }
        if (pi.getRootProcessInstanceId() != null && !pi.getRootProcessInstanceId().equals(pi.getId())) {
            throw new ProcessEngineException("move the root instance; called instances follow their caller");
        }
        String targetDef = copyDefinition(pi.getProcessDefinitionId(), targetTenant);
        ActivityInstance tree = engine.getRuntimeService().getActivityInstance(processInstanceId);
        List<String> leaves = new ArrayList<>();
        collectLeaves(tree, leaves);
        Map<String, Object> vars = engine.getRuntimeService().getVariables(processInstanceId);
        return EngineTx.execute(engine, cc -> {
            ProcessInstantiationBuilder b = engine.getRuntimeService().createProcessInstanceById(targetDef)
                    .businessKey(pi.getBusinessKey()).setVariables(vars);
            for (String a : leaves) {
                b.startBeforeActivity(a);
            }
            String newId = b.execute().getId();
            engine.getRuntimeService().deleteProcessInstance(processInstanceId, "moved to tenant " + targetTenant + " as " + newId);
            Connection c = EngineTx.connection();
            links.link(c, targetTenant, "change-tenant", null, "process", processInstanceId, "process", newId, user);
            for (ProductRowRewriter r : rewriters) {
                r.rewrite(c, processInstanceId, newId, pi.getTenantId(), targetTenant);
            }
            return newId;
        });
    }

    /** Batch handler: item = process instance id, config {@code targetTenant}. */
    public ProductBatchService.ItemHandler asBatchHandler() {
        return (tenant, ref, cfg) -> "moved to " + moveInstance(ref, String.valueOf(cfg.get("targetTenant")), String.valueOf(cfg.get("user")));
    }

    private static void collectLeaves(ActivityInstance ai, List<String> out) {
        if (ai.getChildActivityInstances().length == 0 && ai.getChildTransitionInstances().length == 0) {
            if (ai.getParentActivityInstanceId() != null) {
                out.add(ai.getActivityId());
            }
            return;
        }
        for (ActivityInstance c : ai.getChildActivityInstances()) {
            collectLeaves(c, out);
        }
        for (var t : ai.getChildTransitionInstances()) {
            out.add(t.getActivityId());
        }
    }
}

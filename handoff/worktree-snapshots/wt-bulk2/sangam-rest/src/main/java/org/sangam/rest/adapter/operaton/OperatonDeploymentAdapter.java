// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.repository.DeploymentBuilder;
import org.operaton.bpm.engine.repository.DeploymentQuery;
import org.sangam.core.layer.Hashes;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.DeploymentPort;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.TxSupport;

/**
 * Raw engine deployments on the Operaton {@link RepositoryService}, always into the caller's tenant. Each create and
 * delete is a class-B event on the tenant security chain ({@code sec:<tenant>}) with the SHA-256 of every resource.
 * Governed releases (R43 review, four-eyes, hash pinning) use {@code PackagePort}, which deploys through this port.
 */
public class OperatonDeploymentAdapter implements DeploymentPort {

    private static final long CAP = 10_000;
    private static final long MAX_RESOURCE_BYTES = 10L * 1024 * 1024;

    private final RepositoryService repository;
    private final TxSupport tx;
    private final AuditPort audit;

    public OperatonDeploymentAdapter(RepositoryService repository, TxSupport tx, AuditPort audit) {
        this.repository = repository;
        this.tx = tx;
        this.audit = audit;
    }

    @Override
    public Deployment deploy(RequestContext ctx, DeployRequest r) {
        if (r == null || r.resources() == null || r.resources().isEmpty()) {
            throw ApiProblem.badRequest("RESOURCES_REQUIRED", "at least one resource is required");
        }
        Map<String, String> hashes = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : r.resources().entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank() || e.getKey().contains("..")) {
                throw ApiProblem.badRequest("INVALID_RESOURCE_NAME", "invalid resource name " + e.getKey());
            }
            if (e.getValue() == null || e.getValue().length > MAX_RESOURCE_BYTES) {
                throw ApiProblem.unprocessable("RESOURCE_TOO_LARGE", e.getKey() + " is empty or above 10 MB");
            }
            hashes.put(e.getKey(), Hashes.hex(Hashes.sha256(e.getValue())));
        }
        return tx.inTx(() -> {
            DeploymentBuilder b = repository.createDeployment()
                    .name(r.name() == null || r.name().isBlank() ? "sangam-deployment" : r.name())
                    .source(r.source() == null ? "sangam-rest" : r.source());
            if (ctx.tenantId() != null) {
                b = b.tenantId(ctx.tenantId());
            }
            if (r.duplicateFiltering()) {
                b = b.enableDuplicateFiltering(true);
            }
            for (Map.Entry<String, byte[]> e : r.resources().entrySet()) {
                b = b.addInputStream(e.getKey(), new ByteArrayInputStream(e.getValue()));
            }
            DeploymentBuilder fb = b;
            org.operaton.bpm.engine.repository.Deployment d = OperatonSupport.call(fb::deploy);
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("deployment_id", d.getId());
            p.put("name", d.getName());
            p.put("source", d.getSource());
            p.put("resources", hashes);
            p.put("actor", ctx.actor());
            p.values().removeIf(Objects::isNull);
            audit.record(ctx, securityChain(ctx), "DEPLOYMENT_CREATED", p);
            return map(d);
        });
    }

    @Override
    public Page<Deployment> list(RequestContext ctx, String name, Page.PageRequest page) {
        DeploymentQuery q = repository.createDeploymentQuery();
        if (ctx.tenantId() != null) {
            q.tenantIdIn(ctx.tenantId()).includeDeploymentsWithoutTenantId();
        }
        if (name != null) {
            q.deploymentNameLike("%" + name + "%");
        }
        q.orderByDeploymentTime().desc();
        return OperatonSupport.page(q, page, CAP, this::map);
    }

    @Override
    public Deployment get(RequestContext ctx, String deploymentId) {
        return map(require(ctx, deploymentId));
    }

    @Override
    public byte[] resource(RequestContext ctx, String deploymentId, String resourceName) {
        require(ctx, deploymentId);
        List<String> names = OperatonSupport.call(() -> repository.getDeploymentResourceNames(deploymentId));
        if (!names.contains(resourceName)) {
            throw ApiProblem.notFound("deployment resource", resourceName);
        }
        try (InputStream in = OperatonSupport.call(() -> repository.getResourceAsStream(deploymentId, resourceName))) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new ApiProblem(500, "RESOURCE_READ_FAILED", e.getMessage());
        }
    }

    @Override
    public void delete(RequestContext ctx, String deploymentId, boolean cascade, String reason) {
        if (reason == null || reason.isBlank()) {
            throw ApiProblem.badRequest("REASON_REQUIRED", "A reason is required to delete a deployment");
        }
        tx.runInTx(() -> {
            org.operaton.bpm.engine.repository.Deployment d = require(ctx, deploymentId);
            if (d.getTenantId() == null && ctx.tenantId() != null && !ctx.inGroup("sangam-admin")) {
                throw ApiProblem.notPermitted("deployment.delete (shared deployment)");
            }
            // Without cascade, Operaton refuses while instances exist (mapped to 422 ENGINE_REJECTED).
            OperatonSupport.run(() -> repository.deleteDeployment(deploymentId, cascade));
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("deployment_id", deploymentId);
            p.put("name", d.getName());
            p.put("cascade", cascade);
            p.put("reason", reason);
            p.put("actor", ctx.actor());
            p.values().removeIf(Objects::isNull);
            audit.record(ctx, securityChain(ctx), "DEPLOYMENT_DELETED", p);
        });
    }

    private org.operaton.bpm.engine.repository.Deployment require(RequestContext ctx, String id) {
        org.operaton.bpm.engine.repository.Deployment d = OperatonSupport.call(() ->
                repository.createDeploymentQuery().deploymentId(id).singleResult());
        if (d == null) {
            throw ApiProblem.notFound("deployment", id);
        }
        if (d.getTenantId() != null) {
            OperatonSupport.checkTenant(ctx, d.getTenantId(), "deployment", id);
        }
        return d;
    }

    private Deployment map(org.operaton.bpm.engine.repository.Deployment d) {
        String id = d.getId();
        List<String> resources = OperatonSupport.call(() -> repository.getDeploymentResourceNames(id));
        List<DeployedDefinition> defs = new ArrayList<>();
        OperatonSupport.call(() -> repository.createProcessDefinitionQuery().deploymentId(id).list())
                .forEach(p -> defs.add(new DeployedDefinition(p.getId(), p.getKey(), "bpmn", p.getVersion(), p.getName())));
        OperatonSupport.call(() -> repository.createDecisionDefinitionQuery().deploymentId(id).list())
                .forEach(p -> defs.add(new DeployedDefinition(p.getId(), p.getKey(), "dmn", p.getVersion(), p.getName())));
        OperatonSupport.call(() -> repository.createCaseDefinitionQuery().deploymentId(id).list())
                .forEach(p -> defs.add(new DeployedDefinition(p.getId(), p.getKey(), "cmmn", p.getVersion(), p.getName())));
        for (String name : resources) {
            if (name.endsWith(".form")) {
                String key = name.substring(name.lastIndexOf('/') + 1, name.length() - ".form".length());
                defs.add(new DeployedDefinition(id + ":" + name, key, "form", 1, name));
            }
        }
        return new Deployment(id, d.getName(), d.getSource(), d.getTenantId(),
                OperatonSupport.instant(d.getDeploymentTime()), resources, defs);
    }

    private static String securityChain(RequestContext ctx) {
        return "sec:" + (ctx.tenantId() == null ? "default" : ctx.tenantId());
    }
}

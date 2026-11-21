// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.repository.ProcessDefinitionQuery;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.ProcessPort.DefinitionFilter;
import org.sangam.rest.port.ProcessPort.ProcessDefinition;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.RestProperties;

/**
 * Process definition reads for {@link OperatonProcessAdapter}: the tenant-scoped list, one definition, its BPMN XML, and
 * each definition's running instance and open incident counts (UI-DEMO-5). Read-only: no transaction, no audit.
 */
final class OperatonProcessDefinitions {

    private final RuntimeService runtime;
    private final RepositoryService repository;
    private final RestProperties props;

    OperatonProcessDefinitions(RuntimeService runtime, RepositoryService repository, RestProperties props) {
        this.runtime = runtime;
        this.repository = repository;
        this.props = props;
    }

    /**
     * The scope a start by key takes: true = the caller tenant's own definition, false = the shared one (a caller
     * without a tenant always takes the shared scope). Register E-33 (g): when the caller's tenant has no definition
     * of the key and the shared scope has none either, the answer is 404 naming the key and the caller's tenant; left
     * to the engine, it named the shared scope it was sent to ("no processes deployed with key ... and tenant-id
     * 'null'"). A definition of another tenant is not looked at, so nothing about it is told.
     */
    boolean startsInCallersTenant(RequestContext ctx, String key) {
        if (ctx.tenantId() == null) {
            return false;
        }
        if (count(repository.createProcessDefinitionQuery().processDefinitionKey(key).tenantIdIn(ctx.tenantId())) > 0) {
            return true;
        }
        if (count(repository.createProcessDefinitionQuery().processDefinitionKey(key).withoutTenantId()) == 0) {
            throw new ApiProblem(404, "NOT_FOUND", "no process with key '" + key + "' is deployed for tenant '"
                    + ctx.tenantId() + "'");
        }
        return false;
    }

    private static long count(ProcessDefinitionQuery q) {
        return OperatonSupport.call(q::count);
    }

    Page<ProcessDefinition> list(RequestContext ctx, DefinitionFilter f, Page.PageRequest page) {
        ProcessDefinitionQuery q = repository.createProcessDefinitionQuery();
        if (ctx.tenantId() != null) {
            q.tenantIdIn(ctx.tenantId()).includeProcessDefinitionsWithoutTenantId();
        }
        if (f != null) {
            if (f.key() != null) {
                q.processDefinitionKey(f.key());
            }
            if (Boolean.TRUE.equals(f.latest())) {
                q.latestVersion();
            }
            if (f.deploymentId() != null) {
                q.deploymentId(f.deploymentId());
            }
        }
        q.orderByProcessDefinitionKey().asc().orderByProcessDefinitionVersion().desc();
        return OperatonSupport.page(q, page, props.getCountCap(), d -> mapDefinition(ctx, d));
    }

    ProcessDefinition get(RequestContext ctx, String definitionId) {
        return mapDefinition(ctx, requireDefinition(ctx, definitionId));
    }

    String xml(RequestContext ctx, String definitionId) {
        requireDefinition(ctx, definitionId);
        try (InputStream in = OperatonSupport.readCall(() -> repository.getProcessModel(definitionId))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ApiProblem(500, "RESOURCE_READ_FAILED", e.getMessage());
        }
    }

    private org.operaton.bpm.engine.repository.ProcessDefinition requireDefinition(RequestContext ctx, String id) {
        org.operaton.bpm.engine.repository.ProcessDefinition d = OperatonSupport.readCall(() ->
                repository.createProcessDefinitionQuery().processDefinitionId(id).singleResult());
        if (d == null) {
            throw ApiProblem.notFound("process definition", id);
        }
        if (d.getTenantId() != null) {
            OperatonSupport.checkTenant(ctx, d.getTenantId(), "process definition", id);
        }
        return d;
    }

    /**
     * UI-DEMO-5: a definition carries its running instance and open incident counts, scoped like every instance read
     * (the caller's tenant; a shared definition counts only this tenant's instances).
     */
    private ProcessDefinition mapDefinition(RequestContext ctx, org.operaton.bpm.engine.repository.ProcessDefinition d) {
        long running = OperatonSupport.call(() -> {
            var iq = runtime.createProcessInstanceQuery().processDefinitionId(d.getId());
            if (ctx.tenantId() != null) {
                iq.tenantIdIn(ctx.tenantId());
            }
            return iq.count();
        });
        long incidents = OperatonSupport.call(() -> {
            var nq = runtime.createIncidentQuery().processDefinitionId(d.getId());
            if (ctx.tenantId() != null) {
                nq.tenantIdIn(ctx.tenantId());
            }
            return nq.count();
        });
        return new ProcessDefinition(d.getId(), d.getKey(), d.getName(), d.getVersion(), d.getDeploymentId(),
                d.getTenantId(), d.getVersionTag(), d.isSuspended(), d.getResourceName(), d.isStartableInTasklist(),
                running, incidents);
    }
}

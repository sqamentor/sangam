// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.operaton.bpm.engine.CaseService;
import org.operaton.bpm.engine.HistoryService;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.history.HistoricCaseInstance;
import org.operaton.bpm.engine.history.HistoricCaseInstanceQuery;
import org.operaton.bpm.engine.repository.CaseDefinitionQuery;
import org.operaton.bpm.engine.runtime.CaseExecutionCommandBuilder;
import org.operaton.bpm.engine.runtime.CaseInstanceBuilder;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.CasePort;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;
import org.sangam.rest.support.TypedVariables;

/**
 * CMMN 1.1 cases on the Operaton {@link CaseService}. Reactivation (FLOWABLE-INTO-OPERATON FC-59) is a linked new case of the same definition
 * with the old variables and business key ({@code sangamReactivatedFrom}); Operaton has no native case reactivation.
 * The richer plan-item lifecycle (FLOWABLE-INTO-OPERATON FC-55..FLOWABLE-INTO-OPERATON FC-58) comes from sangam-flowable-ports behind this port.
 */
public class OperatonCaseAdapter implements CasePort {

    static final String REACTIVATED_FROM = "sangamReactivatedFrom";

    private final CaseService cases;
    private final RepositoryService repository;
    private final HistoryService history;
    private final TxSupport tx;
    private final AuditPort audit;
    private final RestProperties props;

    public OperatonCaseAdapter(CaseService cases, RepositoryService repository, HistoryService history, TxSupport tx,
                               AuditPort audit, RestProperties props) {
        this.cases = cases;
        this.repository = repository;
        this.history = history;
        this.tx = tx;
        this.audit = audit;
        this.props = props;
    }

    @Override
    public CaseInstance start(RequestContext ctx, StartCaseRequest r) {
        if (r == null || r.key() == null || r.key().isBlank()) {
            throw ApiProblem.badRequest("KEY_REQUIRED", "the case definition key is required");
        }
        return tx.inTx(() -> {
            Map<String, Object> vars = TypedVariables.fromJson(r.variables());
            vars.put(OperatonTaskAdapter.INITIATOR, ctx.principalId());
            org.operaton.bpm.engine.runtime.CaseInstance ci = create(ctx, r.key(), r.businessKey(), vars);
            audit.record(ctx, ci.getId(), "CASE_STARTED", payload(ctx, Map.of("case_definition_id",
                    ci.getCaseDefinitionId(), "business_key", r.businessKey() == null ? "" : r.businessKey())));
            return get(ctx, ci.getId());
        });
    }

    private org.operaton.bpm.engine.runtime.CaseInstance create(RequestContext ctx, String key, String businessKey,
                                                                Map<String, Object> vars) {
        CaseInstanceBuilder b = cases.withCaseDefinitionByKey(key);
        boolean tenantDef = ctx.tenantId() != null && OperatonSupport.call(() -> repository
                .createCaseDefinitionQuery().caseDefinitionKey(key).tenantIdIn(ctx.tenantId()).count()) > 0;
        b = tenantDef ? b.caseDefinitionTenantId(ctx.tenantId()) : b.caseDefinitionWithoutTenantId();
        if (businessKey != null) {
            b = b.businessKey(businessKey);
        }
        CaseInstanceBuilder fb = b.setVariables(vars);
        return OperatonSupport.call(fb::create);
    }

    @Override
    public Page<CaseInstance> list(RequestContext ctx, String key, String businessKey, Boolean active,
                                   Page.PageRequest page) {
        HistoricCaseInstanceQuery q = history.createHistoricCaseInstanceQuery();
        if (ctx.tenantId() != null) {
            q.tenantIdIn(ctx.tenantId());
        }
        if (key != null) {
            q.caseDefinitionKey(key);
        }
        if (businessKey != null) {
            q.caseInstanceBusinessKey(businessKey);
        }
        if (Boolean.TRUE.equals(active)) {
            q.notClosed();
        } else if (Boolean.FALSE.equals(active)) {
            q.closed();
        }
        q.orderByCaseInstanceCreateTime().desc();
        return OperatonSupport.page(q, page, props.getCountCap(), h -> fromHistory(h, List.of(), null));
    }

    @Override
    public CaseInstance get(RequestContext ctx, String id) {
        HistoricCaseInstance h = OperatonSupport.call(() ->
                history.createHistoricCaseInstanceQuery().caseInstanceId(id).singleResult());
        org.operaton.bpm.engine.runtime.CaseInstance ci = OperatonSupport.call(() ->
                cases.createCaseInstanceQuery().caseInstanceId(id).singleResult());
        if (h == null && ci == null) {
            throw ApiProblem.notFound("case instance", id);
        }
        OperatonSupport.checkTenant(ctx, ci != null ? ci.getTenantId() : h.getTenantId(), "case instance", id);
        if (ci == null) {
            return fromHistory(h, List.of(), null);
        }
        List<CaseExecution> executions = OperatonSupport.call(() ->
                cases.createCaseExecutionQuery().caseInstanceId(id).list()).stream()
                .filter(e -> !e.getId().equals(id))
                .map(OperatonCaseAdapter::mapExecution).toList();
        Map<String, Object> vars = OperatonSupport.call(() -> cases.getVariables(id));
        Object from = vars.get(REACTIVATED_FROM);
        String defId = ci.getCaseDefinitionId();
        String defKey = OperatonSupport.call(() -> repository.getCaseDefinition(defId)).getKey();
        return new CaseInstance(ci.getId(), defId, defKey, ci.getBusinessKey(), ci.getTenantId(), state(ci),
                h == null ? null : OperatonSupport.instant(h.getCreateTime()), executions,
                from == null ? null : String.valueOf(from));
    }

    private static String state(org.operaton.bpm.engine.runtime.CaseInstance ci) {
        if (ci.isCompleted()) {
            return "COMPLETED";
        }
        if (ci.isTerminated()) {
            return "TERMINATED";
        }
        if (ci.isActive()) {
            return "ACTIVE";
        }
        return "AVAILABLE";
    }

    private static CaseInstance fromHistory(HistoricCaseInstance h, List<CaseExecution> executions, String from) {
        String state = h.isClosed() ? "CLOSED" : h.isTerminated() ? "TERMINATED" : h.isCompleted() ? "COMPLETED"
                : h.isActive() ? "ACTIVE" : "UNKNOWN";
        return new CaseInstance(h.getId(), h.getCaseDefinitionId(), h.getCaseDefinitionKey(), h.getBusinessKey(),
                h.getTenantId(), state, OperatonSupport.instant(h.getCreateTime()), executions, from);
    }

    private static CaseExecution mapExecution(org.operaton.bpm.engine.runtime.CaseExecution e) {
        String state = e.isActive() ? "ACTIVE" : e.isEnabled() ? "ENABLED" : e.isDisabled() ? "DISABLED"
                : e.isTerminated() ? "TERMINATED" : e.isAvailable() ? "AVAILABLE" : "UNKNOWN";
        return new CaseExecution(e.getId(), e.getActivityId(), e.getActivityName(), e.getActivityType(), state,
                e.isRequired(), false, e.getParentId());
    }

    @Override
    public CaseInstance executionCommand(RequestContext ctx, String caseInstanceId, String executionId,
                                         ExecutionCommand c) {
        if (c == null || c.action() == null) {
            throw ApiProblem.badRequest("ACTION_REQUIRED", "action is required");
        }
        return tx.inTx(() -> {
            requireRuntime(ctx, caseInstanceId);
            org.operaton.bpm.engine.runtime.CaseExecution e = OperatonSupport.call(() ->
                    cases.createCaseExecutionQuery().caseExecutionId(executionId).singleResult());
            if (e == null || !caseInstanceId.equals(e.getCaseInstanceId())) {
                throw ApiProblem.notFound("case execution", executionId);
            }
            CaseExecutionCommandBuilder b = cases.withCaseExecution(executionId);
            Map<String, Object> vars = TypedVariables.fromJson(c.variables());
            if (!vars.isEmpty()) {
                b = b.setVariables(vars);
            }
            CaseExecutionCommandBuilder fb = b;
            String action = c.action().toLowerCase(Locale.ROOT);
            switch (action) {
                case "start", "manualstart" -> OperatonSupport.run(fb::manualStart);
                case "complete" -> OperatonSupport.run(fb::complete);
                case "disable" -> OperatonSupport.run(fb::disable);
                case "reenable" -> OperatonSupport.run(fb::reenable);
                case "terminate" -> OperatonSupport.run(fb::terminate);
                default -> throw ApiProblem.badRequest("INVALID_ACTION", "unknown case execution action " + c.action());
            }
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("execution_id", executionId);
            extra.put("activity_id", e.getActivityId());
            extra.put("reason", c.reason());
            audit.record(ctx, caseInstanceId, "CASE_EXECUTION_" + (action.equals("manualstart") ? "START"
                    : action.toUpperCase(Locale.ROOT)), payload(ctx, extra));
            return get(ctx, caseInstanceId);
        });
    }

    @Override
    public CaseInstance instanceCommand(RequestContext ctx, String caseInstanceId, InstanceCommand c) {
        if (c == null || c.action() == null) {
            throw ApiProblem.badRequest("ACTION_REQUIRED", "action is required");
        }
        String action = c.action().toLowerCase(Locale.ROOT);
        if ("reactivate".equals(action)) {
            return reactivate(ctx, caseInstanceId, c);
        }
        return tx.inTx(() -> {
            requireRuntime(ctx, caseInstanceId);
            CaseExecutionCommandBuilder b = cases.withCaseExecution(caseInstanceId);
            Map<String, Object> vars = TypedVariables.fromJson(c.variables());
            if (!vars.isEmpty()) {
                b = b.setVariables(vars);
            }
            CaseExecutionCommandBuilder fb = b;
            switch (action) {
                case "complete" -> OperatonSupport.run(fb::complete);
                case "close" -> OperatonSupport.run(fb::close);
                case "terminate" -> OperatonSupport.run(fb::terminate);
                default -> throw ApiProblem.badRequest("INVALID_ACTION", "unknown case action " + c.action());
            }
            audit.record(ctx, caseInstanceId, "CASE_" + action.toUpperCase(Locale.ROOT) + (action.endsWith("e") ? "D"
                    : "ED"), payload(ctx, Map.of("reason", c.reason() == null ? "" : c.reason())));
            return get(ctx, caseInstanceId);
        });
    }

    /** FLOWABLE-INTO-OPERATON FC-59: a linked new case with the old variables and business key; the old case is never resurrected. */
    private CaseInstance reactivate(RequestContext ctx, String oldId, InstanceCommand c) {
        if (c.reason() == null || c.reason().isBlank()) {
            throw ApiProblem.badRequest("REASON_REQUIRED", "A reason is required to reactivate a case");
        }
        return tx.inTx(() -> {
            HistoricCaseInstance h = OperatonSupport.call(() ->
                    history.createHistoricCaseInstanceQuery().caseInstanceId(oldId).singleResult());
            if (h == null) {
                throw ApiProblem.notFound("case instance", oldId);
            }
            OperatonSupport.checkTenant(ctx, h.getTenantId(), "case instance", oldId);
            if (h.isActive()) {
                throw ApiProblem.conflict("CASE_STILL_ACTIVE", "Only a finished case can be reactivated");
            }
            Map<String, Object> vars = new LinkedHashMap<>();
            OperatonSupport.call(() -> history.createHistoricVariableInstanceQuery().caseInstanceId(oldId).list())
                    .forEach(v -> {
                        if (v.getValue() != null) {
                            vars.put(v.getName(), v.getValue());
                        }
                    });
            vars.putAll(TypedVariables.fromJson(c.variables()));
            vars.put(REACTIVATED_FROM, oldId);
            String key = h.getCaseDefinitionKey();
            org.operaton.bpm.engine.runtime.CaseInstance ci = create(ctx, key, h.getBusinessKey(), vars);
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("reason", c.reason());
            extra.put("reactivated_from", oldId);
            extra.put("new_case_instance_id", ci.getId());
            audit.record(ctx, oldId, "CASE_REACTIVATED", payload(ctx, extra));
            audit.record(ctx, ci.getId(), "CASE_REACTIVATED", payload(ctx, extra));
            return get(ctx, ci.getId());
        });
    }

    @Override
    public Map<String, Object> variables(RequestContext ctx, String caseInstanceId) {
        org.operaton.bpm.engine.runtime.CaseInstance ci = OperatonSupport.call(() ->
                cases.createCaseInstanceQuery().caseInstanceId(caseInstanceId).singleResult());
        if (ci == null) {
            HistoricCaseInstance h = OperatonSupport.call(() ->
                    history.createHistoricCaseInstanceQuery().caseInstanceId(caseInstanceId).singleResult());
            if (h == null) {
                throw ApiProblem.notFound("case instance", caseInstanceId);
            }
            OperatonSupport.checkTenant(ctx, h.getTenantId(), "case instance", caseInstanceId);
            Map<String, Object> out = new LinkedHashMap<>();
            OperatonSupport.call(() -> history.createHistoricVariableInstanceQuery().caseInstanceId(caseInstanceId)
                    .list()).forEach(v -> out.put(v.getName(), v.getValue()));
            return TypedVariables.toJson(out);
        }
        OperatonSupport.checkTenant(ctx, ci.getTenantId(), "case instance", caseInstanceId);
        return TypedVariables.toJson(OperatonSupport.call(() -> cases.getVariables(caseInstanceId)));
    }

    @Override
    public Page<CaseDefinition> definitions(RequestContext ctx, String key, Boolean latest, Page.PageRequest page) {
        CaseDefinitionQuery q = repository.createCaseDefinitionQuery();
        if (ctx.tenantId() != null) {
            q.tenantIdIn(ctx.tenantId()).includeCaseDefinitionsWithoutTenantId();
        }
        if (key != null) {
            q.caseDefinitionKey(key);
        }
        if (Boolean.TRUE.equals(latest)) {
            q.latestVersion();
        }
        q.orderByCaseDefinitionKey().asc().orderByCaseDefinitionVersion().desc();
        return OperatonSupport.page(q, page, props.getCountCap(), d -> new CaseDefinition(d.getId(), d.getKey(),
                d.getName(), d.getVersion(), d.getDeploymentId(), d.getTenantId()));
    }

    private org.operaton.bpm.engine.runtime.CaseInstance requireRuntime(RequestContext ctx, String id) {
        org.operaton.bpm.engine.runtime.CaseInstance ci = OperatonSupport.call(() ->
                cases.createCaseInstanceQuery().caseInstanceId(id).singleResult());
        if (ci == null) {
            throw ApiProblem.notFound("case instance", id);
        }
        OperatonSupport.checkTenant(ctx, ci.getTenantId(), "case instance", id);
        return ci;
    }

    private static Map<String, Object> payload(RequestContext ctx, Map<String, ?> extra) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("actor", ctx.actor());
        extra.forEach((k, v) -> {
            if (v != null) {
                p.put(k, v);
            }
        });
        p.values().removeIf(Objects::isNull);
        return p;
    }
}

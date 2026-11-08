// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.operaton.bpm.engine.HistoryService;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.history.HistoricActivityInstanceQuery;
import org.operaton.bpm.engine.history.HistoricDecisionInputInstance;
import org.operaton.bpm.engine.history.HistoricDecisionInstanceQuery;
import org.operaton.bpm.engine.history.HistoricDecisionOutputInstance;
import org.operaton.bpm.engine.history.HistoricIncidentQuery;
import org.operaton.bpm.engine.history.HistoricJobLogQuery;
import org.operaton.bpm.engine.history.HistoricProcessInstanceQuery;
import org.operaton.bpm.engine.history.HistoricTaskInstanceQuery;
import org.operaton.bpm.engine.history.HistoricVariableInstanceQuery;
import org.operaton.bpm.engine.history.UserOperationLogQuery;
import org.operaton.bpm.engine.repository.ProcessDefinition;
import org.operaton.bpm.engine.repository.ProcessDefinitionQuery;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.HistoryPort;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TypedVariables;

/**
 * Default {@link HistoryPort}: class-A operational history from Operaton's history service (R63 history views,
 * Flowable history-query parity). Every query is tenant-scoped (K1) and count-capped (A-16). Not evidence (rule D-0).
 *
 * <p>Filter notes: queries that Operaton cannot filter by definition key (activity instances) use the latest
 * version of that key; a {@code businessKey} filter on a query without a business-key criterion is narrowed to its
 * process instance and must resolve to exactly one instance ({@code 400 FILTER_AMBIGUOUS} otherwise).
 * {@code finished=true} on incidents means resolved incidents.
 */
public class OperatonHistoryAdapter implements HistoryPort {

    private static final String NO_MATCH = "sangam-no-match";

    private final HistoryService historyService;
    private final RepositoryService repositoryService;
    private final RestProperties props;

    public OperatonHistoryAdapter(HistoryService historyService, RepositoryService repositoryService,
                                  RestProperties props) {
        this.historyService = historyService;
        this.repositoryService = repositoryService;
        this.props = props;
    }

    private static HistoryFilter f(HistoryFilter filter) {
        return filter == null ? new HistoryFilter(null, null, null, null, null, null, null, null, null, null) : filter;
    }

    @Override
    public Page<HistoricProcessInstance> processInstances(RequestContext ctx, HistoryFilter filter,
                                                         Page.PageRequest page) {
        HistoryFilter h = f(filter);
        HistoricProcessInstanceQuery q = historyService.createHistoricProcessInstanceQuery();
        if (ctx.tenantId() != null) {
            q.tenantIdIn(ctx.tenantId());
        }
        if (h.processDefinitionKey() != null) {
            q.processDefinitionKey(h.processDefinitionKey());
        }
        if (h.processInstanceId() != null) {
            q.processInstanceId(h.processInstanceId());
        }
        if (h.businessKey() != null) {
            q.processInstanceBusinessKey(h.businessKey());
        }
        if (h.caseInstanceId() != null) {
            q.caseInstanceId(h.caseInstanceId());
        }
        if (h.activityId() != null) {
            q.activityIdIn(h.activityId());
        }
        if (h.assignee() != null) {
            q.startedBy(h.assignee());
        }
        if (Boolean.TRUE.equals(h.finished())) {
            q.finished();
        } else if (Boolean.FALSE.equals(h.finished())) {
            q.unfinished();
        }
        if (h.from() != null) {
            q.startedAfter(OperatonSupport.date(h.from()));
        }
        if (h.to() != null) {
            q.startedBefore(OperatonSupport.date(h.to()));
        }
        q.orderByProcessInstanceStartTime().desc();
        return OperatonSupport.page(q, page, props.getCountCap(), p -> new HistoricProcessInstance(p.getId(),
                p.getProcessDefinitionId(), p.getProcessDefinitionKey(), p.getBusinessKey(), p.getState(),
                OperatonSupport.instant(p.getStartTime()), OperatonSupport.instant(p.getEndTime()),
                p.getDurationInMillis(), p.getStartUserId(), p.getDeleteReason(), p.getTenantId(),
                p.getSuperProcessInstanceId()));
    }

    @Override
    public Page<HistoricActivityInstance> activityInstances(RequestContext ctx, HistoryFilter filter,
                                                           Page.PageRequest page) {
        HistoryFilter h = f(filter);
        HistoricActivityInstanceQuery q = historyService.createHistoricActivityInstanceQuery();
        if (ctx.tenantId() != null) {
            q.tenantIdIn(ctx.tenantId());
        }
        String pi = instanceOf(ctx, h);
        if (pi != null) {
            q.processInstanceId(pi);
        }
        if (h.processDefinitionKey() != null) {
            q.processDefinitionId(latestDefinitionId(ctx, h.processDefinitionKey()));
        }
        if (h.activityId() != null) {
            q.activityId(h.activityId());
        }
        if (h.assignee() != null) {
            q.taskAssignee(h.assignee());
        }
        if (Boolean.TRUE.equals(h.finished())) {
            q.finished();
        } else if (Boolean.FALSE.equals(h.finished())) {
            q.unfinished();
        }
        if (h.from() != null) {
            q.startedAfter(OperatonSupport.date(h.from()));
        }
        if (h.to() != null) {
            q.startedBefore(OperatonSupport.date(h.to()));
        }
        q.orderByHistoricActivityInstanceStartTime().desc();
        return OperatonSupport.page(q, page, props.getCountCap(), a -> new HistoricActivityInstance(a.getId(),
                a.getActivityId(), a.getActivityName(), a.getActivityType(), a.getProcessInstanceId(),
                a.getExecutionId(), a.getTaskId(), a.getAssignee(), OperatonSupport.instant(a.getStartTime()),
                OperatonSupport.instant(a.getEndTime()), a.getDurationInMillis(), a.isCanceled()));
    }

    @Override
    public Page<HistoricTask> tasks(RequestContext ctx, HistoryFilter filter, Page.PageRequest page) {
        HistoryFilter h = f(filter);
        HistoricTaskInstanceQuery q = historyService.createHistoricTaskInstanceQuery();
        if (ctx.tenantId() != null) {
            q.tenantIdIn(ctx.tenantId());
        }
        if (h.processDefinitionKey() != null) {
            q.processDefinitionKey(h.processDefinitionKey());
        }
        if (h.processInstanceId() != null) {
            q.processInstanceId(h.processInstanceId());
        }
        if (h.businessKey() != null) {
            q.processInstanceBusinessKey(h.businessKey());
        }
        if (h.caseInstanceId() != null) {
            q.caseInstanceId(h.caseInstanceId());
        }
        if (h.taskDefinitionKey() != null) {
            q.taskDefinitionKey(h.taskDefinitionKey());
        }
        if (h.assignee() != null) {
            q.taskAssignee(h.assignee());
        }
        if (Boolean.TRUE.equals(h.finished())) {
            q.finished();
        } else if (Boolean.FALSE.equals(h.finished())) {
            q.unfinished();
        }
        if (h.from() != null) {
            q.startedAfter(OperatonSupport.date(h.from()));
        }
        if (h.to() != null) {
            q.startedBefore(OperatonSupport.date(h.to()));
        }
        q.orderByHistoricActivityInstanceStartTime().desc();
        return OperatonSupport.page(q, page, props.getCountCap(), t -> new HistoricTask(t.getId(), t.getName(),
                t.getTaskDefinitionKey(), t.getProcessInstanceId(), t.getCaseInstanceId(), t.getAssignee(),
                t.getOwner(), OperatonSupport.instant(t.getStartTime()), OperatonSupport.instant(t.getEndTime()),
                t.getDurationInMillis(), t.getDeleteReason(), t.getTenantId()));
    }

    @Override
    public Page<HistoricVariable> variables(RequestContext ctx, HistoryFilter filter, Page.PageRequest page) {
        HistoryFilter h = f(filter);
        HistoricVariableInstanceQuery q = historyService.createHistoricVariableInstanceQuery()
                .disableCustomObjectDeserialization();
        if (ctx.tenantId() != null) {
            q.tenantIdIn(ctx.tenantId());
        }
        String pi = instanceOf(ctx, h);
        if (pi != null) {
            q.processInstanceId(pi);
        }
        if (h.processDefinitionKey() != null) {
            q.processDefinitionKey(h.processDefinitionKey());
        }
        if (h.caseInstanceId() != null) {
            q.caseInstanceId(h.caseInstanceId());
        }
        if (h.from() != null) {
            q.createdAfter(OperatonSupport.date(h.from()));
        }
        q.orderByCreationTime().desc();
        return OperatonSupport.page(q, page, props.getCountCap(), v -> new HistoricVariable(v.getId(), v.getName(),
                v.getTypeName(), safeValue(v), v.getProcessInstanceId(), v.getActivityInstanceId(),
                OperatonSupport.instant(v.getCreateTime()), v.getState()));
    }

    @Override
    public Page<HistoricDecision> decisions(RequestContext ctx, HistoryFilter filter, Page.PageRequest page) {
        HistoryFilter h = f(filter);
        HistoricDecisionInstanceQuery q = historyService.createHistoricDecisionInstanceQuery()
                .includeInputs().includeOutputs().disableCustomObjectDeserialization();
        if (ctx.tenantId() != null) {
            q.tenantIdIn(ctx.tenantId());
        }
        String pi = instanceOf(ctx, h);
        if (pi != null) {
            q.processInstanceId(pi);
        }
        if (h.processDefinitionKey() != null) {
            q.processDefinitionKey(h.processDefinitionKey());
        }
        if (h.caseInstanceId() != null) {
            q.caseInstanceId(h.caseInstanceId());
        }
        if (h.activityId() != null) {
            q.activityIdIn(h.activityId());
        }
        if (h.from() != null) {
            q.evaluatedAfter(OperatonSupport.date(h.from()));
        }
        if (h.to() != null) {
            q.evaluatedBefore(OperatonSupport.date(h.to()));
        }
        q.orderByEvaluationTime().desc();
        return OperatonSupport.page(q, page, props.getCountCap(), d -> {
            Map<String, Object> inputs = new LinkedHashMap<>();
            for (HistoricDecisionInputInstance in : d.getInputs()) {
                inputs.put(in.getClauseName() != null ? in.getClauseName() : in.getClauseId(),
                        TypedVariables.toJsonValue(in.getValue()));
            }
            Map<String, Object> outputs = new LinkedHashMap<>();
            for (HistoricDecisionOutputInstance out : d.getOutputs()) {
                String name = out.getVariableName() != null ? out.getVariableName() : out.getClauseId();
                Object value = TypedVariables.toJsonValue(out.getValue());
                outputs.merge(name, value, OperatonHistoryAdapter::collect);
            }
            return new HistoricDecision(d.getId(), d.getDecisionDefinitionKey(), d.getDecisionDefinitionId(),
                    d.getProcessInstanceId(), d.getActivityId(), OperatonSupport.instant(d.getEvaluationTime()),
                    inputs, outputs);
        });
    }

    @SuppressWarnings("unchecked")
    private static Object collect(Object existing, Object next) {
        List<Object> l;
        if (existing instanceof List<?> e && e.getClass() == ArrayList.class) {
            l = (List<Object>) e;
        } else {
            l = new ArrayList<>();
            l.add(existing);
        }
        l.add(next);
        return l;
    }

    @Override
    public Page<HistoricIncident> incidents(RequestContext ctx, HistoryFilter filter, Page.PageRequest page) {
        HistoryFilter h = f(filter);
        HistoricIncidentQuery q = historyService.createHistoricIncidentQuery();
        if (ctx.tenantId() != null) {
            q.tenantIdIn(ctx.tenantId());
        }
        String pi = instanceOf(ctx, h);
        if (pi != null) {
            q.processInstanceId(pi);
        }
        if (h.processDefinitionKey() != null) {
            q.processDefinitionKey(h.processDefinitionKey());
        }
        if (h.activityId() != null) {
            q.activityId(h.activityId());
        }
        if (Boolean.TRUE.equals(h.finished())) {
            q.resolved();
        } else if (Boolean.FALSE.equals(h.finished())) {
            q.open();
        }
        if (h.from() != null) {
            q.createTimeAfter(OperatonSupport.date(h.from()));
        }
        if (h.to() != null) {
            q.createTimeBefore(OperatonSupport.date(h.to()));
        }
        q.orderByCreateTime().desc();
        return OperatonSupport.page(q, page, props.getCountCap(), i -> new HistoricIncident(i.getId(),
                i.getIncidentType(), i.getIncidentMessage(), i.getProcessInstanceId(), i.getActivityId(),
                OperatonSupport.instant(i.getCreateTime()), OperatonSupport.instant(i.getEndTime()),
                i.isOpen() ? "OPEN" : i.isResolved() ? "RESOLVED" : i.isDeleted() ? "DELETED" : "UNKNOWN"));
    }

    @Override
    public Page<HistoricJobLog> jobLog(RequestContext ctx, HistoryFilter filter, Page.PageRequest page) {
        HistoryFilter h = f(filter);
        HistoricJobLogQuery q = historyService.createHistoricJobLogQuery();
        if (ctx.tenantId() != null) {
            q.tenantIdIn(ctx.tenantId());
        }
        String pi = instanceOf(ctx, h);
        if (pi != null) {
            q.processInstanceId(pi);
        }
        if (h.processDefinitionKey() != null) {
            q.processDefinitionKey(h.processDefinitionKey());
        }
        if (h.activityId() != null) {
            q.activityIdIn(h.activityId());
        }
        q.orderByTimestamp().desc();
        return OperatonSupport.page(q, page, props.getCountCap(), j -> new HistoricJobLog(j.getId(), j.getJobId(),
                j.getProcessInstanceId(), j.getActivityId(), OperatonSupport.instant(j.getTimestamp()),
                j.isCreationLog() ? "CREATED" : j.isFailureLog() ? "FAILED" : j.isSuccessLog() ? "SUCCEEDED"
                        : j.isDeletionLog() ? "DELETED" : "UNKNOWN",
                j.getJobExceptionMessage(), j.getJobRetries()));
    }

    @Override
    public Page<UserOperation> operationLog(RequestContext ctx, HistoryFilter filter, Page.PageRequest page) {
        HistoryFilter h = f(filter);
        UserOperationLogQuery q = historyService.createUserOperationLogQuery();
        if (ctx.tenantId() != null) {
            q.tenantIdIn(ctx.tenantId());
        }
        String pi = instanceOf(ctx, h);
        if (pi != null) {
            q.processInstanceId(pi);
        }
        if (h.processDefinitionKey() != null) {
            q.processDefinitionKey(h.processDefinitionKey());
        }
        if (h.caseInstanceId() != null) {
            q.caseInstanceId(h.caseInstanceId());
        }
        if (h.assignee() != null) {
            q.userId(h.assignee());
        }
        if (h.from() != null) {
            q.afterTimestamp(OperatonSupport.date(h.from()));
        }
        if (h.to() != null) {
            q.beforeTimestamp(OperatonSupport.date(h.to()));
        }
        q.orderByTimestamp().desc();
        return OperatonSupport.page(q, page, props.getCountCap(), o -> new UserOperation(o.getId(),
                o.getOperationType(), o.getEntityType(), o.getProperty(), o.getOrgValue(), o.getNewValue(),
                o.getUserId(), OperatonSupport.instant(o.getTimestamp()), o.getProcessInstanceId(),
                o.getAnnotation()));
    }

    // ------------------------------------------------------------------ helpers

    /** The process instance a filter targets: processInstanceId, or the single instance of businessKey. */
    private String instanceOf(RequestContext ctx, HistoryFilter h) {
        if (h.processInstanceId() != null || h.businessKey() == null) {
            return h.processInstanceId();
        }
        HistoricProcessInstanceQuery q = historyService.createHistoricProcessInstanceQuery()
                .processInstanceBusinessKey(h.businessKey());
        if (ctx.tenantId() != null) {
            q.tenantIdIn(ctx.tenantId());
        }
        List<org.operaton.bpm.engine.history.HistoricProcessInstance> found = OperatonSupport.call(() -> q.listPage(0, 2));
        if (found.isEmpty()) {
            return NO_MATCH;
        }
        if (found.size() > 1) {
            throw ApiProblem.badRequest("FILTER_AMBIGUOUS",
                    "businessKey " + h.businessKey() + " matches several process instances; filter by processInstanceId");
        }
        return found.get(0).getId();
    }

    private String latestDefinitionId(RequestContext ctx, String key) {
        ProcessDefinitionQuery q = repositoryService.createProcessDefinitionQuery().processDefinitionKey(key)
                .latestVersion();
        if (ctx.tenantId() != null) {
            q.tenantIdIn(ctx.tenantId()).includeProcessDefinitionsWithoutTenantId();
        }
        List<ProcessDefinition> defs = OperatonSupport.call(() -> q.list());
        return defs.stream().filter(d -> ctx.tenantId() != null && ctx.tenantId().equals(d.getTenantId())).findFirst()
                .or(() -> defs.stream().findFirst()).map(ProcessDefinition::getId).orElse(NO_MATCH);
    }

    private static Object safeValue(org.operaton.bpm.engine.history.HistoricVariableInstance v) {
        try {
            return TypedVariables.toJsonValue(v.getValue());
        } catch (RuntimeException e) {
            return null;
        }
    }
}

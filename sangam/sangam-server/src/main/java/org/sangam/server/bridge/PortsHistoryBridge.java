// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.sangam.core.layer.SqlPredicate;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.ports.history.HistoryQueryService;
import org.sangam.ports.history.HistoryQueryService.AuthPredicate;
import org.sangam.ports.support.Paging;
import org.sangam.rest.adapter.operaton.OperatonHistoryAdapter;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.HistoryPort;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.TypedVariables;
import org.sangam.rest.support.TxSupport;
import org.sangam.security.SangamSecurity;
import org.sangam.security.policy.ResourceDescriptor;
import org.sangam.security.policy.ResourceRegistry;
import org.sangam.security.tenant.TenantContext;

/**
 * {@link HistoryPort} on sangam-flowable-ports' {@link HistoryQueryService} (FC-25, R63, PF-01; CQRS: no load on the
 * engine history tables for lists).
 * <ul>
 *   <li>Process instances, activity instances and tasks come from the history read model ({@code HRM_*}), variables
 *       and decisions from Operaton history through the same service.</li>
 *   <li>Every list and count carries the tenant and the VI.4 predicate (A-16, K2): with sangam-security bound, the
 *       caller's {@code READ_HISTORY} predicate is compiled for the historic resource type over alias {@code h} and
 *       applied to the read-model row as {@code r.<col> IN (SELECT h.<id> FROM <historic table> h WHERE ...)}; the
 *       system identity sees its tenant; a request without a bound identity sees nothing (fail closed). Without
 *       sangam-security the predicate is tenant-only.</li>
 *   <li>Incidents, job log and operation log stay on Operaton history (the default {@link OperatonHistoryAdapter}).</li>
 * </ul>
 * The read model holds no activity names, executions or task ids per activity instance, so those
 * {@link HistoricActivityInstance} fields are null here.
 */
public final class PortsHistoryBridge implements HistoryPort {

    private final HistoryQueryService queries;
    private final OperatonHistoryAdapter operatonHistory;
    private final SangamSecurity securityOrNull;
    private final TxSupport tx;
    private final long countCap;

    public PortsHistoryBridge(HistoryQueryService queries, OperatonHistoryAdapter operatonHistory,
                              SangamSecurity securityOrNull, TxSupport tx) {
        this(queries, operatonHistory, securityOrNull, tx, Paging.COUNT_CAP);
    }

    /** @param countCap the REST count cap ({@code sangam.rest.count-cap}, A-16); totals above it are "more than N" */
    public PortsHistoryBridge(HistoryQueryService queries, OperatonHistoryAdapter operatonHistory,
                              SangamSecurity securityOrNull, TxSupport tx, long countCap) {
        this.queries = queries;
        this.operatonHistory = operatonHistory;
        this.securityOrNull = securityOrNull;
        this.tx = tx;
        this.countCap = countCap <= 0 ? Paging.COUNT_CAP : countCap;
    }

    // ---------------------------------------------------------------- read model

    @Override
    public Page<HistoricProcessInstance> processInstances(RequestContext ctx, HistoryFilter filter, Page.PageRequest page) {
        AuthPredicate auth = auth(ctx, ResourceRegistry.HISTORIC_PROCESS_INSTANCE, "id");
        return map(PortsErrors.call(() -> queries.processInstances(filter(ctx, filter), auth, pr(page))),
                r -> new HistoricProcessInstance(r.id(), r.definitionId(), r.definitionKey(), r.businessKey(), r.state(),
                        r.startTime(), r.endTime(), r.durationMs(), null, null, r.tenantId(), r.superId()));
    }

    @Override
    public Page<HistoricActivityInstance> activityInstances(RequestContext ctx, HistoryFilter filter,
                                                            Page.PageRequest page) {
        AuthPredicate auth = auth(ctx, ResourceRegistry.HISTORIC_PROCESS_INSTANCE, "process_instance_id");
        return map(PortsErrors.call(() -> queries.activityInstances(filter(ctx, filter), auth, pr(page))),
                r -> new HistoricActivityInstance(r.id(), r.activityId(), null, r.activityType(), r.processInstanceId(),
                        null, null, null, r.startTime(), r.endTime(), r.durationMs(),
                        r.state() != null && r.state().toUpperCase(java.util.Locale.ROOT).startsWith("CANCEL")));
    }

    @Override
    public Page<HistoricTask> tasks(RequestContext ctx, HistoryFilter filter, Page.PageRequest page) {
        AuthPredicate auth = auth(ctx, ResourceRegistry.HISTORIC_TASK, "id");
        return map(PortsErrors.call(() -> queries.tasks(filter(ctx, filter), auth, pr(page))),
                r -> new HistoricTask(r.id(), r.name(), r.taskDefinitionKey(), r.processInstanceId(), r.caseInstanceId(),
                        r.assignee(), r.owner(), r.createTime(), r.endTime(), r.durationMs(), r.deleteReason(),
                        r.tenantId()));
    }

    // ---------------------------------------------------------------- Operaton history through the service

    @Override
    public Page<HistoricVariable> variables(RequestContext ctx, HistoryFilter filter, Page.PageRequest page) {
        requireInstanceVisible(ctx, filter);
        return map(PortsErrors.call(() -> queries.variables(filter(ctx, filter), pr(page))),
                r -> new HistoricVariable(r.id(), r.name(), r.type(), TypedVariables.toJsonValue(r.value()),
                        r.processInstanceId(), r.activityInstanceId(), r.createTime(), r.state()));
    }

    @Override
    public Page<HistoricDecision> decisions(RequestContext ctx, HistoryFilter filter, Page.PageRequest page) {
        requireInstanceVisible(ctx, filter);
        return map(PortsErrors.call(() -> queries.decisions(filter(ctx, filter), pr(page))),
                r -> new HistoricDecision(r.id(), r.decisionDefinitionKey(), r.decisionDefinitionId(), r.processInstanceId(),
                        r.activityId(), r.evaluationTime(), jsonMap(r.inputs()), outputs(r.outputs())));
    }

    // ---------------------------------------------------------------- stays on Operaton history

    @Override
    public Page<HistoricIncident> incidents(RequestContext ctx, HistoryFilter filter, Page.PageRequest page) {
        return operatonHistory.incidents(ctx, filter, page);
    }

    @Override
    public Page<HistoricJobLog> jobLog(RequestContext ctx, HistoryFilter filter, Page.PageRequest page) {
        return operatonHistory.jobLog(ctx, filter, page);
    }

    @Override
    public Page<UserOperation> operationLog(RequestContext ctx, HistoryFilter filter, Page.PageRequest page) {
        return operatonHistory.operationLog(ctx, filter, page);
    }

    // ---------------------------------------------------------------- helpers

    /**
     * The VI.4 {@code READ_HISTORY} predicate for {@code resourceType}, applied to read-model column {@code column}
     * of alias {@code r} (the read model has its own column names, so the predicate is compiled over the historic
     * table the descriptor names and joined by id).
     */
    private AuthPredicate auth(RequestContext ctx, String resourceType, String column) {
        if (securityOrNull == null) {
            return AuthPredicate.NONE;      // tenant-only: the service always applies the tenant
        }
        Optional<SecurityIdentity> bound = TenantContext.current();
        if (bound.isEmpty() || !ctx.tenantId().equals(bound.get().tenantId())) {
            return new AuthPredicate("1=0", List.of());
        }
        SecurityIdentity identity = bound.get();
        if (identity.system()) {
            return AuthPredicate.NONE;
        }
        ResourceDescriptor d = securityOrNull.registry().get(resourceType);
        if (d == null || d.table() == null) {
            return new AuthPredicate("1=0", List.of());
        }
        SqlPredicate p = tx.withConnection(c -> securityOrNull.authorization()
                .visible(c, identity, resourceType, "READ_HISTORY", "h"));
        return new AuthPredicate("r." + column + " IN (SELECT h." + d.idColumn() + " FROM " + d.table() + " h WHERE "
                + p.sql() + ")", p.params());
    }

    /**
     * Variables and decisions are read from Operaton history by instance; with sangam-security bound the instance
     * must be visible to the caller (same predicate as the instance list), else 404 (no existence leak).
     */
    private void requireInstanceVisible(RequestContext ctx, HistoryFilter f) {
        if (securityOrNull == null) {
            return;
        }
        if (f == null || (f.processInstanceId() == null && f.caseInstanceId() == null)) {
            throw ApiProblem.badRequest("FILTER_REQUIRED",
                    "processInstanceId or caseInstanceId is required for variable and decision history");
        }
        if (f.processInstanceId() != null) {
            HistoryFilter one = new HistoryFilter(null, f.processInstanceId(), null, null, null, null, null, null, null,
                    null);
            if (processInstances(ctx, one, new Page.PageRequest(0, 1)).items().isEmpty()) {
                throw ApiProblem.notFound("process instance", f.processInstanceId());
            }
        }
    }

    private static HistoryQueryService.Filter filter(RequestContext ctx, HistoryFilter f) {
        HistoryFilter x = f == null ? new HistoryFilter(null, null, null, null, null, null, null, null, null, null) : f;
        return new HistoryQueryService.Filter(ctx.tenantId(), x.processDefinitionKey(), x.processInstanceId(),
                x.caseInstanceId(), x.businessKey(), x.activityId(), x.taskDefinitionKey(), x.assignee(), null,
                x.finished(), x.from(), x.to());
    }

    private static Paging.PageRequest pr(Page.PageRequest p) {
        return new Paging.PageRequest(p.page(), p.size());
    }

    /**
     * Audit PR-6 (FW-16): a ports {@code PageResult} with {@code capped=true} is a lower bound (probed paging: first +
     * items + 1, or the count cap), so it is reported in the REST "more than N" form ({@code X-Total-Count: N},
     * {@code X-Total-Count-Capped: true}), never as an exact count. The REST cap applies on top of the ports' cap.
     */
    private <S, T> Page<T> map(Paging.PageResult<S> r, Function<S, T> f) {
        List<T> items = r.items().stream().map(f).toList();
        boolean capped = r.capped() || r.total() > countCap;
        long total = Math.min(r.total(), countCap);
        return Page.of(items, total, capped);
    }

    private static Map<String, Object> jsonMap(Map<String, Object> in) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (in != null) {
            in.forEach((k, v) -> out.put(k, TypedVariables.toJsonValue(v)));
        }
        return out;
    }

    /** One matched rule: its output map; several (COLLECT / RULE ORDER): output name → list of values. */
    private static Map<String, Object> outputs(List<Map<String, Object>> byRule) {
        if (byRule == null || byRule.isEmpty()) {
            return Map.of();
        }
        if (byRule.size() == 1) {
            return jsonMap(byRule.get(0));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map<String, Object> rule : byRule) {
            rule.forEach((k, v) -> {
                @SuppressWarnings("unchecked")
                List<Object> l = (List<Object>) out.computeIfAbsent(k, x -> new java.util.ArrayList<>());
                l.add(TypedVariables.toJsonValue(v));
            });
        }
        return out;
    }
}

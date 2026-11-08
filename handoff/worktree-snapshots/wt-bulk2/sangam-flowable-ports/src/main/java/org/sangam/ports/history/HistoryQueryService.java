// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.history;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.history.HistoricDecisionInputInstance;
import org.operaton.bpm.engine.history.HistoricDecisionInstance;
import org.operaton.bpm.engine.history.HistoricDecisionInstanceQuery;
import org.operaton.bpm.engine.history.HistoricDecisionOutputInstance;
import org.operaton.bpm.engine.history.HistoricVariableInstance;
import org.operaton.bpm.engine.history.HistoricVariableInstanceQuery;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.ports.support.Paging;
import org.sangam.ports.support.Paging.PageRequest;
import org.sangam.ports.support.Paging.PageResult;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * History query API over the ports' read model (FLOWABLE-INTO-OPERATON FC-25/FLOWABLE-INTO-OPERATON FC-20 {@code HRM_*}; backs sangam-rest {@code HistoryPort}).
 * Process instances, cases, activity instances and tasks are read from the read model (no load on engine history
 * tables, CQRS); variables and decisions are not projected (values never enter the stream, R61), so they are read
 * from Operaton's history service. Every list is filtered, paged and count-capped (A-16).
 *
 * <p>Authorization: {@code tenantId} is mandatory and always applied; WS-SECURITY's VI.4 predicate is supplied by the
 * caller as {@code authPredicate} (a SQL fragment over alias {@code r} with its bind values), so lists and counts use
 * the same predicate (VI.4 rule, A-16). Variables/decisions are filtered by tenant; per-instance visibility for them is
 * enforced by the caller (it only asks for instances it may see).
 *
 * <p>Count cost (FW-16, A-16; Flowable's count under authorization was 550 ms at 10k × 300 groups): read-model counts
 * are capped at {@link Paging#COUNT_CAP}; engine-history lists (variables, decisions) count exactly only when bounded
 * by an instance id, otherwise they probe one extra row and report {@code capped=true} with a lower bound, so an
 * unfiltered history list never runs a full {@code COUNT(*)}. A null {@link AuthPredicate} is refused.
 */
public class HistoryQueryService {

    /** Filter; null fields are ignored. {@code finished} true = ended only, false = running only. */
    public record Filter(String tenantId, String definitionKey, String processInstanceId, String caseInstanceId,
                         String businessKey, String activityId, String taskDefinitionKey, String assignee, String state,
                         Boolean finished, Instant from, Instant to) {
    }

    /** VI.4 predicate from WS-SECURITY over alias {@code r}; {@link #NONE} only for system callers. */
    public record AuthPredicate(String sql, List<Object> params) {
        public static final AuthPredicate NONE = new AuthPredicate("1=1", List.of());
    }

    public record InstanceRow(String id, String scopeType, String definitionKey, String definitionId, String businessKey, String state,
                              String superId, String rootId, Instant startTime, Instant endTime, Long durationMs, String tenantId) {
    }

    public record ActivityRow(String id, String processInstanceId, String activityId, String activityType, String parentId, String state,
                              Instant startTime, Instant endTime, Long durationMs) {
    }

    public record TaskRow(String id, String name, String taskDefinitionKey, String processInstanceId, String caseInstanceId,
                          String assignee, String owner, String state, String deleteReason, Instant createTime, Instant endTime,
                          Long durationMs, String tenantId) {
    }

    public record VariableRow(String id, String name, String type, Object value, String processInstanceId, String caseInstanceId,
                              String activityInstanceId, Instant createTime, String state) {
    }

    public record DecisionRow(String id, String decisionDefinitionKey, String decisionDefinitionId, String processInstanceId,
                              String caseInstanceId, String activityId, Instant evaluationTime, Map<String, Object> inputs,
                              List<Map<String, Object>> outputs) {
    }

    private final ProcessEngine engine;
    private final Dialect dialect;
    private final Transactions tx;

    public HistoryQueryService(ProcessEngine engine, Dialect dialect, Transactions tx) {
        this.engine = engine;
        this.dialect = dialect;
        this.tx = tx;
    }

    // ------------------------------------------------------------------ read model

    public PageResult<InstanceRow> processInstances(Filter f, AuthPredicate auth, PageRequest p) {
        return instances("process", f, auth, p);
    }

    public PageResult<InstanceRow> caseInstances(Filter f, AuthPredicate auth, PageRequest p) {
        return instances("case", f, auth, p);
    }

    private PageResult<InstanceRow> instances(String scope, Filter f, AuthPredicate auth, PageRequest p) {
        Where w = new Where(f, auth);
        w.add("r.scope_type = ?", scope);
        w.eq("r.definition_key", f.definitionKey());
        w.eq("r.id", "case".equals(scope) ? f.caseInstanceId() : f.processInstanceId());
        w.eq("r.business_key", f.businessKey());
        w.eq("r.state", f.state());
        w.finished(f.finished(), "r.end_time");
        w.range("r.start_time", f.from(), f.to());
        return page("FROM HRM_PROCESS_INSTANCE r", w, "r.start_time DESC, r.id",
                "r.id, r.scope_type, r.definition_key, r.definition_id, r.business_key, r.state, r.super_id, r.root_id, r.start_time,"
                        + " r.end_time, r.tenant_id", rs -> new InstanceRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), Jdbc.instant(rs, "start_time"),
                        Jdbc.instant(rs, "end_time"), duration(Jdbc.instant(rs, "start_time"), Jdbc.instant(rs, "end_time")), rs.getString(11)), p);
    }

    public PageResult<ActivityRow> activityInstances(Filter f, AuthPredicate auth, PageRequest p) {
        Where w = new Where(f, auth);
        w.eq("r.process_instance_id", f.processInstanceId() != null ? f.processInstanceId() : f.caseInstanceId());
        w.eq("r.activity_id", f.activityId());
        w.eq("r.state", f.state());
        w.finished(f.finished(), "r.end_time");
        w.range("r.start_time", f.from(), f.to());
        if (f.definitionKey() != null) {
            w.add("r.process_instance_id IN (SELECT i.id FROM HRM_PROCESS_INSTANCE i WHERE i.definition_key = ?)", f.definitionKey());
        }
        return page("FROM HRM_ACTIVITY_INSTANCE r", w, "r.start_time, r.id",
                "r.id, r.process_instance_id, r.activity_id, r.activity_type, r.parent_id, r.state, r.start_time, r.end_time",
                rs -> new ActivityRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                        Jdbc.instant(rs, "start_time"), Jdbc.instant(rs, "end_time"),
                        duration(Jdbc.instant(rs, "start_time"), Jdbc.instant(rs, "end_time"))), p);
    }

    /** FLOWABLE-INTO-OPERATON FC-20: open activity instances of one process or case instance. */
    public List<ActivityRow> openActivities(String tenantId, String instanceId, AuthPredicate auth) {
        Filter f = new Filter(tenantId, null, instanceId, null, null, null, null, null, "open", null, null, null);
        return activityInstances(f, auth, new PageRequest(0, Paging.MAX_PAGE)).items();
    }

    public PageResult<TaskRow> tasks(Filter f, AuthPredicate auth, PageRequest p) {
        Where w = new Where(f, auth);
        w.eq("r.process_instance_id", f.processInstanceId());
        w.eq("r.case_instance_id", f.caseInstanceId());
        w.eq("r.task_def_key", f.taskDefinitionKey());
        w.eq("r.assignee", f.assignee());
        w.eq("r.state", f.state());
        w.finished(f.finished(), "r.end_time");
        w.range("r.create_time", f.from(), f.to());
        if (f.definitionKey() != null) {
            w.add("(r.process_instance_id IN (SELECT i.id FROM HRM_PROCESS_INSTANCE i WHERE i.definition_key = ?)"
                    + " OR r.case_instance_id IN (SELECT j.id FROM HRM_PROCESS_INSTANCE j WHERE j.definition_key = ?))", f.definitionKey(), f.definitionKey());
        }
        return page("FROM HRM_TASK r", w, "r.create_time DESC, r.id",
                "r.id, r.name, r.task_def_key, r.process_instance_id, r.case_instance_id, r.assignee, r.owner_id, r.state, r.delete_reason,"
                        + " r.create_time, r.end_time, r.tenant_id",
                rs -> new TaskRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getString(7), rs.getString(8), rs.getString(9), Jdbc.instant(rs, "create_time"), Jdbc.instant(rs, "end_time"),
                        duration(Jdbc.instant(rs, "create_time"), Jdbc.instant(rs, "end_time")), rs.getString(12)), p);
    }

    // ------------------------------------------------------------------ engine history (not projected)

    public PageResult<VariableRow> variables(Filter f, PageRequest p) {
        HistoricVariableInstanceQuery q = engine.getHistoryService().createHistoricVariableInstanceQuery();
        if (f.tenantId() != null && !f.tenantId().isEmpty()) {
            q.tenantIdIn(f.tenantId());
        }
        if (f.processInstanceId() != null) {
            q.processInstanceId(f.processInstanceId());
        }
        if (f.caseInstanceId() != null) {
            q.caseInstanceId(f.caseInstanceId());
        }
        if (f.definitionKey() != null) {
            q.processDefinitionKey(f.definitionKey());
        }
        boolean bounded = f.processInstanceId() != null || f.caseInstanceId() != null;
        List<VariableRow> rows = new ArrayList<>();
        List<HistoricVariableInstance> page = q.disableBinaryFetching().orderByVariableName().asc()
                .listPage(p.first(), bounded ? p.size() : p.size() + 1);
        boolean more = !bounded && page.size() > p.size();
        for (HistoricVariableInstance v : more ? page.subList(0, p.size()) : page) {
            rows.add(new VariableRow(v.getId(), v.getName(), v.getTypeName(), v.getValue(), v.getProcessInstanceId(), v.getCaseInstanceId(),
                    v.getActivityInstanceId(), instant(v.getCreateTime()), v.getState()));
        }
        return bounded ? Paging.of(rows, q.count(), p) : Paging.probed(rows, more, p);
    }

    public PageResult<DecisionRow> decisions(Filter f, PageRequest p) {
        HistoricDecisionInstanceQuery q = engine.getHistoryService().createHistoricDecisionInstanceQuery().includeInputs().includeOutputs();
        if (f.tenantId() != null && !f.tenantId().isEmpty()) {
            q.tenantIdIn(f.tenantId());
        }
        if (f.processInstanceId() != null) {
            q.processInstanceId(f.processInstanceId());
        }
        if (f.caseInstanceId() != null) {
            q.caseInstanceId(f.caseInstanceId());
        }
        if (f.definitionKey() != null) {
            q.processDefinitionKey(f.definitionKey());
        }
        if (f.activityId() != null) {
            q.activityIdIn(f.activityId());
        }
        if (f.from() != null) {
            q.evaluatedAfter(Date.from(f.from()));
        }
        if (f.to() != null) {
            q.evaluatedBefore(Date.from(f.to()));
        }
        boolean bounded = f.processInstanceId() != null || f.caseInstanceId() != null;
        List<DecisionRow> rows = new ArrayList<>();
        List<HistoricDecisionInstance> page = q.orderByEvaluationTime().desc().listPage(p.first(), bounded ? p.size() : p.size() + 1);
        boolean more = !bounded && page.size() > p.size();
        for (HistoricDecisionInstance d : more ? page.subList(0, p.size()) : page) {
            Map<String, Object> in = new LinkedHashMap<>();
            for (HistoricDecisionInputInstance i : d.getInputs()) {
                in.put(i.getClauseName() != null ? i.getClauseName() : i.getClauseId(), i.getValue());
            }
            Map<Integer, Map<String, Object>> byRule = new LinkedHashMap<>();
            for (HistoricDecisionOutputInstance o : d.getOutputs()) {
                int order = o.getRuleOrder() == null ? 0 : o.getRuleOrder();
                byRule.computeIfAbsent(order, k -> new LinkedHashMap<>())
                        .put(o.getVariableName() != null ? o.getVariableName() : o.getClauseId(), o.getValue());
            }
            rows.add(new DecisionRow(d.getId(), d.getDecisionDefinitionKey(), d.getDecisionDefinitionId(), d.getProcessInstanceId(),
                    d.getCaseInstanceId(), d.getActivityId(), instant(d.getEvaluationTime()), in, new ArrayList<>(byRule.values())));
        }
        return bounded ? Paging.of(rows, q.count(), p) : Paging.probed(rows, more, p);
    }

    // ------------------------------------------------------------------ helpers

    @FunctionalInterface
    private interface Mapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    private <T> PageResult<T> page(String from, Where w, String orderBy, String cols, Mapper<T> m, PageRequest p) {
        String fromWhere = from + " WHERE " + w.sql();
        return tx.inTransaction(c -> {
            long total = Jdbc.one(c, Paging.cappedCount(dialect, fromWhere), rs -> rs.getLong(1), w.params()).orElse(0L);
            List<T> items = Jdbc.query(c, "SELECT " + cols + " " + fromWhere + " ORDER BY " + orderBy + Paging.window(dialect, p),
                    m::map, w.params());
            return Paging.of(items, total, p);
        });
    }

    private static final class Where {
        private final StringBuilder sql = new StringBuilder();
        private final List<Object> params = new ArrayList<>();

        Where(Filter f, AuthPredicate auth) {
            if (f.tenantId() == null) {
                throw new IllegalArgumentException("tenantId is required");
            }
            add("r.tenant_id = ?", f.tenantId());
            // safe default: a missing predicate is an error, not "see everything in the tenant"; system callers pass NONE
            if (auth == null || auth.sql() == null || auth.sql().isBlank()) {
                throw new IllegalArgumentException("authPredicate is required (pass AuthPredicate.NONE for system callers)");
            }
            add("(" + auth.sql() + ")", auth.params() == null ? new Object[0] : auth.params().toArray());
        }

        void add(String clause, Object... values) {
            if (sql.length() > 0) {
                sql.append(" AND ");
            }
            sql.append(clause);
            params.addAll(List.of(values));
        }

        void eq(String col, Object v) {
            if (v != null) {
                add(col + " = ?", v);
            }
        }

        void finished(Boolean finished, String endCol) {
            if (finished != null) {
                add(endCol + (finished ? " IS NOT NULL" : " IS NULL"));
            }
        }

        void range(String col, Instant from, Instant to) {
            if (from != null) {
                add(col + " >= ?", from);
            }
            if (to != null) {
                add(col + " < ?", to);
            }
        }

        String sql() {
            return sql.toString();
        }

        Object[] params() {
            return params.toArray();
        }
    }

    private static Long duration(Instant a, Instant b) {
        return a == null || b == null ? null : b.toEpochMilli() - a.toEpochMilli();
    }

    private static Instant instant(Date d) {
        return d == null ? null : d.toInstant();
    }
}

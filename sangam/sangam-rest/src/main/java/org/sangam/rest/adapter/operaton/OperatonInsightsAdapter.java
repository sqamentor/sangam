// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import org.operaton.bpm.engine.HistoryService;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.TaskService;
import org.operaton.bpm.engine.history.HistoricActivityInstance;
import org.operaton.bpm.engine.history.HistoricProcessInstance;
import org.operaton.bpm.engine.history.HistoricTaskInstance;
import org.operaton.bpm.engine.repository.ProcessDefinition;
import org.operaton.bpm.model.bpmn.BpmnModelInstance;
import org.operaton.bpm.model.bpmn.instance.BoundaryEvent;
import org.operaton.bpm.model.bpmn.instance.EndEvent;
import org.operaton.bpm.model.bpmn.instance.FlowNode;
import org.operaton.bpm.model.bpmn.instance.Gateway;
import org.operaton.bpm.model.bpmn.instance.SequenceFlow;
import org.operaton.bpm.model.bpmn.instance.StartEvent;
import org.operaton.bpm.model.bpmn.instance.SubProcess;
import org.sangam.core.layer.Hashes;
import org.sangam.rest.port.AnalyticsPort;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.InsightsPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.RestProperties;

/**
 * Default {@link InsightsPort} computed from Operaton history (bounded samples; the separate R65 read model replaces
 * it). Conformance is a "replay-lite" check against the BPMN of the latest definition version: consecutive activities
 * of a finished trace must be connected in the model, with gateways transparent. It reports fitness and deviations,
 * not a full alignment, and names its fixture {@code replay-lite:<definitionId>} (R66: no mining claim without a
 * fixture). Agent usage (R72) is empty: the agent runtime is a stage-E module that is not present.
 * Alert rules are stored in memory on this node; evaluation and delivery belong to sangam-integration (R60).
 */
public class OperatonInsightsAdapter implements InsightsPort {

    private static final int SAMPLE = 10_000;
    private static final int ACTIVITY_SAMPLE = 200_000;
    private static final Set<String> OPERATORS = Set.of(">", "<", ">=", "<=");
    private static final Set<String> CHANNELS = Set.of("IN_APP", "EMAIL", "WEBHOOK");

    private final HistoryService history;
    private final RepositoryService repository;
    private final TaskService tasks;
    private final AuditPort audit;
    private final RestProperties props;
    /** Alert rules: REST_DOCUMENT kind {@value #KIND_ALERT} (R65). */
    static final String KIND_ALERT = "insights:alert";

    private final org.sangam.rest.support.DocumentStore docs;

    public OperatonInsightsAdapter(HistoryService history, RepositoryService repository, TaskService tasks,
                                   AuditPort audit, RestProperties props, org.sangam.rest.support.DocumentStore docs) {
        this.docs = docs;
        this.history = history;
        this.repository = repository;
        this.tasks = tasks;
        this.audit = audit;
        this.props = props;
    }

    // ------------------------------------------------------------------ throughput / duration

    @Override
    public List<TimePoint> throughput(RequestContext ctx, AnalyticsPort.Range range, String interval) {
        String iv = interval == null ? "day" : interval;
        if (!Set.of("hour", "day", "week", "month").contains(iv)) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "interval must be hour | day | week | month");
        }
        Map<Instant, long[]> buckets = new TreeMap<>();
        for (HistoricProcessInstance h : instances(ctx, range, false)) {
            Instant s = OperatonSupport.instant(h.getStartTime());
            if (s != null && inRange(s, range)) {
                buckets.computeIfAbsent(bucket(s, iv), k -> new long[2])[0]++;
            }
            Instant e = OperatonSupport.instant(h.getEndTime());
            if (e != null && inRange(e, range)) {
                buckets.computeIfAbsent(bucket(e, iv), k -> new long[2])[1]++;
            }
        }
        List<TimePoint> out = new ArrayList<>();
        buckets.forEach((t, v) -> out.add(new TimePoint(t, v[0], v[1])));
        return out;
    }

    @Override
    public List<DurationReportRow> durationReport(RequestContext ctx, AnalyticsPort.Range range, String period) {
        String p = period == null ? "day" : period;
        if (!Set.of("day", "week", "month").contains(p)) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "period must be day | week | month");
        }
        Map<String, List<Long>> groups = new TreeMap<>();
        for (HistoricProcessInstance h : instances(ctx, range, true)) {
            if (h.getEndTime() == null || h.getDurationInMillis() == null) {
                continue;
            }
            Instant end = h.getEndTime().toInstant();
            groups.computeIfAbsent(label(bucket(end, p), p), k -> new ArrayList<>()).add(h.getDurationInMillis());
        }
        List<DurationReportRow> out = new ArrayList<>();
        groups.forEach((k, v) -> {
            long sum = 0;
            long min = Long.MAX_VALUE;
            long max = 0;
            for (long d : v) {
                sum += d;
                min = Math.min(min, d);
                max = Math.max(max, d);
            }
            out.add(new DurationReportRow(k, v.size(), sum / v.size(), min, max));
        });
        return out;
    }

    // ------------------------------------------------------------------ SLA (R79)

    @Override
    public List<SlaRow> sla(RequestContext ctx, AnalyticsPort.Range range) {
        var q = history.createHistoricTaskInstanceQuery().tenantIdIn(ctx.tenantId());
        if (range.processDefinitionKey() != null) {
            q.processDefinitionKey(range.processDefinitionKey());
        }
        if (range.from() != null) {
            q.startedAfter(Date.from(range.from()));
        }
        if (range.to() != null) {
            q.startedBefore(Date.from(range.to()));
        }
        List<HistoricTaskInstance> list = OperatonSupport.call(() -> q.listPage(0, SAMPLE));
        Instant now = Instant.now();
        Map<String, long[]> counts = new TreeMap<>();
        Map<String, String> names = new HashMap<>();
        Map<String, double[]> days = new HashMap<>();
        for (HistoricTaskInstance t : list) {
            if (t.getDueDate() == null || t.getTaskDefinitionKey() == null) {
                continue;
            }
            String key = t.getTaskDefinitionKey();
            names.putIfAbsent(key, t.getName());
            long[] c = counts.computeIfAbsent(key, k -> new long[4]);
            c[0]++;
            Instant start = OperatonSupport.instant(t.getStartTime());
            Instant due = t.getDueDate().toInstant();
            Instant end = OperatonSupport.instant(t.getEndTime());
            if (end == null) {
                if (now.isAfter(due)) {
                    c[3]++;
                }
                continue;
            }
            if (end.isAfter(due)) {
                c[3]++;
            } else {
                long window = start == null ? 0 : Duration.between(start, due).toMillis();
                if (window > 0 && Duration.between(end, due).toMillis() < window / 5) {
                    c[2]++;
                } else {
                    c[1]++;
                }
            }
            if (start != null) {
                double[] d = days.computeIfAbsent(key, k -> new double[2]);
                d[0] += businessDays(start, end);
                d[1]++;
            }
        }
        List<SlaRow> out = new ArrayList<>();
        counts.forEach((k, c) -> {
            double[] d = days.get(k);
            out.add(new SlaRow(k, names.get(k), c[0], c[1], c[2], c[3], d == null || d[1] == 0 ? null : d[0] / d[1]));
        });
        return out;
    }

    /** Elapsed days excluding Saturdays and Sundays (the business calendar of R31 replaces this). */
    static double businessDays(Instant start, Instant end) {
        if (!end.isAfter(start)) {
            return 0;
        }
        double total = 0;
        Instant cur = start;
        while (cur.isBefore(end)) {
            ZonedDateTime z = cur.atZone(ZoneOffset.UTC);
            Instant nextMidnight = z.toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant segEnd = nextMidnight.isBefore(end) ? nextMidnight : end;
            DayOfWeek dow = z.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
                total += Duration.between(cur, segEnd).toMillis() / 86_400_000.0;
            }
            cur = segEnd;
        }
        return total;
    }

    // ------------------------------------------------------------------ conformance (R66)

    @Override
    public ConformanceResult conformance(RequestContext ctx, AnalyticsPort.Range range) {
        if (range.processDefinitionKey() == null || range.processDefinitionKey().isBlank()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "processDefinitionKey is required");
        }
        ProcessDefinition def = OperatonSupport.call(() -> repository.createProcessDefinitionQuery()
                .processDefinitionKey(range.processDefinitionKey()).tenantIdIn(ctx.tenantId())
                .includeProcessDefinitionsWithoutTenantId().latestVersion().singleResult());
        if (def == null) {
            throw ApiProblem.notFound("process definition", range.processDefinitionKey());
        }
        BpmnModelInstance model = OperatonSupport.call(() -> repository.getBpmnModelInstance(def.getId()));
        Graph graph = Graph.of(model);

        var q = history.createHistoricActivityInstanceQuery().processDefinitionId(def.getId())
                .tenantIdIn(ctx.tenantId()).orderByHistoricActivityInstanceStartTime().asc()
                .orderPartiallyByOccurrence().asc();
        if (range.from() != null) {
            q.startedAfter(Date.from(range.from()));
        }
        if (range.to() != null) {
            q.startedBefore(Date.from(range.to()));
        }
        List<HistoricActivityInstance> acts = OperatonSupport.call(() -> q.listPage(0, ACTIVITY_SAMPLE));
        Set<String> finished = new HashSet<>();
        var fq = history.createHistoricProcessInstanceQuery().processDefinitionId(def.getId())
                .tenantIdIn(ctx.tenantId()).finished();
        OperatonSupport.call(() -> fq.listPage(0, SAMPLE)).forEach(h -> {
            if (h.getDeleteReason() == null) {
                finished.add(h.getId());
            }
        });
        Map<String, List<String>> traces = new LinkedHashMap<>();
        for (HistoricActivityInstance a : acts) {
            if (!finished.contains(a.getProcessInstanceId()) || skipType(a.getActivityType())) {
                continue;
            }
            traces.computeIfAbsent(a.getProcessInstanceId(), k -> new ArrayList<>()).add(a.getActivityId());
        }
        long pairs = 0;
        long ok = 0;
        Map<String, Long> order = new TreeMap<>();
        Map<String, Long> inserted = new TreeMap<>();
        long skipped = 0;
        for (List<String> trace : traces.values()) {
            String prev = null;
            for (String act : trace) {
                if (!graph.nodes.contains(act)) {
                    inserted.merge(act, 1L, Long::sum);
                    continue;
                }
                if (prev != null) {
                    pairs++;
                    if (graph.reachable(prev, act)) {
                        ok++;
                    } else {
                        order.merge(act, 1L, Long::sum);
                    }
                }
                prev = act;
            }
            if (!graph.ends.isEmpty() && trace.stream().noneMatch(graph.ends::contains)) {
                skipped++;
            }
        }
        List<Deviation> deviations = new ArrayList<>();
        order.forEach((a, n) -> deviations.add(new Deviation(a, "ORDER", n)));
        inserted.forEach((a, n) -> deviations.add(new Deviation(a, "INSERTED", n)));
        if (skipped > 0) {
            deviations.add(new Deviation(graph.ends.stream().sorted().findFirst().orElse(null), "SKIPPED", skipped));
        }
        double fitness = pairs == 0 ? (traces.isEmpty() ? 1.0 : (skipped == 0 ? 1.0 : 0.0)) : (double) ok / pairs;
        return new ConformanceResult(fitness, null, deviations, "replay-lite:" + def.getId());
    }

    private static boolean skipType(String type) {
        return type == null || type.toLowerCase(java.util.Locale.ROOT).contains("gateway")
                || "multiInstanceBody".equals(type);
    }

    /** Flow-node graph with gateways transparent; sub-processes enter at their start events and leave at their ends. */
    static final class Graph {
        final Set<String> nodes = new HashSet<>();
        final Set<String> ends = new HashSet<>();
        final Set<String> transparent = new HashSet<>();
        final Map<String, Set<String>> edges = new HashMap<>();
        private final Map<String, Set<String>> reach = new HashMap<>();

        static Graph of(BpmnModelInstance model) {
            Graph g = new Graph();
            Collection<FlowNode> all = model.getModelElementsByType(FlowNode.class);
            for (FlowNode n : all) {
                g.nodes.add(n.getId());
                if (n instanceof Gateway) {
                    g.transparent.add(n.getId());
                }
                if (n instanceof EndEvent && n.getParentElement() instanceof org.operaton.bpm.model.bpmn.instance.Process) {
                    g.ends.add(n.getId());
                }
            }
            for (SequenceFlow f : model.getModelElementsByType(SequenceFlow.class)) {
                g.edge(f.getSource().getId(), f.getTarget().getId());
            }
            for (BoundaryEvent b : model.getModelElementsByType(BoundaryEvent.class)) {
                if (b.getAttachedTo() != null) {
                    g.edge(b.getAttachedTo().getId(), b.getId());
                }
            }
            for (SubProcess sp : model.getModelElementsByType(SubProcess.class)) {
                List<String> after = sp.getOutgoing().stream().map(f -> f.getTarget().getId()).toList();
                for (StartEvent s : sp.getChildElementsByType(StartEvent.class)) {
                    g.edge(sp.getId(), s.getId());
                }
                for (EndEvent e : sp.getChildElementsByType(EndEvent.class)) {
                    after.forEach(t -> g.edge(e.getId(), t));
                }
            }
            return g;
        }

        private void edge(String a, String b) {
            edges.computeIfAbsent(a, k -> new HashSet<>()).add(b);
        }

        /** b is reached from a in one step, passing only through transparent (gateway) nodes. */
        boolean reachable(String a, String b) {
            return reach.computeIfAbsent(a, this::direct).contains(b);
        }

        private Set<String> direct(String a) {
            Set<String> out = new HashSet<>();
            Set<String> seen = new HashSet<>();
            Deque<String> stack = new ArrayDeque<>(edges.getOrDefault(a, Set.of()));
            while (!stack.isEmpty()) {
                String n = stack.pop();
                if (!seen.add(n)) {
                    continue;
                }
                out.add(n);
                if (transparent.contains(n)) {
                    stack.addAll(edges.getOrDefault(n, Set.of()));
                }
            }
            return out;
        }
    }

    // ------------------------------------------------------------------ alerts

    @Override
    public List<AlertRule> alerts(RequestContext ctx) {
        List<AlertRule> l = new ArrayList<>(stored(ctx.tenantId()));
        l.sort(Comparator.comparing(AlertRule::name, Comparator.nullsLast(String::compareTo)));
        return l;
    }

    @Override
    public AlertRule createAlert(RequestContext ctx, AlertRule rule) {
        validate(rule);
        AlertRule r = new AlertRule(Hashes.uuidV7().toString(), rule.name(), rule.kpi(), rule.operator(),
                rule.threshold(), rule.channel(), rule.recipients() == null ? List.of() : List.copyOf(rule.recipients()),
                rule.active());
        return docs.tx().inTx(() -> {
            docs.insert(ctx.tenantId(), KIND_ALERT, r.id(), r.name(), r.active() ? "ACTIVE" : "INACTIVE", null, r.kpi(),
                    r, ctx.principalId());
            auditAlert(ctx, "ALERT_RULE_CREATED", r);
            return r;
        });
    }

    @Override
    public AlertRule updateAlert(RequestContext ctx, String id, AlertRule rule) {
        docs.require(ctx.tenantId(), KIND_ALERT, id, "alert rule");
        validate(rule);
        AlertRule r = new AlertRule(id, rule.name(), rule.kpi(), rule.operator(), rule.threshold(), rule.channel(),
                rule.recipients() == null ? List.of() : List.copyOf(rule.recipients()), rule.active());
        return docs.tx().inTx(() -> {
            docs.update(ctx.tenantId(), KIND_ALERT, id, org.sangam.rest.support.DocumentStore.ANY_REVISION, r.name(),
                    r.active() ? "ACTIVE" : "INACTIVE", null, r.kpi(), r);
            auditAlert(ctx, "ALERT_RULE_UPDATED", r);
            return r;
        });
    }

    @Override
    public void deleteAlert(RequestContext ctx, String id) {
        AlertRule r = docs.require(ctx.tenantId(), KIND_ALERT, id, "alert rule").as(AlertRule.class);
        docs.tx().runInTx(() -> {
            docs.delete(ctx.tenantId(), KIND_ALERT, id);
            auditAlert(ctx, "ALERT_RULE_DELETED", r);
        });
    }

    private static void validate(AlertRule r) {
        if (r == null || r.name() == null || r.name().isBlank() || r.kpi() == null || r.kpi().isBlank()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "name and kpi are required");
        }
        if (!OPERATORS.contains(r.operator())) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "operator must be one of > < >= <=").with("field", "operator");
        }
        if (!CHANNELS.contains(r.channel())) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "channel must be IN_APP | EMAIL | WEBHOOK")
                    .with("field", "channel");
        }
        if (Double.isNaN(r.threshold()) || Double.isInfinite(r.threshold())) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "threshold must be a finite number").with("field", "threshold");
        }
    }

    private void auditAlert(RequestContext ctx, String type, AlertRule r) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("alert_id", r.id());
        p.put("name", r.name());
        p.put("kpi", r.kpi());
        p.put("operator", r.operator());
        p.put("threshold", Double.toString(r.threshold()));
        p.put("channel", r.channel());
        p.put("active", r.active());
        audit.record(ctx, "sec:" + ctx.tenantId(), type, p);
    }

    @Override
    public List<AgentUsage> agentUsage(RequestContext ctx, AnalyticsPort.Range range) {
        return List.of();
    }

    // ------------------------------------------------------------------ helpers

    private List<HistoricProcessInstance> instances(RequestContext ctx, AnalyticsPort.Range range, boolean finishedOnly) {
        var q = history.createHistoricProcessInstanceQuery().tenantIdIn(ctx.tenantId());
        if (range.processDefinitionKey() != null) {
            q.processDefinitionKey(range.processDefinitionKey());
        }
        if (finishedOnly) {
            q.finished();
            if (range.from() != null) {
                q.finishedAfter(Date.from(range.from()));
            }
            if (range.to() != null) {
                q.finishedBefore(Date.from(range.to()));
            }
        } else if (range.to() != null) {
            q.startedBefore(Date.from(range.to()));
        }
        return OperatonSupport.call(() -> q.orderByProcessInstanceStartTime().desc().listPage(0, SAMPLE));
    }

    private static boolean inRange(Instant i, AnalyticsPort.Range r) {
        return (r.from() == null || !i.isBefore(r.from())) && (r.to() == null || i.isBefore(r.to()));
    }

    static Instant bucket(Instant i, String interval) {
        ZonedDateTime z = i.atZone(ZoneOffset.UTC);
        return switch (interval) {
            case "hour" -> z.truncatedTo(ChronoUnit.HOURS).toInstant();
            case "week" -> z.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    .atStartOfDay(ZoneOffset.UTC).toInstant();
            case "month" -> z.toLocalDate().withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            default -> z.truncatedTo(ChronoUnit.DAYS).toInstant();
        };
    }

    private static String label(Instant bucket, String period) {
        LocalDate d = bucket.atZone(ZoneOffset.UTC).toLocalDate();
        return switch (period) {
            case "month" -> d.toString().substring(0, 7);
            case "week" -> d + "/W";
            default -> d.toString();
        };
    }

    /** Exposed for the alert evaluator of sangam-integration: the active rules of one tenant. */
    public List<AlertRule> activeAlerts(String tenant) {
        return docs.all(tenant, KIND_ALERT, org.sangam.rest.support.DocumentStore.Filter.state("ACTIVE")).stream()
                .map(d -> d.as(AlertRule.class)).toList();
    }

    private List<AlertRule> stored(String tenant) {
        return docs.all(tenant, KIND_ALERT, org.sangam.rest.support.DocumentStore.Filter.ALL).stream()
                .map(d -> d.as(AlertRule.class)).toList();
    }

    /** Open tasks past their due date (used by the alert evaluator). */
    public long openOverdueTasks(String tenant) {
        return OperatonSupport.call(() -> tasks.createTaskQuery().tenantIdIn(tenant).dueBefore(new Date()).count());
    }

    /** Count cap used by callers paging these results. */
    public long countCap() {
        return props.getCountCap();
    }
}

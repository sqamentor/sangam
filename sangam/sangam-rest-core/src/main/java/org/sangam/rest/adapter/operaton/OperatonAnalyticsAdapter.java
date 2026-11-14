// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.operaton.bpm.engine.HistoryService;
import org.operaton.bpm.engine.ManagementService;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.history.HistoricActivityInstance;
import org.operaton.bpm.engine.history.HistoricActivityStatistics;
import org.operaton.bpm.engine.history.HistoricProcessInstance;
import org.operaton.bpm.engine.history.HistoricProcessInstanceQuery;
import org.operaton.bpm.engine.history.HistoricTaskInstance;
import org.operaton.bpm.engine.history.HistoricTaskInstanceQuery;
import org.operaton.bpm.engine.repository.ProcessDefinition;
import org.operaton.bpm.engine.repository.ProcessDefinitionQuery;
import org.operaton.bpm.engine.runtime.IncidentQuery;
import org.sangam.rest.port.AnalyticsPort;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.Download;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.RestProperties;

/**
 * Default {@link AnalyticsPort} computed from Operaton history (R63 heatmap/duration overlay, R65 KPIs, R66 variants,
 * R15 XES / OCEL 2.0 export, UP-07). Every figure is tenant-scoped (K1). Durations and variants come from bounded
 * samples ({@value #SAMPLE} instances, {@value #MAX_ACTIVITY_ROWS} activity rows), so very large ranges are
 * approximations; R65's separate read model (fed from the outbox) replaces this in production.
 *
 * <p>Variants and traces leave out routing-only nodes (gateways, multi-instance bodies, sequence-flow markers); an
 * activity sequence is ordered by start time and then by occurrence. Activity statistics (the heatmap) use the
 * latest definition version of the key; variants and exports cover every version of the key.
 */
public class OperatonAnalyticsAdapter implements AnalyticsPort {

    static final int SAMPLE = 10_000;
    static final int MAX_ACTIVITY_ROWS = 200_000;
    private static final int MAX_TRACES = 100_000;
    private static final Set<String> ROUTING_TYPES = Set.of("exclusiveGateway", "parallelGateway", "inclusiveGateway",
            "eventBasedGateway", "complexGateway", "multiInstanceBody", "sequenceFlow");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HistoryService historyService;
    private final RuntimeService runtimeService;
    private final ManagementService managementService;
    private final RepositoryService repositoryService;
    private final RestProperties props;

    public OperatonAnalyticsAdapter(HistoryService historyService, RuntimeService runtimeService,
                                    ManagementService managementService, RepositoryService repositoryService,
                                    RestProperties props) {
        this.historyService = historyService;
        this.runtimeService = runtimeService;
        this.managementService = managementService;
        this.repositoryService = repositoryService;
        this.props = props;
    }

    // ------------------------------------------------------------------ KPIs

    @Override
    public Summary summary(RequestContext ctx, Range range) {
        Range r = range(range);
        Supplier<HistoricProcessInstanceQuery> base = () -> instances(ctx, r);
        return OperatonSupport.call(() -> {
            long started = base.get().count();
            long completed = base.get().completed().count();
            long active = base.get().active().count() + base.get().suspended().count();
            long cancelled = base.get().externallyTerminated().count() + base.get().internallyTerminated().count();
            IncidentQuery iq = runtimeService.createIncidentQuery();
            if (ctx.tenantId() != null) {
                iq.tenantIdIn(ctx.tenantId());
            }
            if (r.processDefinitionKey() != null) {
                iq.processDefinitionKeyIn(r.processDefinitionKey());
            }
            long incidents = iq.count();
            List<Long> durations = base.get().finished().orderByProcessInstanceStartTime().desc().listPage(0, SAMPLE)
                    .stream().map(HistoricProcessInstance::getDurationInMillis).filter(java.util.Objects::nonNull)
                    .sorted().toList();
            return new Summary(r.processDefinitionKey(), r.from(), r.to(), started, completed, active, cancelled,
                    incidents, avg(durations), percentile(durations, 50), percentile(durations, 95));
        });
    }

    @Override
    public List<ActivityStat> activities(RequestContext ctx, Range range) {
        Range r = range(range);
        String key = requireKey(r);
        ProcessDefinition latest = definitions(ctx, key).stream().max(Comparator.comparingInt(ProcessDefinition::getVersion))
                .orElse(null);
        if (latest == null) {
            return List.of();
        }
        return OperatonSupport.call(() -> {
            var sq = historyService.createHistoricActivityStatisticsQuery(latest.getId())
                    .includeFinished().includeCanceled().includeIncidents();
            if (r.from() != null) {
                sq.startedAfter(OperatonSupport.date(r.from()));
            }
            if (r.to() != null) {
                sq.startedBefore(OperatonSupport.date(r.to()));
            }
            Map<String, HistoricActivityStatistics> stats = new LinkedHashMap<>();
            for (HistoricActivityStatistics s : sq.orderByActivityId().asc().list()) {
                stats.put(s.getId(), s);
            }
            var aq = historyService.createHistoricActivityInstanceQuery().processDefinitionId(latest.getId()).finished();
            if (ctx.tenantId() != null) {
                aq.tenantIdIn(ctx.tenantId());
            }
            if (r.from() != null) {
                aq.startedAfter(OperatonSupport.date(r.from()));
            }
            if (r.to() != null) {
                aq.startedBefore(OperatonSupport.date(r.to()));
            }
            Map<String, List<Long>> durations = new HashMap<>();
            Map<String, String[]> meta = new LinkedHashMap<>();
            for (HistoricActivityInstance a : aq.orderByHistoricActivityInstanceStartTime().desc()
                    .listPage(0, MAX_ACTIVITY_ROWS)) {
                meta.putIfAbsent(a.getActivityId(), new String[]{a.getActivityName(), a.getActivityType()});
                if (a.getDurationInMillis() != null) {
                    durations.computeIfAbsent(a.getActivityId(), k -> new ArrayList<>()).add(a.getDurationInMillis());
                }
            }
            Set<String> ids = new LinkedHashSet<>(stats.keySet());
            ids.addAll(meta.keySet());
            List<ActivityStat> out = new ArrayList<>();
            for (String id : ids) {
                HistoricActivityStatistics s = stats.get(id);
                String[] m = meta.getOrDefault(id, new String[]{null, null});
                List<Long> d = durations.getOrDefault(id, List.of());
                long active = s == null ? 0 : s.getInstances();
                long count = s == null ? d.size() : s.getInstances() + s.getFinished();
                out.add(new ActivityStat(id, m[0], m[1], count, active, avg(d),
                        d.isEmpty() ? null : d.stream().mapToLong(Long::longValue).max().getAsLong(),
                        s == null ? 0 : s.getOpenIncidents()));
            }
            return out;
        });
    }

    @Override
    public List<TaskStat> tasks(RequestContext ctx, Range range) {
        Range r = range(range);
        return OperatonSupport.call(() -> {
            HistoricTaskInstanceQuery q = historyService.createHistoricTaskInstanceQuery();
            if (ctx.tenantId() != null) {
                q.tenantIdIn(ctx.tenantId());
            }
            if (r.processDefinitionKey() != null) {
                q.processDefinitionKey(r.processDefinitionKey());
            }
            if (r.from() != null) {
                q.startedAfter(OperatonSupport.date(r.from()));
            }
            if (r.to() != null) {
                q.startedBefore(OperatonSupport.date(r.to()));
            }
            Map<String, String> names = new LinkedHashMap<>();
            Map<String, long[]> counts = new HashMap<>();
            Map<String, List<Long>> durations = new HashMap<>();
            for (HistoricTaskInstance t : q.orderByHistoricActivityInstanceStartTime().desc().listPage(0, MAX_ACTIVITY_ROWS)) {
                String key = t.getTaskDefinitionKey() == null ? "(standalone)" : t.getTaskDefinitionKey();
                names.putIfAbsent(key, t.getName());
                long[] c = counts.computeIfAbsent(key, k -> new long[2]);
                if (t.getEndTime() == null) {
                    c[1]++;
                } else {
                    c[0]++;
                    if (t.getDurationInMillis() != null) {
                        durations.computeIfAbsent(key, k -> new ArrayList<>()).add(t.getDurationInMillis());
                    }
                }
            }
            List<TaskStat> out = new ArrayList<>();
            for (Map.Entry<String, String> e : names.entrySet()) {
                List<Long> d = new ArrayList<>(durations.getOrDefault(e.getKey(), List.of()));
                d.sort(null);
                long[] c = counts.get(e.getKey());
                out.add(new TaskStat(e.getKey(), e.getValue(), c[0], c[1], avg(d), percentile(d, 95)));
            }
            return out;
        });
    }

    // ------------------------------------------------------------------ mining

    /** A finished instance with its routing-free activity sequence. */
    private record Trace(HistoricProcessInstance instance, List<HistoricActivityInstance> activities) {
    }

    @Override
    public List<Variant> variants(RequestContext ctx, Range range, int limit) {
        Range r = range(range);
        requireKey(r);
        List<Trace> traces = traces(ctx, r, SAMPLE, true);
        Map<List<String>, List<Long>> groups = new LinkedHashMap<>();
        for (Trace t : traces) {
            List<String> seq = t.activities().stream().map(HistoricActivityInstance::getActivityId).toList();
            groups.computeIfAbsent(seq, k -> new ArrayList<>()).add(t.instance().getDurationInMillis());
        }
        long total = traces.size();
        int top = limit <= 0 ? 20 : Math.min(limit, 1000);
        return groups.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
                .limit(top)
                .map(e -> new Variant(e.getKey(), e.getValue().size(),
                        total == 0 ? 0.0 : Math.round(e.getValue().size() * 10_000.0 / total) / 10_000.0,
                        avg(e.getValue().stream().filter(java.util.Objects::nonNull).toList())))
                .toList();
    }

    @Override
    public Download exportXes(RequestContext ctx, Range range, int maxTraces) {
        Range r = range(range);
        String key = requireKey(r);
        List<Trace> traces = traces(ctx, r, cap(maxTraces), false);
        StringBuilder x = new StringBuilder(64 * 1024);
        x.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        x.append("<log xes.version=\"1849-2016\" xes.features=\"nested-attributes\" xmlns=\"http://www.xes-standard.org/\">\n");
        x.append("  <extension name=\"Concept\" prefix=\"concept\" uri=\"http://www.xes-standard.org/concept.xesext\"/>\n");
        x.append("  <extension name=\"Time\" prefix=\"time\" uri=\"http://www.xes-standard.org/time.xesext\"/>\n");
        x.append("  <extension name=\"Lifecycle\" prefix=\"lifecycle\" uri=\"http://www.xes-standard.org/lifecycle.xesext\"/>\n");
        x.append("  <extension name=\"Organizational\" prefix=\"org\" uri=\"http://www.xes-standard.org/org.xesext\"/>\n");
        x.append("  <global scope=\"trace\">\n    <string key=\"concept:name\" value=\"__INVALID__\"/>\n  </global>\n");
        x.append("  <global scope=\"event\">\n    <string key=\"concept:name\" value=\"__INVALID__\"/>\n")
                .append("    <string key=\"lifecycle:transition\" value=\"complete\"/>\n")
                .append("    <date key=\"time:timestamp\" value=\"1970-01-01T00:00:00Z\"/>\n  </global>\n");
        x.append("  <classifier name=\"Activity\" keys=\"concept:name\"/>\n");
        x.append("  <string key=\"concept:name\" value=\"").append(esc(key)).append("\"/>\n");
        x.append("  <string key=\"sangam:tenant\" value=\"").append(esc(ctx.tenantId())).append("\"/>\n");
        for (Trace t : traces) {
            HistoricProcessInstance pi = t.instance();
            x.append("  <trace>\n");
            attr(x, "    ", "string", "concept:name", pi.getId());
            attr(x, "    ", "string", "sangam:businessKey", pi.getBusinessKey());
            attr(x, "    ", "string", "sangam:processDefinitionId", pi.getProcessDefinitionId());
            attr(x, "    ", "string", "sangam:state", pi.getState());
            if (pi.getStartTime() != null) {
                attr(x, "    ", "date", "sangam:startTime", pi.getStartTime().toInstant().toString());
            }
            for (HistoricActivityInstance a : t.activities()) {
                Instant at = OperatonSupport.instant(a.getEndTime() != null ? a.getEndTime() : a.getStartTime());
                x.append("    <event>\n");
                attr(x, "      ", "string", "concept:name", a.getActivityName() != null ? a.getActivityName() : a.getActivityId());
                attr(x, "      ", "string", "sangam:activityId", a.getActivityId());
                attr(x, "      ", "string", "sangam:activityType", a.getActivityType());
                attr(x, "      ", "string", "lifecycle:transition", a.getEndTime() != null ? "complete" : "start");
                attr(x, "      ", "date", "time:timestamp", at == null ? null : at.toString());
                attr(x, "      ", "string", "org:resource", a.getAssignee());
                if (a.getDurationInMillis() != null) {
                    attr(x, "      ", "int", "sangam:durationMs", a.getDurationInMillis().toString());
                }
                x.append("    </event>\n");
            }
            x.append("  </trace>\n");
        }
        x.append("</log>\n");
        return new Download("application/xml", fileName(key, "xes"), x.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Download exportOcel(RequestContext ctx, Range range, int maxTraces) {
        Range r = range(range);
        String key = requireKey(r);
        List<Trace> traces = traces(ctx, r, cap(maxTraces), false);
        List<Map<String, Object>> objects = new ArrayList<>();
        List<Map<String, Object>> events = new ArrayList<>();
        Map<String, Map<String, Object>> eventTypes = new LinkedHashMap<>();
        Set<String> businessKeys = new LinkedHashSet<>();
        Set<String> tasks = new LinkedHashSet<>();
        for (Trace t : traces) {
            HistoricProcessInstance pi = t.instance();
            String piObj = "pi:" + pi.getId();
            List<Map<String, Object>> rels = new ArrayList<>();
            if (pi.getBusinessKey() != null) {
                String bkObj = "bk:" + pi.getBusinessKey();
                rels.add(rel(bkObj, "has business key"));
                if (businessKeys.add(pi.getBusinessKey())) {
                    objects.add(object(bkObj, "BusinessKey", List.of(), List.of()));
                }
            }
            String time = pi.getStartTime() == null ? null : pi.getStartTime().toInstant().toString();
            objects.add(object(piObj, "ProcessInstance", List.of(
                    oattr("processDefinitionKey", time, pi.getProcessDefinitionKey()),
                    oattr("processDefinitionId", time, pi.getProcessDefinitionId()),
                    oattr("state", pi.getEndTime() == null ? time : pi.getEndTime().toInstant().toString(), pi.getState())),
                    rels));
            for (HistoricActivityInstance a : t.activities()) {
                String type = a.getActivityName() != null ? a.getActivityName() : a.getActivityId();
                eventTypes.computeIfAbsent(type, k -> {
                    Map<String, Object> et = new LinkedHashMap<>();
                    et.put("name", k);
                    et.put("attributes", List.of(Map.of("name", "activityId", "type", "string"),
                            Map.of("name", "activityType", "type", "string"),
                            Map.of("name", "resource", "type", "string")));
                    return et;
                });
                Instant at = OperatonSupport.instant(a.getEndTime() != null ? a.getEndTime() : a.getStartTime());
                List<Map<String, Object>> evRels = new ArrayList<>();
                evRels.add(rel(piObj, "process instance"));
                if (a.getTaskId() != null) {
                    String taskObj = "task:" + a.getTaskId();
                    evRels.add(rel(taskObj, "task"));
                    if (tasks.add(a.getTaskId())) {
                        objects.add(object(taskObj, "UserTask", List.of(
                                oattr("taskDefinitionKey", at == null ? null : at.toString(), a.getActivityId())),
                                List.of(rel(piObj, "part of"))));
                    }
                }
                if (pi.getBusinessKey() != null) {
                    evRels.add(rel("bk:" + pi.getBusinessKey(), "business key"));
                }
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("id", "ev:" + a.getId());
                ev.put("type", type);
                ev.put("time", at == null ? null : at.toString());
                ev.put("attributes", List.of(eattr("activityId", a.getActivityId()),
                        eattr("activityType", a.getActivityType()), eattr("resource", a.getAssignee())));
                ev.put("relationships", evRels);
                events.add(ev);
            }
        }
        Map<String, Object> ocel = new LinkedHashMap<>();
        ocel.put("objectTypes", List.of(
                Map.of("name", "ProcessInstance", "attributes", List.of(
                        Map.of("name", "processDefinitionKey", "type", "string"),
                        Map.of("name", "processDefinitionId", "type", "string"),
                        Map.of("name", "state", "type", "string"))),
                Map.of("name", "BusinessKey", "attributes", List.of()),
                Map.of("name", "UserTask", "attributes", List.of(Map.of("name", "taskDefinitionKey", "type", "string")))));
        ocel.put("eventTypes", new ArrayList<>(eventTypes.values()));
        ocel.put("objects", objects);
        ocel.put("events", events);
        try {
            return new Download("application/json", fileName(key, "ocel.json"), JSON.writeValueAsBytes(ocel));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Finished (or, for exports, all) instances of every version of the key, with their routing-free activity
     * sequences; one activity query per definition version.
     */
    private List<Trace> traces(RequestContext ctx, Range r, int max, boolean finishedOnly) {
        return OperatonSupport.call(() -> {
            HistoricProcessInstanceQuery q = instances(ctx, r);
            if (finishedOnly) {
                q.finished();
            }
            List<HistoricProcessInstance> pis = q.orderByProcessInstanceStartTime().desc().listPage(0, max);
            Map<String, List<HistoricActivityInstance>> byInstance = new HashMap<>();
            Set<String> wanted = new LinkedHashSet<>();
            Set<String> defIds = new LinkedHashSet<>();
            for (HistoricProcessInstance pi : pis) {
                wanted.add(pi.getId());
                defIds.add(pi.getProcessDefinitionId());
            }
            int rows = 0;
            for (String defId : defIds) {
                if (rows >= MAX_ACTIVITY_ROWS) {
                    break;
                }
                var aq = historyService.createHistoricActivityInstanceQuery().processDefinitionId(defId);
                if (ctx.tenantId() != null) {
                    aq.tenantIdIn(ctx.tenantId());
                }
                if (r.from() != null) {
                    aq.startedAfter(OperatonSupport.date(r.from()));
                }
                List<HistoricActivityInstance> acts = aq.orderByHistoricActivityInstanceStartTime().asc()
                        .orderPartiallyByOccurrence().asc().listPage(0, MAX_ACTIVITY_ROWS - rows);
                rows += acts.size();
                for (HistoricActivityInstance a : acts) {
                    if (wanted.contains(a.getProcessInstanceId()) && !ROUTING_TYPES.contains(a.getActivityType())) {
                        byInstance.computeIfAbsent(a.getProcessInstanceId(), k -> new ArrayList<>()).add(a);
                    }
                }
            }
            List<Trace> out = new ArrayList<>(pis.size());
            for (HistoricProcessInstance pi : pis) {
                out.add(new Trace(pi, byInstance.getOrDefault(pi.getId(), List.of())));
            }
            return out;
        });
    }

    private HistoricProcessInstanceQuery instances(RequestContext ctx, Range r) {
        HistoricProcessInstanceQuery q = historyService.createHistoricProcessInstanceQuery();
        if (ctx.tenantId() != null) {
            q.tenantIdIn(ctx.tenantId());
        }
        if (r.processDefinitionKey() != null) {
            q.processDefinitionKey(r.processDefinitionKey());
        }
        if (r.from() != null) {
            q.startedAfter(OperatonSupport.date(r.from()));
        }
        if (r.to() != null) {
            q.startedBefore(OperatonSupport.date(r.to()));
        }
        return q;
    }

    private List<ProcessDefinition> definitions(RequestContext ctx, String key) {
        ProcessDefinitionQuery q = repositoryService.createProcessDefinitionQuery().processDefinitionKey(key);
        if (ctx.tenantId() != null) {
            q.tenantIdIn(ctx.tenantId()).includeProcessDefinitionsWithoutTenantId();
        }
        return OperatonSupport.call(q::list);
    }

    private static Range range(Range r) {
        Range out = r == null ? new Range(null, null, null) : r;
        if (out.from() != null && out.to() != null && out.from().isAfter(out.to())) {
            throw ApiProblem.badRequest("INVALID_RANGE", "from must not be after to");
        }
        return out;
    }

    private static String requireKey(Range r) {
        if (r.processDefinitionKey() == null || r.processDefinitionKey().isBlank()) {
            throw ApiProblem.badRequest("PROCESS_DEFINITION_KEY_REQUIRED", "processDefinitionKey is required");
        }
        return r.processDefinitionKey();
    }

    private static int cap(int maxTraces) {
        return maxTraces <= 0 ? SAMPLE : Math.min(maxTraces, MAX_TRACES);
    }

    private static Long avg(List<Long> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        long sum = 0;
        for (Long v : values) {
            sum += v;
        }
        return sum / values.size();
    }

    /** Nearest-rank percentile over a sorted list. */
    private static Long percentile(List<Long> sorted, int p) {
        if (sorted == null || sorted.isEmpty()) {
            return null;
        }
        int rank = (int) Math.ceil(p / 100.0 * sorted.size());
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, rank - 1)));
    }

    private static void attr(StringBuilder x, String indent, String type, String key, String value) {
        if (value == null) {
            return;
        }
        x.append(indent).append('<').append(type).append(" key=\"").append(esc(key)).append("\" value=\"")
                .append(esc(value)).append("\"/>\n");
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&apos;");
                default -> {
                    if (c >= 0x20 || c == '\t' || c == '\n' || c == '\r') {
                        b.append(c);
                    }
                }
            }
        }
        return b.toString();
    }

    private static String fileName(String key, String ext) {
        return key.replaceAll("[^A-Za-z0-9._-]", "_") + "." + ext;
    }

    private static Map<String, Object> rel(String objectId, String qualifier) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("objectId", objectId);
        m.put("qualifier", qualifier);
        return m;
    }

    private static Map<String, Object> object(String id, String type, List<Map<String, Object>> attributes,
                                              List<Map<String, Object>> relationships) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("type", type);
        m.put("attributes", attributes);
        m.put("relationships", relationships);
        return m;
    }

    private static Map<String, Object> oattr(String name, String time, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("time", time == null ? "1970-01-01T00:00:00Z" : time);
        m.put("value", value);
        return m;
    }

    private static Map<String, Object> eattr(String name, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("value", value);
        return m;
    }
}

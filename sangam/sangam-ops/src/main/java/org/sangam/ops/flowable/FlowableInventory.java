// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.flowable;

import org.sangam.ops.flowable.FlowableSnapshot.Execution;
import org.sangam.ops.flowable.FlowableSnapshot.Instance;
import org.sangam.ops.flowable.FlowableSnapshot.Job;
import org.sangam.ops.flowable.FlowableSnapshot.JobKind;
import org.sangam.ops.flowable.FlowableSnapshot.Subscription;
import org.sangam.ops.flowable.FlowableSnapshot.Task;
import org.sangam.ops.flowable.FlowableSnapshot.Variable;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneOffsetTransition;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.sangam.ops.flowable.FlowableTables.*;

/**
 * M-135 step 1, inventory: reads the Flowable runtime of one source tenant into a {@link FlowableSnapshot}. Nothing is
 * written anywhere.
 *
 * <p>Timestamps: Flowable stores timestamps without a zone. They are read as wall-clock values and converted with the
 * explicitly named source zone (with its historical rules, never a fixed offset). A wall-clock time that is ambiguous
 * (DST fold) or does not exist (DST gap) in that zone is recorded in {@link Instance#timeProblems()}, and the instance is
 * rejected by the classifier; no offset is guessed.
 */
public final class FlowableInventory {

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    private final FlowableRows rows;
    private final ZoneId sourceZone;
    private final Map<String, FlowableModelListeners.Found> modelListeners = new java.util.concurrent.ConcurrentHashMap<>();

    public FlowableInventory(FlowableRows rows, ZoneId sourceZone) {
        this.rows = Objects.requireNonNull(rows, "rows");
        this.sourceZone = Objects.requireNonNull(sourceZone, "sourceZone");
    }

    /** Every process instance of {@code sourceTenant} ({@code ""} or null = Flowable's no-tenant value). */
    public FlowableSnapshot read(String sourceTenant) {
        List<String> ids = new ArrayList<>();
        Map<String, Map<String, Object>> roots = new LinkedHashMap<>();
        for (Map<String, Object> r : rows.all(EXECUTION)) {
            String id = str(r, ID);
            if (id != null && id.equals(str(r, PROC_INST_ID)) && sameTenant(str(r, TENANT_ID), sourceTenant)) {
                roots.put(id, r);
                ids.add(id);
            }
        }
        ids.sort(Comparator.naturalOrder());
        List<Instance> instances = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += FlowableRows.IN_CHUNK) {
            instances.addAll(readBatch(ids.subList(i, Math.min(ids.size(), i + FlowableRows.IN_CHUNK)), roots));
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String t : ALL) {
            counts.put(t, rows.count(t));
        }
        List<String> outOfScope = new ArrayList<>();
        for (Map<String, Object> t : rows.all(TASK)) {
            if (str(t, PROC_INST_ID) == null && sameTenant(str(t, TENANT_ID), sourceTenant)) {
                outOfScope.add("standalone task " + str(t, ID) + ": not part of a process instance, not migrated");
            }
        }
        for (String jt : List.of(TIMER_JOB, SUSPENDED_JOB)) {
            for (Map<String, Object> j : rows.all(jt)) {
                if (str(j, JOB_PROCESS_INSTANCE_ID) == null && sameTenant(str(j, TENANT_ID), sourceTenant)) {
                    outOfScope.add("definition-level job " + str(j, ID) + " (" + str(j, HANDLER_TYPE)
                            + "): recreated by the target deployment, not migrated");
                }
            }
        }
        return new FlowableSnapshot(instances, counts, outOfScope);
    }

    /** A source process definition with the listeners its deployed model declares (decision 231). */
    public record Definition(String id, String key, int version, String tenantId, FlowableModelListeners.Found listeners) {
    }

    /** Process definition {@code definitionId} of {@code sourceTenant}, or empty when absent or of another tenant. */
    public java.util.Optional<Definition> definition(String definitionId, String sourceTenant) {
        Objects.requireNonNull(definitionId, "definitionId");
        for (Map<String, Object> d : rows.where(PROCDEF, ID, List.of(definitionId))) {
            if (sameTenant(str(d, TENANT_ID), sourceTenant)) {
                return java.util.Optional.of(new Definition(definitionId, str(d, KEY), (int) num(d, VERSION, 0),
                        nullIfEmpty(str(d, TENANT_ID)), listeners(definitionId, d)));
            }
        }
        return java.util.Optional.empty();
    }

    /** Re-reads the given instances (freeze and apply compare their fingerprints with the plan). */
    public Map<String, Instance> reread(Collection<String> instanceIds) {
        Map<String, Map<String, Object>> roots = new LinkedHashMap<>();
        List<String> ids = new ArrayList<>(new java.util.TreeSet<>(instanceIds));
        for (int i = 0; i < ids.size(); i += FlowableRows.IN_CHUNK) {
            for (Map<String, Object> r : rows.where(EXECUTION, ID, ids.subList(i, Math.min(ids.size(), i + FlowableRows.IN_CHUNK)))) {
                if (str(r, ID).equals(str(r, PROC_INST_ID))) {
                    roots.put(str(r, ID), r);
                }
            }
        }
        Map<String, Instance> out = new LinkedHashMap<>();
        List<String> present = new ArrayList<>(roots.keySet());
        for (int i = 0; i < present.size(); i += FlowableRows.IN_CHUNK) {
            for (Instance in : readBatch(present.subList(i, Math.min(present.size(), i + FlowableRows.IN_CHUNK)), roots)) {
                out.put(in.id(), in);
            }
        }
        return out;
    }

    private List<Instance> readBatch(List<String> ids, Map<String, Map<String, Object>> roots) {
        Map<String, List<Map<String, Object>>> execs = group(rows.where(EXECUTION, PROC_INST_ID, ids), PROC_INST_ID);
        Map<String, List<Map<String, Object>>> tasks = group(rows.where(TASK, PROC_INST_ID, ids), PROC_INST_ID);
        Map<String, List<Map<String, Object>>> vars = group(rows.where(VARIABLE, PROC_INST_ID, ids), PROC_INST_ID);
        Map<String, List<Map<String, Object>>> subs = group(rows.where(EVENT_SUBSCR, PROC_INST_ID, ids), PROC_INST_ID);
        Map<String, List<Object[]>> jobs = new HashMap<>();
        for (String table : JOB_TABLES) {
            for (Map<String, Object> j : rows.where(table, JOB_PROCESS_INSTANCE_ID, ids)) {
                jobs.computeIfAbsent(str(j, JOB_PROCESS_INSTANCE_ID), k -> new ArrayList<>()).add(new Object[]{table, j});
            }
        }
        Set<String> taskIds = new HashSet<>();
        tasks.values().forEach(l -> l.forEach(t -> taskIds.add(str(t, ID))));
        Map<String, List<Map<String, Object>>> links = group(rows.where(IDENTITYLINK, TASK_ID, taskIds), TASK_ID);
        Set<String> execIds = new HashSet<>();
        execs.values().forEach(l -> l.forEach(e -> execIds.add(str(e, ID))));
        Set<String> superOfChild = new HashSet<>();
        for (Map<String, Object> child : rows.where(EXECUTION, SUPER_EXEC, execIds)) {
            superOfChild.add(str(child, SUPER_EXEC));
        }
        Set<String> defIds = new HashSet<>();
        roots.values().forEach(r -> defIds.add(str(r, PROC_DEF_ID)));
        Map<String, Map<String, Object>> defs = new HashMap<>();
        for (Map<String, Object> d : rows.where(PROCDEF, ID, defIds)) {
            defs.put(str(d, ID), d);
        }

        List<Instance> out = new ArrayList<>();
        for (String id : ids) {
            Map<String, Object> root = roots.get(id);
            List<String> problems = new ArrayList<>();
            List<Execution> ex = new ArrayList<>();
            boolean sub = false;
            for (Map<String, Object> e : execs.getOrDefault(id, List.of())) {
                ex.add(new Execution(str(e, ID), str(e, PARENT_ID), str(e, ACT_ID), bool(e, IS_ACTIVE), bool(e, IS_SCOPE),
                        bool(e, IS_MI_ROOT), bool(e, IS_EVENT_SCOPE)));
                sub |= superOfChild.contains(str(e, ID));
            }
            ex.sort(Comparator.comparing(Execution::id));
            List<Task> ts = new ArrayList<>();
            for (Map<String, Object> t : tasks.getOrDefault(id, List.of())) {
                List<String> users = new ArrayList<>();
                List<String> groups = new ArrayList<>();
                for (Map<String, Object> l : links.getOrDefault(str(t, ID), List.of())) {
                    if ("candidate".equals(str(l, TYPE))) {
                        if (str(l, USER_ID) != null) {
                            users.add(str(l, USER_ID));
                        }
                        if (str(l, GROUP_ID) != null) {
                            groups.add(str(l, GROUP_ID));
                        }
                    }
                }
                users.sort(Comparator.naturalOrder());
                groups.sort(Comparator.naturalOrder());
                ts.add(new Task(str(t, ID), str(t, EXECUTION_ID), str(t, TASK_DEF_KEY), str(t, NAME), str(t, ASSIGNEE),
                        str(t, OWNER), str(t, DELEGATION), (int) num(t, PRIORITY, 50), instant(t, DUE_DATE, "task " + str(t, ID), problems),
                        str(t, PARENT_TASK_ID), str(t, SCOPE_TYPE), users, groups));
            }
            ts.sort(Comparator.comparing(Task::id));
            List<Job> js = new ArrayList<>();
            for (Object[] tj : jobs.getOrDefault(id, List.of())) {
                @SuppressWarnings("unchecked")
                Map<String, Object> j = (Map<String, Object>) tj[1];
                String what = "job " + str(j, ID);
                js.add(new Job(str(j, ID), kind((String) tj[0], str(j, TYPE)), str(j, EXECUTION_ID), elementId(j),
                        str(j, LOCK_OWNER), instant(j, LOCK_EXP_TIME, what, problems), (int) num(j, RETRIES, 0),
                        str(j, EXCEPTION_MSG), instant(j, DUEDATE, what, problems), str(j, REPEAT), str(j, HANDLER_TYPE)));
            }
            js.sort(Comparator.comparing(Job::id));
            List<Subscription> ss = new ArrayList<>();
            for (Map<String, Object> s : subs.getOrDefault(id, List.of())) {
                ss.add(new Subscription(str(s, EVENT_TYPE), str(s, EVENT_NAME), str(s, EXECUTION_ID), str(s, ACTIVITY_ID)));
            }
            ss.sort(Comparator.comparing((Subscription s) -> String.valueOf(s.activityId()))
                    .thenComparing(s -> String.valueOf(s.eventName())).thenComparing(s -> String.valueOf(s.executionId())));
            List<Variable> vs = new ArrayList<>();
            for (Map<String, Object> v : vars.getOrDefault(id, List.of())) {
                vs.add(variable(v));
            }
            vs.sort(Comparator.comparing((Variable v) -> String.valueOf(v.taskId())).thenComparing(v -> String.valueOf(v.executionId()))
                    .thenComparing(Variable::name));
            Map<String, Object> def = defs.getOrDefault(str(root, PROC_DEF_ID), Map.of());
            out.add(new Instance(id, str(root, PROC_DEF_ID), str(def, KEY), (int) num(def, VERSION, 0), nullIfEmpty(str(root, TENANT_ID)),
                    str(root, BUSINESS_KEY), num(root, SUSPENSION_STATE, 1) == 2, str(root, SUPER_EXEC), str(root, CALLBACK_TYPE),
                    sub, ex, ts, js, ss, vs, problems, listeners(str(root, PROC_DEF_ID), def)));
        }
        return out;
    }

    /**
     * The listeners the deployed model of a definition declares, read once per definition (a deployed resource never
     * changes): ACT_RE_PROCDEF DEPLOYMENT_ID_ and RESOURCE_NAME_ ({@code flowable.h2.create.engine.sql:85-86}) to
     * ACT_GE_BYTEARRAY BYTES_ ({@code flowable.h2.create.common.sql:8-15}).
     */
    private FlowableModelListeners.Found listeners(String definitionId, Map<String, Object> def) {
        if (definitionId == null || def.isEmpty()) {
            return FlowableModelListeners.Found.unread("process definition " + definitionId + " is not in " + PROCDEF);
        }
        return modelListeners.computeIfAbsent(definitionId, id -> {
            String deployment = str(def, DEPLOYMENT_ID);
            String resource = str(def, RESOURCE_NAME);
            if (deployment == null || resource == null) {
                return FlowableModelListeners.Found.unread("process definition " + id + " names no deployed resource");
            }
            return FlowableModelListeners.parse(rows.resource(deployment, resource), str(def, KEY), resource);
        });
    }

    /**
     * ELEMENT_ID_, or for a {@code trigger-timer} job without it the activity id from HANDLER_CFG_. ELEMENT_ID_ was
     * added without a backfill ({@code flowable.h2.upgradestep.6500.to.6501.common.sql:4-10}); before that the timer
     * activity was only in the handler configuration ({@code TimerEventHandler.java:28-38}). Read as
     * {@code TimerEventHandler.getActivityIdFromConfiguration} ({@code TimerEventHandler.java:50-63}): JSON with an
     * {@code activityId} gives it; JSON without one, and anything that is not JSON, is the configuration text itself
     * (legacy format). One deliberate difference: an explicit JSON {@code null} gives null here (the classifier then
     * rejects with TIMER_ELEMENT_MISSING) instead of whatever text the source Jackson renders for it.
     */
    static String elementId(Map<String, Object> job) {
        String element = str(job, ELEMENT_ID);
        String cfg = str(job, HANDLER_CFG);
        if (element != null || cfg == null || !HANDLER_TRIGGER_TIMER.equals(str(job, HANDLER_TYPE))) {
            return element;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = JSON.readTree(cfg);
            if (node == null || node.isMissingNode()) {
                return cfg;
            }
            com.fasterxml.jackson.databind.JsonNode id = node.get(TIMER_CFG_ACTIVITY_ID);
            if (id == null) {
                return cfg;
            }
            return id.isNull() ? null : id.asText();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return cfg;
        }
    }

    /** The exact value as text, by Flowable variable type. */
    static Variable variable(Map<String, Object> v) {
        String type = str(v, TYPE);
        boolean binary = str(v, BYTEARRAY_ID) != null;
        String text;
        if (binary || type == null) {
            text = null;
        } else {
            text = switch (type) {
                case "integer", "long", "short", "date" -> v.get(LONG) == null ? null : String.valueOf(((Number) v.get(LONG)).longValue());
                case "boolean" -> v.get(LONG) == null ? null : String.valueOf(((Number) v.get(LONG)).longValue() != 0);
                case "double" -> v.get(DOUBLE) == null ? null
                        : v.get(DOUBLE) instanceof java.math.BigDecimal bd ? bd.toPlainString() : String.valueOf(((Number) v.get(DOUBLE)).doubleValue());
                case "null" -> null;
                default -> str(v, TEXT);
            };
        }
        return new Variable(str(v, NAME), type, str(v, EXECUTION_ID), str(v, TASK_ID), text, binary);
    }

    private static JobKind kind(String table, String type) {
        return switch (table) {
            case JOB -> JobKind.ASYNC;
            case TIMER_JOB -> JobKind.TIMER;
            case SUSPENDED_JOB -> JOB_TYPE_TIMER.equals(type) ? JobKind.SUSPENDED_TIMER : JobKind.SUSPENDED_ASYNC;
            case DEADLETTER_JOB -> JobKind.DEADLETTER;
            case EXTERNAL_JOB -> JobKind.EXTERNAL;
            default -> throw new IllegalArgumentException(table);
        };
    }

    /** Wall-clock source time to an instant in the source zone; ambiguous or missing local times are recorded. */
    Instant instant(Map<String, Object> row, String column, String what, List<String> problems) {
        Object v = row.get(column);
        if (v == null) {
            return null;
        }
        if (v instanceof OffsetDateTime odt) {
            return odt.toInstant();
        }
        if (v instanceof java.sql.Timestamp ts) {
            v = ts.toLocalDateTime();
        }
        LocalDateTime local = v instanceof LocalDateTime l ? l : LocalDateTime.parse(String.valueOf(v).replace(' ', 'T'));
        List<ZoneOffset> offsets = sourceZone.getRules().getValidOffsets(local);
        if (offsets.size() == 1) {
            return local.atOffset(offsets.get(0)).toInstant();
        }
        ZoneOffsetTransition tr = sourceZone.getRules().getTransition(local);
        problems.add(what + " " + column + " " + local + " is " + (offsets.isEmpty() ? "in a DST gap" : "ambiguous (DST fold)")
                + " in " + sourceZone + (tr == null ? "" : " at transition " + tr) + "; left unresolved");
        return null;
    }

    private static Map<String, List<Map<String, Object>>> group(List<Map<String, Object>> list, String column) {
        Map<String, List<Map<String, Object>>> out = new HashMap<>();
        for (Map<String, Object> r : list) {
            out.computeIfAbsent(str(r, column), k -> new ArrayList<>()).add(r);
        }
        return out;
    }

    static boolean sameTenant(String a, String b) {
        return Objects.equals(nullIfEmpty(a), nullIfEmpty(b));
    }

    static String nullIfEmpty(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    static String str(Map<String, Object> r, String column) {
        Object v = r.get(column);
        return v == null ? null : String.valueOf(v);
    }

    static long num(Map<String, Object> r, String column, long dflt) {
        Object v = r.get(column);
        if (v == null) {
            return dflt;
        }
        return v instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(v).trim());
    }

    static boolean bool(Map<String, Object> r, String column) {
        Object v = r.get(column);
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            return n.longValue() != 0;
        }
        return v != null && ("true".equalsIgnoreCase(String.valueOf(v)) || "1".equals(String.valueOf(v)));
    }
}

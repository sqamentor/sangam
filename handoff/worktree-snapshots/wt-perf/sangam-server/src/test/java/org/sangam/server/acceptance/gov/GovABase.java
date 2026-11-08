// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.gov;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.sangam.core.api.EngineAdapter;
import org.sangam.core.api.EngineAdapter.ExternalWork;
import org.sangam.core.api.EngineAdapter.TaskQuery;
import org.sangam.core.api.EngineAdapter.TaskView;
import org.sangam.server.acceptance.engine.EngineScenarioSupport;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Harness of the gov-a ports (spike {@code experiments-gov-a} {@code GovBase} + the Operaton {@code Kit}), on the
 * shared Sangam server. The spike built one fresh engine and database per scenario; here every case runs on the
 * server's engine through {@link GovATenantAdapter} (tenant, topic and business-key scoping, see there). Model
 * helpers, worker loop and outcome vocabulary are the spike's.
 */
public abstract class GovABase extends EngineScenarioSupport {

    @Autowired
    protected DataSource dataSource;

    /** The scoped product adapter (named {@code a} as in the spike). */
    protected EngineAdapter a;
    protected GovARec rec;
    private GovAKit kit;

    @BeforeEach
    void scope() {
        GovATenantAdapter scoped = new GovATenantAdapter(adapter, RUN);
        a = scoped;
        kit = new GovAKit(engine, scoped);
    }

    protected GovAKit k() {
        return kit;
    }

    @FunctionalInterface
    protected interface RecBody {
        void run(GovARec r) throws Exception;
    }

    /** One spike scenario: its checks are assertions of this JUnit test. */
    protected void scenario(String id, String title, RecBody body) {
        scenario(id, ctx -> {
            rec = new GovARec(ctx, title);
            body.run(rec);
        });
    }

    // ------------------------------------------------------------------ models

    protected String defs(String body) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\"\n"
                + "  xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
                + "  " + k().ns() + "\n"
                + "  id=\"defs_" + UUID.randomUUID().toString().substring(0, 8) + "\"\n"
                + "  targetNamespace=\"http://sangam.org/spike/gov-a\">\n"
                + body + "\n</definitions>\n";
    }

    /** DMN 1.3 decision table with one input and one output; rules are {inputEntry, outputEntry}. */
    protected static String dmn(String key, String hitPolicy, String input, String inputType, String output,
                                String outputType, List<String[]> rules) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<definitions xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\" id=\"def_").append(key)
                .append("\" name=\"").append(key).append("\" namespace=\"http://sangam.org/spike/gov-a\">\n");
        sb.append("  <decision id=\"").append(key).append("\" name=\"").append(key).append("\">\n");
        sb.append("    <decisionTable id=\"dt_").append(key).append("\" hitPolicy=\"").append(hitPolicy).append("\">\n");
        sb.append("      <input id=\"in_").append(key).append("\" label=\"").append(input).append("\"><inputExpression id=\"ie_")
                .append(key).append("\" typeRef=\"").append(inputType).append("\"><text>").append(input)
                .append("</text></inputExpression></input>\n");
        sb.append("      <output id=\"out_").append(key).append("\" name=\"").append(output).append("\" typeRef=\"")
                .append(outputType).append("\"/>\n");
        int i = 0;
        for (String[] r : rules) {
            i++;
            sb.append("      <rule id=\"r").append(i).append("_").append(key).append("\"><inputEntry id=\"ri").append(i)
                    .append("_").append(key).append("\"><text>").append(xml(r[0])).append("</text></inputEntry>")
                    .append("<outputEntry id=\"ro").append(i).append("_").append(key).append("\"><text>").append(xml(r[1]))
                    .append("</text></outputEntry></rule>\n");
        }
        sb.append("    </decisionTable>\n  </decision>\n</definitions>\n");
        return sb.toString();
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    protected String deploy(String name, Object... nameAndContent) {
        Map<String, byte[]> res = new LinkedHashMap<>();
        for (int i = 0; i + 1 < nameAndContent.length; i += 2) {
            res.put((String) nameAndContent[i], ((String) nameAndContent[i + 1]).getBytes(StandardCharsets.UTF_8));
        }
        return a.deploy(null, name, res);
    }

    protected static String userTask(String id, String groupExpr) {
        return "<userTask id=\"" + id + "\" name=\"" + id + "\"><potentialOwner><resourceAssignmentExpression>"
                + "<formalExpression>group(" + groupExpr + ")</formalExpression></resourceAssignmentExpression>"
                + "</potentialOwner></userTask>";
    }

    protected static String flow(String id, String from, String to) {
        return "<sequenceFlow id=\"" + id + "\" sourceRef=\"" + from + "\" targetRef=\"" + to + "\"/>";
    }

    protected static String flow(String id, String from, String to, String condition) {
        return "<sequenceFlow id=\"" + id + "\" sourceRef=\"" + from + "\" targetRef=\"" + to + "\">"
                + "<conditionExpression xsi:type=\"tFormalExpression\">" + condition.replace("&", "&amp;")
                .replace("<", "&lt;").replace(">", "&gt;") + "</conditionExpression></sequenceFlow>";
    }

    // ------------------------------------------------------------------ tasks

    protected List<TaskView> tasks(String pid) {
        return a.queryTasks(new TaskQuery(null, null, null, pid, null, 0, 0));
    }

    @Override
    protected List<TaskView> tasks(String pid, String taskKey) {
        return a.queryTasks(new TaskQuery(null, null, null, pid, taskKey, 0, 0));
    }

    protected List<TaskView> tasksForGroups(String pid, List<String> groups) {
        return a.queryTasks(new TaskQuery(null, null, groups, pid, null, 0, 0));
    }

    protected TaskView one(String pid, String taskKey) {
        List<TaskView> l = tasks(pid, taskKey);
        if (l.size() != 1) {
            throw new AssertionError("expected 1 task '" + taskKey + "' in " + pid + ", found " + l.size()
                    + " (open: " + names(tasks(pid)) + ")");
        }
        return l.get(0);
    }

    protected static List<String> names(List<TaskView> l) {
        List<String> out = new ArrayList<>();
        l.forEach(t -> out.add(t.taskDefinitionKey()));
        return out;
    }

    protected Object var(String pid, String name) {
        EngineAdapter.InstanceView v = a.getInstance(pid);
        return v == null ? null : v.variables().get(name);
    }

    // ------------------------------------------------------------------ external work

    public record Outcome(String kind, Map<String, Object> vars, String code) {
        public static Outcome complete(Map<String, Object> vars) {
            return new Outcome("complete", vars, null);
        }

        public static Outcome bpmnError(String code, Map<String, Object> vars) {
            return new Outcome("bpmnError", vars, code);
        }

        public static Outcome fail(String message) {
            return new Outcome("fail", Map.of(), message);
        }

        /** The worker dies after doing its effect: nothing is reported, the lease runs out. */
        public static Outcome crash() {
            return new Outcome("crash", Map.of(), null);
        }
    }

    @FunctionalInterface
    protected interface Handler {
        Outcome handle(ExternalWork w) throws Exception;
    }

    /** Fetches every available item of {@code topic} once and reports each outcome; returns the number fetched. */
    protected int work(String topic, Handler h) throws Exception {
        List<ExternalWork> items = a.fetchAndLock("worker-1", topic, 100, Duration.ofMinutes(5));
        for (ExternalWork w : items) {
            Outcome o = h.handle(w);
            switch (o.kind()) {
                case "complete" -> a.completeExternal(w.id(), "worker-1", w.lockVersion(), o.vars());
                case "bpmnError" -> a.bpmnErrorExternal(w.id(), "worker-1", w.lockVersion(), o.code(), o.vars());
                case "fail" -> a.failExternal(w.id(), "worker-1", w.lockVersion(), o.code(), 3, Duration.ZERO);
                default -> { /* crash */ }
            }
        }
        return items.size();
    }

    /** Stable operationId (VI.3.2): UUID from tenant, process instance and activity instance. */
    protected static String operationId(ExternalWork w) {
        return UUID.nameUUIDFromBytes(("gov-a|" + w.processInstanceId() + "|" + w.activityInstanceId())
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    // ------------------------------------------------------------------ time

    protected static Date ist(String localDateTime) {
        return Date.from(LocalDateTime.parse(localDateTime).atZone(GovACal.IST).toInstant());
    }

    protected void clock(String localDateTime) {
        clock(ist(localDateTime).toInstant());
    }

    protected int jobs() {
        return a.executeDueJobs();
    }

    // ------------------------------------------------------------------ scale measurements (details only)

    /** Row counts of the engine tables (ACT_*), for the spike's row-delta details. */
    protected Map<String, Long> engineTableRows() {
        Map<String, Long> out = new TreeMap<>();
        try (Connection c = dataSource.getConnection()) {
            List<String> tables = new ArrayList<>();
            try (ResultSet rs = c.getMetaData().getTables(null, null, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    String n = rs.getString("TABLE_NAME");
                    if (n.toUpperCase().startsWith("ACT_")) {
                        tables.add(n);
                    }
                }
            }
            try (Statement s = c.createStatement()) {
                for (String tname : tables) {
                    try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + tname)) {
                        rs.next();
                        out.put(tname.toUpperCase(), rs.getLong(1));
                    }
                }
            }
        } catch (Exception e) {
            out.put("ERROR " + e.getMessage(), -1L);
        }
        return out;
    }

    protected static long heapUsedAfterGcMb() {
        for (int i = 0; i < 3; i++) {
            System.gc();
        }
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
    }

    protected static void resetPeaks() {
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
            if (p.getType() == MemoryType.HEAP) {
                p.resetPeakUsage();
            }
        }
    }

    protected static long peakHeapMb() {
        long sum = 0;
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
            if (p.getType() == MemoryType.HEAP) {
                sum += p.getPeakUsage().getUsed();
            }
        }
        return sum / (1024 * 1024);
    }

    protected Map<String, Object> rowDelta(Map<String, Long> before, Map<String, Long> after) {
        Map<String, Object> out = new LinkedHashMap<>();
        long total = 0;
        long ru = 0;
        long hi = 0;
        for (Map.Entry<String, Long> e : after.entrySet()) {
            long d = e.getValue() - before.getOrDefault(e.getKey(), 0L);
            if (d != 0) {
                out.put(e.getKey(), d);
                total += d;
                if (e.getKey().startsWith("ACT_RU")) {
                    ru += d;
                }
                if (e.getKey().startsWith("ACT_HI")) {
                    hi += d;
                }
            }
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("totalRowsDelta", total);
        res.put("runtimeRowsDelta", ru);
        res.put("historyRowsDelta", hi);
        res.put("perTable", out);
        return res;
    }

    protected static int scaleN() {
        return Integer.getInteger("gov.scaleN", 5000);
    }

    protected static Instant instant(String iso) {
        return Instant.parse(iso);
    }
}

// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.gov;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.operaton.bpm.engine.history.HistoricDetail;
import org.operaton.bpm.engine.history.HistoricProcessInstance;
import org.operaton.bpm.engine.history.HistoricVariableUpdate;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.jobexecutor.JobExecutor;
import org.operaton.bpm.engine.management.JobDefinition;
import org.operaton.bpm.engine.runtime.Execution;
import org.operaton.bpm.engine.runtime.Job;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.sangam.core.api.EngineAdapter.ExternalWork;
import org.sangam.core.api.EngineAdapter.InstanceView;
import org.sangam.server.acceptance.engine.EngineScenarioSupport;

/**
 * Port of the spike's gov-c {@code GovBase} + {@code OperatonNative} onto the running server.
 *
 * <p>Differences from the spike, all forced by the shared server context:
 * <ul>
 *   <li>The spike ran every scenario on a fresh engine and database with the job executor off. Here every test pauses
 *       the server's job executor (restarted afterwards) so timers fire only through {@code executeDueJobs()}, as in
 *       the spike, and deletes its deployments (cascade) afterwards so timer start events and leftover instances never
 *       leak into later tests.</li>
 *   <li>Business keys get the per-run suffix ({@link #b}); external-work topics get the prefix {@code govc.} (model
 *       token expansion) and product tables the prefix {@code govc_}, so sibling ITs in the same JVM never collide.</li>
 * </ul>
 */
abstract class GovCSupport extends EngineScenarioSupport {

    static final Instant START = Instant.parse("2027-01-04T04:30:00Z");
    static final String TOPIC_PREFIX = "govc.";

    private final List<String> deployments = new ArrayList<>();
    private boolean executorWasActive;

    record Timer(String jobId, String activityId, Instant due, boolean suspended, String executionId) {
    }

    @BeforeEach
    void govcSetUp() {
        JobExecutor je = jobExecutor();
        executorWasActive = je != null && je.isActive();
        if (executorWasActive) {
            je.shutdown();
        }
        clock(START);
    }

    @AfterEach
    void govcTearDown() {
        for (String d : deployments) {
            try {
                engine.getRepositoryService().deleteDeployment(d, true, true, true);
            } catch (RuntimeException ignored) {
                // best effort
            }
        }
        deployments.clear();
        adapter.setClock(null);
        if (executorWasActive) {
            jobExecutor().start();
        }
    }

    private JobExecutor jobExecutor() {
        return ((ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration()).getJobExecutor();
    }

    // ---------------------------------------------------------------- models (spike Models + Operaton dialect)

    private static final Pattern TOKEN = Pattern.compile("@@(ext|expr|callio|callbk)(?:\\|(.*?))?@@", Pattern.DOTALL);
    private static final String NS = "xmlns:operaton=\"http://operaton.org/schema/1.0/bpmn\"";

    protected void deploy(String... models) {
        Map<String, byte[]> res = new LinkedHashMap<>();
        for (String n : models) {
            // stored as *.bpmn.model: the Operaton starter auto-deploys every *.bpmn on the class path at boot, and
            // these files hold unexpanded vendor tokens until this method expands them
            String xml = new String(resource("gov-c/" + n + ".model"), StandardCharsets.UTF_8);
            Matcher m = TOKEN.matcher(xml);
            StringBuilder sb = new StringBuilder();
            boolean any = false;
            while (m.find()) {
                any = true;
                String arg = m.group(2) == null ? "" : m.group(2);
                String rep = switch (m.group(1)) {
                    case "ext" -> "operaton:type=\"external\" operaton:topic=\"" + TOPIC_PREFIX + arg.split(";")[0] + "\"";
                    case "expr" -> "operaton:expression=\"" + attr(arg) + "\"";
                    case "callio" -> callIo(list(arg, "in"), list(arg, "out"), (";" + arg + ";").contains(";bk;"));
                    case "callbk" -> "";
                    default -> throw new IllegalStateException(m.group(1));
                };
                m.appendReplacement(sb, Matcher.quoteReplacement(rep));
            }
            m.appendTail(sb);
            String out = sb.toString();
            if (any) {
                out = out.replaceFirst("<definitions ", "<definitions " + NS + " ");
            }
            res.put(n, out.getBytes(StandardCharsets.UTF_8));
        }
        deployments.add(adapter.deploy(null, "govc-" + String.join("+", models) + "-" + RUN, res));
    }

    private static String callIo(List<String> in, List<String> out, boolean businessKey) {
        StringBuilder sb = new StringBuilder("<extensionElements>");
        if (businessKey) {
            sb.append("<operaton:in businessKey=\"#{execution.processBusinessKey}\"/>");
        }
        in.forEach(v -> sb.append("<operaton:in source=\"").append(v).append("\" target=\"").append(v).append("\"/>"));
        out.forEach(v -> sb.append("<operaton:out source=\"").append(v).append("\" target=\"").append(v).append("\"/>"));
        return sb.append("</extensionElements>").toString();
    }

    private static List<String> list(String arg, String key) {
        for (String part : arg.split(";")) {
            String p = part.trim();
            if (p.startsWith(key + "=")) {
                List<String> out = new ArrayList<>();
                for (String v : p.substring(key.length() + 1).split(",")) {
                    if (!v.isBlank()) {
                        out.add(v.trim());
                    }
                }
                return out;
            }
        }
        return List.of();
    }

    private static String attr(String s) {
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ---------------------------------------------------------------- keys, topics, small helpers

    /** Business key with the per-run suffix. */
    protected static String b(String key) {
        return key + "-" + RUN;
    }

    protected static String t(String topic) {
        return TOPIC_PREFIX + topic;
    }

    protected static Date date(Instant i) {
        return Date.from(i);
    }

    protected static long num(Object o) {
        return o instanceof Number n ? n.longValue() : Long.MIN_VALUE;
    }

    protected static boolean throwsOn(Runnable r) {
        try {
            r.run();
            return false;
        } catch (RuntimeException e) {
            return true;
        }
    }

    protected Object var(String instanceId, String name) {
        InstanceView v = adapter.getInstance(instanceId);
        return v == null ? null : v.variables().get(name);
    }

    protected boolean visited(String pid, String activityId) {
        return historicActivityIds(pid).contains(activityId);
    }

    protected long countVisits(String pid, String activityId) {
        return historicActivityIds(pid).stream().filter(activityId::equals).count();
    }

    // ---------------------------------------------------------------- external work (topic without prefix)

    protected ExternalWork fetchOne(Ctx ctx, String instanceId, String topic) {
        String full = t(topic);
        List<ExternalWork> got = adapter.fetchAndLock("w-" + full, full, 100, Duration.ofMinutes(5));
        ExternalWork mine = null;
        for (ExternalWork w : got) {
            if ((instanceId == null || instanceId.equals(w.processInstanceId())) && mine == null) {
                mine = w;
            } else {
                adapter.unlockExternal(w.id(), "w-" + full, w.lockVersion());
            }
        }
        ctx.require("external work on topic " + topic + " fetched", mine != null);
        return mine;
    }

    protected List<ExternalWork> fetch(String topic, int max) {
        return adapter.fetchAndLock("w-" + t(topic), t(topic), max, Duration.ofMinutes(5));
    }

    protected void completeWork(ExternalWork w, Map<String, Object> vars) {
        adapter.completeExternal(w.id(), "w-" + w.topic(), w.lockVersion(), vars);
    }

    protected void work(Ctx ctx, String instanceId, String topic, Map<String, Object> vars) {
        completeWork(fetchOne(ctx, instanceId, topic), vars == null ? Map.of() : vars);
    }

    /** Spike GovBase.complete: null variables allowed. */
    @Override
    protected void complete(Ctx ctx, String pid, String key, String group, Map<String, Object> vars) {
        super.complete(ctx, pid, key, group, vars == null ? Map.of() : vars);
    }

    // ---------------------------------------------------------------- timers (public Operaton API)

    private Timer timer(Job j) {
        String activity = null;
        if (j.getJobDefinitionId() != null) {
            JobDefinition d = engine.getManagementService().createJobDefinitionQuery().jobDefinitionId(j.getJobDefinitionId())
                    .singleResult();
            activity = d == null ? null : d.getActivityId();
        }
        return new Timer(j.getId(), activity, j.getDuedate() == null ? null : j.getDuedate().toInstant(), j.isSuspended(),
                j.getExecutionId());
    }

    protected List<Timer> timers(String pid) {
        List<Timer> out = new ArrayList<>();
        for (Job j : engine.getManagementService().createJobQuery().processInstanceId(pid).timers().list()) {
            out.add(timer(j));
        }
        return out;
    }

    protected List<Timer> startTimers(String processKey) {
        List<Timer> out = new ArrayList<>();
        for (Job j : engine.getManagementService().createJobQuery().processDefinitionKey(processKey).timers().list()) {
            if (j.getProcessInstanceId() == null) {
                out.add(timer(j));
            }
        }
        return out;
    }

    protected Timer timerAt(String pid, String activityId) {
        for (Timer t : timers(pid)) {
            if (activityId.equals(t.activityId())) {
                return t;
            }
        }
        return null;
    }

    protected static boolean dueIs(Timer t, Instant expected) {
        return t != null && t.due() != null && Math.abs(Duration.between(t.due(), expected).toSeconds()) <= 1;
    }

    protected void setTimerDue(String jobId, Instant due) {
        engine.getManagementService().setJobDuedate(jobId, Date.from(due));
    }

    protected void recalculateTimerFromModel(String jobId) {
        engine.getManagementService().recalculateJobDuedate(jobId, false);
    }

    // ---------------------------------------------------------------- runtime (public Operaton API)

    protected void suspendInstance(String pid) {
        engine.getRuntimeService().suspendProcessInstanceById(pid);
    }

    protected void activateInstance(String pid) {
        engine.getRuntimeService().activateProcessInstanceById(pid);
    }

    protected boolean isSuspended(String pid) {
        ProcessInstance p = engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(pid).singleResult();
        return p != null && p.isSuspended();
    }

    protected void setVariable(String id, String name, Object value) {
        engine.getRuntimeService().setVariable(id, name, value);
    }

    protected Object getVariable(String id, String name) {
        return engine.getRuntimeService().getVariable(id, name);
    }

    protected void setVariablesLocal(String executionId, Map<String, Object> vars) {
        engine.getRuntimeService().setVariablesLocal(executionId, vars);
    }

    protected List<String> messageSubscriptions(String pid, String messageName) {
        List<String> out = new ArrayList<>();
        for (Execution e : engine.getRuntimeService().createExecutionQuery().processInstanceId(pid)
                .messageEventSubscriptionName(messageName).list()) {
            out.add(e.getId());
        }
        return out;
    }

    protected String taskExecutionId(String taskId) {
        return engine.getTaskService().createTaskQuery().taskId(taskId).singleResult().getExecutionId();
    }

    protected void startAdditionalActivity(String pid, String activityId) {
        engine.getRuntimeService().createProcessInstanceModification(pid).startBeforeActivity(activityId).execute();
    }

    protected String superInstance(String child) {
        ProcessInstance p = engine.getRuntimeService().createProcessInstanceQuery().subProcessInstanceId(child).singleResult();
        return p == null ? null : p.getId();
    }

    protected List<String> subInstances(String pid) {
        List<String> out = new ArrayList<>();
        for (ProcessInstance p : engine.getRuntimeService().createProcessInstanceQuery().superProcessInstanceId(pid).list()) {
            out.add(p.getId());
        }
        return out;
    }

    protected List<String> runningInstances(String processKey) {
        List<String> out = new ArrayList<>();
        for (ProcessInstance p : engine.getRuntimeService().createProcessInstanceQuery().processDefinitionKey(processKey).list()) {
            out.add(p.getId());
        }
        return out;
    }

    /**
     * Delivers a message to the multi-instance branch whose {@code loopCounter} is {@code index}. Sangam replacement
     * (D-35): {@code SangamEngineAdapter.correlateMessageToBranch} (root-locked, addressed by the branch's local
     * variable) instead of the spike's execution query + {@code messageEventReceived}. Its variables are set through
     * the correlation (Operaton {@code setVariables}), not as branch-local variables.
     */
    protected boolean deliverToBranch(String pid, String messageName, int index, Map<String, Object> vars) {
        try {
            return adapter.correlateMessageToBranch(null, messageName, pid, "loopCounter", index, vars) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    protected String startLinked(String processKey, String businessKey, Map<String, Object> vars, String refId, String refType) {
        Map<String, Object> v = new LinkedHashMap<>(vars);
        v.put("linkRefId", refId);
        v.put("linkRefType", refType);
        return adapter.startProcess(null, processKey, businessKey, v);
    }

    protected List<String> linkedInstances(String refId, String refType) {
        List<String> out = new ArrayList<>();
        for (ProcessInstance p : engine.getRuntimeService().createProcessInstanceQuery()
                .variableValueEquals("linkRefId", refId).variableValueEquals("linkRefType", refType).list()) {
            out.add(p.getId());
        }
        return out;
    }

    // ---------------------------------------------------------------- history

    protected List<Object> variableHistory(String pid, String name) {
        List<Object> out = new ArrayList<>();
        for (HistoricDetail d : engine.getHistoryService().createHistoricDetailQuery().processInstanceId(pid)
                .variableUpdates().orderPartiallyByOccurrence().asc().list()) {
            if (d instanceof HistoricVariableUpdate u && name.equals(u.getVariableName())) {
                out.add(u.getValue());
            }
        }
        return out;
    }

    protected List<String> historicInstancesByBusinessKey(String processKey, String businessKey) {
        List<String> out = new ArrayList<>();
        for (HistoricProcessInstance h : engine.getHistoryService().createHistoricProcessInstanceQuery()
                .processDefinitionKey(processKey).processInstanceBusinessKey(businessKey).list()) {
            out.add(h.getId());
        }
        return out;
    }

    protected void deleteHistoricInstance(String pid) {
        engine.getHistoryService().deleteHistoricProcessInstance(pid);
    }

    // ---------------------------------------------------------------- product SQL (engine transaction)

    protected void sql(String s) {
        adapter.inEngineTransaction(c -> {
            try (Statement st = c.createStatement()) {
                st.execute(s);
            } catch (SQLException e) {
                throw new IllegalStateException(s, e);
            }
            return null;
        });
    }

    protected long count(String q) {
        return adapter.inEngineTransaction(c -> {
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(q)) {
                return rs.next() ? rs.getLong(1) : -1L;
            } catch (SQLException e) {
                throw new IllegalStateException(q, e);
            }
        });
    }

    protected static List<Integer> queryInts(Connection c, String q) {
        List<Integer> out = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(q)) {
            while (rs.next()) {
                out.add(rs.getInt(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(q, e);
        }
        return Collections.unmodifiableList(out);
    }
}

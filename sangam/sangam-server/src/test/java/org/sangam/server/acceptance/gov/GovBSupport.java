// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.gov;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.operaton.bpm.engine.history.HistoricProcessInstance;
import org.operaton.bpm.engine.history.HistoricProcessInstanceQuery;
import org.operaton.bpm.engine.history.HistoricTaskInstance;
import org.operaton.bpm.engine.runtime.CaseExecution;
import org.operaton.bpm.engine.runtime.Job;
import org.operaton.bpm.engine.runtime.ProcessInstanceQuery;
import org.sangam.core.api.EngineAdapter.ExternalWork;
import org.sangam.core.api.EngineAdapter.InstanceView;
import org.sangam.core.api.EngineAdapter.TaskQuery;
import org.sangam.core.api.EngineAdapter.TaskView;
import org.sangam.server.acceptance.engine.EngineScenarioSupport;

/**
 * Port of the spike's gov-b support ({@code Gov}, {@code GovTestBase}, {@code Models}, {@code NativeOperaton}) onto
 * the running server: engine calls go through Sangam's {@code SangamEngineAdapter}; the spike's "native" reads use
 * the server's {@code ProcessEngine}. Differences forced by the shared server (one database and one Spring context
 * for every acceptance class in the JVM, where the spike had a fresh database per class):
 * <ul>
 *   <li>every spike tenant {@code x} becomes {@code x-<run>} ({@link #tn}), and every native count is scoped to the
 *   pattern's tenant, so counts mean exactly what they meant on the spike's private database;</li>
 *   <li>models are rendered for Operaton only (the spike's Flowable branch is gone, ADR-D-1 FINAL).</li>
 * </ul>
 */
public abstract class GovBSupport extends EngineScenarioSupport {

    /** Monday 2 Nov 2026, 10:00 IST: the engine clock at the start of every pattern. */
    protected static final Instant T0 = Instant.parse("2026-11-02T04:30:00Z");
    protected static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
    protected static final int THREADS = 4;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final DateTimeFormatter ISO_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    /** Sangam D-12: a DMN call must pin its binding (the spike relied on Operaton's default 'latest'). */
    private static final String BINDING = " operaton:decisionRefBinding=\"deployment\"";
    private static final Pattern TOKEN = Pattern.compile("\\{\\{([A-Z]+)(?: ([^}]*))?}}");

    @BeforeEach
    void clockAtT0() {
        clock(T0);
    }

    /** The run-unique engine tenant for spike tenant {@code base}. */
    protected static String tn(String base) {
        return base + "-" + RUN;
    }

    // ---------------------------------------------------------------- models (spike Models, Operaton rendering)

    protected static byte[] render(String file) {
        // stored as *.tpl: the Operaton starter auto-deploys every *.bpmn/*.cmmn/*.dmn on the class path at startup
        String xml = new String(resource("gov-b/" + file + ".tpl"), StandardCharsets.UTF_8);
        boolean cmmn = file.endsWith(".cmmn");
        Matcher m = TOKEN.matcher(xml);
        StringBuilder sb = new StringBuilder();
        boolean any = false;
        while (m.find()) {
            any = true;
            String args = m.group(2) == null ? "" : m.group(2).trim();
            String out = switch (m.group(1)) {
                case "NS" -> "";
                case "EXT" -> "operaton:type=\"external\" operaton:topic=\"" + args + "\"";
                case "DMN" -> {
                    String[] a = args.split("\\|");
                    StringBuilder d = new StringBuilder("<businessRuleTask id=\"" + a[0] + "\" name=\"" + a[1]
                            + "\" operaton:decisionRef=\"" + a[2] + "\"" + BINDING
                            + "><extensionElements><operaton:inputOutput>");
                    for (String o : a[3].split(",")) {
                        d.append("<operaton:outputParameter name=\"").append(o)
                                .append("\">${decisionResult.getSingleResult().").append(o)
                                .append("}</operaton:outputParameter>");
                    }
                    yield d.append("</operaton:inputOutput></extensionElements></businessRuleTask>").toString();
                }
                case "DMNCOLLECT" -> {
                    String[] a = args.split("\\|");
                    yield "<businessRuleTask id=\"" + a[0] + "\" name=\"" + a[1] + "\" operaton:decisionRef=\"" + a[2]
                            + "\"" + BINDING + " operaton:mapDecisionResult=\"collectEntries\" operaton:resultVariable=\"" + a[2] + "\"/>";
                }
                case "CALLED" -> {
                    // Sangam D-12 (FLOWABLE-INTO-OPERATON): an unbound call activity binds to its own deployment; the
                    // callee is a separate deployment of the CALLER's tenant, so the binding is declared explicitly.
                    // No calledElementTenantId: SL-13 tenant isolation refuses a definition of another tenant
                    // (SANGAM.md section 10, J-P2-E09; proven by sangam-engine CrossTenantCallH2Test).
                    yield "calledElement=\"" + args.trim() + "\" operaton:calledElementBinding=\"latest\"";
                }
                case "CALLIO" -> {
                    StringBuilder io = new StringBuilder("<extensionElements>");
                    for (String part : args.split(";")) {
                        String[] kv = part.split("=", 2);
                        for (String pair : kv[1].split(",")) {
                            String[] st = pair.split(">");
                            io.append("<operaton:").append(kv[0].trim()).append(" source=\"").append(st[0].trim())
                                    .append("\" target=\"").append(st[1].trim()).append("\"/>");
                        }
                    }
                    yield io.append("</extensionElements>").toString();
                }
                default -> throw new IllegalArgumentException("unknown token " + m.group(1) + " in " + file);
            };
            m.appendReplacement(sb, Matcher.quoteReplacement(out));
        }
        m.appendTail(sb);
        String result = sb.toString();
        if (any) {
            String ns = cmmn ? " xmlns:operaton=\"http://operaton.org/schema/1.0/cmmn\""
                    : " xmlns:operaton=\"http://operaton.org/schema/1.0/bpmn\"";
            result = result.replaceFirst("<definitions ", "<definitions" + ns + " ");
        }
        return result.getBytes(StandardCharsets.UTF_8);
    }

    /** Deploys rendered models to the run tenant of {@code tenant}. */
    protected String deploy(String tenant, String... files) {
        Map<String, byte[]> res = new LinkedHashMap<>();
        for (String f : files) {
            res.put(f, render(f));
        }
        TENANTS.add(tn(tenant));
        return adapter.deploy(tn(tenant), String.join("+", files) + "-" + RUN, res);
    }

    // ---------------------------------------------------------------- spike Gov helpers

    protected String start(String tenant, String key, String bk, Map<String, Object> vars) {
        return adapter.startProcess(tn(tenant), key, bk, vars);
    }

    protected String startCase(String tenant, String key, String bk, Map<String, Object> vars) {
        return adapter.startCase(tn(tenant), key, bk, vars);
    }

    protected List<TaskView> tasksOf(String instanceId) {
        return adapter.queryTasks(new TaskQuery(null, null, List.of(), instanceId, null, 0, 0));
    }

    protected List<TaskView> tasksIn(String instanceId, String key) {
        return adapter.queryTasks(new TaskQuery(null, null, List.of(), instanceId, key, 0, 0));
    }

    protected long count(String tenant, String key) {
        return adapter.countTasks(new TaskQuery(tn(tenant), null, List.of(), null, key, 0, 0));
    }

    protected List<TaskView> forGroups(String tenant, String... groups) {
        return adapter.queryTasks(new TaskQuery(tn(tenant), null, Arrays.asList(groups), null, null, 0, 0));
    }

    protected List<TaskView> forUser(String tenant, String user) {
        return adapter.queryTasks(new TaskQuery(tn(tenant), user, List.of(), null, null, 0, 0));
    }

    protected TaskView task(String instanceId, String key) {
        List<TaskView> t = tasksIn(instanceId, key);
        if (t.size() != 1) {
            throw new AssertionError("expected 1 task '" + key + "' in " + instanceId + ", found " + t.size()
                    + " (open: " + tasksOf(instanceId).stream().map(TaskView::taskDefinitionKey).toList() + ")");
        }
        return t.get(0);
    }

    protected void completeTask(String instanceId, String key, Map<String, Object> vars) {
        adapter.completeTask(task(instanceId, key).id(), vars);
    }

    protected void completeAs(String instanceId, String key, String user, Map<String, Object> vars) {
        TaskView t = task(instanceId, key);
        adapter.claimTask(t.id(), user);
        adapter.completeTask(t.id(), vars);
    }

    /** Spike {@code Gov.correlate}: Operaton side, the adapter's correlation (no fallback was needed there). */
    protected String correlate(String tenant, String message, String bk, Map<String, Object> vars) {
        return adapter.correlateMessage(tn(tenant), message, bk, vars);
    }

    protected void cancelQuietly(String id) {
        try {
            adapter.cancelInstance(id, "test cleanup");
        } catch (RuntimeException ignored) {
            // already ended
        }
    }

    /**
     * The spike ran with the job executor off, so one {@code executeDueJobs()} ran every due job. The server's job
     * executor is on: a due job it has locked is skipped by the adapter's pass and finishes a moment later. This
     * drains the due jobs of this class's tenants (adapter passes until none is executable, at most 60 s), which is
     * the spike's "every due job has run" state, not a weaker one.
     */
    protected int jobs() {
        int n = adapter.executeDueJobs();
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline && !TENANTS.isEmpty()
                && engine.getManagementService().createJobQuery().tenantIdIn(TENANTS.toArray(String[]::new))
                .executable().count() > 0) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            n += adapter.executeDueJobs();
        }
        return n;
    }

    private static final java.util.Set<String> TENANTS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    protected InstanceView instance(String id) {
        return adapter.getInstance(id);
    }

    protected Object var(String id, String name) {
        InstanceView v = adapter.getInstance(id);
        return v == null ? null : v.variables().get(name);
    }

    /** {@code n} items on {@link #THREADS} threads, retrying an item up to 5 times on a conflict; returns retries. */
    protected long parallel(int n, IntConsumer item) {
        AtomicLong retries = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int k = i;
                fs.add(pool.submit(() -> {
                    for (int attempt = 1; ; attempt++) {
                        try {
                            item.accept(k);
                            return;
                        } catch (RuntimeException e) {
                            if (attempt >= 5 || !isConflict(e)) {
                                throw e;
                            }
                            retries.incrementAndGet();
                        }
                    }
                }));
            }
            for (Future<?> f : fs) {
                f.get();
            }
        } catch (Exception e) {
            throw new IllegalStateException(e.getCause() != null ? e.getCause() : e);
        } finally {
            pool.shutdownNow();
        }
        return retries.get();
    }

    static boolean isConflict(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            String n = t.getClass().getSimpleName();
            if (n.contains("OptimisticLocking") || n.contains("Deadlock")) {
                return true;
            }
        }
        return false;
    }

    protected List<ExternalWork> fetchAll(String worker, String topic, int batch) {
        List<ExternalWork> all = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            List<ExternalWork> w = adapter.fetchAndLock(worker, topic, batch, Duration.ofMinutes(5));
            if (w.isEmpty()) {
                break;
            }
            all.addAll(w);
        }
        return all;
    }

    protected ExternalWork fetchOne(String worker, String topic) {
        List<ExternalWork> w = adapter.fetchAndLock(worker, topic, 1, Duration.ofMinutes(5));
        if (w.size() != 1) {
            throw new AssertionError("expected 1 work item on topic " + topic + ", got " + w.size());
        }
        return w.get(0);
    }

    // ---------------------------------------------------------------- spike GovTestBase helpers

    protected static Map<String, Object> m(Object... kv) {
        Map<String, Object> out = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            out.put((String) kv[i], kv[i + 1]);
        }
        return out;
    }

    protected static String iso(Instant t) {
        return ISO_SECONDS.format(t.atOffset(IST));
    }

    protected static Instant days(long d) {
        return T0.plus(Duration.ofDays(d));
    }

    protected static Instant hours(long h) {
        return T0.plus(Duration.ofHours(h));
    }

    protected static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected static HttpResponse<String> post(URI uri, String contentType, String body) {
        try {
            return HTTP.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).header("Content-Type", contentType)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Operaton collectEntries gives a {@code List}; anything else is reported as is. */
    protected static List<String> collected(Object v) {
        if (v == null) {
            return List.of();
        }
        if (v instanceof List<?> l) {
            return l.stream().map(String::valueOf).toList();
        }
        throw new IllegalStateException("COLLECT result is not a List: " + v.getClass().getName() + " " + v);
    }

    protected static String root(Throwable t) {
        Throwable r = t;
        while (r.getCause() != null && r.getCause() != r) {
            r = r.getCause();
        }
        return r.getClass().getSimpleName() + ": " + String.valueOf(r.getMessage()).lines().findFirst().orElse("");
    }

    protected static String first(Throwable t) {
        return t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()).lines().findFirst().orElse("");
    }

    /** The spike's {@code assertEquals}: a required check (numbers compared as long). */
    protected static void eq(Ctx ctx, Object expected, Object actual, String what) {
        Object e = expected instanceof Number n && !(expected instanceof Double) ? (Object) n.longValue() : expected;
        Object a = actual instanceof Number n && !(actual instanceof Double) ? (Object) n.longValue() : actual;
        ctx.require(what + " (expected " + expected + ", got " + actual + ")", Objects.equals(e, a));
    }

    protected static void yes(Ctx ctx, boolean ok, String what) {
        ctx.require(what, ok);
    }

    protected static long ms(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    // ---------------------------------------------------------------- spike NativeOperaton, tenant-scoped

    private ProcessInstanceQuery piq(String tenant, String key) {
        return engine.getRuntimeService().createProcessInstanceQuery().processDefinitionKey(key).tenantIdIn(tn(tenant));
    }

    private HistoricProcessInstanceQuery hpq(String tenant, String key) {
        return engine.getHistoryService().createHistoricProcessInstanceQuery().processDefinitionKey(key)
                .tenantIdIn(tn(tenant));
    }

    protected long runningInstances(String tenant, String key) {
        return piq(tenant, key).count();
    }

    protected long finishedInstances(String tenant, String key) {
        return hpq(tenant, key).finished().count();
    }

    protected void suspend(String pid) {
        engine.getRuntimeService().suspendProcessInstanceById(pid);
    }

    protected void activate(String pid) {
        engine.getRuntimeService().activateProcessInstanceById(pid);
    }

    protected boolean isSuspended(String pid) {
        return engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(pid).singleResult().isSuspended();
    }

    protected void setVariable(String pid, String name, Object value) {
        engine.getRuntimeService().setVariable(pid, name, value);
    }

    protected List<String> enabledCaseItems(String caseId, String planItemId) {
        List<String> ids = new ArrayList<>();
        for (CaseExecution ce : engine.getCaseService().createCaseExecutionQuery().caseInstanceId(caseId)
                .activityId(planItemId).enabled().list()) {
            ids.add(ce.getId());
        }
        return ids;
    }

    protected void startCaseItem(String itemId) {
        engine.getCaseService().manuallyStartCaseExecution(itemId);
    }

    protected List<String> historicByBusinessKey(String tenant, String key, String bk) {
        return hpq(tenant, key).processInstanceBusinessKey(bk).list().stream().map(HistoricProcessInstance::getId).toList();
    }

    protected long runtimeInstancesByVar(String tenant, String key, String name, Object value) {
        return piq(tenant, key).variableValueEquals(name, value).count();
    }

    protected long runtimeInstancesByVarLike(String tenant, String key, String name, String like) {
        return piq(tenant, key).variableValueLike(name, like).count();
    }

    protected long historicInstancesByVar(String tenant, String key, String name, Object value) {
        return hpq(tenant, key).variableValueEquals(name, value).count();
    }

    protected long tasksByProcessVar(String tenant, String name, Object value) {
        return engine.getTaskService().createTaskQuery().tenantIdIn(tn(tenant)).processVariableValueEquals(name, value)
                .count();
    }

    protected long failedWork(String pid) {
        return engine.getRuntimeService().createIncidentQuery().processInstanceId(pid).count();
    }

    protected List<Map<String, Object>> evaluateDecision(String key, String tenant, Map<String, Object> vars) {
        var b = engine.getDecisionService().evaluateDecisionTableByKey(key).variables(vars);
        if (tenant != null) {
            b.decisionDefinitionTenantId(tn(tenant));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        b.evaluate().getResultList().forEach(r -> out.add(new HashMap<>(r)));
        return out;
    }

    protected List<String> subProcessInstances(String superPid) {
        return engine.getHistoryService().createHistoricProcessInstanceQuery().superProcessInstanceId(superPid).list()
                .stream().map(HistoricProcessInstance::getId).toList();
    }

    protected List<String> processInstancesOfCase(String caseId) {
        return engine.getHistoryService().createHistoricProcessInstanceQuery().superCaseInstanceId(caseId).list()
                .stream().map(HistoricProcessInstance::getId).toList();
    }

    protected Instant earliestTimerDue(String pid) {
        return engine.getManagementService().createJobQuery().processInstanceId(pid).timers().list().stream()
                .map(Job::getDuedate).filter(Objects::nonNull).map(Date::toInstant).min(Instant::compareTo).orElse(null);
    }

    protected void rescheduleTimers(String pid, Instant due) {
        for (Job j : engine.getManagementService().createJobQuery().processInstanceId(pid).timers().list()) {
            engine.getManagementService().setJobDuedate(j.getId(), Date.from(due));
        }
    }

    protected long historicActivityCount(String pid, String activityId) {
        return engine.getHistoryService().createHistoricActivityInstanceQuery().processInstanceId(pid)
                .activityId(activityId).count();
    }

    protected Map<String, Long> finishedTasksByAssignee(String tenant, String key) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (HistoricTaskInstance t : engine.getHistoryService().createHistoricTaskInstanceQuery()
                .processDefinitionKey(key).tenantIdIn(tn(tenant)).finished().list()) {
            out.merge(String.valueOf(t.getAssignee()), 1L, Long::sum);
        }
        return out;
    }

    protected long finishedByEndActivity(String tenant, String key, String endActivityId) {
        return hpq(tenant, key).finished().list().stream().filter(h -> endActivityId.equals(h.getEndActivityId())).count();
    }

    protected double avgDurationMs(String tenant, String key) {
        return hpq(tenant, key).finished().list().stream()
                .mapToLong(h -> h.getDurationInMillis() == null ? 0 : h.getDurationInMillis()).average().orElse(0);
    }

    protected String instanceTenant(String pid) {
        HistoricProcessInstance h = engine.getHistoryService().createHistoricProcessInstanceQuery().processInstanceId(pid)
                .singleResult();
        return h == null ? null : h.getTenantId();
    }
}

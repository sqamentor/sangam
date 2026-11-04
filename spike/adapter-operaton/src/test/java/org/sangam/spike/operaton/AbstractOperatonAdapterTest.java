package org.sangam.spike.operaton;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import org.operaton.bpm.engine.BadUserRequestException;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.runtime.ProcessInstance;

import org.sangam.spike.api.EngineAdapter.ExternalWork;
import org.sangam.spike.api.EngineAdapter.InstanceView;
import org.sangam.spike.api.EngineAdapter.TaskQuery;
import org.sangam.spike.api.EngineAdapter.TaskView;
import org.sangam.spike.api.ExpiryPolicy;
import org.sangam.spike.api.LeaseTable;
import org.sangam.spike.api.StaleLeaseException;
import org.sangam.spike.api.StaleLeaseException.Reason;

/** Adapter tests, run once per database (H2 and PostgreSQL subclasses). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractOperatonAdapterTest {

    protected OperatonEngineAdapter adapter;

    protected abstract OperatonEngineAdapter newAdapter();

    @BeforeAll
    void startEngine() {
        adapter = newAdapter();
    }

    @AfterAll
    void stopEngine() {
        try {
            if (adapter != null) {
                adapter.close();
            }
        } finally {
            afterEngineStopped();
        }
    }

    /** Called after the engine is closed (subclasses stop their database here). */
    protected void afterEngineStopped() {
    }

    @AfterEach
    void cleanUp() {
        adapter.setClock(null);
        adapter.setExpiryPolicy(null);
        var rs = adapter.processEngine().getRuntimeService();
        for (ProcessInstance pi : rs.createProcessInstanceQuery().list()) {
            rs.deleteProcessInstance(pi.getId(), "test cleanup");
        }
    }

    private static String uniq(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private void deploy(String name, String xml) {
        adapter.deploy(null, name, Map.of(name, Models.bytes(xml)));
    }

    // ---------------------------------------------------------------- 1. basic flow

    @Test
    void userTaskThenExternalTaskEndsInstance() {
        String key = uniq("basic");
        String group = uniq("clerks");
        deploy(key + ".bpmn", Models.userThenPay(key, group));

        String id = adapter.startProcess(null, key, "bk-" + key, Map.of("amount", 500));
        InstanceView running = adapter.getInstance(id);
        assertFalse(running.ended());
        assertEquals(List.of("review"), running.activeActivityIds());
        assertEquals(key, running.processKey());

        TaskQuery byGroup = new TaskQuery(null, null, List.of(group), id, null, 0, 10);
        assertEquals(1, adapter.countTasks(byGroup));
        List<TaskView> tasks = adapter.queryTasks(byGroup);
        assertEquals(1, tasks.size());
        TaskView task = tasks.get(0);
        assertEquals("review", task.taskDefinitionKey());
        assertEquals(500, task.variables().get("amount"));

        // candidate user + groups is an OR query; still exactly one task, also after claiming
        TaskQuery userOrGroup = new TaskQuery(null, "asha", List.of(group), id, null, 0, 10);
        assertEquals(1, adapter.countTasks(userOrGroup));
        adapter.claimTask(task.id(), "asha");
        assertEquals(1, adapter.countTasks(userOrGroup));
        assertEquals("asha", adapter.queryTasks(userOrGroup).get(0).assignee());
        adapter.completeTask(task.id(), Map.of("approved", true));

        List<ExternalWork> work = adapter.fetchAndLock("worker-1", "pay", 10, Duration.ofMinutes(5));
        ExternalWork w = work.stream().filter(x -> x.processInstanceId().equals(id)).findFirst().orElseThrow();
        assertEquals("pay", w.topic());
        assertEquals("pay", w.activityId());
        assertEquals(Boolean.TRUE, w.variables().get("approved"));
        assertNotNull(w.lockExpiresAt());
        assertEquals(1, w.lockVersion());
        assertNotNull(w.attemptId());
        String actInst = adapter.processEngine().getExternalTaskService().createExternalTaskQuery()
                .externalTaskId(w.id()).singleResult().getActivityInstanceId();
        assertEquals(actInst, w.activityInstanceId(), "Operaton: native activity instance id");

        adapter.completeExternal(w.id(), "worker-1", w.lockVersion(), Map.of("paymentRef", "PFMS-1"));

        InstanceView done = adapter.getInstance(id);
        assertTrue(done.ended());
        assertEquals("bk-" + key, done.businessKey());
        assertEquals("PFMS-1", done.variables().get("paymentRef"));
    }

    // ---------------------------------------------------------------- 2. Q1: product rows in the engine tx

    private static void exec(Connection c, String sql, Object... args) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.execute();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** q1_product, created with the dialect's create-if-missing form (SQL Server has no IF NOT EXISTS). */
    private static void createQ1Table(Connection c) {
        String cols = "(id varchar(64) primary key, note varchar(200))";
        switch (LeaseTable.Dialect.detect(c)) {
            case SQLSERVER -> exec(c, "if object_id('q1_product', 'U') is null create table q1_product " + cols);
            case ORACLE -> exec(c, "begin execute immediate 'create table q1_product " + cols + "'; "
                    + "exception when others then if sqlcode != -955 then raise; end if; end;");
            default -> exec(c, "create table if not exists q1_product " + cols);
        }
    }

    private long productRows(String id) {
        return adapter.inEngineTransaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("select count(*) from q1_product where id = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private long engineInstances(String businessKey) {
        var engine = adapter.processEngine();
        return engine.getRuntimeService().createProcessInstanceQuery().processInstanceBusinessKey(businessKey).count()
                + engine.getHistoryService().createHistoricProcessInstanceQuery()
                .processInstanceBusinessKey(businessKey).count();
    }

    @Test
    void q1ProductRowAndEngineStateRollBackAndCommitTogether() {
        String key = uniq("q1");
        deploy(key + ".bpmn", Models.singleUserTask(key));
        adapter.inEngineTransaction(c -> {
            createQ1Table(c);
            return null;
        });

        // (a) product row + engine start, then an exception from product code: both roll back
        String bkA = uniq("a");
        IllegalStateException boom = assertThrows(IllegalStateException.class, () -> adapter.inEngineTransaction(c -> {
            try {
                assertFalse(c.getAutoCommit(), "engine connection must not be in auto-commit");
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            exec(c, "insert into q1_product (id, note) values (?, ?)", bkA, "idempotency row");
            adapter.startProcess(null, key, bkA, Map.of());
            throw new IllegalStateException("boom after engine change");
        }));
        assertEquals("boom after engine change", boom.getMessage());
        assertEquals(0, productRows(bkA));
        assertEquals(0, engineInstances(bkA));

        // (b) product row, then an engine exception: the product row rolls back too
        String bkB = uniq("b");
        assertThrows(ProcessEngineException.class, () -> adapter.inEngineTransaction(c -> {
            exec(c, "insert into q1_product (id, note) values (?, ?)", bkB, "outbox row");
            adapter.startProcess(null, key, bkB, Map.of());
            adapter.completeTask("no-such-task", Map.of());
            return null;
        }));
        assertEquals(0, productRows(bkB));
        assertEquals(0, engineInstances(bkB));

        // (c) no exception: both commit
        String bkC = uniq("c");
        String instanceId = adapter.inEngineTransaction(c -> {
            exec(c, "insert into q1_product (id, note) values (?, ?)", bkC, "ledger row");
            return adapter.startProcess(null, key, bkC, Map.of());
        });
        assertEquals(1, productRows(bkC));
        assertEquals(2, engineInstances(bkC)); // one runtime row + one history row
        assertFalse(adapter.getInstance(instanceId).ended());
    }

    /**
     * Evidence for ledger entry W-2: a plain Operaton command (no workaround) that writes a product row through the
     * engine connection and then throws COMMITS the product row, because MyBatis skips rollback for a session with
     * no MyBatis writes and JdbcTransaction.close() calls setAutoCommit(true).
     */
    @Test
    void q1HazardPlainCommandCommitsProductRowOnException() {
        adapter.inEngineTransaction(c -> {
            createQ1Table(c);
            return null;
        });
        String id = uniq("hazard");
        var cfg = (org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl)
                adapter.processEngine().getProcessEngineConfiguration();
        assertThrows(IllegalStateException.class, () -> cfg.getCommandExecutorTxRequired().execute(cc -> {
            Connection c = cc.getDbSqlSession().getSqlSession().getConnection();
            exec(c, "insert into q1_product (id, note) values (?, ?)", id, "should have rolled back");
            throw new IllegalStateException("boom");
        }));
        assertEquals(1, productRows(id), "unpatched engine command commits the product row (W-2)");
    }

    // ---------------------------------------------------------------- 3. timers via setClock + executeDueJobs

    @Test
    void timerFiresOnlyWhenEngineClockPassesDueDate() {
        String key = uniq("timer");
        deploy(key + ".bpmn", Models.timer(key, "PT1H"));
        Instant t0 = Instant.parse("2026-11-05T10:00:00Z");
        adapter.setClock(t0);

        String id = adapter.startProcess(null, key, null, Map.of());
        assertEquals(List.of("wait"), adapter.getInstance(id).activeActivityIds());
        var jobs = adapter.processEngine().getManagementService().createJobQuery().processInstanceId(id);
        assertEquals(1, jobs.count());
        assertEquals(Instant.parse("2026-11-05T11:00:00Z"), jobs.singleResult().getDuedate().toInstant());

        assertEquals(0, adapter.executeDueJobs());
        adapter.setClock(t0.plus(Duration.ofMinutes(59)));
        assertEquals(0, adapter.executeDueJobs());
        assertEquals(List.of("wait"), adapter.getInstance(id).activeActivityIds());

        adapter.setClock(t0.plus(Duration.ofMinutes(61)));
        assertEquals(1, adapter.executeDueJobs());
        assertEquals(List.of("afterTimer"), adapter.getInstance(id).activeActivityIds());
        assertEquals(0, adapter.executeDueJobs());
    }

    // ---------------------------------------------------------------- Q3 evidence: lease semantics as shipped

    private ExternalWork fetchFor(String workerId, String instanceId, Duration lease) {
        return adapter.fetchAndLock(workerId, "pay", 50, lease).stream()
                .filter(w -> w.processInstanceId().equals(instanceId)).findFirst().orElse(null);
    }

    /**
     * Q3 evidence. Engine-as-shipped behaviour is shown with direct ExternalTaskService calls; the adapter calls show
     * the fenced behaviour (W-8).
     */
    @Test
    void leaseSemanticsOfExternalTasks() {
        String key = uniq("lease");
        deploy(key + ".bpmn", Models.payWithErrorBoundary(key));
        Instant t0 = Instant.parse("2026-11-05T10:00:00Z");
        adapter.setClock(t0);
        var engineExternal = adapter.processEngine().getExternalTaskService();

        // Locked work is not offered to another worker; only the owner may extend.
        String x = adapter.startProcess(null, key, null, Map.of());
        ExternalWork wa = fetchFor("A", x, Duration.ofMinutes(1));
        assertNotNull(wa);
        assertEquals(t0.plus(Duration.ofMinutes(1)), wa.lockExpiresAt());
        assertEquals(null, fetchFor("B", x, Duration.ofMinutes(1)));
        assertThrows(StaleLeaseException.class, () -> adapter.extendLock(wa.id(), "B", wa.lockVersion(), Duration.ofMinutes(5)));
        assertThrows(BadUserRequestException.class, () -> engineExternal.extendLock(wa.id(), "B", 300_000L)); // engine: owner check
        adapter.extendLock(wa.id(), "A", wa.lockVersion(), Duration.ofMinutes(1)); // new expiry = now + 1 min

        // After expiry: the owner can no longer extend or complete through the adapter ...
        adapter.setClock(t0.plus(Duration.ofMinutes(3)));
        assertThrows(StaleLeaseException.class, () -> adapter.extendLock(wa.id(), "A", wa.lockVersion(), Duration.ofMinutes(1)));
        assertThrows(StaleLeaseException.class, () -> adapter.completeExternal(wa.id(), "A", wa.lockVersion(), Map.of()));
        assertFalse(adapter.getInstance(x).ended());
        // ... but the engine alone accepts the stale complete: it checks only the worker id, not the expiry (gap G-2).
        engineExternal.complete(wa.id(), "A", Map.of());
        assertTrue(adapter.getInstance(x).ended());

        // Expired lease re-fetched by B: the stale owner A is fenced.
        String y = adapter.startProcess(null, key, null, Map.of());
        ExternalWork ya = fetchFor("A", y, Duration.ofMinutes(1));
        adapter.setClock(t0.plus(Duration.ofMinutes(5)));
        ExternalWork yb = fetchFor("B", y, Duration.ofMinutes(1));
        assertNotNull(yb);
        assertEquals(ya.id(), yb.id()); // same engine id on redelivery; the adapter's lockVersion tells them apart
        assertTrue(yb.lockVersion() > ya.lockVersion());
        assertThrows(StaleLeaseException.class, () -> adapter.completeExternal(ya.id(), "A", ya.lockVersion(), Map.of()));
        adapter.completeExternal(yb.id(), "B", yb.lockVersion(), Map.of());
        assertTrue(adapter.getInstance(y).ended());

        // Failure with retries and back-off; retries=0 raises an incident.
        String z = adapter.startProcess(null, key, null, Map.of());
        ExternalWork z1 = fetchFor("A", z, Duration.ofMinutes(1));
        adapter.failExternal(z1.id(), "A", z1.lockVersion(), "IFMS timeout", 1, Duration.ofSeconds(30));
        assertEquals(null, fetchFor("A", z, Duration.ofMinutes(1)), "back-off hides the task");
        adapter.setClock(t0.plus(Duration.ofMinutes(5)).plusSeconds(31));
        ExternalWork z2 = fetchFor("A", z, Duration.ofMinutes(1));
        assertNotNull(z2);
        assertEquals(1, z2.retries());
        assertTrue(z2.lockVersion() > z1.lockVersion());
        adapter.failExternal(z2.id(), "A", z2.lockVersion(), "IFMS timeout again", 0, Duration.ZERO);
        assertEquals(1, adapter.processEngine().getRuntimeService().createIncidentQuery()
                .processInstanceId(z).count());

        // Unlock by the owner returns the task to AVAILABLE immediately. The engine's unlock(id) has no owner check.
        String u = adapter.startProcess(null, key, null, Map.of());
        ExternalWork u1 = fetchFor("A", u, Duration.ofMinutes(10));
        adapter.unlockExternal(u1.id(), "A", u1.lockVersion());
        assertNotNull(fetchFor("B", u, Duration.ofMinutes(1)));

        // BPMN error routes through the boundary event.
        String e = adapter.startProcess(null, key, null, Map.of());
        ExternalWork e1 = fetchFor("A", e, Duration.ofMinutes(1));
        adapter.bpmnErrorExternal(e1.id(), "A", e1.lockVersion(), "PAY_REJECTED", Map.of("reason", "account closed"));
        InstanceView ev = adapter.getInstance(e);
        assertEquals(List.of("handleRejection"), ev.activeActivityIds());
        assertEquals("account closed", ev.variables().get("reason"));
    }

    // ---------------------------------------------------------------- adapter-side fencing (VI.3.2 rule 2, W-8)

    private static Reason reason(Runnable call) {
        return assertThrows(StaleLeaseException.class, call::run).reason();
    }

    private long leaseVersion(String workId) {
        return adapter.inEngineTransaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("select lock_version from spike_lease where work_id = ?")) {
                ps.setString(1, workId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : -1L;
                }
            } catch (SQLException ex) {
                throw new IllegalStateException(ex);
            }
        });
    }

    private String startFencing() {
        String key = uniq("fence");
        deploy(key + ".bpmn", Models.payWithErrorBoundary(key));
        adapter.setClock(Instant.parse("2026-11-05T10:00:00Z"));
        return adapter.startProcess(null, key, null, Map.of());
    }

    /** (a) A stale lockVersion is rejected and changes nothing; the current one is accepted. */
    @Test
    void fencing_a_staleLockVersionIsRejected() {
        String pid = startFencing();
        ExternalWork v1 = fetchFor("w1", pid, Duration.ofMinutes(5));
        adapter.unlockExternal(v1.id(), "w1", v1.lockVersion());
        ExternalWork v3 = fetchFor("w1", pid, Duration.ofMinutes(5));
        assertEquals(v1.id(), v3.id());
        assertEquals(v1.lockVersion() + 2, v3.lockVersion(), "+1 on release, +1 on re-acquire");
        assertNotEquals(v1.attemptId(), v3.attemptId());

        assertEquals(Reason.STALE_VERSION, reason(() -> adapter.completeExternal(v1.id(), "w1", v1.lockVersion(), Map.of())));
        assertEquals(Reason.STALE_VERSION, reason(() -> adapter.failExternal(v1.id(), "w1", v1.lockVersion(), "x", 0, Duration.ZERO)));
        assertEquals(Reason.STALE_VERSION, reason(() -> adapter.bpmnErrorExternal(v1.id(), "w1", v1.lockVersion(), "PAY_REJECTED", Map.of())));
        assertEquals(Reason.STALE_VERSION, reason(() -> adapter.extendLock(v1.id(), "w1", v1.lockVersion(), Duration.ofMinutes(1))));
        assertEquals(Reason.STALE_VERSION, reason(() -> adapter.unlockExternal(v1.id(), "w1", v1.lockVersion())));
        assertEquals(v3.lockVersion(), leaseVersion(v3.id()), "nothing changed");
        assertFalse(adapter.getInstance(pid).ended());

        adapter.completeExternal(v3.id(), "w1", v3.lockVersion(), Map.of());
        assertTrue(adapter.getInstance(pid).ended());
        assertEquals(-1L, leaseVersion(v3.id()), "lease row removed on completion");
    }

    /** (b) Complete, fail or BPMN error after expires_at is rejected even though nobody re-acquired the work. */
    @Test
    void fencing_b_callsAfterExpiryAreRejected() {
        String pid = startFencing();
        ExternalWork w = fetchFor("w1", pid, Duration.ofMinutes(5));
        adapter.setClock(Instant.parse("2026-11-05T10:05:00Z")); // exactly at expiry: already expired
        assertEquals(Reason.EXPIRED, reason(() -> adapter.completeExternal(w.id(), "w1", w.lockVersion(), Map.of())));
        assertEquals(Reason.EXPIRED, reason(() -> adapter.failExternal(w.id(), "w1", w.lockVersion(), "x", 1, Duration.ZERO)));
        assertEquals(Reason.EXPIRED, reason(() -> adapter.bpmnErrorExternal(w.id(), "w1", w.lockVersion(), "PAY_REJECTED", Map.of())));
        assertEquals(Reason.EXPIRED, reason(() -> adapter.extendLock(w.id(), "w1", w.lockVersion(), Duration.ofMinutes(5))));
        assertEquals(Reason.EXPIRED, reason(() -> adapter.unlockExternal(w.id(), "w1", w.lockVersion())));
        assertFalse(adapter.getInstance(pid).ended());
        assertEquals(List.of("pay"), adapter.getInstance(pid).activeActivityIds());
    }

    /** (c) Re-acquire after expiry: higher lockVersion, new attemptId, and the old worker is fenced out. */
    @Test
    void fencing_c_reacquireAfterExpiryFencesTheOldWorker() {
        String pid = startFencing();
        ExternalWork a = fetchFor("A", pid, Duration.ofMinutes(5));
        adapter.setClock(Instant.parse("2026-11-05T10:06:00Z"));
        ExternalWork b = fetchFor("B", pid, Duration.ofMinutes(5));
        assertEquals(a.id(), b.id());
        assertTrue(b.lockVersion() > a.lockVersion());
        assertNotEquals(a.attemptId(), b.attemptId());
        assertEquals(a.activityInstanceId(), b.activityInstanceId(), "operationId input is stable across expiry");

        assertEquals(Reason.STALE_VERSION, reason(() -> adapter.completeExternal(a.id(), "A", a.lockVersion(), Map.of())));
        assertEquals(Reason.STALE_VERSION, reason(() -> adapter.extendLock(a.id(), "A", a.lockVersion(), Duration.ofMinutes(5))));
        assertEquals(Reason.NOT_OWNER, reason(() -> adapter.completeExternal(a.id(), "A", b.lockVersion(), Map.of())));

        adapter.completeExternal(b.id(), "B", b.lockVersion(), Map.of("paymentRef", "B-1"));
        assertTrue(adapter.getInstance(pid).ended());
        assertEquals("B-1", adapter.getInstance(pid).variables().get("paymentRef"));
    }

    /** (d) Unlock by a worker that does not own the lease is rejected; the owner can unlock. */
    @Test
    void fencing_d_unlockByNonOwnerIsRejected() {
        String pid = startFencing();
        ExternalWork w = fetchFor("w1", pid, Duration.ofMinutes(5));
        assertEquals(Reason.NOT_OWNER, reason(() -> adapter.unlockExternal(w.id(), "intruder", w.lockVersion())));
        assertNull(fetchFor("w2", pid, Duration.ofMinutes(5)), "still locked by w1");

        adapter.unlockExternal(w.id(), "w1", w.lockVersion());
        ExternalWork w2 = fetchFor("w2", pid, Duration.ofMinutes(5));
        assertEquals(w.id(), w2.id());
        assertTrue(w2.lockVersion() > w.lockVersion());
    }

    // ---------------------------------------------------------------- DMN and CMMN enabled

    @Test
    void dmnBusinessRuleTaskEvaluates() {
        String key = uniq("dmnproc");
        String decision = uniq("band");
        adapter.deploy(null, key, Map.of(
                key + ".bpmn", Models.bytes(Models.businessRule(key, decision)),
                decision + ".dmn", Models.bytes(Models.approverBandDmn(decision))));
        String id = adapter.startProcess(null, key, null, Map.of("amount", 50_000));
        assertEquals("B", adapter.getInstance(id).variables().get("band"));
        String id2 = adapter.startProcess(null, key, null, Map.of("amount", 250_000));
        assertEquals("C", adapter.getInstance(id2).variables().get("band"));
    }

    /** Guards ledger entry W-1: FEEL's JSON built-ins load jackson-module-scala, which needs databind 2.22.x. */
    @Test
    void feelJsonBuiltinWorks() {
        String decision = uniq("json");
        String dmn = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/"
                             id="jdefs" name="Json" namespace="http://sangam.example/dmn">
                  <decision id="%s" name="Json">
                    <variable name="out" typeRef="string"/>
                    <literalExpression id="le"><text>to json({"amount": amount})</text></literalExpression>
                  </decision>
                </definitions>
                """.formatted(decision);
        adapter.deploy(null, decision, Map.of(decision + ".dmn", Models.bytes(dmn)));
        Object out = adapter.processEngine().getDecisionService().evaluateDecisionByKey(decision)
                .variables(Map.of("amount", 7)).evaluate().getSingleResult().getSingleEntry();
        assertEquals("{\"amount\":7}", out);
    }

    /** (e) startCase, then the case human task through queryTasks/completeTask; getInstance(unknown) is null. */
    @Test
    void startCaseAndCompleteCaseHumanTask() {
        String key = uniq("case");
        deploy(key + ".cmmn", Models.smokeCase(key));
        String caseId = adapter.startCase(null, key, "lic-e", Map.of("applicant", "X"));
        InstanceView running = adapter.getInstance(caseId);
        assertNotNull(running);
        assertFalse(running.ended());
        assertEquals(key, running.processKey());
        assertEquals(List.of("PI_scrutiny"), running.activeActivityIds());

        TaskQuery q = new TaskQuery(null, null, null, caseId, null, 0, 10);
        assertEquals(1, adapter.countTasks(q));
        TaskView task = adapter.queryTasks(q).get(0);
        // Operaton: a case task's taskDefinitionKey is the PLAN ITEM id (Flowable gives the humanTask id "scrutiny").
        assertEquals("PI_scrutiny", task.taskDefinitionKey());
        assertEquals(caseId, task.processInstanceId());
        assertEquals("X", task.variables().get("applicant"));

        adapter.claimTask(task.id(), "asha");
        adapter.completeTask(task.id(), Map.of("scrutinyOk", true));
        InstanceView done = adapter.getInstance(caseId);
        assertTrue(done.ended());
        assertEquals(true, done.variables().get("scrutinyOk"));
        assertEquals(0, adapter.countTasks(q));

        assertNull(adapter.getInstance("no-such-instance"));
    }

    @Test
    void cmmnCaseRunsThroughStartProcess() {
        String key = uniq("case");
        deploy(key + ".cmmn", Models.smokeCase(key));
        String caseId = adapter.startProcess(null, key, "lic-1", Map.of("applicant", "X"));
        InstanceView running = adapter.getInstance(caseId);
        assertNotNull(running);
        assertFalse(running.ended());
        assertEquals(List.of("PI_scrutiny"), running.activeActivityIds());

        List<TaskView> tasks = adapter.queryTasks(new TaskQuery(null, null, null, caseId, null, 0, 10));
        assertEquals(1, tasks.size());
        assertEquals(caseId, tasks.get(0).processInstanceId());
        adapter.completeTask(tasks.get(0).id(), Map.of("scrutinyOk", true));
        assertTrue(adapter.getInstance(caseId).ended());
    }

    @Test
    void tenantScopedDeploymentAndQuery() {
        String key = uniq("tenant");
        String group = uniq("g");
        adapter.deploy("dept-a", key, Map.of(key + ".bpmn", Models.bytes(Models.userThenPay(key, group))));
        adapter.deploy("dept-b", key, Map.of(key + ".bpmn", Models.bytes(Models.userThenPay(key, group))));
        adapter.startProcess("dept-a", key, null, Map.of());
        adapter.startProcess("dept-a", key, null, Map.of());
        adapter.startProcess("dept-b", key, null, Map.of());
        assertEquals(2, adapter.countTasks(new TaskQuery("dept-a", null, List.of(group), null, null, 0, 0)));
        assertEquals(1, adapter.countTasks(new TaskQuery("dept-b", null, List.of(group), null, null, 0, 0)));
        List<TaskView> page = adapter.queryTasks(new TaskQuery(null, null, List.of(group), null, "review", 1, 1));
        assertEquals(1, page.size());
    }

    // ---------------------------------------------------------------- concurrent fetch (XI.10.4 defect, W-9)

    private ExternalWork fetchOn(String workerId, String topic, String instanceId, Duration lease) {
        return adapter.fetchAndLock(workerId, topic, 50, lease).stream()
                .filter(w -> w.processInstanceId().equals(instanceId)).findFirst().orElse(null);
    }

    private long leaseRows(List<String> workIds) {
        return adapter.inEngineTransaction(c -> {
            long n = 0;
            for (String id : workIds) {
                try (PreparedStatement ps = c.prepareStatement("select count(*) from spike_lease where work_id = ?")) {
                    ps.setString(1, id);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        n += rs.getLong(1);
                    }
                } catch (SQLException ex) {
                    throw new IllegalStateException(ex);
                }
            }
            return n;
        });
    }

    /**
     * 8 workers fetch and complete 200 tasks of one topic at the same time (E9 finding B-2: 17 % failures and stuck
     * instances with 4 fetchers). Every task is delivered once and completed once, every instance ends, no worker call
     * throws, and no lease row is left.
     */
    @Test
    void concurrentFetch_8Workers_200Tasks_eachCompletesExactlyOnce() throws Exception {
        String key = uniq("conc");
        String topic = uniq("topic");
        deploy(key + ".bpmn", Models.singleExternalTask(key, topic));
        int tasks = 200;
        int workers = 8;
        for (int i = 0; i < tasks; i++) {
            adapter.startProcess(null, key, "bk-" + i, Map.of("i", i));
        }

        Map<String, AtomicInteger> deliveries = new ConcurrentHashMap<>();
        Map<String, AtomicInteger> completions = new ConcurrentHashMap<>();
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        AtomicInteger completed = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(8);
        long retriesBefore = adapter.fetchRetries();
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        for (int w = 0; w < workers; w++) {
            String workerId = "cw-" + w;
            pool.submit(() -> {
                try {
                    go.await();
                    while (completed.get() < tasks && System.nanoTime() < deadline && errors.size() < 20) {
                        List<ExternalWork> got = adapter.fetchAndLock(workerId, topic, 10, Duration.ofMinutes(30));
                        if (got.isEmpty()) {
                            Thread.sleep(5);
                            continue;
                        }
                        for (ExternalWork x : got) {
                            deliveries.computeIfAbsent(x.id(), k -> new AtomicInteger()).incrementAndGet();
                            try {
                                adapter.completeExternal(x.id(), workerId, x.lockVersion(), Map.of("by", workerId));
                                completions.computeIfAbsent(x.id(), k -> new AtomicInteger()).incrementAndGet();
                                completed.incrementAndGet();
                            } catch (Throwable t) {
                                errors.add(t);
                            }
                        }
                    }
                } catch (Throwable t) {
                    errors.add(t);
                }
                return null;
            });
        }
        long t0 = System.nanoTime();
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.MINUTES), "workers did not stop");
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        System.out.printf("[%s] concurrent fetch: %d workers x %d tasks in %d ms, fetch retries %d, give-ups %d%n",
                getClass().getSimpleName(), workers, tasks, millis, adapter.fetchRetries() - retriesBefore,
                adapter.fetchGiveUps());

        if (!errors.isEmpty()) {
            Throwable first = errors.peek();
            throw new AssertionError(errors.size() + " worker call(s) threw; first: " + first, first);
        }
        assertEquals(tasks, completions.size(), "every task completed");
        assertTrue(completions.values().stream().allMatch(n -> n.get() == 1), "each task completed exactly once");
        assertTrue(deliveries.values().stream().allMatch(n -> n.get() == 1), "each task delivered exactly once");
        var engine = adapter.processEngine();
        assertEquals(0, engine.getRuntimeService().createProcessInstanceQuery().processDefinitionKey(key).count(),
                "no stuck instance");
        assertEquals(tasks, engine.getHistoryService().createHistoricProcessInstanceQuery()
                .processDefinitionKey(key).finished().count());
        assertEquals(0, engine.getExternalTaskService().createExternalTaskQuery().topicName(topic).count());
        assertEquals(0, leaseRows(new ArrayList<>(completions.keySet())), "no lease row left");
    }

    // ---------------------------------------------------------------- A-10 expiry hook (W-10)

    /**
     * ExpiryPolicy HOLD: the policy runs in the expiry transaction before the work can be fetched again (in fetch and
     * in executeDueJobs), the held work is not re-offered, the stale owner is fenced, and the product can release it
     * through the engine (setRetries).
     */
    @Test
    void expiryPolicyHold_expiredWorkIsNotReoffered() {
        String key = uniq("hold");
        String topic = uniq("htopic");
        deploy(key + ".bpmn", Models.singleExternalTask(key, topic));
        Instant t0 = Instant.parse("2026-11-05T10:00:00Z");
        adapter.setClock(t0);
        List<ExpiryPolicy.ExpiredWork> seen = Collections.synchronizedList(new ArrayList<>());
        adapter.setExpiryPolicy((work, connection) -> {
            assertNotNull(connection, "policy gets the engine transaction's connection");
            seen.add(work);
            return ExpiryPolicy.Decision.HOLD;
        });
        var engineExternal = adapter.processEngine().getExternalTaskService();
        var runtime = adapter.processEngine().getRuntimeService();

        // (1) expiry found by the next fetch
        String p1 = adapter.startProcess(null, key, null, Map.of());
        ExternalWork a = fetchOn("A", topic, p1, Duration.ofMinutes(1));
        assertNotNull(a);
        adapter.setClock(t0.plus(Duration.ofMinutes(2)));
        assertNull(fetchOn("B", topic, p1, Duration.ofMinutes(1)), "held work is not re-offered");
        assertEquals(1, seen.size(), "policy called once, before the re-fetch");
        ExpiryPolicy.ExpiredWork e = seen.get(0);
        assertEquals(a.id(), e.workId());
        assertEquals("A", e.workerId());
        assertEquals(a.lockVersion(), e.lockVersion());
        assertEquals(a.attemptId(), e.attemptId());
        assertEquals(a.lockExpiresAt(), e.expiredAt());
        assertEquals(topic, e.topic());
        assertEquals(p1, e.processInstanceId());
        assertEquals("work", e.activityId());
        assertEquals(a.activityInstanceId(), e.activityInstanceId());
        assertEquals(0, engineExternal.createExternalTaskQuery().externalTaskId(a.id()).singleResult().getRetries());
        assertEquals(1, runtime.createIncidentQuery().processInstanceId(p1).count(), "held = incident");
        assertEquals(Reason.STALE_VERSION, reason(() -> adapter.completeExternal(a.id(), "A", a.lockVersion(), Map.of())));
        assertNull(fetchOn("C", topic, p1, Duration.ofMinutes(1)));
        assertEquals(1, seen.size(), "an ended lease is not expired twice");
        assertFalse(adapter.getInstance(p1).ended());

        // (2) expiry found by executeDueJobs
        String p2 = adapter.startProcess(null, key, null, Map.of());
        ExternalWork b = fetchOn("A", topic, p2, Duration.ofMinutes(1));
        assertNotNull(b);
        adapter.setClock(t0.plus(Duration.ofMinutes(4)));
        adapter.executeDueJobs();
        assertEquals(2, seen.size());
        assertEquals(b.id(), seen.get(1).workId());
        assertNull(fetchOn("B", topic, p2, Duration.ofMinutes(1)));

        // (3) default policy again; the product releases the held work through the engine
        adapter.setExpiryPolicy(null);
        engineExternal.setRetries(a.id(), 1);
        assertEquals(0, runtime.createIncidentQuery().processInstanceId(p1).count(), "release resolves the incident");
        ExternalWork a2 = fetchOn("C", topic, p1, Duration.ofMinutes(1));
        assertNotNull(a2);
        assertEquals(a.lockVersion() + 2, a2.lockVersion(), "+1 at expiry, +1 at the new fetch");
        adapter.completeExternal(a2.id(), "C", a2.lockVersion(), Map.of());
        assertTrue(adapter.getInstance(p1).ended());
        assertEquals(2, seen.size(), "the default policy does not call the HOLD policy");
    }

    /** The default policy (REOFFER) is called, the lease ends and the work is offered again at once. */
    @Test
    void expiryPolicyReoffer_isCalledAndWorkIsOfferedAgain() {
        String key = uniq("reoffer");
        String topic = uniq("rtopic");
        deploy(key + ".bpmn", Models.singleExternalTask(key, topic));
        Instant t0 = Instant.parse("2026-11-05T10:00:00Z");
        adapter.setClock(t0);
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        adapter.setExpiryPolicy((work, connection) -> {
            seen.add(work.workId());
            return ExpiryPolicy.Decision.REOFFER;
        });
        String p = adapter.startProcess(null, key, null, Map.of());
        ExternalWork a = fetchOn("A", topic, p, Duration.ofMinutes(1));
        adapter.setClock(t0.plus(Duration.ofMinutes(2)));
        ExternalWork b = fetchOn("B", topic, p, Duration.ofMinutes(1));
        assertNotNull(b);
        assertEquals(List.of(a.id()), seen);
        assertEquals(a.lockVersion() + 2, b.lockVersion());
        adapter.completeExternal(b.id(), "B", b.lockVersion(), Map.of());
        assertTrue(adapter.getInstance(p).ended());
    }

    // ---------------------------------------------------------------- leases on delete (E7 F-7)

    /** cancelInstance removes the lease rows in the same command; a late call gets NO_LEASE and changes nothing. */
    @Test
    void deleteThenLateComplete_getsNoLease() {
        String key = uniq("del");
        String topic = uniq("dtopic");
        deploy(key + ".bpmn", Models.singleExternalTask(key, topic));
        String p = adapter.startProcess(null, key, null, Map.of());
        ExternalWork w = fetchOn("A", topic, p, Duration.ofMinutes(5));
        assertNotNull(w);
        assertEquals(1, leaseRows(List.of(w.id())));

        adapter.cancelInstance(p, "withdrawn by applicant");
        assertEquals(0, leaseRows(List.of(w.id())), "lease row removed with the instance");
        assertTrue(adapter.getInstance(p).ended());

        assertEquals(Reason.NO_LEASE, reason(() -> adapter.completeExternal(w.id(), "A", w.lockVersion(), Map.of())));
        assertEquals(Reason.NO_LEASE, reason(() -> adapter.failExternal(w.id(), "A", w.lockVersion(), "x", 0, Duration.ZERO)));
        assertEquals(Reason.NO_LEASE, reason(() -> adapter.extendLock(w.id(), "A", w.lockVersion(), Duration.ofMinutes(1))));
        assertEquals(Reason.NO_LEASE, reason(() -> adapter.unlockExternal(w.id(), "A", w.lockVersion())));
        assertTrue(adapter.getInstance(p).ended(), "the instance stays deleted");
        assertEquals(0, leaseRows(List.of(w.id())));
    }
}

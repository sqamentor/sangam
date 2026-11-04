package org.sangam.spike.flowable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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

import org.flowable.cmmn.api.runtime.CaseInstance;
import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.job.service.impl.asyncexecutor.AbstractAsyncExecutor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.sangam.spike.api.EngineAdapter.ExternalWork;
import org.sangam.spike.api.EngineAdapter.InstanceView;
import org.sangam.spike.api.EngineAdapter.TaskQuery;
import org.sangam.spike.api.EngineAdapter.TaskView;
import org.sangam.spike.api.ExpiryPolicy;
import org.sangam.spike.api.StaleLeaseException;
import org.sangam.spike.api.StaleLeaseException.Reason;
import org.sangam.spike.flowable.FlowableEngineAdapter.ContinuationMode;

/**
 * The same tests on every database; subclasses provide the adapter. One engine per class (a Testcontainers database
 * per class); every test leaves no instance, lease or job behind ({@link #cleanUp()}).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractFlowableAdapterTest {

    static final Instant T0 = Instant.parse("2026-11-04T10:00:00Z");

    static final String TENANT = "t1";

    static final String PAY_BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:flowable="http://flowable.org/bpmn"
                         targetNamespace="sangam-spike">
              <process id="pay" name="Pay" isExecutable="true">
                <startEvent id="start"/>
                <sequenceFlow id="f1" sourceRef="start" targetRef="approve"/>
                <userTask id="approve" name="Approve" flowable:candidateGroups="checkers"/>
                <sequenceFlow id="f2" sourceRef="approve" targetRef="payment"/>
                <serviceTask id="payment" name="Payment" flowable:type="external-worker" flowable:topic="pay"/>
                <sequenceFlow id="f3" sourceRef="payment" targetRef="end"/>
                <endEvent id="end"/>
              </process>
            </definitions>
            """;

    static final String TIMER_BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:flowable="http://flowable.org/bpmn"
                         targetNamespace="sangam-spike">
              <process id="sla" name="SLA" isExecutable="true">
                <startEvent id="start"/>
                <sequenceFlow id="f1" sourceRef="start" targetRef="wait"/>
                <intermediateCatchEvent id="wait">
                  <timerEventDefinition><timeDuration>PT1H</timeDuration></timerEventDefinition>
                </intermediateCatchEvent>
                <sequenceFlow id="f2" sourceRef="wait" targetRef="escalated"/>
                <userTask id="escalated" name="Escalated"/>
                <sequenceFlow id="f3" sourceRef="escalated" targetRef="end"/>
                <endEvent id="end"/>
              </process>
            </definitions>
            """;

    static final String ONE_TASK_BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         targetNamespace="sangam-spike">
              <process id="oneTask" isExecutable="true">
                <startEvent id="start"/>
                <sequenceFlow id="f1" sourceRef="start" targetRef="t"/>
                <userTask id="t" name="T"/>
                <sequenceFlow id="f2" sourceRef="t" targetRef="end"/>
                <endEvent id="end"/>
              </process>
            </definitions>
            """;

    protected FlowableEngineAdapter adapter;

    protected abstract FlowableEngineAdapter newAdapter();

    /** Called after the engine is closed (subclasses stop their database here). */
    protected void afterEngineStopped() {
    }

    @BeforeAll
    void startEngine() {
        adapter = newAdapter();
        adapter.setClock(T0);
        adapter.deploy(TENANT, "spike-" + UUID.randomUUID(), Map.of(
                "pay.bpmn20.xml", PAY_BPMN.getBytes(StandardCharsets.UTF_8),
                "sla.bpmn20.xml", TIMER_BPMN.getBytes(StandardCharsets.UTF_8),
                "oneTask.bpmn20.xml", ONE_TASK_BPMN.getBytes(StandardCharsets.UTF_8)));
        // portable DDL (H2, PostgreSQL, Oracle, SQL Server, MySQL); created once
        adapter.inEngineTransaction(c -> {
            try (ResultSet rs = c.getMetaData().getTables(null, null, "%", new String[] {"TABLE"})) {
                while (rs.next()) {
                    if ("spike_product_row".equalsIgnoreCase(rs.getString("TABLE_NAME"))) {
                        return null;
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            return exec(c, "create table spike_product_row (id varchar(64) primary key, note varchar(200))");
        });
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

    @BeforeEach
    void setUp() {
        adapter.setClock(T0);
    }

    @AfterEach
    void cleanUp() {
        adapter.setExpiryPolicy(null);
        adapter.setContinuationMode(ContinuationMode.AFTER_COMMIT);
        adapter.setClock(T0);
        var rs = adapter.processEngine().getRuntimeService();
        for (ProcessInstance pi : rs.createProcessInstanceQuery().list()) {
            if (rs.createProcessInstanceQuery().processInstanceId(pi.getId()).count() > 0) {
                adapter.cancelInstance(pi.getId(), "test cleanup");
            }
        }
        var crs = adapter.cmmnEngineConfiguration().getCmmnRuntimeService();
        for (CaseInstance ci : crs.createCaseInstanceQuery().list()) {
            crs.terminateCaseInstance(ci.getId());
        }
        adapter.inEngineTransaction(c -> exec(c, "delete from spike_lease"));
    }

    // ------------------------------------------------------------------ test 1: user task + external worker

    @Test
    void userTaskThenExternalWorkerThenEnd() {
        String bk = "bill-" + UUID.randomUUID();
        String pid = adapter.startProcess(TENANT, "pay", bk, Map.of("amount", 1200));

        TaskQuery q = new TaskQuery(TENANT, "alice", List.of("checkers"), pid, "approve", 0, 10);
        assertEquals(1, adapter.countTasks(q));
        List<TaskView> tasks = adapter.queryTasks(q);
        assertEquals(1, tasks.size());
        assertEquals(1200, tasks.get(0).variables().get("amount"));
        assertEquals(TENANT, tasks.get(0).tenantId());
        assertEquals(0, adapter.countTasks(new TaskQuery(TENANT, "bob", List.of("others"), pid, null, 0, 10)));

        adapter.claimTask(tasks.get(0).id(), "alice");
        adapter.completeTask(tasks.get(0).id(), Map.of("approved", true));

        List<ExternalWork> work = adapter.fetchAndLock("worker-1", "pay", 5, Duration.ofMinutes(5));
        assertEquals(1, work.size());
        ExternalWork w = work.get(0);
        assertEquals("pay", w.topic());
        assertEquals(pid, w.processInstanceId());
        assertEquals("payment", w.activityId());
        assertEquals(Instant.parse("2026-11-04T10:05:00Z"), w.lockExpiresAt());
        assertEquals(true, w.variables().get("approved"));
        assertEquals(1, w.lockVersion());
        assertNotNull(w.attemptId());
        assertNotNull(w.activityInstanceId());
        String actInst = adapter.processEngine().getRuntimeService().createActivityInstanceQuery()
                .processInstanceId(pid).activityId("payment").unfinished().singleResult().getId();
        assertEquals(actInst, w.activityInstanceId(), "Flowable: runtime ActivityInstance id (LEDGER-F8)");
        assertTrue(adapter.fetchAndLock("worker-2", "pay", 5, Duration.ofMinutes(5)).isEmpty(), "locked work is not offered twice");

        adapter.completeExternal(w.id(), "worker-1", w.lockVersion(), Map.of("utr", "UTR123"));
        // Default ContinuationMode.AFTER_COMMIT (LEDGER-F11): the adapter ran Flowable's continuation job after the
        // completion committed, so nothing is left for executeDueJobs.
        assertEquals(0, adapter.executeDueJobs());

        InstanceView iv = adapter.getInstance(pid);
        assertNotNull(iv);
        assertTrue(iv.ended());
        assertEquals("pay", iv.processKey());
        assertEquals(bk, iv.businessKey());
        assertEquals("UTR123", iv.variables().get("utr"));
    }

    // ------------------------------------------------------------------ lease behaviour (Q3 evidence)

    @Test
    void leaseExtendExpireStaleWorkerAndRetry() {
        String pid = adapter.startProcess(TENANT, "pay", "lease-" + UUID.randomUUID(), Map.of());
        TaskView t = adapter.queryTasks(new TaskQuery(TENANT, null, null, pid, null, 0, 10)).get(0);
        adapter.completeTask(t.id(), Map.of());

        ExternalWork w = adapter.fetchAndLock("w1", "pay", 1, Duration.ofMinutes(5)).get(0);

        // extend: 10:00 + 20 min
        adapter.extendLock(w.id(), "w1", w.lockVersion(), Duration.ofMinutes(20));
        adapter.setClock(Instant.parse("2026-11-04T10:15:00Z"));
        adapter.executeDueJobs();
        assertTrue(adapter.fetchAndLock("w2", "pay", 1, Duration.ofMinutes(5)).isEmpty(), "extended lease still held");

        // a different worker cannot complete
        assertThrows(StaleLeaseException.class, () -> adapter.completeExternal(w.id(), "w2", w.lockVersion(), Map.of()));

        // the lease expires: w1 is now stale even before anybody re-acquires
        adapter.setClock(Instant.parse("2026-11-04T10:21:00Z"));
        assertThrows(StaleLeaseException.class, () -> adapter.completeExternal(w.id(), "w1", w.lockVersion(), Map.of()));
        assertThrows(StaleLeaseException.class, () -> adapter.extendLock(w.id(), "w1", w.lockVersion(), Duration.ofMinutes(5)));

        // expired lease is reset by executeDueJobs; w2 acquires the SAME job id with a higher lockVersion
        adapter.executeDueJobs();
        ExternalWork w2 = adapter.fetchAndLock("w2", "pay", 1, Duration.ofMinutes(5)).get(0);
        assertEquals(w.id(), w2.id());
        assertTrue(w2.lockVersion() > w.lockVersion());

        // fail with back-off: not available until the back-off has elapsed
        adapter.failExternal(w2.id(), "w2", w2.lockVersion(), "PFMS timeout", 2, Duration.ofMinutes(10));
        adapter.executeDueJobs();
        assertTrue(adapter.fetchAndLock("w3", "pay", 1, Duration.ofMinutes(5)).isEmpty(), "inside back-off");
        adapter.setClock(Instant.parse("2026-11-04T10:32:00Z"));
        adapter.executeDueJobs();
        ExternalWork w3 = adapter.fetchAndLock("w3", "pay", 1, Duration.ofMinutes(5)).get(0);
        assertEquals(2, w3.retries());

        // explicit release
        adapter.unlockExternal(w3.id(), "w3", w3.lockVersion());
        ExternalWork w4 = adapter.fetchAndLock("w4", "pay", 1, Duration.ofMinutes(5)).get(0);
        assertEquals(w.id(), w4.id());
        adapter.completeExternal(w4.id(), "w4", w4.lockVersion(), Map.of());
        adapter.executeDueJobs();
        assertTrue(adapter.getInstance(pid).ended());
    }

    // ------------------------------------------------------------------ adapter-side fencing (VI.3.2 rule 2, LEDGER-F6)

    /** Starts "pay", completes the approval and returns the process instance id (payment job is then acquirable). */
    private String startAtPayment() {
        String pid = adapter.startProcess(TENANT, "pay", "fence-" + UUID.randomUUID(), Map.of());
        TaskView t = adapter.queryTasks(new TaskQuery(TENANT, null, null, pid, "approve", 0, 10)).get(0);
        adapter.completeTask(t.id(), Map.of());
        return pid;
    }

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
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /** (a) A stale lockVersion is rejected and changes nothing; the current one is accepted. */
    @Test
    void fencing_a_staleLockVersionIsRejected() {
        String pid = startAtPayment();
        ExternalWork v1 = adapter.fetchAndLock("w1", "pay", 1, Duration.ofMinutes(5)).get(0);
        adapter.unlockExternal(v1.id(), "w1", v1.lockVersion());
        ExternalWork v3 = adapter.fetchAndLock("w1", "pay", 1, Duration.ofMinutes(5)).get(0);
        assertEquals(v1.id(), v3.id());
        assertEquals(v1.lockVersion() + 2, v3.lockVersion(), "+1 on release, +1 on re-acquire");
        assertNotEquals(v1.attemptId(), v3.attemptId());

        // same worker, old version: fenced on every call
        assertEquals(Reason.STALE_VERSION, reason(() -> adapter.completeExternal(v1.id(), "w1", v1.lockVersion(), Map.of())));
        assertEquals(Reason.STALE_VERSION, reason(() -> adapter.failExternal(v1.id(), "w1", v1.lockVersion(), "x", 0, Duration.ZERO)));
        assertEquals(Reason.STALE_VERSION, reason(() -> adapter.bpmnErrorExternal(v1.id(), "w1", v1.lockVersion(), "E", Map.of())));
        assertEquals(Reason.STALE_VERSION, reason(() -> adapter.extendLock(v1.id(), "w1", v1.lockVersion(), Duration.ofMinutes(1))));
        assertEquals(Reason.STALE_VERSION, reason(() -> adapter.unlockExternal(v1.id(), "w1", v1.lockVersion())));
        assertEquals(v3.lockVersion(), leaseVersion(v3.id()), "nothing changed");
        assertFalse(adapter.getInstance(pid).ended());

        adapter.completeExternal(v3.id(), "w1", v3.lockVersion(), Map.of());
        adapter.executeDueJobs();
        assertTrue(adapter.getInstance(pid).ended());
        assertEquals(-1L, leaseVersion(v3.id()), "lease row removed on completion");
    }

    /** (b) Complete, fail or BPMN error after expires_at is rejected even though nobody re-acquired the work. */
    @Test
    void fencing_b_callsAfterExpiryAreRejected() {
        String pid = startAtPayment();
        ExternalWork w = adapter.fetchAndLock("w1", "pay", 1, Duration.ofMinutes(5)).get(0);
        adapter.setClock(Instant.parse("2026-11-04T10:05:00Z")); // exactly at expiry: already expired
        assertEquals(Reason.EXPIRED, reason(() -> adapter.completeExternal(w.id(), "w1", w.lockVersion(), Map.of())));
        assertEquals(Reason.EXPIRED, reason(() -> adapter.failExternal(w.id(), "w1", w.lockVersion(), "x", 1, Duration.ZERO)));
        assertEquals(Reason.EXPIRED, reason(() -> adapter.bpmnErrorExternal(w.id(), "w1", w.lockVersion(), "E", Map.of())));
        assertEquals(Reason.EXPIRED, reason(() -> adapter.extendLock(w.id(), "w1", w.lockVersion(), Duration.ofMinutes(5))));
        assertEquals(Reason.EXPIRED, reason(() -> adapter.unlockExternal(w.id(), "w1", w.lockVersion())));
        assertFalse(adapter.getInstance(pid).ended());
        assertEquals(1, adapter.processEngine().getManagementService().createExternalWorkerJobQuery().jobId(w.id()).count());
    }

    /** (c) Re-acquire after expiry: higher lockVersion, new attemptId, and the old worker is fenced out. */
    @Test
    void fencing_c_reacquireAfterExpiryFencesTheOldWorker() {
        String pid = startAtPayment();
        ExternalWork a = adapter.fetchAndLock("A", "pay", 1, Duration.ofMinutes(5)).get(0);
        adapter.setClock(Instant.parse("2026-11-04T10:06:00Z"));
        ExternalWork b = adapter.fetchAndLock("B", "pay", 1, Duration.ofMinutes(5)).get(0);
        assertEquals(a.id(), b.id());
        assertTrue(b.lockVersion() > a.lockVersion());
        assertNotEquals(a.attemptId(), b.attemptId());
        assertEquals(a.activityInstanceId(), b.activityInstanceId(), "operationId input is stable across expiry");

        assertEquals(Reason.STALE_VERSION, reason(() -> adapter.completeExternal(a.id(), "A", a.lockVersion(), Map.of())));
        assertEquals(Reason.STALE_VERSION, reason(() -> adapter.extendLock(a.id(), "A", a.lockVersion(), Duration.ofMinutes(5))));
        // the old worker cannot borrow the new version either
        assertEquals(Reason.NOT_OWNER, reason(() -> adapter.completeExternal(a.id(), "A", b.lockVersion(), Map.of())));

        adapter.completeExternal(b.id(), "B", b.lockVersion(), Map.of("utr", "B-1"));
        adapter.executeDueJobs();
        assertTrue(adapter.getInstance(pid).ended());
        assertEquals("B-1", adapter.getInstance(pid).variables().get("utr"));
    }

    /** (d) Unlock by a worker that does not own the lease is rejected; the owner can unlock. */
    @Test
    void fencing_d_unlockByNonOwnerIsRejected() {
        startAtPayment();
        ExternalWork w = adapter.fetchAndLock("w1", "pay", 1, Duration.ofMinutes(5)).get(0);
        assertEquals(Reason.NOT_OWNER, reason(() -> adapter.unlockExternal(w.id(), "intruder", w.lockVersion())));
        assertTrue(adapter.fetchAndLock("w2", "pay", 1, Duration.ofMinutes(5)).isEmpty(), "still locked by w1");

        adapter.unlockExternal(w.id(), "w1", w.lockVersion());
        ExternalWork w2 = adapter.fetchAndLock("w2", "pay", 1, Duration.ofMinutes(5)).get(0);
        assertEquals(w.id(), w2.id());
        assertTrue(w2.lockVersion() > w.lockVersion());
    }

    /** (e) startCase, then the case human task through queryTasks/completeTask; getInstance(unknown) is null. */
    @Test
    void startCaseAndCompleteCaseHumanTask() {
        adapter.deploy(TENANT, "case-" + UUID.randomUUID(), Map.of("licence.cmmn", CASE_CMMN.getBytes(StandardCharsets.UTF_8)));
        String caseId = adapter.startCase(TENANT, "licenceCase", "lic-e", Map.of("applicant", "X"));
        InstanceView running = adapter.getInstance(caseId);
        assertNotNull(running);
        assertFalse(running.ended());
        assertEquals("licenceCase", running.processKey());
        assertEquals(List.of("pi1"), running.activeActivityIds());

        TaskQuery q = new TaskQuery(TENANT, null, null, caseId, null, 0, 10);
        assertEquals(1, adapter.countTasks(q));
        TaskView task = adapter.queryTasks(q).get(0);
        assertEquals("scrutiny", task.taskDefinitionKey());
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

    // ------------------------------------------------------------------ test 2: Q1 same transaction

    @Test
    void q1_productRowAndEngineChangeRollBackTogether() {
        String id = "q1-rb-" + UUID.randomUUID();
        RuntimeException boom = assertThrows(RuntimeException.class, () -> adapter.inEngineTransaction(c -> {
            insertProductRow(c, id);
            String pid = adapter.startProcess(TENANT, "oneTask", id, Map.of());
            adapter.flushEngineSession();
            // the engine rows are on the same connection, uncommitted, in the same transaction
            assertEquals(1, count(c, "select count(*) from ACT_RU_EXECUTION where ID_ = ?", pid));
            assertEquals(1, count(c, "select count(*) from spike_product_row where id = ?", id));
            throw new IllegalStateException("boom");
        }));
        assertEquals("boom", boom.getMessage());

        assertEquals(0, productRows(id));
        assertEquals(0, engineInstancesWithBusinessKey(id));
    }

    @Test
    void q1_productRowRollsBackWhenEngineCommandFails() {
        String id = "q1-engfail-" + UUID.randomUUID();
        assertThrows(RuntimeException.class, () -> adapter.inEngineTransaction(c -> {
            insertProductRow(c, id);
            adapter.startProcess(TENANT, "oneTask", id, Map.of());
            adapter.completeTask("no-such-task", Map.of()); // engine throws
            return null;
        }));
        assertEquals(0, productRows(id));
        assertEquals(0, engineInstancesWithBusinessKey(id));
    }

    @Test
    void q1_productOnlyWriteRollsBack() {
        // Guards the MyBatis dirty-flag case (no engine write in the transaction): see LEDGER-F5.
        String id = "q1-only-" + UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> adapter.inEngineTransaction(c -> {
            insertProductRow(c, id);
            throw new IllegalStateException("boom");
        }));
        assertEquals(0, productRows(id));
    }

    @Test
    void q1_productRowAndEngineChangeCommitTogether() {
        String id = "q1-ok-" + UUID.randomUUID();
        String pid = adapter.inEngineTransaction(c -> {
            insertProductRow(c, id);
            return adapter.startProcess(TENANT, "oneTask", id, Map.of());
        });
        assertEquals(1, productRows(id));
        assertEquals(1, engineInstancesWithBusinessKey(id));
        assertFalse(adapter.getInstance(pid).ended());

        String id2 = "q1-ok-only-" + UUID.randomUUID();
        adapter.inEngineTransaction(c -> insertProductRow(c, id2));
        assertEquals(1, productRows(id2));
    }

    // ------------------------------------------------------------------ test 3: timer with engine clock

    @Test
    void timerFiresOnlyWhenEngineClockPassesDueDate() {
        String pid = adapter.startProcess(TENANT, "sla", "sla-" + UUID.randomUUID(), Map.of());
        assertEquals(List.of("wait"), adapter.getInstance(pid).activeActivityIds());

        assertEquals(0, adapter.executeDueJobs());
        adapter.setClock(Instant.parse("2026-11-04T10:59:00Z"));
        assertEquals(0, adapter.executeDueJobs());
        assertEquals(List.of("wait"), adapter.getInstance(pid).activeActivityIds());

        adapter.setClock(Instant.parse("2026-11-04T11:00:01Z"));
        assertEquals(1, adapter.executeDueJobs());
        assertEquals(List.of("escalated"), adapter.getInstance(pid).activeActivityIds());
        assertEquals(0, adapter.executeDueJobs(), "fires exactly once");
    }

    // ------------------------------------------------------------------ smoke: DMN + CMMN engines are wired

    static final String BAND_DMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="dop" name="DoP" namespace="sangam-spike">
              <decision id="band" name="Approver band">
                <decisionTable id="bandTable" hitPolicy="FIRST">
                  <input id="in1"><inputExpression id="ie1" typeRef="number"><text>amount</text></inputExpression></input>
                  <output id="out1" name="approverBand" typeRef="string"/>
                  <rule id="r1"><inputEntry id="e1"><text>&lt; 100000</text></inputEntry><outputEntry id="o1"><text>"SO"</text></outputEntry></rule>
                  <rule id="r2"><inputEntry id="e2"><text>&gt;= 100000</text></inputEntry><outputEntry id="o2"><text>"DDO"</text></outputEntry></rule>
                </decisionTable>
              </decision>
            </definitions>
            """;

    static final String DMN_BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:flowable="http://flowable.org/bpmn" targetNamespace="sangam-spike">
              <process id="dopProcess" isExecutable="true">
                <startEvent id="start"/>
                <sequenceFlow id="f1" sourceRef="start" targetRef="rule"/>
                <serviceTask id="rule" flowable:type="dmn">
                  <extensionElements>
                    <flowable:field name="decisionTableReferenceKey"><flowable:string>band</flowable:string></flowable:field>
                    <flowable:field name="sameDeployment"><flowable:string>true</flowable:string></flowable:field>
                  </extensionElements>
                </serviceTask>
                <sequenceFlow id="f2" sourceRef="rule" targetRef="t"/>
                <userTask id="t"/>
                <sequenceFlow id="f3" sourceRef="t" targetRef="end"/>
                <endEvent id="end"/>
              </process>
            </definitions>
            """;

    static final String CASE_CMMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL" targetNamespace="sangam-spike">
              <case id="licenceCase">
                <casePlanModel id="plan">
                  <planItem id="pi1" definitionRef="scrutiny"/>
                  <humanTask id="scrutiny" name="Scrutiny"/>
                </casePlanModel>
              </case>
            </definitions>
            """;

    @Test
    void dmnAndCmmnInOneDeployment() {
        adapter.deploy(TENANT, "dop-" + UUID.randomUUID(), Map.of(
                "dop.bpmn20.xml", DMN_BPMN.getBytes(StandardCharsets.UTF_8),
                "dop.dmn", BAND_DMN.getBytes(StandardCharsets.UTF_8),
                "licence.cmmn", CASE_CMMN.getBytes(StandardCharsets.UTF_8)));

        String pid = adapter.startProcess(TENANT, "dopProcess", "dop-1", Map.of("amount", 250000));
        assertEquals("DDO", adapter.getInstance(pid).variables().get("approverBand"));

        String caseId = adapter.startCase(TENANT, "licenceCase", "lic-1", Map.of());
        assertEquals(1, adapter.cmmnEngineConfiguration().getCmmnTaskService().createTaskQuery().caseInstanceId(caseId).count());
    }

    // ------------------------------------------------------------------ models for the parity tests

    private static String uniq(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    static String singleExternalTask(String key, String topic) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn" targetNamespace="sangam-spike">
                  <process id="%s" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="work"/>
                    <serviceTask id="work" flowable:type="external-worker" flowable:topic="%s"/>
                    <sequenceFlow id="f2" sourceRef="work" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(key, topic);
    }

    /** E5-5 / E6 shape: a message catch in one branch of a parallel gateway (a child execution). */
    static String parallelMessageCatch(String key, String messageName) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn" targetNamespace="sangam-spike">
                  <message id="msg" name="%2$s"/>
                  <process id="%1$s" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="fork"/>
                    <parallelGateway id="fork"/>
                    <sequenceFlow id="f2" sourceRef="fork" targetRef="review"/>
                    <sequenceFlow id="f3" sourceRef="fork" targetRef="awaitDocs"/>
                    <userTask id="review" name="Review"/>
                    <intermediateCatchEvent id="awaitDocs"><messageEventDefinition messageRef="msg"/></intermediateCatchEvent>
                    <sequenceFlow id="f4" sourceRef="review" targetRef="join"/>
                    <sequenceFlow id="f5" sourceRef="awaitDocs" targetRef="join"/>
                    <parallelGateway id="join"/>
                    <sequenceFlow id="f6" sourceRef="join" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(key, messageName);
    }

    private void deployOne(String key, String xml) {
        adapter.deploy(TENANT, key, Map.of(key + ".bpmn20.xml", xml.getBytes(StandardCharsets.UTF_8)));
    }

    private ExternalWork fetchOn(String workerId, String topic, String instanceId, Duration lease) {
        return adapter.fetchAndLock(workerId, topic, 50, lease).stream()
                .filter(w -> w.processInstanceId().equals(instanceId)).findFirst().orElse(null);
    }

    private long leaseRows(List<String> workIds) {
        return adapter.inEngineTransaction(c -> {
            long n = 0;
            for (String id : workIds) {
                n += count(c, "select count(*) from spike_lease where work_id = ?", id);
            }
            return n;
        });
    }

    // ------------------------------------------------------------------ fix 1: correlation into a child execution

    /** E5-5 / E6 B-3: the message catch waits in a parallel branch; correlation by business key finds it. */
    @Test
    void correlateMessage_findsCatchInParallelBranch() {
        String key = uniq("par");
        String msg = uniq("docsArrived");
        deployOne(key, parallelMessageCatch(key, msg));
        String bk = "app-" + UUID.randomUUID();
        String pid = adapter.startProcess(TENANT, key, bk, Map.of());
        String other = adapter.startProcess(TENANT, key, "other-" + UUID.randomUUID(), Map.of());
        assertTrue(adapter.getInstance(pid).activeActivityIds().containsAll(List.of("review", "awaitDocs")));
        var child = adapter.processEngine().getRuntimeService().createExecutionQuery()
                .messageEventSubscriptionName(msg).processInstanceId(pid).singleResult();
        assertNotEquals(pid, child.getId(), "the catch is a child execution");

        assertEquals(pid, adapter.correlateMessage(TENANT, msg, bk, Map.of("docs", "received")));
        assertEquals(List.of("review"), adapter.getInstance(pid).activeActivityIds());
        assertEquals("received", adapter.getInstance(pid).variables().get("docs"));
        assertTrue(adapter.getInstance(other).activeActivityIds().contains("awaitDocs"), "other instance untouched");
        assertThrows(IllegalStateException.class, () -> adapter.correlateMessage(TENANT, msg, bk, Map.of()),
                "nothing waits any more");

        TaskView review = adapter.queryTasks(new TaskQuery(TENANT, null, null, pid, "review", 0, 10)).get(0);
        adapter.completeTask(review.id(), Map.of());
        assertTrue(adapter.getInstance(pid).ended());
    }

    // ------------------------------------------------------------------ fix 2: A-10 expiry hook (LEDGER-F12)

    /**
     * HOLD: the policy runs in the expiry transaction before the work can be acquired again (in fetch and in
     * executeDueJobs), the held work is parked in the dead-letter table and not re-offered, the stale owner is fenced,
     * and the product releases it through the engine (moveDeadLetterJobToExecutableJob).
     */
    @Test
    void expiryPolicyHold_expiredWorkIsNotReoffered() {
        String key = uniq("hold");
        String topic = uniq("htopic");
        deployOne(key, singleExternalTask(key, topic));
        List<ExpiryPolicy.ExpiredWork> seen = Collections.synchronizedList(new ArrayList<>());
        adapter.setExpiryPolicy((work, connection) -> {
            assertNotNull(connection, "policy gets the engine transaction's connection");
            seen.add(work);
            return ExpiryPolicy.Decision.HOLD;
        });
        var mgmt = adapter.processEngine().getManagementService();

        // (1) expiry found by the next fetch
        String p1 = adapter.startProcess(TENANT, key, null, Map.of());
        ExternalWork a = fetchOn("A", topic, p1, Duration.ofMinutes(1));
        assertNotNull(a);
        adapter.setClock(T0.plus(Duration.ofMinutes(2)));
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
        assertEquals(0, mgmt.createExternalWorkerJobQuery().jobId(a.id()).count());
        assertEquals(1, mgmt.createDeadLetterJobQuery().jobId(a.id()).count(), "held = dead letter, same job id");
        assertEquals(Reason.STALE_VERSION, reason(() -> adapter.completeExternal(a.id(), "A", a.lockVersion(), Map.of())));
        assertNull(fetchOn("C", topic, p1, Duration.ofMinutes(1)));
        assertEquals(1, seen.size(), "an ended lease is not expired twice");
        assertFalse(adapter.getInstance(p1).ended());

        // (2) expiry found by executeDueJobs
        String p2 = adapter.startProcess(TENANT, key, null, Map.of());
        ExternalWork b = fetchOn("A", topic, p2, Duration.ofMinutes(1));
        assertNotNull(b);
        adapter.setClock(T0.plus(Duration.ofMinutes(4)));
        adapter.executeDueJobs();
        assertEquals(2, seen.size());
        assertEquals(b.id(), seen.get(1).workId());
        assertNull(fetchOn("B", topic, p2, Duration.ofMinutes(1)));

        // (3) default policy again; the product releases the held work through the engine
        adapter.setExpiryPolicy(null);
        mgmt.moveDeadLetterJobToExecutableJob(a.id(), 1);
        assertEquals(0, mgmt.createDeadLetterJobQuery().processInstanceId(p1).count(), "released");
        ExternalWork a2 = fetchOn("C", topic, p1, Duration.ofMinutes(1));
        assertNotNull(a2);
        assertEquals(a.id(), a2.id());
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
        deployOne(key, singleExternalTask(key, topic));
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        adapter.setExpiryPolicy((work, connection) -> {
            seen.add(work.workId());
            return ExpiryPolicy.Decision.REOFFER;
        });
        String p = adapter.startProcess(TENANT, key, null, Map.of());
        ExternalWork a = fetchOn("A", topic, p, Duration.ofMinutes(1));
        adapter.setClock(T0.plus(Duration.ofMinutes(2)));
        ExternalWork b = fetchOn("B", topic, p, Duration.ofMinutes(1));
        assertNotNull(b);
        assertEquals(List.of(a.id()), seen);
        assertEquals(a.lockVersion() + 2, b.lockVersion());
        adapter.completeExternal(b.id(), "B", b.lockVersion(), Map.of());
        assertTrue(adapter.getInstance(p).ended());
    }

    /**
     * With Flowable's async executor running (ContinuationMode.ASYNC_EXECUTOR, reset thread every 100 ms), an expired
     * lease is NOT reset by the executor (LEDGER-F12); only the adapter's sweep resets it, after the policy.
     */
    @Test
    void asyncExecutorResetDoesNotBypassExpiryPolicy() throws Exception {
        String key = uniq("aexp");
        String topic = uniq("atopic");
        deployOne(key, singleExternalTask(key, topic));
        var ex = (AbstractAsyncExecutor) ((ProcessEngineConfigurationImpl) adapter.processEngine()
                .getProcessEngineConfiguration()).getAsyncExecutor();
        int interval = ex.getResetExpiredJobsInterval();
        ex.setResetExpiredJobsInterval(100);
        try {
            List<String> seen = Collections.synchronizedList(new ArrayList<>());
            adapter.setExpiryPolicy((work, connection) -> {
                seen.add(work.workId());
                return ExpiryPolicy.Decision.REOFFER;
            });
            String p = adapter.startProcess(TENANT, key, null, Map.of());
            ExternalWork a = fetchOn("A", topic, p, Duration.ofMinutes(1));
            adapter.setClock(T0.plus(Duration.ofMinutes(2)));
            adapter.setContinuationMode(ContinuationMode.ASYNC_EXECUTOR);
            Thread.sleep(1500);
            var job = adapter.processEngine().getManagementService().createExternalWorkerJobQuery().jobId(a.id()).singleResult();
            assertEquals("A", job.getLockOwner(), "the executor's reset did not release the expired lease");
            assertTrue(seen.isEmpty());
            ExternalWork b = fetchOn("B", topic, p, Duration.ofMinutes(1));
            assertNotNull(b);
            assertEquals(List.of(a.id()), seen, "the adapter's sweep called the policy");
            adapter.completeExternal(b.id(), "B", b.lockVersion(), Map.of());
            awaitEnded(p);
        } finally {
            adapter.setContinuationMode(ContinuationMode.AFTER_COMMIT);
            ex.setResetExpiredJobsInterval(interval);
        }
    }

    private void awaitEnded(String pid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!adapter.getInstance(pid).ended()) {
            assertTrue(System.nanoTime() < deadline, "instance " + pid + " did not end");
            Thread.sleep(50);
        }
    }

    // ------------------------------------------------------------------ fix 3: leases on delete (E7 F-7)

    /** cancelInstance removes the lease rows in the same command; a late call gets NO_LEASE and changes nothing. */
    @Test
    void deleteThenLateComplete_getsNoLease() {
        String key = uniq("del");
        String topic = uniq("dtopic");
        deployOne(key, singleExternalTask(key, topic));
        String p = adapter.startProcess(TENANT, key, null, Map.of());
        ExternalWork w = fetchOn("A", topic, p, Duration.ofMinutes(5));
        assertNotNull(w);
        assertEquals(1, leaseRows(List.of(w.id())));

        adapter.cancelInstance(p, "withdrawn by applicant");
        assertEquals(0, leaseRows(List.of(w.id())), "lease row removed with the instance");
        assertTrue(adapter.getInstance(p).ended());

        assertEquals(Reason.NO_LEASE, reason(() -> adapter.completeExternal(w.id(), "A", w.lockVersion(), Map.of())));
        assertEquals(Reason.NO_LEASE, reason(() -> adapter.failExternal(w.id(), "A", w.lockVersion(), "x", 0, Duration.ZERO)));
        assertEquals(Reason.NO_LEASE, reason(() -> adapter.bpmnErrorExternal(w.id(), "A", w.lockVersion(), "E", Map.of())));
        assertEquals(Reason.NO_LEASE, reason(() -> adapter.extendLock(w.id(), "A", w.lockVersion(), Duration.ofMinutes(1))));
        assertEquals(Reason.NO_LEASE, reason(() -> adapter.unlockExternal(w.id(), "A", w.lockVersion())));
        assertTrue(adapter.getInstance(p).ended(), "the instance stays deleted");
        assertEquals(0, leaseRows(List.of(w.id())));
    }

    // ------------------------------------------------------------------ fix 5: concurrent fetch (same test as Operaton)

    /**
     * 8 workers fetch and complete 200 tasks of one topic at the same time (the Operaton E9 B-2 test). Every task is
     * delivered once and completed once, every instance ends, no worker call throws, and no lease row or job is left.
     */
    @Test
    void concurrentFetch_8Workers_200Tasks_eachCompletesExactlyOnce() throws Exception {
        String key = uniq("conc");
        String topic = uniq("topic");
        deployOne(key, singleExternalTask(key, topic));
        int tasks = 200;
        int workers = 8;
        for (int i = 0; i < tasks; i++) {
            adapter.startProcess(TENANT, key, "bk-" + i, Map.of("i", i));
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
        System.out.printf("[%s] concurrent fetch: %d workers x %d tasks in %d ms, fetch retries %d, give-ups %d, "
                        + "continuation failures %d%n", getClass().getSimpleName(), workers, tasks, millis,
                adapter.fetchRetries() - retriesBefore, adapter.fetchGiveUps(), adapter.continuationFailures());

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
        assertEquals(0, engine.getManagementService().createExternalWorkerJobQuery().processDefinitionKey(key).count());
        assertEquals(0, engine.getManagementService().createJobQuery().processDefinitionKey(key).count(),
                "no continuation job left");
        assertEquals(0, leaseRows(new ArrayList<>(completions.keySet())), "no lease row left");
    }

    // ------------------------------------------------------------------ fix 6: continuation after completion (LEDGER-F11)

    /** The three continuation modes, including AFTER_COMMIT inside a caller's transaction and its rollback. */
    @Test
    void continuationModes() throws Exception {
        String key = uniq("cont");
        String topic = uniq("ctopic");
        deployOne(key, singleExternalTask(key, topic));
        var jobs = adapter.processEngine().getManagementService();

        // DEFERRED: Flowable's native behaviour without an executor
        adapter.setContinuationMode(ContinuationMode.DEFERRED);
        String p1 = adapter.startProcess(TENANT, key, null, Map.of());
        ExternalWork w1 = fetchOn("w", topic, p1, Duration.ofMinutes(5));
        adapter.completeExternal(w1.id(), "w", w1.lockVersion(), Map.of());
        assertFalse(adapter.getInstance(p1).ended(), "completion committed, process not continued");
        assertEquals(1, jobs.createJobQuery().processInstanceId(p1).handlerType("external-worker-complete").count());
        assertEquals(1, adapter.executeDueJobs());
        assertTrue(adapter.getInstance(p1).ended());

        // AFTER_COMMIT nested in a caller's transaction: continued after the OUTER commit
        adapter.setContinuationMode(ContinuationMode.AFTER_COMMIT);
        String p2 = adapter.startProcess(TENANT, key, null, Map.of());
        ExternalWork w2 = fetchOn("w", topic, p2, Duration.ofMinutes(5));
        adapter.inEngineTransaction(c -> {
            adapter.completeExternal(w2.id(), "w", w2.lockVersion(), Map.of());
            assertFalse(adapter.getInstance(p2).ended(), "not continued inside the caller's transaction");
            return null;
        });
        assertTrue(adapter.getInstance(p2).ended(), "continued right after the outer commit");

        // AFTER_COMMIT, outer transaction rolls back: no continuation, the lease is intact
        String p3 = adapter.startProcess(TENANT, key, null, Map.of());
        ExternalWork w3 = fetchOn("w", topic, p3, Duration.ofMinutes(5));
        assertThrows(IllegalStateException.class, () -> adapter.inEngineTransaction(c -> {
            adapter.completeExternal(w3.id(), "w", w3.lockVersion(), Map.of());
            throw new IllegalStateException("product failure after completion");
        }));
        assertFalse(adapter.getInstance(p3).ended());
        assertEquals(0, jobs.createJobQuery().processInstanceId(p3).count());
        adapter.completeExternal(w3.id(), "w", w3.lockVersion(), Map.of());
        assertTrue(adapter.getInstance(p3).ended());

        // ASYNC_EXECUTOR: Flowable's executor runs the continuation
        adapter.setContinuationMode(ContinuationMode.ASYNC_EXECUTOR);
        String p4 = adapter.startProcess(TENANT, key, null, Map.of());
        ExternalWork w4 = fetchOn("w", topic, p4, Duration.ofMinutes(5));
        adapter.completeExternal(w4.id(), "w", w4.lockVersion(), Map.of());
        awaitEnded(p4);
        adapter.setContinuationMode(ContinuationMode.AFTER_COMMIT);
    }

    // ------------------------------------------------------------------ fix 7: targeted expiry reset (LEDGER-F9)

    /**
     * Cost of the expired-lock reset that runs before every fetch, with a backlog of 1000 unlocked external-worker
     * jobs: the pre-fix full scan (every job loaded through the engine query) against the targeted SQL (only elapsed
     * locks of the topic). Prints the record for the ledger.
     */
    @Test
    void fetchResetCost_targetedVsFullScan() {
        String key = uniq("cost");
        String topic = uniq("ktopic");
        deployOne(key, singleExternalTask(key, topic));
        int backlog = 1000;
        for (int i = 0; i < backlog; i++) {
            adapter.startProcess(TENANT, key, null, Map.of());
        }
        long[] legacy;
        System.setProperty("sangam.flowable.legacyResetScan", "true");
        try {
            legacy = measureFetch(topic, 30);
        } finally {
            System.clearProperty("sangam.flowable.legacyResetScan");
        }
        long[] targeted = measureFetch(topic, 30);
        System.out.printf("[%s] fetch reset cost, backlog %d: full scan p50 %d us / mean %d us / rows read per fetch %d;"
                        + " targeted p50 %d us / mean %d us / rows read per fetch %d%n", getClass().getSimpleName(),
                backlog, legacy[0], legacy[1], legacy[2], targeted[0], targeted[1], targeted[2]);
        assertTrue(legacy[2] >= backlog, "the old scan reads every external-worker job");
        assertEquals(0, targeted[2], "the targeted reset reads only elapsed locks (none here)");
    }

    /** {p50 us, mean us, rows read per fetch} of fetchAndLock(1) + unlock, after 5 warm-up rounds. */
    private long[] measureFetch(String topic, int rounds) {
        List<Long> micros = new ArrayList<>();
        long rows = 0;
        for (int i = 0; i < rounds + 5; i++) {
            long r0 = adapter.resetRowsRead();
            long t0 = System.nanoTime();
            List<ExternalWork> got = adapter.fetchAndLock("m", topic, 1, Duration.ofMinutes(5));
            long us = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - t0);
            long read = adapter.resetRowsRead() - r0;
            assertEquals(1, got.size());
            adapter.unlockExternal(got.get(0).id(), "m", got.get(0).lockVersion());
            if (i >= 5) {
                micros.add(us);
                rows += read;
            }
        }
        Collections.sort(micros);
        long mean = (long) micros.stream().mapToLong(Long::longValue).average().orElse(0);
        return new long[] {micros.get(micros.size() / 2), mean, rows / rounds};
    }

    // ------------------------------------------------------------------ helpers

    private Object insertProductRow(Connection c, String id) {
        try (PreparedStatement ps = c.prepareStatement("insert into spike_product_row (id, note) values (?, ?)")) {
            ps.setString(1, id);
            ps.setString(2, "product");
            ps.executeUpdate();
            return null;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private long productRows(String id) {
        return adapter.inEngineTransaction(c -> count(c, "select count(*) from spike_product_row where id = ?", id));
    }

    private long engineInstancesWithBusinessKey(String bk) {
        // history (audit level) holds running and ended instances; runtime is checked separately for rollback
        long hist = adapter.processEngine().getHistoryService().createHistoricProcessInstanceQuery().processInstanceBusinessKey(bk).count();
        long run = adapter.processEngine().getRuntimeService().createProcessInstanceQuery().processInstanceBusinessKey(bk).count();
        return Math.max(hist, run);
    }

    static long count(Connection c, String sql, String arg) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, arg);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    static Object exec(Connection c, String sql) {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
            return null;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}

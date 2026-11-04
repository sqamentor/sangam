package org.sangam.spike.operaton;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import org.operaton.bpm.engine.ExternalTaskService;
import org.operaton.bpm.engine.externaltask.LockedExternalTask;
import org.operaton.bpm.engine.history.HistoricProcessInstance;

import org.sangam.spike.sim.db.Databases;

/**
 * MySQL finding (scoring evidence, ADR-D-15): Operaton 2.2.0 creates its MySQL time columns with <b>second
 * precision</b> ({@code datetime}), and what that does to leases, timers and ordering; then the same checks after the
 * datetime(3) patch P-1 ({@link MysqlDatetimePrecisionPatch}). Engine API only (no adapter fencing), engine clock
 * fixed with {@code ClockUtil} so the sub-second offsets are exact. Default MySQL Connector/J settings
 * ({@code sendFractionalSeconds=true}: the server <b>rounds</b> to the nearest second; with Operaton's recommended
 * {@code sendFractionalSeconds=false} the driver truncates instead).
 *
 * <p>Results are printed as {@code [P-1] ...} lines for the ledger. Runs when {@code mysql} is in
 * {@code -Doperaton.dbs}; one container, stopped after the class.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OperatonMysqlDatetimePrecisionTest {

    private static final Instant DAY = Instant.parse("2026-01-01T00:00:00Z");

    private Databases.Running db;
    private String url;
    private OperatonEngineAdapter adapter;

    @BeforeAll
    void start() {
        Assumptions.assumeTrue(AbstractContainerDbTest.selected("mysql"), "mysql not selected by -Doperaton.dbs");
        db = Databases.start("mysql");
        url = AbstractContainerDbTest.createMysqlDatabase(db, "operaton_dt");
        adapter = new OperatonEngineAdapter(url, db.username(), db.password());
    }

    @AfterAll
    void stop() {
        try {
            if (adapter != null) {
                adapter.setClock(null);
                adapter.close();
            }
        } finally {
            if (db != null) {
                db.close();
            }
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url, db.username(), db.password());
    }

    private int precision(String table, String column) throws SQLException {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement("select datetime_precision from "
                + "information_schema.columns where table_schema = database() and table_name = ? and column_name = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), table + "." + column + " missing");
                return rs.getInt(1);
            }
        }
    }

    private static String uniq(String p) {
        return p + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private static Instant at(long secondOfDay, long millis) {
        return DAY.plusSeconds(secondOfDay).plusMillis(millis);
    }

    // ---------------------------------------------------------------- (1) the column types the engine created

    @Test
    @Order(1)
    void engineCreatesSecondPrecisionTimeColumns() throws SQLException {
        Map<String, Integer> p = new LinkedHashMap<>();
        for (String tc : List.of("ACT_RU_EXT_TASK.LOCK_EXP_TIME_", "ACT_RU_JOB.LOCK_EXP_TIME_", "ACT_RU_JOB.DUEDATE_",
                "ACT_RU_TASK.CREATE_TIME_", "ACT_HI_PROCINST.START_TIME_", "ACT_HI_ACTINST.START_TIME_",
                "ACT_HI_ACTINST.END_TIME_", "ACT_HI_DETAIL.TIME_", "ACT_RU_METER_LOG.TIMESTAMP_")) {
            String[] x = tc.split("\\.");
            p.put(tc, precision(x[0], x[1]));
        }
        int total;
        String charset;
        try (Connection c = connect()) {
            total = MysqlDatetimePrecisionPatch.secondPrecisionColumns(c).size();
            try (PreparedStatement ps = c.prepareStatement("select character_set_name, collation_name from "
                    + "information_schema.columns where table_schema = database() and table_name = 'ACT_RU_VARIABLE' "
                    + "and column_name = 'TEXT_'"); ResultSet rs = ps.executeQuery()) {
                rs.next();
                charset = rs.getString(1) + "/" + rs.getString(2);
            }
        }
        System.out.printf("[P-1] unpatched datetime precision: %s; ACT_* time columns with precision 0: %d; "
                + "ACT_RU_VARIABLE.TEXT_ charset %s%n", p, total, charset);
        p.forEach((k, v) -> assertEquals(0, v, k));
        assertTrue(total >= 60, "expected ~66 second-precision columns, found " + total);
    }

    // ---------------------------------------------------------------- (2) effects, unpatched

    @Test
    @Order(2)
    void unpatched_lease1500ms_isStoredInWholeSecondsAndCanBeStolenEarly() {
        LeaseProbe r = leaseProbe("unpatched");
        assertEquals(0, r.storedLockExpiry.toEpochMilli() % 1000, "LOCK_EXP_TIME_ holds whole seconds: " + r);
        assertTrue(r.storedLockExpiry.isBefore(r.intendedLockExpiry), "rounded down: " + r);
        assertTrue(r.stolenAtIntendedMinus100ms, "worker-2 got the task while worker-1's 1500 ms lock was live: " + r);
    }

    @Test
    @Order(3)
    void unpatched_timerFiresEarly() {
        TimerProbe r = timerProbe("unpatched");
        assertEquals(0, r.storedDue.toEpochMilli() % 1000, "DUEDATE_ holds whole seconds: " + r);
        assertTrue(r.firedBeforeDue, "PT1S timer fired 300 ms before its due time: " + r);
    }

    @Test
    @Order(4)
    void unpatched_eventsInTheSameSecondCannotBeOrdered() {
        OrderProbe r = orderProbe("unpatched");
        assertEquals(r.first, r.second, "both start times stored as the same second: " + r);
    }

    // ---------------------------------------------------------------- (3) the fix: datetime(3), then the same checks

    @Test
    @Order(5)
    void patched_columnsHaveMillisecondsAndEffectsAreGone() throws SQLException {
        int altered;
        try (Connection c = connect()) {
            altered = MysqlDatetimePrecisionPatch.apply(c).size();
            assertEquals(0, MysqlDatetimePrecisionPatch.secondPrecisionColumns(c).size());
        }
        assertEquals(3, precision("ACT_RU_EXT_TASK", "LOCK_EXP_TIME_"));
        assertEquals(3, precision("ACT_RU_JOB", "DUEDATE_"));
        System.out.printf("[P-1] patch applied: %d columns altered to precision 3%n", altered);

        LeaseProbe lease = leaseProbe("patched");
        assertEquals(lease.intendedLockExpiry, lease.storedLockExpiry, lease.toString());
        assertFalse(lease.stolenAtIntendedMinus100ms, lease.toString());
        TimerProbe timer = timerProbe("patched");
        assertEquals(timer.intendedDue, timer.storedDue, timer.toString());
        assertFalse(timer.firedBeforeDue, timer.toString());
        assertTrue(timer.firedAfterDue, timer.toString());
        OrderProbe order = orderProbe("patched");
        assertTrue(order.first.isBefore(order.second), order.toString());
    }

    // ---------------------------------------------------------------- probes

    record LeaseProbe(String phase, Instant intendedLockExpiry, Instant storedLockExpiry, boolean stolenAtIntendedMinus100ms) {
    }

    /** Lock at hh:mm:01.700 for 1500 ms (due 03.200); a second worker fetches at 03.100, 100 ms before expiry. */
    private LeaseProbe leaseProbe(String phase) {
        long base = phase.equals("unpatched") ? 100 : 200;
        String key = uniq("lease");
        String topic = uniq("t");
        adapter.deploy(null, key + ".bpmn", Map.of(key + ".bpmn", Models.bytes(Models.singleExternalTask(key, topic))));
        Instant lockAt = at(base + 1, 700);
        adapter.setClock(lockAt);
        adapter.startProcess(null, key, null, Map.of());
        ExternalTaskService ext = adapter.processEngine().getExternalTaskService();
        List<LockedExternalTask> w1 = ext.fetchAndLock(1, "worker-1").topic(topic, 1500).execute();
        assertEquals(1, w1.size());
        Instant intended = lockAt.plusMillis(1500);
        Instant stored = ext.createExternalTaskQuery().externalTaskId(w1.get(0).getId()).singleResult()
                .getLockExpirationTime().toInstant();
        adapter.setClock(intended.minusMillis(100));
        List<LockedExternalTask> w2 = ext.fetchAndLock(1, "worker-2").topic(topic, 1500).execute();
        LeaseProbe r = new LeaseProbe(phase, intended, stored, !w2.isEmpty());
        System.out.printf("[P-1] %s lease: locked at %s for 1500 ms, intended expiry %s, stored LOCK_EXP_TIME_ %s, "
                + "fetch by worker-2 at %s -> %s%n", phase, lockAt, intended, stored, intended.minusMillis(100),
                w2.isEmpty() ? "nothing (lock still held)" : "GOT THE TASK (lock lost 100 ms early)");
        adapter.setClock(null);
        return r;
    }

    record TimerProbe(String phase, Instant intendedDue, Instant storedDue, boolean firedBeforeDue, boolean firedAfterDue) {
    }

    /** PT1S timer started at hh:mm:10.400 (due 11.400); jobs run at 11.100 (300 ms early) and at 11.500. */
    private TimerProbe timerProbe(String phase) {
        long base = phase.equals("unpatched") ? 300 : 400;
        String key = uniq("timer");
        adapter.deploy(null, key + ".bpmn", Map.of(key + ".bpmn", Models.bytes(Models.timer(key, "PT1S"))));
        Instant startAt = at(base + 10, 400);
        adapter.setClock(startAt);
        String pi = adapter.startProcess(null, key, null, Map.of());
        Instant intended = startAt.plusMillis(1000);
        Instant stored = adapter.processEngine().getManagementService().createJobQuery().processInstanceId(pi)
                .singleResult().getDuedate().toInstant();
        adapter.setClock(intended.minusMillis(300));
        adapter.executeDueJobs();
        boolean early = adapter.getInstance(pi).activeActivityIds().contains("afterTimer");
        adapter.setClock(intended.plusMillis(100));
        adapter.executeDueJobs();
        boolean after = adapter.getInstance(pi).activeActivityIds().contains("afterTimer");
        TimerProbe r = new TimerProbe(phase, intended, stored, early, after);
        System.out.printf("[P-1] %s timer PT1S: started %s, intended due %s, stored DUEDATE_ %s, fired at -300 ms: %s, "
                + "fired at +100 ms: %s%n", phase, startAt, intended, stored, early, after);
        adapter.setClock(null);
        return r;
    }

    record OrderProbe(String phase, Instant first, Instant second) {
    }

    /** Two instances started 300 ms apart in the same second (hh:mm:20.100 and 20.400): their history start times. */
    private OrderProbe orderProbe(String phase) {
        long base = phase.equals("unpatched") ? 500 : 600;
        String key = uniq("order");
        adapter.deploy(null, key + ".bpmn", Map.of(key + ".bpmn", Models.bytes(Models.singleUserTask(key))));
        List<String> ids = new ArrayList<>();
        adapter.setClock(at(base + 20, 100));
        ids.add(adapter.startProcess(null, key, null, Map.of()));
        adapter.setClock(at(base + 20, 400));
        ids.add(adapter.startProcess(null, key, null, Map.of()));
        adapter.setClock(null);
        var hs = adapter.processEngine().getHistoryService();
        HistoricProcessInstance a = hs.createHistoricProcessInstanceQuery().processInstanceId(ids.get(0)).singleResult();
        HistoricProcessInstance b = hs.createHistoricProcessInstanceQuery().processInstanceId(ids.get(1)).singleResult();
        OrderProbe r = new OrderProbe(phase, a.getStartTime().toInstant(), b.getStartTime().toInstant());
        System.out.printf("[P-1] %s ordering: started at %s and %s, stored START_TIME_ %s and %s (%s)%n", phase,
                at(base + 20, 100), at(base + 20, 400), r.first, r.second,
                r.first.equals(r.second) ? "TIE: order by start time is undefined" : "ordered");
        for (String id : ids) {
            adapter.processEngine().getRuntimeService().deleteProcessInstance(id, "probe");
        }
        return r;
    }
}

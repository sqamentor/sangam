// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.utc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.Json;
import org.sangam.ops.utc.UtcConversionTool.Run;
import org.sangam.persistence.SangamSchema;

/**
 * Owner decision 240 on H2: the MySQL/MariaDB session-zoned engine column {@code ACT_HI_OP_LOG.TIMESTAMP_} converted with
 * the run's declared historical session zone. H2 has no MySQL {@code timestamp} semantics, so {@link EmulatedSession}
 * stands in for the server: it stores instants and presents them as wall clocks of the current session zone, as MySQL
 * does for a {@code timestamp} column. The same flow on a real MySQL and MariaDB server is a sangam-server vendor IT
 * (NOT_RUN in this coding phase; it needs the server's time-zone tables loaded for a named zone).
 */
class UtcSessionZoneH2Test {

    static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");
    static final Instant CUTOVER = Instant.parse("2026-11-01T00:00:00Z");
    static final String ENGINE_CSV = UtcEngineRulesH2Test.ENGINE_CSV;

    /**
     * A MySQL session over H2 for {@code ACT_HI_OP_LOG.TIMESTAMP_}: the stored instant is what {@code +00:00} reads;
     * switching the session zone re-presents every value as the wall clock of the new zone (a wall clock written under a
     * zone is stored as that zone's instant, earlier offset in an overlap).
     */
    static final class EmulatedSession implements SessionZones {
        String zone = UTC;
        final List<String> calls = new ArrayList<>();

        @Override
        public Set<String> zonedColumns() {
            return UtcInventory.MYSQL_SESSION_ZONED;
        }

        @Override
        public String current(Connection c) {
            return zone;
        }

        @Override
        public void set(Connection c, String next) {
            calls.add(next);
            if (next.equals(zone)) {
                return;
            }
            ZoneId from = ZoneId.of(zone);
            ZoneId to = ZoneId.of(next);
            List<Object[]> rows = Jdbc.query(c, "SELECT ID_, TIMESTAMP_ FROM ACT_HI_OP_LOG",
                    rs -> new Object[]{rs.getString(1), Jdbc.instant(rs, "TIMESTAMP_")});
            for (Object[] r : rows) {
                String id = (String) r[0];
                Instant shownNow = (Instant) r[1];
                if (shownNow == null) {
                    stored.remove(id);
                    continue;
                }
                // a value not written since it was presented keeps its stored instant (reading never changes it);
                // a value written under the current zone is stored as that zone's instant
                Instant known = stored.get(id);
                Instant s = known != null && present(known, from).equals(shownNow) ? known
                        : LocalDateTime.ofInstant(shownNow, ZoneOffset.UTC).atZone(from).toInstant();
                stored.put(id, s);
                Jdbc.update(c, "UPDATE ACT_HI_OP_LOG SET TIMESTAMP_ = ? WHERE ID_ = ?", present(s, to), id);
            }
            zone = next;
        }

        final Map<String, Instant> stored = new java.util.HashMap<>();

        static Instant present(Instant stored, ZoneId zone) {
            return LocalDateTime.ofInstant(stored, zone).toInstant(ZoneOffset.UTC);
        }
    }

    Transactions tx;
    final AuditChain audit = new AuditChain(Dialect.POSTGRESQL);
    final EmulatedSession session = new EmulatedSession();
    UtcConversionTool tool;

    @BeforeEach
    void db() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=TRUE");
        SangamSchema.apply(ds);
        tx = Transactions.of(ds);
        sql("CREATE TABLE ACT_RU_JOB (ID_ VARCHAR(64) PRIMARY KEY, LOCK_EXP_TIME_ TIMESTAMP WITH TIME ZONE)");
        sql("CREATE TABLE ACT_RU_EXT_TASK (ID_ VARCHAR(64) PRIMARY KEY, LOCK_EXP_TIME_ TIMESTAMP WITH TIME ZONE)");
        sql("CREATE TABLE ACT_HI_OP_LOG (ID_ VARCHAR(64) PRIMARY KEY, TIMESTAMP_ TIMESTAMP WITH TIME ZONE,"
                + " REMOVAL_TIME_ TIMESTAMP WITH TIME ZONE)");
        // the session access is emulated; every other statement is plain H2 (PostgreSQL dialect)
        tool = new UtcConversionTool(tx, Dialect.POSTGRESQL, audit, UtcInventory.product().withEngine(ENGINE_CSV), session);
    }

    void sql(String sql, Object... params) {
        tx.inTransaction(c -> Jdbc.update(c, sql, params));
    }

    /** Stored instants (as {@code +00:00} reads them). */
    void log(String id, String storedTimestamp, String removal) {
        sql("INSERT INTO ACT_HI_OP_LOG(ID_, TIMESTAMP_, REMOVAL_TIME_) VALUES (?, ?, ?)", id, Instant.parse(storedTimestamp),
                removal == null ? null : Instant.parse(removal));
    }

    Instant stored(String id, String column) {
        assertEquals(SessionZones.UTC, session.zone, "the tool restored the connection's session zone");
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT " + column + " FROM ACT_HI_OP_LOG WHERE ID_ = ?",
                rs -> Jdbc.instant(rs, column), id).get(0));
    }

    static UtcDataset opLog(ZoneId sessionZone, String... columns) {
        return new UtcDataset("ACT_HI_OP_LOG", List.of(columns), "TIMESTAMP_", BERLIN, CUTOVER, null, sessionZone);
    }

    @Test
    void aDeclaredSessionZoneConvertsReconcilesAndRestoresExactly() {
        // engine JVM in Berlin wrote Berlin wall clocks; the MySQL session was Asia/Kolkata (+05:30)
        log("o1", "2026-01-15T04:30:00Z", "2026-01-20T12:00:00Z"); // wall 10:00 under Kolkata; removal is datetime
        log("o2", "2026-10-31T19:00:00Z", null);                   // wall 2026-11-01 00:30: still before the cutover
        log("o3", "2026-11-15T10:00:00Z", "2026-11-20T10:00:00Z"); // written in UTC after the cutover
        UtcDataset d = opLog(KOLKATA, "TIMESTAMP_", "REMOVAL_TIME_");
        assertNotEquals(opLog(null, "TIMESTAMP_", "REMOVAL_TIME_").fingerprint(), d.fingerprint());

        Run dry = tool.dryRun("s1", d, "operator", "ops");
        assertEquals(3, dry.converted(), "o1 two cells, o2 one");
        assertEquals(1, dry.alreadyUtc(), "o3 judged on its stored instant");
        assertEquals(0, dry.unresolved(), dry.report());
        Map<String, Object> report = Json.readMap(dry.report());
        assertEquals("Asia/Kolkata", report.get("sessionZone"));
        assertTrue(report.get("samples").toString().contains("2026-01-15T10:00:00Z"), "read as the wall clock written");
        assertTrue(session.calls.contains("Asia/Kolkata"), session.calls.toString());
        assertEquals(Instant.parse("2026-01-15T04:30:00Z"), stored("o1", "TIMESTAMP_"), "a dry run changes nothing");
        assertEquals("Asia/Kolkata", tx.inTransaction(c -> Jdbc.one(c, "SELECT session_zone FROM OPS_UTC_RUN WHERE"
                + " run_id = 's1'", rs -> rs.getString(1)).orElseThrow()));

        assertEquals("APPLIED", tool.apply("s1", 2, "MW-240", "operator", "ops").state());
        assertEquals(Instant.parse("2026-01-15T09:00:00Z"), stored("o1", "TIMESTAMP_"), "Berlin 10:00 in winter");
        assertEquals(Instant.parse("2026-01-20T11:00:00Z"), stored("o1", "REMOVAL_TIME_"), "not session-zoned");
        assertEquals(Instant.parse("2026-10-31T23:30:00Z"), stored("o2", "TIMESTAMP_"));
        assertEquals(Instant.parse("2026-11-15T10:00:00Z"), stored("o3", "TIMESTAMP_"));
        List<String> payloads = tx.inTransaction(c -> Jdbc.query(c, "SELECT e.event_type, p.payload FROM AUDIT_PAYLOAD p"
                + " JOIN AUDIT_EVENT e ON e.event_id = p.event_id WHERE e.chain_id = ? ORDER BY e.seq",
                rs -> rs.getString(1) + " " + rs.getString(2), UtcConversionTool.CHAIN_PREFIX + "s1"));
        assertEquals(2, payloads.stream().filter(p -> p.contains("\"sessionZone\":\"Asia/Kolkata\"")).count(),
                payloads.toString());
        assertTrue(payloads.get(0).startsWith(UtcConversionTool.EVENT_DRY_RUN)
                && payloads.get(1).startsWith(UtcConversionTool.EVENT_APPLY_STARTED), payloads.toString());

        Run rec = tool.reconcile("s1", "operator", "ops");
        assertEquals("RECONCILED", rec.state(), rec.report());
        assertEquals(3, rec.matched());

        assertEquals("ROLLED_BACK", tool.rollback("s1", "MW-240b", "operator", "ops").state());
        assertEquals(Instant.parse("2026-01-15T04:30:00Z"), stored("o1", "TIMESTAMP_"), "the stored instant again");
        assertEquals(Instant.parse("2026-01-20T12:00:00Z"), stored("o1", "REMOVAL_TIME_"));
        assertEquals(Instant.parse("2026-10-31T19:00:00Z"), stored("o2", "TIMESTAMP_"));
        assertEquals(SessionZones.UTC, session.calls.get(session.calls.size() - 1));
    }

    @Test
    void aWallClockInTheSessionZonesOverlapStaysUnresolved() {
        // Europe/London repeats 01:00-02:00 on 2026-10-25: both instants read as wall clock 01:30
        log("v1", "2026-10-25T00:30:00Z", null);
        log("v2", "2026-10-25T01:30:00Z", null);
        log("v3", "2026-01-15T09:00:00Z", null); // London winter: wall clock 09:00
        Run dry = tool.dryRun("s2", opLog(ZoneId.of("Europe/London"), "TIMESTAMP_"), "operator", "ops");
        assertEquals(Map.of("SESSION_ZONE_OVERLAP", 2), Json.readMap(dry.report()).get("unresolvedByReason"));
        assertEquals(1, dry.converted());
        tool.apply("s2", 100, "MW-240", "operator", "ops");
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), stored("v1", "TIMESTAMP_"), "never written");
        assertEquals(Instant.parse("2026-10-25T01:30:00Z"), stored("v2", "TIMESTAMP_"));
        assertEquals(Instant.parse("2026-01-15T08:00:00Z"), stored("v3", "TIMESTAMP_"));
    }

    @Test
    void aDeclarationIsRequiredOnMysqlAndRefusedWhereItMeansNothing() {
        UtcInventory inv = UtcInventory.product().withEngine(ENGINE_CSV);
        UtcConversionTool mysql = new UtcConversionTool(tx, Dialect.MYSQL, new AuditChain(Dialect.MYSQL), inv);
        IllegalArgumentException none = assertThrows(IllegalArgumentException.class,
                () -> mysql.check(opLog(null, "TIMESTAMP_")));
        assertTrue(none.getMessage().contains("declare that historical session zone"), none.getMessage());
        mysql.check(opLog(KOLKATA, "TIMESTAMP_"));
        mysql.check(opLog(ZoneOffset.ofHoursMinutes(5, 30), "TIMESTAMP_"));
        UtcDataset plain = new UtcDataset("ACT_HI_OP_LOG", List.of("REMOVAL_TIME_"), "REMOVAL_TIME_", BERLIN, CUTOVER,
                null, KOLKATA);
        assertThrows(IllegalArgumentException.class, () -> mysql.check(plain), "no session-zoned column read");
        UtcConversionTool pg = new UtcConversionTool(tx, Dialect.POSTGRESQL, audit, inv);
        assertThrows(IllegalArgumentException.class, () -> pg.check(opLog(KOLKATA, "TIMESTAMP_")),
                "zone-aware dialects take no session zone");
        assertThrows(IllegalArgumentException.class, () -> opLog(ZoneOffset.ofHoursMinutesSeconds(5, 30, 10), "TIMESTAMP_"));
        assertThrows(IllegalArgumentException.class, () -> opLog(ZoneId.of("UTC+05:30"), "TIMESTAMP_"),
                "declare the offset itself");
        assertEquals("+05:30", SessionZones.text(ZoneOffset.ofHoursMinutes(5, 30)));
        assertEquals(SessionZones.UTC, SessionZones.text(ZoneOffset.UTC));
        assertNull(new UtcDataset("HRM_TASK", List.of("create_time"), "create_time", BERLIN, CUTOVER, null).sessionZone());
    }
}

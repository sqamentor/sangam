import os
import sys

os.chdir(sys.argv[1])


def edit(p, pairs):
    s = open(p, encoding='utf-8').read()
    for o, n in pairs:
        assert s.count(o) == 1, (p, o[:80], s.count(o))
        s = s.replace(o, n)
    open(p, 'w', encoding='utf-8', newline='').write(s)


edit('sangam-ops/src/main/java/org/sangam/ops/utc/WallClock.java', [
    ('    public enum Unresolved { DST_GAP, DST_OVERLAP, NO_PROVENANCE, CUTOVER_AMBIGUOUS, CUTOVER_INCONSISTENT }',
     '''    public enum Unresolved {
        DST_GAP, DST_OVERLAP, NO_PROVENANCE, CUTOVER_AMBIGUOUS, CUTOVER_INCONSISTENT,
        /** Decision 240: the wall clock read under the declared session zone lies in its DST overlap (not restorable). */
        SESSION_ZONE_OVERLAP,
        /** Decision 240: the wall clock read under the declared session zone lies in its DST gap (rules disagree). */
        SESSION_ZONE_GAP
    }'''),
    ('''    public static Resolution side(Instant provenance, ZoneId zone, Instant cutover) {
        if (provenance == null) {''',
     '''    public static Resolution side(Instant provenance, ZoneId zone, Instant cutover) {
        return side(provenance, provenance, zone, cutover);
    }

    /**
     * As {@link #side(Instant, ZoneId, Instant)} for a column whose wall clock and stored instant are read apart
     * (decision 240, MySQL session-zoned columns): {@code provenance} is the wall clock as written, {@code stored} the
     * instant the database holds, which is what an already-UTC row wrote.
     */
    public static Resolution side(Instant provenance, Instant stored, ZoneId zone, Instant cutover) {
        if (provenance == null) {'''),
    ('''        boolean after = !provenance.isBefore(cutover);''', '''        boolean after = !stored.isBefore(cutover);'''),
])

t = 'sangam-ops/src/main/java/org/sangam/ops/utc/UtcConversionTool.java'
edit(t, [
    ('''import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;''', '''import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;'''),
    ('''import java.util.Optional;
import java.util.TreeMap;''', '''import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;'''),
    (''' * five engine columns Operaton declares {@code timestamp} are refused ({@link UtcInventory#MYSQL_SESSION_ZONED}).
 */''', ''' * five engine columns Operaton declares {@code timestamp} ({@link UtcInventory#MYSQL_SESSION_ZONED}) are converted only
 * with the run's declared historical session zone (owner decision 240): read under that zone (the wall clock as
 * written) and under UTC (the stored instant), guarded, written and reconciled in UTC, restored under that zone; a wall
 * clock in the session zone's DST overlap stays unresolved. Rollback, like apply, refuses while an engine lock is live.
 */'''),
    ('''    private final UtcConversionStore store;

    public UtcConversionTool(Transactions tx, Dialect dialect, AuditChain audit, UtcInventory inventory) {
        this.tx = Objects.requireNonNull(tx, "tx");''', '''    private final UtcConversionStore store;
    private final SessionZones sessions;

    public UtcConversionTool(Transactions tx, Dialect dialect, AuditChain audit, UtcInventory inventory) {
        this(tx, dialect, audit, inventory, SessionZones.of(dialect));
    }

    /** With the session zone access given (tests; production takes the dialect's). */
    UtcConversionTool(Transactions tx, Dialect dialect, AuditChain audit, UtcInventory inventory, SessionZones sessions) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.tx = Objects.requireNonNull(tx, "tx");'''),
    ('''    void check(UtcDataset d) {
        for (String col : d.columns()) {
            requireInstant(d.table(), col, "column");
        }
        requireInstant(d.table(), d.provenanceColumn(), "provenance column");''',
     '''    void check(UtcDataset d) {
        for (String col : d.columns()) {
            requireInstant(d, col, "column");
        }
        requireInstant(d, d.provenanceColumn(), "provenance column");
        if (d.sessionZone() != null) {
            if (sessions.zonedColumns().isEmpty()) {
                throw new IllegalArgumentException("a session zone is declared only for the MySQL/MariaDB session-zoned"
                        + " engine columns (decision 240); this dialect stores zone-aware values");
            }
            if (zonedColumns(d).isEmpty()) {
                throw new IllegalArgumentException("session zone " + d.sessionZone() + " is declared but no column of "
                        + d.table() + " in this dataset is session-zoned (decision 240)");
            }
        }'''),
    ('''    private void requireInstant(String table, String column, String what) {''',
     '''    private void requireInstant(UtcDataset d, String column, String what) {
        String table = d.table();'''),
    ('''        if (dialect == Dialect.MYSQL && UtcInventory.MYSQL_SESSION_ZONED.contains(table + "." + column)) {
            throw new IllegalArgumentException(what + " " + table + "." + column + " is a MySQL/MariaDB timestamp column:"
                    + " the server stored it converted from the writing session's time zone, which the tool cannot know"
                    + " for past writes; it is not converted on MySQL or MariaDB (decision 150)");
        }
    }''', '''        if (d.sessionZone() == null && sessions.zonedColumns().contains(table + "." + column)) {
            throw new IllegalArgumentException(what + " " + table + "." + column + " is a MySQL/MariaDB timestamp column:"
                    + " the server stored it converted from the writing session's time zone; declare that historical"
                    + " session zone on the dataset to convert it (decision 240)");
        }
    }

    /** Decision 240: the session-zoned columns among the dataset's read columns. */
    private Set<String> zonedColumns(UtcDataset d) {
        Set<String> out = new java.util.LinkedHashSet<>();
        for (String col : timeColumns(d)) {
            if (sessions.zonedColumns().contains(d.table() + "." + col)) {
                out.add(col);
            }
        }
        return out;
    }

    /** The row access of the dataset: with the declared session zone when it has session-zoned columns. */
    private UtcRows rows(Connection c, UtcDataset d, boolean tenantFiltered) {
        Set<String> zoned = d.sessionZone() == null ? Set.of() : zonedColumns(d);
        UtcRows.Session s = zoned.isEmpty() ? null
                : new UtcRows.Session(sessions, SessionZones.text(d.sessionZone()), zoned);
        return UtcRows.of(c, d.table(), timeColumns(d), tenantFiltered, s);
    }'''),
    ('''        UtcRows rows = tx.inTransaction(c -> UtcRows.of(c, dataset.table(), timeColumns(dataset),
                dataset.tenantId() != null));''', '''        UtcRows rows = tx.inTransaction(c -> rows(c, dataset, dataset.tenantId() != null));'''),
    ('''            audit.append(c, auditTenant, CHAIN_PREFIX + runId, EVENT_APPLY_STARTED, Map.of("runId", runId, "actor", actor,
                    "maintenanceRef", maintenanceRef, "resumed", r.state() == State.APPLYING));''',
     '''            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("runId", runId);
            payload.put("actor", actor);
            payload.put("maintenanceRef", maintenanceRef);
            payload.put("resumed", r.state() == State.APPLYING);
            payload.put("sessionZone", sessionZoneText(r.dataset()));
            audit.append(c, auditTenant, CHAIN_PREFIX + runId, EVENT_APPLY_STARTED, payload);'''),
    ('''        UtcRows rows = tx.inTransaction(c -> UtcRows.of(c, d.table(), timeColumns(d), d.tenantId() != null));''',
     '''        UtcRows rows = tx.inTransaction(c -> rows(c, d, d.tenantId() != null));'''),
    ('''        UtcRows rows = tx.inTransaction(c -> UtcRows.of(c, d.table(), timeColumns(d), false));
        long matched = 0;''', '''        UtcRows rows = tx.inTransaction(c -> rows(c, d, false));
        long matched = 0;'''),
    ('''        Instant now = row.get().values().get(cell.column());''', '''        Instant now = row.get().stored().get(cell.column());'''),
    ('''            RunRow r = store.findRun(c, runId, true).orElseThrow(() -> new IllegalArgumentException("no run " + runId));
            if (r.state() == State.DRY_RUN || r.state() == State.ROLLED_BACK) {
                throw new IllegalStateException("run " + runId + " is " + r.state() + ": nothing to roll back");
            }''', '''            RunRow r = store.findRun(c, runId, true).orElseThrow(() -> new IllegalArgumentException("no run " + runId));
            if (r.state() == State.DRY_RUN || r.state() == State.ROLLED_BACK) {
                throw new IllegalStateException("run " + runId + " is " + r.state() + ": nothing to roll back");
            }
            EngineLocks.requireNone(c, dialect, r.dataset().table());'''),
    ('''        UtcRows rows = tx.inTransaction(c -> UtcRows.of(c, d.table(), timeColumns(d), false));
        while (true) {
            int restored = tx.inTransaction(c -> {
                List<Cell> cells = store.cells(c, runId, CellStatus.CONVERTED, null, null, 500);
                Instant now = Jdbc.dbNow(c, dialect);
                for (Cell cell : cells) {
                    if (!rows.replace(c, UtcRows.Key.parse(cell.keyJson()), cell.column(), cell.converted(),
                            cell.original())) {''', '''        UtcRows rows = tx.inTransaction(c -> rows(c, d, false));
        Set<String> zoned = d.sessionZone() == null ? Set.of() : zonedColumns(d);
        while (true) {
            int restored = tx.inTransaction(c -> {
                EngineLocks.requireNone(c, dialect, d.table());
                List<Cell> cells = store.cells(c, runId, CellStatus.CONVERTED, null, null, 500);
                Instant now = Jdbc.dbNow(c, dialect);
                for (Cell cell : cells) {
                    UtcRows.Key key = UtcRows.Key.parse(cell.keyJson());
                    boolean ok = zoned.contains(cell.column())
                            ? rows.restoreWallClock(c, key, cell.column(), cell.converted(), cell.original())
                            : rows.replace(c, key, cell.column(), cell.converted(), cell.original());
                    if (!ok) {'''),
    ('''        Instant provenance = row.values().get(d.provenanceColumn());
        Optional<Cell> pc = provenance == null ? Optional.empty() : store.cell(c, d.table(), d.provenanceColumn(), keyHash);
        if (pc.isPresent() && pc.get().status() == CellStatus.CONVERTED) {
            provenance = pc.get().original(); // judged on the value as written, not as converted
        }
        Resolution side = WallClock.side(provenance, d.sourceZone(), d.cutover());''',
     '''        Instant provenance = row.values().get(d.provenanceColumn());
        Instant provenanceStored = row.stored().get(d.provenanceColumn());
        Optional<Cell> pc = provenance == null ? Optional.empty() : store.cell(c, d.table(), d.provenanceColumn(), keyHash);
        if (pc.isPresent() && pc.get().status() == CellStatus.CONVERTED) {
            provenance = pc.get().original(); // judged on the value as written, not as converted
            provenanceStored = provenance;
        }
        Resolution side = WallClock.side(provenance, provenanceStored, d.sourceZone(), d.cutover());'''),
    ('''            Resolution r = side.unresolved() != null ? side : WallClock.resolve(v, d.sourceZone());''',
     '''            Resolution r = side.unresolved() != null ? side : sessionCheck(d, col, v);
            if (r == null) {
                r = WallClock.resolve(v, d.sourceZone());
            }'''),
    ('''                if (!w.rows().replace(c, row.key(), col, v, r.instant())) {''',
     '''                if (!w.rows().replace(c, row.key(), col, row.stored().get(col), r.instant())) {'''),
    ('''    // ------------------------------------------------------------------ helpers
''', '''    // ------------------------------------------------------------------ helpers

    /**
     * Decision 240: a session-zoned wall clock that lies in a DST overlap (or gap) of the declared session zone is left
     * unresolved: the server's conversion of it is not one instant, so a rollback could not restore it exactly.
     *
     * @return the unresolved reason, or null to convert
     */
    private Resolution sessionCheck(UtcDataset d, String col, Instant wall) {
        if (d.sessionZone() == null || !sessions.zonedColumns().contains(d.table() + "." + col)) {
            return null;
        }
        int offsets = d.sessionZone().getRules().getValidOffsets(LocalDateTime.ofInstant(wall, ZoneOffset.UTC)).size();
        if (offsets == 1) {
            return null;
        }
        return new Resolution(null, offsets == 0 ? WallClock.Unresolved.SESSION_ZONE_GAP
                : WallClock.Unresolved.SESSION_ZONE_OVERLAP);
    }

    private static String sessionZoneText(UtcDataset d) {
        return d.sessionZone() == null ? null : SessionZones.text(d.sessionZone());
    }
'''),
    ('''            r.put("tenantId", d.tenantId());
            r.put("rowsRead", rowsRead);''', '''            r.put("tenantId", d.tenantId());
            r.put("sessionZone", sessionZoneText(d));
            r.put("rowsRead", rowsRead);'''),
    ('''        for (String k : List.of("table", "columns", "provenanceColumn", "sourceZone", "zoneRulesVersion", "cutover",
                "rowsRead",''', '''        for (String k : List.of("table", "columns", "provenanceColumn", "sourceZone", "zoneRulesVersion", "cutover",
                "sessionZone", "rowsRead",'''),
])
print('ok')

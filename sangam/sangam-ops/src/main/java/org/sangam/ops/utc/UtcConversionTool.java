// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.utc;

import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.Json;
import org.sangam.ops.utc.UtcConversionStore.Cell;
import org.sangam.ops.utc.UtcConversionStore.CellStatus;
import org.sangam.ops.utc.UtcConversionStore.Counts;
import org.sangam.ops.utc.UtcConversionStore.RunRow;
import org.sangam.ops.utc.UtcConversionStore.State;
import org.sangam.ops.utc.UtcInventory.ColumnClass;
import org.sangam.ops.utc.WallClock.Resolution;

/**
 * M-114 (owner decisions 150 and 203): the versioned, idempotent, operator-run tool that converts existing timestamp
 * values written as source-zone wall clocks into UTC instants. Liquibase manages only the schema (its run and snapshot
 * tables, changesets 0045-01/02); this tool changes rows, and its run records are the versioned changesets decision 150
 * asks for.
 * <ol>
 *   <li>{@link #dryRun}: records a run in state DRY_RUN with counts, representative before/after samples, precision
 *       check and every unresolved value; changes no row. A run is applied only after its own dry run.</li>
 *   <li>{@link #apply}: in primary-key batches, each one transaction with its resume key; every converted cell keeps its
 *       original value in {@code OPS_UTC_CELL} (the recovery snapshot) and can never be converted again by any run;
 *       a row whose value changed under the tool fails the batch (writers and schedulers must be stopped: the operator
 *       names the maintenance window, recorded on the run). The apply and every batch also refuse while an engine job
 *       or external task holds an unexpired lock ({@link EngineLocks}).</li>
 *   <li>{@link #reconcile}: every converted cell is read back and compared, and its snapshot is re-derived (recovery
 *       verification); a run is complete (RECONCILED) only with no mismatch.</li>
 *   <li>{@link #rollback}: restores every original value from the snapshot, only where the converted value is still
 *       there.</li>
 * </ol>
 * Rows are classified per dataset from a provenance column against the operator's cutover ({@link WallClock#side});
 * DST gaps and overlaps, ambiguous cutover rows and rows without provenance are recorded unresolved, never chosen.
 * Only {@link ColumnClass#EVENT_INSTANT} columns of the {@link UtcInventory} are converted: date-only, local business,
 * protected (hash, digest or signature in the table) and tool columns are refused. The engine's {@code ACT_*} columns
 * join through the engine owner's inventory rows ({@link UtcInventory#productAndEngine()}); on MySQL and MariaDB the
 * five engine columns Operaton declares {@code timestamp} ({@link UtcInventory#MYSQL_SESSION_ZONED}) are converted only
 * with the run's declared historical session zone (owner decision 240): read under that zone (the wall clock as
 * written) and under UTC (the stored instant), guarded, written and reconciled in UTC, restored under that zone; a wall
 * clock in the session zone's DST overlap stays unresolved. Rollback, like apply, refuses while an engine lock is live.
 * <p>
 * Engine-work gate (integrator ruling 2026-11-13, option C; {@link UtcGate}): apply and rollback close the gate in their
 * own committed transaction before the run moves to APPLYING or ROLLING_BACK (engine acquisition and fetch then do no
 * work), keep {@link EngineLocks} as the second check, and reopen the gate in the transaction that reaches APPLIED or
 * ROLLED_BACK. A refusal before any row changed reopens it; a run left APPLYING or ROLLING_BACK keeps it closed until
 * that run is resumed or rolled back (owner decision 262: no forced reopen).
 */
public final class UtcConversionTool {

    public static final String TOOL_VERSION = "sangam-utc-conversion/1";
    public static final String CHAIN_PREFIX = "utc-conversion:";
    public static final String EVENT_DRY_RUN = "UTC_CONVERSION_DRY_RUN";
    public static final String EVENT_APPLY_STARTED = "UTC_CONVERSION_APPLY_STARTED";
    public static final String EVENT_APPLIED = "UTC_CONVERSION_APPLIED";
    public static final String EVENT_RECONCILED = "UTC_CONVERSION_RECONCILED";
    public static final String EVENT_ROLLED_BACK = "UTC_CONVERSION_ROLLED_BACK";
    static final int MAX_LISTED = 100;
    static final int SAMPLES = 20;
    private static final Pattern RUN_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}");

    /**
     * A run as stored.
     *
     * @param state            DRY_RUN, APPLYING (also an interrupted apply), APPLIED, RECONCILED (complete),
     *                         ROLLING_BACK (also an interrupted rollback) or ROLLED_BACK
     * @param rowsRead         rows read by the dry run, then by the apply
     * @param converted        cells converted (snapshot held)
     * @param alreadyUtc       rows written after the cutover, left unchanged
     * @param alreadyConverted cells converted before by any run, left unchanged
     * @param unresolved       cells left unchanged and recorded unresolved
     * @param matched          cells reconciled
     * @param mismatched       cells whose value or snapshot did not reconcile
     * @param restored         cells restored by a rollback
     * @param report           the dry-run or reconciliation report (JSON)
     */
    public record Run(String runId, String toolVersion, UtcDataset dataset, String state, long rowsRead, long converted,
                      long alreadyUtc, long alreadyConverted, long unresolved, long matched, long mismatched,
                      long restored, String maintenanceRef, String report) {
    }

    private final Transactions tx;
    private final Dialect dialect;
    private final AuditChain audit;
    private final UtcInventory inventory;
    private final UtcConversionStore store;
    private final SessionZones sessions;

    public UtcConversionTool(Transactions tx, Dialect dialect, AuditChain audit, UtcInventory inventory) {
        this(tx, dialect, audit, inventory, SessionZones.of(dialect));
    }

    /** With the session zone access given (tests; production takes the dialect's). */
    UtcConversionTool(Transactions tx, Dialect dialect, AuditChain audit, UtcInventory inventory, SessionZones sessions) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.tx = Objects.requireNonNull(tx, "tx");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.store = new UtcConversionStore(dialect);
    }

    public Optional<Run> run(String runId) {
        return tx.inTransaction(c -> store.findRun(c, runId, false)).map(UtcConversionTool::view);
    }

    /** Checks the dataset against the inventory; every refusal names the column and its class. */
    void check(UtcDataset d) {
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
        }
        for (UtcInventory.Column col : inventory.columnsOf(d.table())) {
            if (col.cls() == ColumnClass.PROTECTED || col.cls() == ColumnClass.TOOL) {
                throw new IllegalArgumentException("table " + d.table() + " is " + col.cls() + " (" + col.column()
                        + "): its rows are not converted without an explicit preservation strategy (decision 150)");
            }
        }
    }

    private void requireInstant(UtcDataset d, String column, String what) {
        String table = d.table();
        UtcInventory.Column col = inventory.find(table, column).orElseThrow(() -> new IllegalArgumentException(
                what + " " + table + "." + column + " is not in the timestamp inventory"));
        if (col.cls() != ColumnClass.EVENT_INSTANT) {
            throw new IllegalArgumentException(what + " " + table + "." + column + " is " + col.cls()
                    + ": only EVENT_INSTANT columns are converted (date-only and local business values never)");
        }
        if (d.sessionZone() == null && sessions.zonedColumns().contains(table + "." + column)) {
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
    }

    // ------------------------------------------------------------------ dry run

    /** Records run {@code runId} in state DRY_RUN with its report; changes no row of the dataset. */
    public Run dryRun(String runId, UtcDataset dataset, String actor, String auditTenant) {
        requireRunId(runId);
        Objects.requireNonNull(dataset, "dataset");
        requireActor(actor);
        AuditChain.requireTenant(auditTenant, EVENT_DRY_RUN);
        check(dataset);
        UtcRows rows = tx.inTransaction(c -> rows(c, dataset, dataset.tenantId() != null));
        Tally t = new Tally();
        UtcRows.Key after = null;
        while (true) {
            UtcRows.Key from = after;
            List<UtcRows.Row> batch = tx.inTransaction(c -> rows.batch(c, dataset.tenantId(), from, 500));
            if (batch.isEmpty()) {
                break;
            }
            tx.inTransaction(c -> {
                for (UtcRows.Row r : batch) {
                    visit(c, dataset, r, null, t);
                }
                return null;
            });
            after = batch.get(batch.size() - 1).key();
        }
        Map<String, Object> report = t.report(dataset);
        RunRow row = new RunRow(runId, TOOL_VERSION, dataset, dataset.fingerprint(), State.DRY_RUN, t.counts(), null,
                Json.write(report), null, actor, 1);
        tx.inTransaction(c -> {
            if (store.findRun(c, runId, false).isPresent()) {
                throw new IllegalArgumentException("run " + runId + " exists: a run id is used once");
            }
            store.insertRun(c, row, Jdbc.dbNow(c, dialect));
            audit.append(c, auditTenant, CHAIN_PREFIX + runId, EVENT_DRY_RUN, auditPayload(row, actor, report));
            return null;
        });
        return view(row);
    }

    // ------------------------------------------------------------------ apply

    /**
     * Applies run {@code runId} after its dry run, or resumes an interrupted apply.
     *
     * @param maintenanceRef the operator's reference to the maintenance window in which writers and schedulers of the
     *                       table are stopped (recorded; required)
     */
    public Run apply(String runId, int batchSize, String maintenanceRef, String actor, String auditTenant) {
        requireActor(actor);
        AuditChain.requireTenant(auditTenant, EVENT_APPLY_STARTED);
        requireMaintenance(maintenanceRef);
        if (batchSize < 1 || batchSize > 10_000) {
            throw new IllegalArgumentException("batchSize is 1 to 10000");
        }
        // The gate closes in its own committed transaction before the run moves to APPLYING (UtcGate).
        tx.inTransaction(c -> {
            applicable(c, runId);
            UtcGate.close(c, dialect, audit, auditTenant, runId, actor, "apply");
            return null;
        });
        try {
            return applyBehindGate(runId, batchSize, maintenanceRef, actor, auditTenant);
        } catch (RuntimeException e) {
            afterFailure(runId, actor, auditTenant, "apply", e);
            throw e;
        }
    }

    /** The run under {@code runId} if it may be applied (a dry run, or an interrupted apply); refuses otherwise. */
    private RunRow applicable(Connection c, String runId) {
        RunRow r = store.findRun(c, runId, true).orElseThrow(() -> new IllegalArgumentException("no run " + runId));
        if (r.state() != State.DRY_RUN && r.state() != State.APPLYING) {
            throw new IllegalStateException("run " + runId + " is " + r.state() + ": only a dry-run or interrupted"
                    + " run is applied");
        }
        if (!TOOL_VERSION.equals(r.toolVersion()) || !r.dataset().fingerprint().equals(r.fingerprint())) {
            throw new IllegalStateException("run " + runId + " was dry-run by " + r.toolVersion() + " with"
                    + " fingerprint " + r.fingerprint() + "; the tool or the zone rules changed: dry-run a new run");
        }
        check(r.dataset());
        List<String> busy = store.otherRuns(c, r.dataset().table(), runId, State.APPLYING, State.ROLLING_BACK);
        if (!busy.isEmpty()) {
            throw new IllegalStateException("table " + r.dataset().table() + " is being changed by run " + busy);
        }
        return r;
    }

    private Run applyBehindGate(String runId, int batchSize, String maintenanceRef, String actor, String auditTenant) {
        RunRow started = tx.inTransaction(c -> {
            RunRow r = applicable(c, runId);
            EngineLocks.requireNone(c, dialect, r.dataset().table()); // second check, behind the closed gate
            Counts counts = r.state() == State.DRY_RUN ? Counts.ZERO : r.counts();
            RunRow next = new RunRow(r.runId(), r.toolVersion(), r.dataset(), r.fingerprint(), State.APPLYING, counts,
                    r.lastKey(), r.reportJson(), maintenanceRef, r.createdBy(), r.version());
            store.updateRun(c, next, Jdbc.dbNow(c, dialect));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("runId", runId);
            payload.put("actor", actor);
            payload.put("maintenanceRef", maintenanceRef);
            payload.put("resumed", r.state() == State.APPLYING);
            payload.put("sessionZone", sessionZoneText(r.dataset()));
            audit.append(c, auditTenant, CHAIN_PREFIX + runId, EVENT_APPLY_STARTED, payload);
            return new RunRow(next.runId(), next.toolVersion(), next.dataset(), next.fingerprint(), next.state(),
                    next.counts(), next.lastKey(), next.reportJson(), next.maintenanceRef(), next.createdBy(),
                    next.version() + 1);
        });
        UtcDataset d = started.dataset();
        UtcRows rows = tx.inTransaction(c -> rows(c, d, d.tenantId() != null));
        while (true) {
            boolean more = tx.inTransaction(c -> {
                RunRow r = store.findRun(c, runId, true).orElseThrow();
                if (r.state() != State.APPLYING) {
                    throw new IllegalStateException("run " + runId + " left APPLYING concurrently: " + r.state());
                }
                EngineLocks.requireNone(c, dialect, d.table());
                UtcRows.Key after = r.lastKey() == null ? null : UtcRows.Key.parse(r.lastKey());
                List<UtcRows.Row> batch = rows.batch(c, d.tenantId(), after, batchSize);
                if (batch.isEmpty()) {
                    return false;
                }
                Tally t = new Tally();
                Instant now = Jdbc.dbNow(c, dialect);
                for (UtcRows.Row row : batch) {
                    visit(c, d, row, new Writer(runId, rows, now), t);
                }
                store.updateRun(c, new RunRow(r.runId(), r.toolVersion(), d, r.fingerprint(), State.APPLYING,
                        r.counts().plus(t.counts()), batch.get(batch.size() - 1).key().json(), r.reportJson(),
                        r.maintenanceRef(), r.createdBy(), r.version()), now);
                return true;
            });
            if (!more) {
                break;
            }
        }
        RunRow done = tx.inTransaction(c -> {
            RunRow r = store.findRun(c, runId, true).orElseThrow();
            RunRow next = new RunRow(r.runId(), r.toolVersion(), d, r.fingerprint(), State.APPLIED, r.counts(),
                    r.lastKey(), r.reportJson(), r.maintenanceRef(), r.createdBy(), r.version());
            store.updateRun(c, next, Jdbc.dbNow(c, dialect));
            audit.append(c, auditTenant, CHAIN_PREFIX + runId, EVENT_APPLIED, counts(runId, actor, r.counts()));
            UtcGate.open(c, dialect, audit, auditTenant, runId, actor, "applied");
            return next;
        });
        return view(done);
    }

    // ------------------------------------------------------------------ reconcile

    /** Reads back every converted cell of the run and re-derives its snapshot; RECONCILED only without mismatch. */
    public Run reconcile(String runId, String actor, String auditTenant) {
        requireActor(actor);
        AuditChain.requireTenant(auditTenant, EVENT_RECONCILED);
        RunRow r0 = tx.inTransaction(c -> store.findRun(c, runId, false))
                .orElseThrow(() -> new IllegalArgumentException("no run " + runId));
        if (r0.state() != State.APPLIED && r0.state() != State.RECONCILED) {
            throw new IllegalStateException("run " + runId + " is " + r0.state() + ": only an applied run is reconciled");
        }
        UtcDataset d = r0.dataset();
        UtcRows rows = tx.inTransaction(c -> rows(c, d, false));
        long matched = 0;
        long mismatched = 0;
        List<String> problems = new ArrayList<>();
        String afterHash = null;
        String afterColumn = null;
        while (true) {
            String h = afterHash;
            String col = afterColumn;
            List<Cell> cells = tx.inTransaction(c -> store.cells(c, runId, CellStatus.CONVERTED, h, col, 500));
            if (cells.isEmpty()) {
                break;
            }
            for (Cell cell : cells) {
                String problem = tx.inTransaction(c -> reconcileCell(c, d, rows, cell));
                if (problem == null) {
                    matched++;
                } else {
                    mismatched++;
                    if (problems.size() < MAX_LISTED) {
                        problems.add(cell.column() + " " + cell.keyJson() + ": " + problem);
                    }
                }
            }
            afterHash = cells.get(cells.size() - 1).keyHash();
            afterColumn = cells.get(cells.size() - 1).column();
        }
        long m = matched;
        long mm = mismatched;
        RunRow done = tx.inTransaction(c -> {
            RunRow r = store.findRun(c, runId, true).orElseThrow();
            long unresolved = store.countCells(c, runId, CellStatus.UNRESOLVED);
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("matched", m);
            report.put("mismatched", mm);
            report.put("unresolvedCells", unresolved);
            report.put("mismatches", problems);
            Counts n = r.counts();
            RunRow next = new RunRow(r.runId(), r.toolVersion(), d, r.fingerprint(),
                    mm == 0 ? State.RECONCILED : State.APPLIED, new Counts(n.rowsRead(), n.converted(), n.alreadyUtc(),
                    n.alreadyConverted(), n.unresolved(), m, mm, n.restored()), r.lastKey(), Json.write(report),
                    r.maintenanceRef(), r.createdBy(), r.version());
            store.updateRun(c, next, Jdbc.dbNow(c, dialect));
            Map<String, Object> payload = new LinkedHashMap<>(report);
            payload.put("runId", runId);
            payload.put("actor", actor);
            payload.put("complete", mm == 0);
            audit.append(c, auditTenant, CHAIN_PREFIX + runId, EVENT_RECONCILED, payload);
            return next;
        });
        return view(done);
    }

    private String reconcileCell(Connection c, UtcDataset d, UtcRows rows, Cell cell) {
        Resolution again = WallClock.resolve(cell.original(), d.sourceZone());
        if (!again.resolved() || !again.instant().equals(cell.converted())) {
            return "snapshot does not re-derive the converted value (" + cell.original() + " -> " + again.instant()
                    + ", stored " + cell.converted() + ")";
        }
        Optional<UtcRows.Row> row = rows.find(c, UtcRows.Key.parse(cell.keyJson()));
        if (row.isEmpty()) {
            return "row no longer exists";
        }
        Instant now = row.get().stored().get(cell.column());
        return cell.converted().equals(now) ? null : "value is " + now + ", converted " + cell.converted();
    }

    // ------------------------------------------------------------------ rollback

    /** Restores every original value of the run from its snapshot; resumes an interrupted rollback. */
    public Run rollback(String runId, String maintenanceRef, String actor, String auditTenant) {
        requireActor(actor);
        AuditChain.requireTenant(auditTenant, EVENT_ROLLED_BACK);
        requireMaintenance(maintenanceRef);
        // The gate closes in its own committed transaction before the run moves to ROLLING_BACK (UtcGate, ruling item 4).
        tx.inTransaction(c -> {
            rollbackable(c, runId);
            UtcGate.close(c, dialect, audit, auditTenant, runId, actor, "rollback");
            return null;
        });
        try {
            return rollbackBehindGate(runId, maintenanceRef, actor, auditTenant);
        } catch (RuntimeException e) {
            afterFailure(runId, actor, auditTenant, "rollback", e);
            throw e;
        }
    }

    /**
     * After an apply or rollback failed behind the gate. When the run is not left APPLYING or ROLLING_BACK, no row of the
     * dataset changed (a refusal before the state change): the gate this run closed is reopened, audited. A run left
     * APPLYING or ROLLING_BACK holds part of its table converted, so the gate stays CLOSED: engine work on the table
     * waits until the same run is resumed or rolled back and reaches APPLIED or ROLLED_BACK, which reopens it; there is
     * no forced reopen (owner decision 262). The original failure is never masked.
     */
    private void afterFailure(String runId, String actor, String auditTenant, String step, RuntimeException failure) {
        try {
            tx.inTransaction(c -> {
                State state = store.findRun(c, runId, true).map(RunRow::state).orElse(null);
                if (state == State.APPLYING || state == State.ROLLING_BACK) {
                    return null;
                }
                if (UtcGate.read(c).filter(g -> UtcGate.CLOSED.equals(g.state()) && runId.equals(g.runId())).isPresent()) {
                    UtcGate.open(c, dialect, audit, auditTenant, runId, actor, step + " refused: " + failure.getMessage());
                }
                return null;
            });
        } catch (RuntimeException reopen) {
            failure.addSuppressed(reopen);
        }
    }

    private RunRow rollbackable(Connection c, String runId) {
        RunRow r = store.findRun(c, runId, true).orElseThrow(() -> new IllegalArgumentException("no run " + runId));
        if (r.state() == State.DRY_RUN || r.state() == State.ROLLED_BACK) {
            throw new IllegalStateException("run " + runId + " is " + r.state() + ": nothing to roll back");
        }
        return r;
    }

    private Run rollbackBehindGate(String runId, String maintenanceRef, String actor, String auditTenant) {
        RunRow started = tx.inTransaction(c -> {
            RunRow r = rollbackable(c, runId);
            EngineLocks.requireNone(c, dialect, r.dataset().table()); // second check, behind the closed gate
            RunRow next = new RunRow(r.runId(), r.toolVersion(), r.dataset(), r.fingerprint(), State.ROLLING_BACK,
                    r.counts(), r.lastKey(), r.reportJson(), maintenanceRef, r.createdBy(), r.version());
            store.updateRun(c, next, Jdbc.dbNow(c, dialect));
            return next;
        });
        UtcDataset d = started.dataset();
        UtcRows rows = tx.inTransaction(c -> rows(c, d, false));
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
                    if (!ok) {
                        throw new IllegalStateException("cell " + cell.column() + " " + cell.keyJson() + " no longer holds"
                                + " its converted value " + cell.converted() + ": not overwritten; resolve by hand,"
                                + " then resume the rollback");
                    }
                    store.markRestored(c, d.table(), cell, now);
                }
                if (!cells.isEmpty()) {
                    RunRow r = store.findRun(c, runId, true).orElseThrow();
                    Counts n = r.counts();
                    store.updateRun(c, new RunRow(r.runId(), r.toolVersion(), d, r.fingerprint(), State.ROLLING_BACK,
                            new Counts(n.rowsRead(), n.converted(), n.alreadyUtc(), n.alreadyConverted(), n.unresolved(),
                                    n.matched(), n.mismatched(), n.restored() + cells.size()), r.lastKey(),
                            r.reportJson(), r.maintenanceRef(), r.createdBy(), r.version()), now);
                }
                return cells.size();
            });
            if (restored == 0) {
                break;
            }
        }
        RunRow done = tx.inTransaction(c -> {
            RunRow r = store.findRun(c, runId, true).orElseThrow();
            RunRow next = new RunRow(r.runId(), r.toolVersion(), d, r.fingerprint(), State.ROLLED_BACK, r.counts(),
                    r.lastKey(), r.reportJson(), r.maintenanceRef(), r.createdBy(), r.version());
            store.updateRun(c, next, Jdbc.dbNow(c, dialect));
            audit.append(c, auditTenant, CHAIN_PREFIX + runId, EVENT_ROLLED_BACK, counts(runId, actor, r.counts()));
            UtcGate.open(c, dialect, audit, auditTenant, runId, actor, "rolled back");
            return next;
        });
        return view(done);
    }

    // ------------------------------------------------------------------ one row

    /** Writes of an apply; null in a dry run. */
    private record Writer(String runId, UtcRows rows, Instant now) {
    }

    /** Classifies one row and, with a writer, converts its cells; counts into {@code t}. */
    private void visit(Connection c, UtcDataset d, UtcRows.Row row, Writer w, Tally t) {
        t.rowsRead++;
        String keyHash = row.key().hash();
        Instant provenance = row.values().get(d.provenanceColumn());
        Instant provenanceStored = row.stored().get(d.provenanceColumn());
        Optional<Cell> pc = provenance == null ? Optional.empty() : store.cell(c, d.table(), d.provenanceColumn(), keyHash);
        if (pc.isPresent() && pc.get().status() == CellStatus.CONVERTED) {
            provenance = pc.get().original(); // judged on the value as written, not as converted
            provenanceStored = provenance;
        }
        Resolution side = WallClock.side(provenance, provenanceStored, d.sourceZone(), d.cutover());
        if (side.instant() == null && side.unresolved() == null) {
            t.alreadyUtc++;
            return;
        }
        for (String col : d.columns()) {
            Instant v = row.values().get(col);
            if (v == null) {
                continue;
            }
            Optional<Cell> existing = col.equals(d.provenanceColumn()) ? pc : store.cell(c, d.table(), col, keyHash);
            if (existing.isPresent() && existing.get().status() == CellStatus.CONVERTED) {
                t.alreadyConverted++;
                continue;
            }
            Resolution r = side.unresolved() != null ? side : sessionCheck(d, col, v);
            if (r == null) {
                r = WallClock.resolve(v, d.sourceZone());
            }
            if (!r.resolved()) {
                t.unresolved(row.key(), col, r.unresolved().name(), v);
                if (w != null) {
                    store.putUnresolved(c, d.table(), col, row.key(), w.runId(), r.unresolved().name(), v, w.now());
                }
                continue;
            }
            t.converted(row.key(), col, v, r.instant());
            if (w != null) {
                if (!w.rows().replace(c, row.key(), col, row.stored().get(col), r.instant())) {
                    throw new IllegalStateException("row " + row.key().json() + " of " + d.table() + " changed during the"
                            + " conversion: stop its writers and schedulers, then resume the run");
                }
                store.putConverted(c, d.table(), col, row.key(), w.runId(), v, r.instant(), w.now());
            }
        }
    }

    /** Counters and report material of a pass. */
    private static final class Tally {
        long rowsRead;
        long converted;
        long alreadyUtc;
        long alreadyConverted;
        long unresolved;
        long precisionChanges;
        long subSecond;
        final Map<String, Long> reasons = new TreeMap<>();
        final List<Map<String, Object>> samples = new ArrayList<>();
        final List<Map<String, Object>> unresolvedList = new ArrayList<>();

        void converted(UtcRows.Key key, String col, Instant before, Instant after) {
            converted++;
            if (before.getNano() != 0) {
                subSecond++;
            }
            if (before.getNano() != after.getNano()) {
                precisionChanges++;
            }
            if (samples.size() < SAMPLES) {
                samples.add(Map.of("key", key.json(), "column", col, "before", before.toString(),
                        "after", after.toString()));
            }
        }

        void unresolved(UtcRows.Key key, String col, String reason, Instant value) {
            unresolved++;
            reasons.merge(reason, 1L, Long::sum);
            if (unresolvedList.size() < MAX_LISTED) {
                unresolvedList.add(Map.of("key", key.json(), "column", col, "reason", reason,
                        "value", value.toString()));
            }
        }

        Counts counts() {
            return new Counts(rowsRead, converted, alreadyUtc, alreadyConverted, unresolved, 0, 0, 0);
        }

        Map<String, Object> report(UtcDataset d) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("table", d.table());
            r.put("columns", d.columns());
            r.put("provenanceColumn", d.provenanceColumn());
            r.put("sourceZone", d.sourceZone().getId());
            r.put("zoneRulesVersion", UtcDataset.rulesVersion(d.sourceZone()));
            r.put("cutover", d.cutover().toString());
            r.put("tenantId", d.tenantId());
            r.put("sessionZone", sessionZoneText(d));
            r.put("rowsRead", rowsRead);
            r.put("toConvert", converted);
            r.put("alreadyUtc", alreadyUtc);
            r.put("alreadyConverted", alreadyConverted);
            r.put("unresolved", unresolved);
            r.put("unresolvedByReason", reasons);
            r.put("unresolvedValues", unresolvedList);
            r.put("samples", samples);
            r.put("subSecondValues", subSecond);
            r.put("precisionChanges", precisionChanges);
            return r;
        }
    }

    // ------------------------------------------------------------------ helpers

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

    private static List<String> timeColumns(UtcDataset d) {
        List<String> out = new ArrayList<>(d.columns());
        if (!out.contains(d.provenanceColumn())) {
            out.add(d.provenanceColumn());
        }
        return out;
    }

    private static Map<String, Object> auditPayload(RunRow r, String actor, Map<String, Object> report) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("runId", r.runId());
        p.put("actor", actor);
        p.put("toolVersion", r.toolVersion());
        p.put("fingerprint", r.fingerprint());
        for (String k : List.of("table", "columns", "provenanceColumn", "sourceZone", "zoneRulesVersion", "cutover",
                "sessionZone", "rowsRead", "toConvert", "alreadyUtc", "alreadyConverted", "unresolved", "unresolvedByReason")) {
            p.put(k, report.get(k));
        }
        return p;
    }

    private static Map<String, Object> counts(String runId, String actor, Counts n) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("runId", runId);
        p.put("actor", actor);
        p.put("rowsRead", n.rowsRead());
        p.put("converted", n.converted());
        p.put("alreadyUtc", n.alreadyUtc());
        p.put("alreadyConverted", n.alreadyConverted());
        p.put("unresolved", n.unresolved());
        p.put("restored", n.restored());
        return p;
    }

    private static Run view(RunRow r) {
        Counts n = r.counts();
        return new Run(r.runId(), r.toolVersion(), r.dataset(), r.state().name(), n.rowsRead(), n.converted(),
                n.alreadyUtc(), n.alreadyConverted(), n.unresolved(), n.matched(), n.mismatched(), n.restored(),
                r.maintenanceRef(), r.reportJson());
    }

    private static void requireRunId(String runId) {
        if (runId == null || !RUN_ID.matcher(runId).matches()) {
            throw new IllegalArgumentException("run id is 1 to 64 letters, digits, '.', '_', ':' or '-': " + runId);
        }
    }

    private static void requireActor(String actor) {
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("actor is required");
        }
    }

    private static void requireMaintenance(String ref) {
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException("maintenanceRef is required: name the window in which the table's writers"
                    + " and schedulers are stopped (decision 150)");
        }
    }
}

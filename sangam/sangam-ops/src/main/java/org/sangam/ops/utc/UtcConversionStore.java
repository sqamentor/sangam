// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.utc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.integration.connector.Json;

/**
 * Run records ({@code OPS_UTC_RUN}) and cell snapshots ({@code OPS_UTC_CELL}) of the UTC conversion tool (changesets
 * 0045-01/02, and the declared session zone of decision 240, alter 0045-09). Every method works on the caller's
 * connection and never commits.
 */
final class UtcConversionStore {

    /** Lifecycle of a run. */
    enum State { DRY_RUN, APPLYING, APPLIED, RECONCILED, ROLLING_BACK, ROLLED_BACK }

    /** A cell's state: converted (snapshot held), restored by a rollback, or left unchanged and unresolved. */
    enum CellStatus { CONVERTED, RESTORED, UNRESOLVED }

    /** The counters of a run (see {@link UtcConversionTool.Run}). */
    record Counts(long rowsRead, long converted, long alreadyUtc, long alreadyConverted, long unresolved, long matched,
                  long mismatched, long restored) {
        static final Counts ZERO = new Counts(0, 0, 0, 0, 0, 0, 0, 0);

        Counts plus(Counts d) {
            return new Counts(rowsRead + d.rowsRead, converted + d.converted, alreadyUtc + d.alreadyUtc,
                    alreadyConverted + d.alreadyConverted, unresolved + d.unresolved, matched + d.matched,
                    mismatched + d.mismatched, restored + d.restored);
        }
    }

    /** One stored run. */
    record RunRow(String runId, String toolVersion, UtcDataset dataset, String fingerprint, State state, Counts counts,
                  String lastKey, String reportJson, String maintenanceRef, String createdBy, long version) {
    }

    /** One stored cell. */
    record Cell(String column, String keyHash, String keyJson, String runId, CellStatus status, String reason,
                Instant original, Instant converted) {
    }

    private static final String RUN_COLUMNS = "run_id, tool_version, table_name, columns_json, provenance_column,"
            + " tenant_filter, source_zone, cutover_at, fingerprint, state, rows_read, converted, already_utc,"
            + " already_converted, unresolved, matched, mismatched, restored, last_key, report_json, maintenance_ref,"
            + " created_by, version, session_zone";
    private static final String CELL_COLUMNS = "column_name, key_hash, key_json, run_id, status, reason, original_value,"
            + " converted_value";

    private final Dialect dialect;

    UtcConversionStore(Dialect dialect) {
        this.dialect = dialect;
    }

    void insertRun(Connection c, RunRow r, Instant now) {
        UtcDataset d = r.dataset();
        Counts n = r.counts();
        Jdbc.update(c, "INSERT INTO OPS_UTC_RUN(" + RUN_COLUMNS + ", created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?,"
                        + " ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                r.runId(), r.toolVersion(), d.table(), Json.write(d.columns()), d.provenanceColumn(), d.tenantId(),
                d.sourceZone().getId(), d.cutover(), r.fingerprint(), r.state().name(), n.rowsRead(), n.converted(),
                n.alreadyUtc(), n.alreadyConverted(), n.unresolved(), n.matched(), n.mismatched(), n.restored(),
                r.lastKey(), r.reportJson(), r.maintenanceRef(), r.createdBy(), r.version(),
                d.sessionZone() == null ? null : SessionZones.text(d.sessionZone()), now, now);
    }

    Optional<RunRow> findRun(Connection c, String runId, boolean lock) {
        String sql = lock ? dialect.lockingSelect(RUN_COLUMNS, "OPS_UTC_RUN", "run_id = ?")
                : "SELECT " + RUN_COLUMNS + " FROM OPS_UTC_RUN WHERE run_id = ?";
        return Jdbc.one(c, sql, UtcConversionStore::mapRun, runId);
    }

    /** Runs of {@code table} in {@code states} other than {@code runId}. */
    List<String> otherRuns(Connection c, String table, String runId, State... states) {
        List<String> out = new ArrayList<>();
        for (State s : states) {
            out.addAll(Jdbc.query(c, "SELECT run_id FROM OPS_UTC_RUN WHERE table_name = ? AND state = ? AND run_id <> ?",
                    rs -> rs.getString(1), table, s.name(), runId));
        }
        return out;
    }

    /** Writes state, counters, resume key and maintenance reference; refused when the version moved. */
    void updateRun(Connection c, RunRow r, Instant now) {
        Counts n = r.counts();
        int updated = Jdbc.update(c, "UPDATE OPS_UTC_RUN SET state = ?, rows_read = ?, converted = ?, already_utc = ?,"
                        + " already_converted = ?, unresolved = ?, matched = ?, mismatched = ?, restored = ?, last_key = ?,"
                        + " report_json = ?, maintenance_ref = ?, updated_at = ?, version = ? WHERE run_id = ? AND version = ?",
                r.state().name(), n.rowsRead(), n.converted(), n.alreadyUtc(), n.alreadyConverted(), n.unresolved(),
                n.matched(), n.mismatched(), n.restored(), r.lastKey(), r.reportJson(), r.maintenanceRef(), now,
                r.version() + 1, r.runId(), r.version());
        if (updated != 1) {
            throw new IllegalStateException("run " + r.runId() + " was changed concurrently");
        }
    }

    Optional<Cell> cell(Connection c, String table, String column, String keyHash) {
        return Jdbc.one(c, "SELECT " + CELL_COLUMNS + " FROM OPS_UTC_CELL WHERE table_name = ? AND column_name = ?"
                + " AND key_hash = ?", UtcConversionStore::mapCell, table, column, keyHash);
    }

    /** Records a conversion; a cell left RESTORED or UNRESOLVED by an earlier run is taken over. */
    void putConverted(Connection c, String table, String column, UtcRows.Key key, String runId, Instant original,
                      Instant converted, Instant now) {
        put(c, table, column, key, runId, CellStatus.CONVERTED, null, original, converted, now);
    }

    void putUnresolved(Connection c, String table, String column, UtcRows.Key key, String runId, String reason,
                       Instant original, Instant now) {
        put(c, table, column, key, runId, CellStatus.UNRESOLVED, reason, original, null, now);
    }

    private void put(Connection c, String table, String column, UtcRows.Key key, String runId, CellStatus status,
                     String reason, Instant original, Instant converted, Instant now) {
        String hash = key.hash();
        boolean inserted = Jdbc.insertIfAbsent(c, dialect, "INSERT INTO OPS_UTC_CELL(table_name, column_name, key_hash,"
                        + " key_json, run_id, status, reason, original_value, converted_value, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                table, column, hash, key.json(), runId, status.name(), reason, original, converted, now);
        if (!inserted) {
            int n = Jdbc.update(c, "UPDATE OPS_UTC_CELL SET run_id = ?, status = ?, reason = ?, original_value = ?,"
                            + " converted_value = ?, updated_at = ? WHERE table_name = ? AND column_name = ? AND key_hash = ?"
                            + " AND status <> ?", runId, status.name(), reason, original, converted, now, table, column,
                    hash, CellStatus.CONVERTED.name());
            if (n != 1) {
                throw new IllegalStateException("cell " + table + "." + column + " " + key.json()
                        + " is already converted: a second conversion is refused");
            }
        }
    }

    void markRestored(Connection c, String table, Cell cell, Instant now) {
        int n = Jdbc.update(c, "UPDATE OPS_UTC_CELL SET status = ?, updated_at = ? WHERE table_name = ? AND column_name = ?"
                        + " AND key_hash = ? AND run_id = ? AND status = ?", CellStatus.RESTORED.name(), now, table,
                cell.column(), cell.keyHash(), cell.runId(), CellStatus.CONVERTED.name());
        if (n != 1) {
            throw new IllegalStateException("cell " + cell.keyJson() + " changed during the rollback");
        }
    }

    /** Up to {@code limit} cells of {@code runId} in {@code status}, after ({@code afterHash}, {@code afterColumn}). */
    List<Cell> cells(Connection c, String runId, CellStatus status, String afterHash, String afterColumn, int limit) {
        String sql = "SELECT " + CELL_COLUMNS + " FROM OPS_UTC_CELL WHERE run_id = ? AND status = ?"
                + (afterHash == null ? "" : " AND (key_hash > ? OR (key_hash = ? AND column_name > ?))")
                + " ORDER BY key_hash, column_name";
        Object[] params = afterHash == null ? new Object[]{runId, status.name()}
                : new Object[]{runId, status.name(), afterHash, afterHash, afterColumn};
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setMaxRows(limit);
            Jdbc.bind(ps, sql, 1, params);
            List<Cell> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapCell(rs));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new Jdbc.LayerSqlException(sql, e);
        }
    }

    long countCells(Connection c, String runId, CellStatus status) {
        return Jdbc.one(c, "SELECT COUNT(*) FROM OPS_UTC_CELL WHERE run_id = ? AND status = ?", rs -> rs.getLong(1),
                runId, status.name()).orElse(0L);
    }

    private static RunRow mapRun(ResultSet rs) throws SQLException {
        List<String> columns = new ArrayList<>();
        JsonNode cols = Json.tree(rs.getString("columns_json"));
        for (JsonNode n : cols) {
            columns.add(n.asText());
        }
        UtcDataset d = new UtcDataset(rs.getString("table_name"), columns, rs.getString("provenance_column"),
                ZoneId.of(rs.getString("source_zone")), Jdbc.instant(rs, "cutover_at"), rs.getString("tenant_filter"),
                rs.getString("session_zone") == null ? null : ZoneId.of(rs.getString("session_zone")));
        Counts n = new Counts(rs.getLong("rows_read"), rs.getLong("converted"), rs.getLong("already_utc"),
                rs.getLong("already_converted"), rs.getLong("unresolved"), rs.getLong("matched"), rs.getLong("mismatched"),
                rs.getLong("restored"));
        return new RunRow(rs.getString("run_id"), rs.getString("tool_version"), d, rs.getString("fingerprint"),
                State.valueOf(rs.getString("state")), n, rs.getString("last_key"), rs.getString("report_json"),
                rs.getString("maintenance_ref"), rs.getString("created_by"), rs.getLong("version"));
    }

    private static Cell mapCell(ResultSet rs) throws SQLException {
        return new Cell(rs.getString("column_name"), rs.getString("key_hash"), rs.getString("key_json"),
                rs.getString("run_id"), CellStatus.valueOf(rs.getString("status")), rs.getString("reason"),
                Jdbc.instant(rs, "original_value"), Jdbc.instant(rs, "converted_value"));
    }
}

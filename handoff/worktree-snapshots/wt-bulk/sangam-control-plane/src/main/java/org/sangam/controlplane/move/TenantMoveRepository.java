// SPDX-License-Identifier: Apache-2.0
package org.sangam.controlplane.move;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** CP_TENANT_MOVE access. */
@Repository
public class TenantMoveRepository {

    private static final String COLS = "move_id, tenant_code, source_cell, target_cell, state, process_instance_id,"
            + " requested_by, reason, rows_copied, checksum_source, checksum_target, source_watermark, started_at_ms,"
            + " finished_at_ms, last_error";

    private static final RowMapper<TenantMove> MAPPER = (rs, i) -> {
        long fin = rs.getLong(14);
        Long finished = rs.wasNull() ? null : fin;
        return new TenantMove(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getLong(9), rs.getString(10), rs.getString(11),
                rs.getString(12), rs.getLong(13), finished, rs.getString(15));
    };

    private final JdbcTemplate jdbc;

    public TenantMoveRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(TenantMove m) {
        jdbc.update("INSERT INTO CP_TENANT_MOVE(" + COLS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                m.moveId(), m.tenantCode(), m.sourceCell(), m.targetCell(), m.state(), m.processInstanceId(),
                m.requestedBy(), m.reason(), m.rowsCopied(), m.checksumSource(), m.checksumTarget(), m.sourceWatermark(),
                m.startedAtMs(), m.finishedAtMs(), m.lastError());
    }

    public Optional<TenantMove> find(String moveId) {
        return jdbc.query("SELECT " + COLS + " FROM CP_TENANT_MOVE WHERE move_id = ?", MAPPER, moveId).stream().findFirst();
    }

    public TenantMove get(String moveId) {
        return find(moveId).orElseThrow(() -> new IllegalArgumentException("move " + moveId + " not found"));
    }

    public List<TenantMove> forTenant(String tenant) {
        return jdbc.query("SELECT " + COLS + " FROM CP_TENANT_MOVE WHERE tenant_code = ? ORDER BY started_at_ms DESC",
                MAPPER, tenant);
    }

    public void setState(String moveId, String state) {
        jdbc.update("UPDATE CP_TENANT_MOVE SET state = ? WHERE move_id = ?", state, moveId);
    }

    public void setProcess(String moveId, String pid) {
        jdbc.update("UPDATE CP_TENANT_MOVE SET process_instance_id = ? WHERE move_id = ?", pid, moveId);
    }

    public void recordCopy(String moveId, long rows, String watermark) {
        jdbc.update("UPDATE CP_TENANT_MOVE SET rows_copied = ?, source_watermark = ? WHERE move_id = ?", rows, watermark, moveId);
    }

    public void recordChecksums(String moveId, String src, String tgt) {
        jdbc.update("UPDATE CP_TENANT_MOVE SET checksum_source = ?, checksum_target = ? WHERE move_id = ?", src, tgt, moveId);
    }

    public void finish(String moveId, String state, String error) {
        jdbc.update("UPDATE CP_TENANT_MOVE SET state = ?, finished_at_ms = ?, last_error = ? WHERE move_id = ?", state,
                System.currentTimeMillis(), error == null ? null : error.length() > 2000 ? error.substring(0, 2000) : error,
                moveId);
    }
}

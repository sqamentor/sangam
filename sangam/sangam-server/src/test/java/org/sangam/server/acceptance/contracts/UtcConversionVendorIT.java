// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Jdbc;
import org.sangam.ops.utc.UtcConversionTool;
import org.sangam.ops.utc.UtcConversionTool.Run;
import org.sangam.ops.utc.UtcDataset;
import org.sangam.ops.utc.UtcInventory;

/**
 * Decisions 150 and 203 on every acceptance database ({@code -Dsangam.db}; Oracle and SQL Server included), on the
 * production schema ({@code OPS_UTC_RUN}, {@code OPS_UTC_CELL} and {@code HRM_TASK} from the Liquibase changelogs):
 * the per-dialect timestamp types round-trip through the conversion (microseconds kept on PostgreSQL, MySQL DATETIME(6),
 * Oracle TIMESTAMP WITH TIME ZONE and SQL Server DATETIMEOFFSET), the guarded update finds the row by key and value,
 * reconcile reads every cell back, and rollback restores the snapshot. NOT_RUN in the coding phase.
 */
class UtcConversionVendorIT extends LayerSpecSupport {

    static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    static final Instant CUTOVER = Instant.parse("2026-11-01T00:00:00Z");

    private UtcConversionTool tool() {
        return new UtcConversionTool(TX, DIALECT, new AuditChain(DIALECT), UtcInventory.product());
    }

    private void task(String id, String tenant, Instant create, Instant end) {
        run(c -> Jdbc.update(c, "INSERT INTO HRM_TASK(id, tenant_id, state, create_time, end_time) VALUES (?, ?,"
                + " 'completed', ?, ?)", id, tenant, create, end));
    }

    private Instant value(String id, String column) {
        return inTx(c -> Jdbc.query(c, "SELECT " + column + " FROM HRM_TASK WHERE id = ?", rs -> Jdbc.instant(rs, column),
                id).get(0));
    }

    @Test
    void convertReconcileAndRollBackOnThisDatabase() {
        String tenant = "t-" + UUID.randomUUID();
        task("u1", tenant, Instant.parse("2026-01-15T10:00:00.123456Z"), Instant.parse("2026-01-15T11:00:00Z"));
        task("u2", tenant, Instant.parse("2026-05-10T10:00:00Z"), null);
        task("u3", tenant, Instant.parse("2026-11-15T10:00:00Z"), null);
        task("u4", tenant, Instant.parse("2026-03-29T02:30:00Z"), null);
        UtcDataset ds = new UtcDataset("HRM_TASK", List.of("create_time", "end_time"), "create_time", BERLIN, CUTOVER,
                tenant);
        UtcConversionTool tool = tool();
        String runId = "v-" + UUID.randomUUID().toString().substring(0, 8);
        Run dry = tool.dryRun(runId, ds, "operator", ACC_TENANT);
        assertEquals(3, dry.converted());
        assertEquals(1, dry.alreadyUtc());
        assertEquals(1, dry.unresolved(), "the DST gap is recorded, not chosen");

        Run applied = tool.apply(runId, 2, "MW-vendor", "operator", ACC_TENANT);
        assertEquals("APPLIED", applied.state());
        assertEquals(Instant.parse("2026-01-15T09:00:00.123456Z"), value("u1", "create_time"), "microseconds kept");
        assertEquals(Instant.parse("2026-05-10T08:00:00Z"), value("u2", "create_time"));
        assertEquals(Instant.parse("2026-11-15T10:00:00Z"), value("u3", "create_time"));
        assertEquals(Instant.parse("2026-03-29T02:30:00Z"), value("u4", "create_time"));

        assertEquals("RECONCILED", tool.reconcile(runId, "auditor", ACC_TENANT).state());
        assertThrows(IllegalStateException.class, () -> tool.apply(runId, 2, "MW-vendor", "operator", ACC_TENANT));

        Run rolled = tool.rollback(runId, "MW-vendor", "operator", ACC_TENANT);
        assertEquals("ROLLED_BACK", rolled.state());
        assertEquals(3, rolled.restored());
        assertEquals(Instant.parse("2026-01-15T10:00:00.123456Z"), value("u1", "create_time"));
        assertEquals(Instant.parse("2026-01-15T11:00:00Z"), value("u1", "end_time"));
        assertTrue(inTx(c -> new AuditChain(DIALECT).verify(c, ACC_TENANT, UtcConversionTool.CHAIN_PREFIX + runId)).ok());
    }

    static final String ACC_TENANT = "acc-utc";
}

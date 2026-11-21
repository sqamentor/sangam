// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.archive;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.sangam.core.history.HistoryArchiveGate;

/**
 * The table prefix is the only configured text the archive exporter and the partition-drop guard put into statement
 * text (identifiers cannot be bound). A prefix outside the identifier grammar is refused when they are built, before
 * any SQL is written (Semgrep sangam-jdbc-sql-concat, round 9).
 */
class ArchiveTablePrefixTest {

    private static final List<String> BAD = List.of("x; DROP TABLE ACT_HI_PROCINST --", "ops..", "o'", "a b", "ops.--");

    @Test
    void theExporterRefusesAPrefixThatIsNotAnIdentifier() {
        for (String bad : BAD) {
            assertThrows(IllegalArgumentException.class, () -> new HistoryArchiveExporter(bad, "0.1.0"), bad);
        }
        for (String ok : new String[] {null, "", "SANGAM_", "ops.", "ops.SG_"}) {
            assertDoesNotThrow(() -> new HistoryArchiveExporter(ok, "0.1.0"), String.valueOf(ok));
        }
    }

    @Test
    void thePartitionDropGuardRefusesAPrefixThatIsNotAnIdentifier() {
        HistoryArchiveGate gate = HistoryArchiveGate.notConfigured();
        for (String bad : BAD) {
            assertThrows(IllegalArgumentException.class, () -> new PartitionDropGuard(gate, bad), bad);
        }
        assertDoesNotThrow(() -> new PartitionDropGuard(gate, "ops."));
        assertDoesNotThrow(() -> new PartitionDropGuard(gate, null));
    }
}

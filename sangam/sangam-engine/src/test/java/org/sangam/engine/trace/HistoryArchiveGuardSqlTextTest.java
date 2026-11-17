// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;

/**
 * The guard's lookups put only a vetted table prefix and an allow-listed column into statement text (identifiers
 * cannot be bound; Semgrep sangam-jdbc-sql-concat, round 9). Anything else is refused before a connection is used.
 */
class HistoryArchiveGuardSqlTextTest {

    @Test
    void anEngineTablePrefixOutsideTheIdentifierGrammarIsRefused() {
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        assertEquals("", HistoryArchiveGuard.prefix(cfg));
        cfg.setDatabaseTablePrefix("ops.");
        assertEquals("ops.", HistoryArchiveGuard.prefix(cfg));
        assertEquals("ops.", ProcessTraceHistoryRemoval.prefix(cfg));
        cfg.setDatabaseTablePrefix("x; DROP TABLE ACT_HI_PROCINST --");
        assertThrows(IllegalArgumentException.class, () -> HistoryArchiveGuard.prefix(cfg));
        assertThrows(IllegalArgumentException.class, () -> ProcessTraceHistoryRemoval.prefix(cfg));
    }

    @Test
    void theLookupsRefuseABadPrefixOrAColumnOffTheAllowList() {
        List<String> one = List.of("id-1");
        assertThrows(IllegalArgumentException.class,
                () -> HistoryArchiveGuard.treesOfInstances(null, "o'", one));
        assertThrows(IllegalArgumentException.class,
                () -> HistoryArchiveGuard.treesOfDecisions(null, "o'", "ID_", one));
        assertThrows(IllegalArgumentException.class,
                () -> HistoryArchiveGuard.treesOfDecisions(null, "", "ID_ OR 1=1 --", one));
        assertEquals(Set.of("ID_", "DEC_DEF_ID_"), HistoryArchiveGuard.DECISION_COLUMNS);
    }
}

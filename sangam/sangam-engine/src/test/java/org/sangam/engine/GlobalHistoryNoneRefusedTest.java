// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.impl.history.HistoryLevel;
import org.sangam.engine.history.MandatoryAuditHistoryLevel;
import org.sangam.engine.history.PerDefinitionHistoryLevel;

/**
 * M-012, owner decision 2026-11-12 (SANGAM.md X.2.3): the global history level {@code none} is refused at startup,
 * failing closed, with a message that names the setting and the alternatives; every other level starts.
 */
class GlobalHistoryNoneRefusedTest {

    private static void build(Consumer<SangamEngineSettings> config) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:hn" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamEngineSettings s = new SangamEngineSettings();
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true");
        s.setApplyEnvironment(false);
        config.accept(s);
        SangamEngineFactory.build(ds, s, "h2").close();
    }

    private static void assertRefusalMessage(String message) {
        assertTrue(message.contains("X.2.3"), "cites X.2.3: " + message);
        assertTrue(message.contains("operaton.bpm.history-level") && message.contains("sangam.engine.history"),
                "names the setting: " + message);
        assertTrue(message.contains("'activity'"), "names the alternative level: " + message);
        assertTrue(message.contains("sangam.engine.history-level-by-process-key"), "names per-definition none: " + message);
    }

    // ------------------------------------------------------------------ unit: the check itself

    @Test
    void theResolvedBuiltInNoneIsRefused() {
        ProcessEngineException e = assertThrows(ProcessEngineException.class,
                () -> SangamProcessEnginePlugin.refuseGlobalHistoryNone(HistoryLevel.HISTORY_LEVEL_NONE, "none"));
        assertEquals(SangamProcessEnginePlugin.GLOBAL_NONE_REFUSED, e.getMessage());
        assertRefusalMessage(e.getMessage());
        assertThrows(ProcessEngineException.class, () -> SangamProcessEnginePlugin.refuseGlobalHistoryNone(null, " NONE "),
                "configured name, level not yet resolved");
    }

    @Test
    void everyOtherLevelPassesTheCheck() {
        Set<String> audited = Set.of("PROCESS_INSTANCE_START");
        for (HistoryLevel l : List.of(HistoryLevel.HISTORY_LEVEL_ACTIVITY, HistoryLevel.HISTORY_LEVEL_AUDIT,
                HistoryLevel.HISTORY_LEVEL_FULL,
                new MandatoryAuditHistoryLevel(HistoryLevel.HISTORY_LEVEL_ACTIVITY, audited),
                new MandatoryAuditHistoryLevel(HistoryLevel.HISTORY_LEVEL_AUDIT, audited),
                new MandatoryAuditHistoryLevel(new PerDefinitionHistoryLevel(HistoryLevel.HISTORY_LEVEL_FULL,
                        Map.of("k", "none")), audited))) {
            assertDoesNotThrow(() -> SangamProcessEnginePlugin.refuseGlobalHistoryNone(l, l.getName()), l.getName());
        }
        for (String name : List.of("activity", "audit", "full", "auto")) {
            assertDoesNotThrow(() -> SangamProcessEnginePlugin.refuseGlobalHistoryNone(null, name), name);
        }
        assertDoesNotThrow(() -> SangamProcessEnginePlugin.refuseGlobalHistoryNone(null, null));
    }

    @Test
    void historyAutoIsRefusedWithAnExplicitLevelAsTheAlternative() {
        ProcessEngineException e = assertThrows(ProcessEngineException.class,
                () -> SangamProcessEnginePlugin.refuseHistoryAuto(null, "auto"));
        assertEquals(SangamProcessEnginePlugin.AUTO_REFUSED, e.getMessage());
        assertTrue(e.getMessage().contains("X.2.3"), e.getMessage());
        assertTrue(e.getMessage().contains("operaton.bpm.history-level") && e.getMessage().contains("sangam.engine.history"),
                e.getMessage());
        assertTrue(e.getMessage().contains("'activity'") && e.getMessage().contains("'full'"), e.getMessage());
        for (String name : List.of("activity", "audit", "full", "variable")) {
            assertDoesNotThrow(() -> SangamProcessEnginePlugin.refuseHistoryAuto(null, name), name);
        }
        assertDoesNotThrow(() -> SangamProcessEnginePlugin.refuseHistoryAuto(HistoryLevel.HISTORY_LEVEL_FULL, "full"));
    }

    @Test
    void anEngineWithHistoryAutoDoesNotStart() {
        ProcessEngineException e = assertThrows(ProcessEngineException.class, () -> build(s -> s.setHistory("auto")));
        assertEquals(SangamProcessEnginePlugin.AUTO_REFUSED, e.getMessage());
    }

    @Test
    void autoAsThePerDefinitionBaseIsRefusedToo() {
        ProcessEngineException unit = assertThrows(ProcessEngineException.class,
                () -> SangamProcessEnginePlugin.PerDefinitionHistoryLevelBase.of(" AUTO "));
        assertEquals(SangamProcessEnginePlugin.AUTO_REFUSED, unit.getMessage());
        assertEquals(HistoryLevel.HISTORY_LEVEL_ACTIVITY, SangamProcessEnginePlugin.PerDefinitionHistoryLevelBase.of("variable"));
        ProcessEngineException e = assertThrows(ProcessEngineException.class, () -> build(s -> {
            s.setHistory("auto");
            s.setHistoryLevelByProcessKey(Map.of("anyKey", "none"));
        }));
        assertEquals(SangamProcessEnginePlugin.AUTO_REFUSED, e.getMessage(), "not mapped to full");
    }

    // ------------------------------------------------------------------ engine startup

    @Test
    void anEngineWithGlobalNoneDoesNotStart() {
        ProcessEngineException e = assertThrows(ProcessEngineException.class, () -> build(s -> s.setHistory("none")));
        assertRefusalMessage(e.getMessage());
    }

    @Test
    void anEngineStartsAtEveryOtherGlobalLevel() {
        for (String level : List.of("activity", "audit", "full", "variable")) { // variable: deprecated alias of activity
            assertDoesNotThrow(() -> build(s -> s.setHistory(level)), level);
        }
    }

    @Test
    void perDefinitionNoneStillStarts() {
        assertDoesNotThrow(() -> build(s -> {
            s.setHistory("full");
            s.setHistoryLevelByProcessKey(Map.of("anyKey", "none"));
        }), "per-definition none keeps the audit through MandatoryAuditHistoryLevel");
    }
}

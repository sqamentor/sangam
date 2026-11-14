// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.impl.cfg.StandaloneProcessEngineConfiguration;
import org.sangam.core.engine.TimerCatchUpPolicy;

/**
 * M-098 (SANGAM.md §1.2 W7, P1-031): {@link SangamEngineSettings} is frozen once {@link SangamEngineConfigurer#configure}
 * has run. Every setter (found by reflection, so a new setter without the guard fails here) and
 * {@code applyEnvironment} then throw {@link IllegalStateException} before touching the value; map and set settings
 * become unmodifiable copies in their order; before the freeze the bean is unchanged.
 */
class SangamEngineSettingsFreezeTest {

    private static List<Method> setters() {
        List<Method> out = new ArrayList<>();
        for (Method m : SangamEngineSettings.class.getMethods()) {
            if (m.getName().startsWith("set") && m.getParameterCount() == 1 && Modifier.isPublic(m.getModifiers())
                    && m.getDeclaringClass() == SangamEngineSettings.class) {
                out.add(m);
            }
        }
        return out;
    }

    /** A placeholder argument: the guard must throw before the value is looked at, so null / 0 / false suffice. */
    private static Object arg(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0f;
        }
        return null;
    }

    @Test
    void everySetterIsGuardedAfterTheFreeze() throws Exception {
        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope("system:platform"); // decision 144: required at engine start
        s.freeze();
        assertTrue(s.isFrozen());
        List<Method> setters = setters();
        assertTrue(setters.size() >= 69, "all setters found by reflection: " + setters.size());
        for (Method m : setters) {
            InvocationTargetException e = assertThrows(InvocationTargetException.class,
                    () -> m.invoke(s, arg(m.getParameterTypes()[0])), m.getName());
            assertTrue(e.getCause() instanceof IllegalStateException, m.getName() + " -> " + e.getCause());
            assertTrue(e.getCause().getMessage().contains("M-098"), e.getCause().getMessage());
        }
        IllegalStateException env = assertThrows(IllegalStateException.class, () -> s.applyEnvironment(Map.of()));
        assertTrue(env.getMessage().contains("frozen"), env.getMessage());
    }

    @Test
    void theValuesSurviveTheFreezeUnchanged() {
        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope("system:platform"); // decision 144: required at engine start
        s.setJobMaxPoolSize(7);
        s.setHistory("audit");
        s.getHistoryLevelByProcessKey().put("b", "none");
        s.getHistoryLevelByProcessKey().put("a", "activity");
        s.getTimerCatchUpByProcessKey().put("p", TimerCatchUpPolicy.FIRE_ONCE);
        s.getJobBulkheads().put("async-continuation", 4);
        List<String> audited = new ArrayList<>(s.getAuditedHistoryEvents());
        s.freeze();
        assertEquals(7, s.getJobMaxPoolSize());
        assertEquals("audit", s.getHistory());
        assertEquals(List.of("b", "a"), new ArrayList<>(s.getHistoryLevelByProcessKey().keySet()), "order kept");
        assertEquals(TimerCatchUpPolicy.FIRE_ONCE, s.catchUpFor("p"));
        assertEquals(Map.of("async-continuation", 4), s.getJobBulkheads());
        assertEquals(audited, new ArrayList<>(s.getAuditedHistoryEvents()), "order kept");
    }

    @Test
    void mapsAndSetsAreUnmodifiableAfterTheFreeze() {
        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope("system:platform"); // decision 144: required at engine start
        s.freeze();
        assertThrows(UnsupportedOperationException.class, () -> s.getWorkCalendars().put("x", null));
        assertThrows(UnsupportedOperationException.class, () -> s.getTimerCatchUpByProcessKey().clear());
        assertThrows(UnsupportedOperationException.class, () -> s.getHistoryTtlDaysByRetentionClass().put("c", 1));
        assertThrows(UnsupportedOperationException.class, () -> s.getHistoryLevelByProcessKey().put("k", "none"));
        assertThrows(UnsupportedOperationException.class, () -> s.getAuditedHistoryEvents().clear());
        assertThrows(UnsupportedOperationException.class, () -> s.getPublishedHistoryEvents().add("X"));
        assertThrows(UnsupportedOperationException.class, () -> s.getOutboxOnlyHistoryKeys().add("k"));
        assertThrows(UnsupportedOperationException.class, () -> s.getJobBulkheads().put("t", 1));
    }

    @Test
    void aMapHeldBeforeTheFreezeNoLongerReachesTheSettings() {
        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope("system:platform"); // decision 144: required at engine start
        Map<String, String> early = s.getHistoryLevelByProcessKey();
        s.freeze();
        early.put("late", "none");
        assertFalse(s.getHistoryLevelByProcessKey().containsKey("late"), "the frozen copy is detached");
    }

    @Test
    void beforeTheFreezeTheBeanIsMutableAsBefore() {
        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope("system:platform"); // decision 144: required at engine start
        assertFalse(s.isFrozen());
        assertDoesNotThrow(() -> {
            s.setJobCorePoolSize(3);
            s.getWorkCalendars().clear();
            s.setHistoryLevelByProcessKey(new LinkedHashMap<>(Map.of("k", "audit")));
            s.setOutboxOnlyHistoryKeys(new LinkedHashSet<>());
            s.applyEnvironment(Map.of("SANGAM_JOB_CORE_POOL_SIZE", "5", "SANGAM_JOB_VIRTUAL_THREADS", "true"));
        });
        assertEquals(5, s.getJobCorePoolSize());
        assertTrue(s.isJobVirtualThreads(), "M-054 env override");
    }

    @Test
    void freezeIsIdempotentAndKeepsNullCollectionsNull() {
        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope("system:platform"); // decision 144: required at engine start
        s.setPublishedHistoryEvents(null);
        s.freeze();
        Map<String, String> frozen = s.getHistoryLevelByProcessKey();
        s.freeze();
        assertSame(frozen, s.getHistoryLevelByProcessKey(), "a second freeze changes nothing");
        assertEquals(null, s.getPublishedHistoryEvents());
    }

    @Test
    void configureFreezesTheSettingsAndASecondPrepareDoesNotReapplyTheEnvironment() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:fz" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope("system:platform"); // decision 144: required at engine start
        s.setAllowMultipleEnginesPerJvm(true);
        s.setApplyProductSchema(false);
        SangamEngineConfigurer c = new SangamEngineConfigurer(s);
        try {
            var prepared = c.prepare(ds);
            assertFalse(s.isFrozen(), "prepare alone does not freeze");
            c.configure(new StandaloneProcessEngineConfiguration(), prepared);
            assertTrue(s.isFrozen(), "configure completed the bootstrap");
            assertThrows(IllegalStateException.class, () -> s.setJobMaxPoolSize(1));
            SangamEngineConfigurer again = new SangamEngineConfigurer(s);
            assertDoesNotThrow(() -> again.prepare(ds), "frozen settings: the environment is not applied twice");
        } finally {
            c.releaseEngineSlot();
        }
    }
}

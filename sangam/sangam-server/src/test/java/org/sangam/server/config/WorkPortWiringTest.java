// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import org.sangam.rest.config.SpringTxSupport;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.sangam.core.api.EngineAdapter;
import org.sangam.rest.adapter.operaton.EngineAdapterWorkAdapter;
import org.sangam.rest.adapter.operaton.OperatonWorkAdapter;
import org.sangam.rest.adapter.operaton.TrackingWorkPort;
import org.sangam.rest.adapter.operaton.WorkerTopicGuard;
import org.sangam.rest.config.SangamRestAutoConfiguration;
import org.sangam.rest.port.WorkPort;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;
import org.sangam.rest.support.WorkLongPoll;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;

/**
 * The worker port's wiring (SL-158, X1-07, M-062): on both engine paths (sangam-engine adapter, or Operaton directly
 * when the engine module is off) the worker-topic guard and the long-poll back-off settings are bound before the
 * tracking wrapper; trace links follow {@code sangam.engine.trace-links} (default off).
 */
class WorkPortWiringTest {

    static Object field(Object target, Class<?> owner, String name) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    static WorkPort build(EngineAdapter adapterOrNull, WorkerTopicGuard guard, WorkLongPoll poll, MockEnvironment env) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:workport" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        TxSupport tx = new SpringTxSupport(ds, new DataSourceTransactionManager(ds), TxSupport.detectDialect(ds));
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        if (adapterOrNull != null) {
            bf.registerSingleton("engineAdapter", adapterOrNull);
        }
        bf.registerSingleton("guard", guard);
        bf.registerSingleton("poll", poll);
        ProcessEngine engine = (ProcessEngine) Proxy.newProxyInstance(WorkPortWiringTest.class.getClassLoader(),
                new Class<?>[] {ProcessEngine.class}, (p, m, a) -> null);
        return new SangamRestAutoConfiguration().sangamWorkPort(bf.getBeanProvider(EngineAdapter.class), engine, tx, null,
                new RestProperties(), null, null, bf.getBeanProvider(WorkerTopicGuard.class),
                bf.getBeanProvider(WorkLongPoll.class), env);
    }

    static Object raw(WorkPort port) throws Exception {
        return field(assertInstanceOf(TrackingWorkPort.class, port), TrackingWorkPort.class, "delegate");
    }

    @Test
    void bothEnginePathsGetTheGuardAndTheLongPollSettings() throws Exception {
        WorkerTopicGuard guard = (ctx, topic) -> { };
        WorkLongPoll.Settings settings = new WorkLongPoll.Settings(10, 1, Duration.ofMillis(50), Duration.ofMillis(800));
        WorkLongPoll poll = new WorkLongPoll(settings, List.of());
        try {
            EngineAdapter adapter = (EngineAdapter) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {EngineAdapter.class}, (p, m, a) -> null);
            Object sangam = raw(build(adapter, guard, poll, new MockEnvironment()));
            assertInstanceOf(EngineAdapterWorkAdapter.class, sangam);
            assertSame(guard, field(sangam, EngineAdapterWorkAdapter.class, "topicGuard"));
            assertSame(settings, field(sangam, EngineAdapterWorkAdapter.class, "longPollSettings"));
            assertFalse((Boolean) field(sangam, EngineAdapterWorkAdapter.class, "traceLinks"), "trace links off by default");

            Object operaton = raw(build(null, guard, poll, new MockEnvironment()));
            assertInstanceOf(OperatonWorkAdapter.class, operaton);
            assertSame(guard, field(operaton, OperatonWorkAdapter.class, "topicGuard"), "SL-158 without the engine module");
            assertSame(settings, field(operaton, OperatonWorkAdapter.class, "longPollSettings"));
        } finally {
            poll.close();
        }
    }

    @Test
    void traceLinksFollowTheEngineSetting() {
        assertTrue(traceLinks("true"));
        assertFalse(traceLinks("false"));
        assertEquals(false, traceLinks(null));
    }

    static boolean traceLinks(String value) {
        MockEnvironment env = new MockEnvironment();
        if (value != null) {
            env.setProperty("sangam.engine.trace-links", value);
        }
        try {
            java.lang.reflect.Method m = SangamRestAutoConfiguration.class.getDeclaredMethod("traceLinks",
                    org.springframework.core.env.Environment.class);
            m.setAccessible(true);
            return (Boolean) m.invoke(null, env);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}

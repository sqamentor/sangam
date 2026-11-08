// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.ix8;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * T-SCALE probe: counts JDBC round trips (statement executions, batches, commits, rollbacks) of the server's primary
 * pool, split into request threads ({@code http-nio-*}) and background threads, with a per-statement breakdown. Test
 * scope only; wraps the {@code dataSource} bean after every other post-processor ran (so the pool is configured as
 * in production), and {@code unwrap} still reaches the Hikari pool.
 */
public final class StatementCounter {

    private static final Set<String> EXEC = Set.of("execute", "executeQuery", "executeUpdate", "executeLargeUpdate",
            "executeBatch", "executeLargeBatch");

    public static final AtomicLong REQUEST = new AtomicLong();
    public static final AtomicLong BACKGROUND = new AtomicLong();
    private static final Map<String, AtomicLong> BY_SQL = new ConcurrentHashMap<>();
    private static volatile boolean breakdown;

    private StatementCounter() {
    }

    /** Clears the counters; {@code withBreakdown} also records per-statement counts of request threads. */
    public static void reset(boolean withBreakdown) {
        REQUEST.set(0);
        BACKGROUND.set(0);
        BY_SQL.clear();
        breakdown = withBreakdown;
    }

    public static Map<String, Long> breakdown() {
        Map<String, Long> m = new TreeMap<>();
        BY_SQL.forEach((k, v) -> m.put(k, v.get()));
        return m;
    }

    private static void count(String what) {
        boolean req = Thread.currentThread().getName().startsWith("http-nio");
        (req ? REQUEST : BACKGROUND).incrementAndGet();
        if (breakdown && req) {
            String k = what == null ? "?" : what.replaceAll("\\s+", " ").trim();
            BY_SQL.computeIfAbsent(k.length() > 110 ? k.substring(0, 110) : k, x -> new AtomicLong()).incrementAndGet();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {
        @Bean
        static BeanPostProcessor statementCountingDataSource() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if ("dataSource".equals(name) && bean instanceof DataSource ds) {
                        return wrap(ds);
                    }
                    return bean;
                }
            };
        }
    }

    static DataSource wrap(DataSource target) {
        return (DataSource) Proxy.newProxyInstance(StatementCounter.class.getClassLoader(), new Class<?>[] {DataSource.class},
                (p, m, a) -> {
                    if (m.getName().equals("unwrap") && a[0] instanceof Class<?> c && c.isInstance(target)) {
                        return target;
                    }
                    if (m.getName().equals("isWrapperFor") && a[0] instanceof Class<?> c && c.isInstance(target)) {
                        return true;
                    }
                    Object r = invoke(target, m, a);
                    return r instanceof Connection c ? connection(c) : r;
                });
    }

    private static Connection connection(Connection target) {
        return (Connection) Proxy.newProxyInstance(StatementCounter.class.getClassLoader(), new Class<?>[] {Connection.class},
                (p, m, a) -> {
                    String n = m.getName();
                    if (n.equals("commit") || n.equals("rollback")) {
                        count(n.toUpperCase());
                    }
                    Object r = invoke(target, m, a);
                    if (r instanceof CallableStatement cs) {
                        return statement(cs, CallableStatement.class, (String) a[0]);
                    }
                    if (r instanceof PreparedStatement ps) {
                        return statement(ps, PreparedStatement.class, (String) a[0]);
                    }
                    if (r instanceof Statement s) {
                        return statement(s, Statement.class, null);
                    }
                    return r;
                });
    }

    private static Object statement(Statement target, Class<?> type, String sql) {
        InvocationHandler h = (p, m, a) -> {
            if (EXEC.contains(m.getName())) {
                count(m.getName().contains("Batch") ? "BATCH " + sql : sql != null ? sql : a != null && a.length > 0 ? String.valueOf(a[0]) : "?");
            }
            return invoke(target, m, a);
        };
        return Proxy.newProxyInstance(StatementCounter.class.getClassLoader(), new Class<?>[] {type}, h);
    }

    private static Object invoke(Object target, Method m, Object[] a) throws Throwable {
        try {
            return m.invoke(target, a);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}

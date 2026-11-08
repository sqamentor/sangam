// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.tenancy;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.ProcessEngineImpl;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.jobexecutor.DefaultJobExecutor;
import org.sangam.core.engine.SangamClockHolder;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.logging.Logger;

/**
 * Database-per-tenant routing (FLOWABLE-INTO-OPERATON FC-34; Flowable {@code MultiSchemaMultiTenantProcessEngineConfiguration} and
 * {@code TenantAwareDataSource}): one Operaton engine per tenant database, all sharing <b>one</b> job executor
 * (Operaton's {@code JobExecutor} serves several registered engines, which removes the per-engine thread-pool cost).
 * {@link TenantContext} selects the engine and the product-layer pool ({@link RoutingDataSource}) per request.
 *
 * <p><b>Clock limit (FLOWABLE-INTO-OPERATON D-10).</b> Operaton's {@code ClockUtil} (behind {@code SangamClockHolder}) is a JVM-wide static: every engine in this JVM shares one
 * clock. Production never moves the clock, so routing is safe there; tests that move the clock must run one tenant
 * engine per JVM. {@link #register} therefore refuses a second engine while a fake clock is set, and
 * {@link #assertRealClock} is called before every engine start. A per-engine clock SPI is patch P-6 (PATCHES.md,
 * upstream offer); until it lands, "several engines in one JVM" is supported only with the real clock. Per-tenant
 * outbox relays (A-5) and per-tenant schema upgrades (R32) are owned by WS-INTEGRATION and WS-ENGINE.
 */
public class TenantEngineRegistry implements AutoCloseable {

    /** Current tenant of the thread (set by the REST filter from the token claim, ARCHITECTURE §4). */
    public static final class TenantContext {
        private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

        private TenantContext() {
        }

        public static void set(String tenant) {
            CURRENT.set(tenant);
        }

        public static String get() {
            return CURRENT.get();
        }

        public static void clear() {
            CURRENT.remove();
        }
    }

    private final Map<String, ProcessEngine> engines = new ConcurrentHashMap<>();
    private final Map<String, DataSource> dataSources = new ConcurrentHashMap<>();
    private final BiFunction<String, DataSource, ProcessEngineConfigurationImpl> configFactory;
    private final DefaultJobExecutor sharedJobExecutor;

    /**
     * @param configFactory builds a fully configured (plugins included) engine configuration for a tenant and its
     *                      data source; the registry overrides engine name, job-executor wiring and activation
     */
    public TenantEngineRegistry(BiFunction<String, DataSource, ProcessEngineConfigurationImpl> configFactory, int jobThreads) {
        this.configFactory = configFactory;
        this.sharedJobExecutor = new DefaultJobExecutor();
        this.sharedJobExecutor.setCorePoolSize(Math.max(1, jobThreads / 2));
        this.sharedJobExecutor.setMaxPoolSize(Math.max(1, jobThreads));
    }

    public synchronized ProcessEngine register(String tenant, DataSource ds) {
        if (engines.containsKey(tenant)) {
            return engines.get(tenant);
        }
        if (!engines.isEmpty()) {
            assertRealClock();
        }
        ProcessEngineConfigurationImpl cfg = configFactory.apply(tenant, ds);
        cfg.setProcessEngineName("sangam-" + tenant);
        cfg.setDataSource(ds);
        cfg.setJobExecutor(sharedJobExecutor);
        cfg.setJobExecutorActivate(false);
        ProcessEngine engine = cfg.buildProcessEngine();
        // the engine registers itself with its job executor while it is built; registering it a second time would make
        // the shared acquisition visit it twice and leave a stale (closed) entry behind after unregister
        if (!sharedJobExecutor.getProcessEngines().contains(engine)) {
            sharedJobExecutor.registerProcessEngine((ProcessEngineImpl) engine);
        }
        if (!sharedJobExecutor.isActive()) {
            sharedJobExecutor.start();
        }
        engines.put(tenant, engine);
        dataSources.put(tenant, ds);
        return engine;
    }

    public synchronized void unregister(String tenant) {
        ProcessEngine e = engines.remove(tenant);
        dataSources.remove(tenant);
        if (e != null) {
            sharedJobExecutor.unregisterProcessEngine((ProcessEngineImpl) e);
            e.close();
        }
    }

    public ProcessEngine engine(String tenant) {
        ProcessEngine e = engines.get(tenant);
        if (e == null) {
            throw new IllegalStateException("no engine for tenant " + tenant);
        }
        return e;
    }

    /** Engine of the current {@link TenantContext}. */
    public ProcessEngine current() {
        String t = TenantContext.get();
        if (t == null) {
            throw new IllegalStateException("no tenant in context");
        }
        return engine(t);
    }

    public Set<String> tenants() {
        return Set.copyOf(engines.keySet());
    }

    public DataSource routingDataSource() {
        return new RoutingDataSource(dataSources);
    }

    /** FLOWABLE-INTO-OPERATON D-10 guard: a moved clock would apply to every tenant engine of this JVM. */
    public static void assertRealClock() {
        long drift = Math.abs(SangamClockHolder.now().toEpochMilli() - System.currentTimeMillis());
        if (drift > 5_000) {
            throw new IllegalStateException("the engine clock is moved (FLOWABLE-INTO-OPERATON D-10): several tenant engines cannot share a fake clock; "
                    + "run one engine per JVM for clock tests");
        }
    }

    @Override
    public synchronized void close() {
        sharedJobExecutor.shutdown();
        for (String t : Set.copyOf(engines.keySet())) {
            ProcessEngine e = engines.remove(t);
            e.close();
        }
        dataSources.clear();
    }

    /** Product-layer pool routed by {@link TenantContext} (Flowable {@code TenantAwareDataSource}). */
    public static final class RoutingDataSource implements DataSource {
        private final Map<String, DataSource> targets;

        RoutingDataSource(Map<String, DataSource> targets) {
            this.targets = targets;
        }

        private DataSource target() {
            String t = TenantContext.get();
            DataSource ds = t == null ? null : targets.get(t);
            if (ds == null) {
                throw new IllegalStateException("no data source for tenant " + t);
            }
            return ds;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return target().getConnection();
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return target().getConnection(username, password);
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            if (iface.isInstance(this)) {
                return iface.cast(this);
            }
            return target().unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return iface.isInstance(this) || target().isWrapperFor(iface);
        }
    }
}

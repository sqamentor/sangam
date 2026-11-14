// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.impl.ProcessEngineImpl;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.cfg.StandaloneProcessEngineConfiguration;
import org.operaton.bpm.engine.impl.jobexecutor.DefaultJobExecutor;
import org.operaton.bpm.engine.impl.jobexecutor.ExecuteJobsRunnable;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineComponents;
import org.sangam.engine.SangamEngineConfigurer;
import org.sangam.engine.SangamEngineFactory;
import org.sangam.engine.SangamEngineSettings;
import org.sangam.engine.SangamProcessEnginePlugin;
import org.sangam.engine.db.EngineDialect;

/**
 * M-054 (SANGAM.md §4.4, R5, C7): the opt-in virtual-thread job executor and per-type bulkheads. Off by default, the
 * engine keeps Operaton's {@link DefaultJobExecutor} exactly; each option installs {@link SangamJobExecutor} with the
 * same sizing; the virtual pool has Operaton's pool shape; a host executor that cannot honour an option is refused.
 */
class SangamJobExecutorTest {

    private static SangamEngineSettings settings() {
        SangamEngineSettings s = new SangamEngineSettings();
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true");
        s.setApplyEnvironment(false); // a SANGAM_JOB_* variable on the build host must not decide the test
        return s;
    }

    private static JdbcDataSource h2() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:m054" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        return ds;
    }

    private static void withEngine(Consumer<SangamEngineSettings> config, Consumer<ProcessEngineConfigurationImpl> test) {
        SangamEngineSettings s = settings();
        config.accept(s);
        try (OperatonEngineAdapter a = SangamEngineFactory.build(h2(), s, "h2")) {
            test.accept((ProcessEngineConfigurationImpl) a.processEngine().getProcessEngineConfiguration());
        }
    }

    @Test
    void bothOptionsAreOffByDefault() {
        SangamEngineSettings s = new SangamEngineSettings();
        assertFalse(s.isJobVirtualThreads());
        assertTrue(s.getJobBulkheads().isEmpty());
        assertEquals(Duration.ofSeconds(10), s.getJobBulkheadWait());
        SangamEngineSettings env = new SangamEngineSettings();
        env.applyEnvironment(Map.of("SANGAM_JOB_VIRTUAL_THREADS", "true"));
        assertTrue(env.isJobVirtualThreads(), "SANGAM_JOB_VIRTUAL_THREADS=true switches it on");
        SangamEngineSettings blank = new SangamEngineSettings();
        blank.applyEnvironment(Map.of("SANGAM_JOB_VIRTUAL_THREADS", " "));
        assertFalse(blank.isJobVirtualThreads(), "a blank value keeps the default");
    }

    @Test
    void offByDefaultTheEngineKeepsOperatonsExecutor() {
        withEngine(s -> { }, cfg -> assertSame(DefaultJobExecutor.class, cfg.getJobExecutor().getClass(),
                "no option set: exactly Operaton's DefaultJobExecutor, as before M-054"));
    }

    @Test
    void virtualThreadsInstallTheSangamExecutorWithTheSameSizing() {
        withEngine(s -> {
            s.setJobVirtualThreads(true);
            s.setJobCorePoolSize(3);
            s.setJobMaxPoolSize(5);
            s.setJobQueueCapacity(7);
            s.setJobMaxJobsPerAcquisition(9);
        }, cfg -> {
            SangamJobExecutor je = assertInstanceOf(SangamJobExecutor.class, cfg.getJobExecutor());
            assertTrue(je.isVirtualThreads());
            assertNull(je.bulkheads(), "no bulkhead configured");
            assertEquals(3, je.getCorePoolSize());
            assertEquals(5, je.getMaxPoolSize());
            assertEquals(7, je.getQueueSize());
            assertEquals(9, je.getMaxJobsPerAcquisition());
            Runnable r = je.getExecuteJobsRunnable(List.of("j"), (ProcessEngineImpl) processEngine(cfg));
            assertSame(ExecuteJobsRunnable.class, r.getClass(), "without bulkheads Operaton's runnable runs the jobs");
        });
    }

    @Test
    void bulkheadsInstallTheSangamExecutorAndItsRunnable() {
        withEngine(s -> s.getJobBulkheads().put("async-continuation", 2), cfg -> {
            SangamJobExecutor je = assertInstanceOf(SangamJobExecutor.class, cfg.getJobExecutor());
            assertFalse(je.isVirtualThreads());
            assertEquals(2, je.bulkheads().available("async-continuation"));
            Runnable r = je.getExecuteJobsRunnable(List.of("j"), (ProcessEngineImpl) processEngine(cfg));
            assertInstanceOf(BulkheadExecuteJobsRunnable.class, r);
        });
    }

    @Test
    void anInvalidBulkheadFailsTheStart() {
        SangamEngineSettings s = settings();
        s.getJobBulkheads().put("async-continuation", 0);
        RuntimeException e = assertThrows(RuntimeException.class, () -> SangamEngineFactory.build(h2(), s, "h2").close());
        Throwable t = e;
        while (t != null && !(t instanceof IllegalArgumentException)) {
            t = t.getCause();
        }
        assertTrue(t != null && t.getMessage().contains("sangam.engine.job-bulkheads.async-continuation"),
                "the start fails on the invalid limit: " + e);
    }

    @Test
    void theVirtualPoolHasOperatonsPoolShapeOnVirtualThreads() throws Exception {
        SangamJobExecutor je = new SangamJobExecutor(true, Map.of(), Duration.ZERO);
        je.setCorePoolSize(2);
        je.setMaxPoolSize(4);
        je.setQueueSize(6);
        ThreadPoolExecutor pool = je.newVirtualThreadPool();
        try {
            assertEquals(2, pool.getCorePoolSize());
            assertEquals(4, pool.getMaximumPoolSize());
            assertEquals(6, pool.getQueue().remainingCapacity());
            assertEquals(0L, pool.getKeepAliveTime(TimeUnit.MILLISECONDS));
            assertInstanceOf(ThreadPoolExecutor.AbortPolicy.class, pool.getRejectedExecutionHandler(),
                    "a full queue is rejected to Operaton's rejected-jobs handler, as with DefaultJobExecutor");
            assertTrue(pool.submit(() -> Thread.currentThread().isVirtual()).get(5, TimeUnit.SECONDS),
                    "jobs run on virtual threads");
            assertTrue(pool.submit(() -> Thread.currentThread().getName()).get(5, TimeUnit.SECONDS)
                    .startsWith("sangam-job-"));
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * I-20 pin: Operaton's {@code DefaultJobExecutor.startExecutingJobs} builds its pool only when there is none (or it
     * is shut down), so the virtual pool installed first is the one that runs the jobs, also after a restart.
     */
    @Test
    void operatonsStartKeepsTheVirtualPoolAcrossARestart() throws Exception {
        SangamJobExecutor je = new SangamJobExecutor(true, Map.of(), Duration.ZERO);
        je.setCorePoolSize(1);
        je.setMaxPoolSize(2);
        for (int round = 0; round < 2; round++) {
            je.start(); // no engine registered: the acquisition thread idles
            try {
                assertTrue(je.getThreadPoolExecutor().submit(() -> Thread.currentThread().isVirtual())
                        .get(5, TimeUnit.SECONDS), "round " + round + ": the started executor runs on virtual threads");
            } finally {
                je.shutdown();
            }
            assertTrue(je.getThreadPoolExecutor().isShutdown());
        }
        DefaultJobExecutor plain = new DefaultJobExecutor();
        plain.start();
        try {
            assertFalse(plain.getThreadPoolExecutor().submit(() -> Thread.currentThread().isVirtual())
                    .get(5, TimeUnit.SECONDS), "Operaton's own executor stays on platform threads");
        } finally {
            plain.shutdown();
        }
    }

    @Test
    void aHostExecutorThatCannotHonourAnOptionIsRefused() {
        SangamEngineSettings s = settings();
        s.setJobVirtualThreads(true);
        StandaloneProcessEngineConfiguration cfg = new StandaloneProcessEngineConfiguration();
        cfg.setJobExecutor(new DefaultJobExecutor());
        SangamProcessEnginePlugin plugin = new SangamProcessEnginePlugin(new SangamEngineComponents(s, EngineDialect.H2));
        ProcessEngineException e = assertThrows(ProcessEngineException.class, () -> plugin.preInit(cfg));
        assertTrue(e.getMessage().contains("M-054") && e.getMessage().contains(SangamJobExecutor.class.getName()),
                e.getMessage());
    }

    @Test
    void aHostProvidedSangamExecutorIsKept() {
        SangamEngineSettings s = settings();
        s.setJobVirtualThreads(true);
        SangamEngineConfigurer c = new SangamEngineConfigurer(s);
        var ds = c.prepare(h2());
        StandaloneProcessEngineConfiguration cfg = new StandaloneProcessEngineConfiguration();
        cfg.setDatabaseSchemaUpdate("true");
        SangamJobExecutor host = new SangamJobExecutor(true, Map.of(), Duration.ZERO);
        cfg.setJobExecutor(host);
        c.configure(cfg, ds);
        ProcessEngine engine = null;
        try {
            engine = cfg.buildProcessEngine();
            assertSame(host, ((ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration()).getJobExecutor());
        } finally {
            if (engine != null) {
                engine.close();
            }
            c.releaseEngineSlot();
        }
    }

    private static ProcessEngine processEngine(ProcessEngineConfigurationImpl cfg) {
        return cfg.getProcessEngine();
    }
}

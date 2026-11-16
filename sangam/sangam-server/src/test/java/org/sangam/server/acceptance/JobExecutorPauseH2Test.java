// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.engine.spring.components.jobexecutor.SpringJobExecutor;
import org.operaton.bpm.model.bpmn.Bpmn;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * IFMSPACKS-EXEC-RACE: on the server's job executor type ({@link SpringJobExecutor} on a {@link ThreadPoolTaskExecutor},
 * as the Spring Boot host builds it), {@code shutdown()} returns while a job batch it already dispatched still runs, so a
 * driver that then ran due jobs itself raced that batch on the same job ids. {@link JobExecutorPause} returns only once
 * the dispatched batch finished, and the executor works again after the pause. Real engine on H2, one async job whose
 * delegate blocks until the test releases it.
 */
class JobExecutorPauseH2Test {

    /** The job's delegate: says when it runs, then waits for the test. */
    public static final class Blocker {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger runs = new AtomicInteger();

        public void run() throws InterruptedException {
            runs.incrementAndGet();
            started.countDown();
            if (!release.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("never released");
            }
        }
    }

    private final Blocker blocker = new Blocker();
    private ThreadPoolTaskExecutor pool;
    private SpringJobExecutor executor;
    private ProcessEngine engine;

    @BeforeEach
    void engine() {
        pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(2);
        pool.setMaxPoolSize(2);
        pool.setQueueCapacity(4);
        pool.setThreadNamePrefix("test-operatonTaskExecutor-");
        pool.initialize();
        executor = new SpringJobExecutor();
        executor.setTaskExecutor(pool);
        executor.setWaitTimeInMillis(50);
        executor.setMaxWait(100);
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:JobExecutorPauseH2Test-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutor(executor);
        cfg.setJobExecutorActivate(false);
        cfg.setBeans(Map.of("blocker", blocker));
        cfg.setProcessEngineName("JobExecutorPauseH2Test-" + System.nanoTime());
        engine = cfg.buildProcessEngine();
        engine.getRepositoryService().createDeployment().addModelInstance("pause.bpmn",
                Bpmn.createExecutableProcess("pause").operatonHistoryTimeToLive(1).startEvent()
                        .serviceTask("block").operatonAsyncBefore().operatonExpression("${blocker.run()}")
                        .endEvent().done()).deploy();
    }

    @AfterEach
    void close() {
        blocker.release.countDown();
        if (executor.isActive()) {
            executor.shutdown();
        }
        engine.close();
        pool.shutdown();
    }

    /** Pins the root cause in Operaton 2.2.0: shutdown() does not wait for a batch the executor already dispatched. */
    @Test
    void plainShutdownReturnsWhileADispatchedBatchStillRuns() throws Exception {
        startBlockedJob();
        executor.shutdown();
        assertFalse(executor.isActive());
        assertEquals(1, pool.getActiveCount(), "the dispatched batch still runs after shutdown() returned");
        assertEquals(1, engine.getManagementService().createJobQuery().count(), "its job is not finished yet");
    }

    @Test
    void thePauseReturnsOnlyAfterTheDispatchedBatchFinishedAndTheExecutorRunsAgainAfterIt() throws Exception {
        startBlockedJob();
        CompletableFuture<JobExecutorPause> pausing = CompletableFuture.supplyAsync(
                () -> JobExecutorPause.pause(executor, Duration.ofSeconds(30)));
        Thread.sleep(500);
        assertFalse(pausing.isDone(), "the pause must not return while a dispatched job still runs");
        blocker.release.countDown();
        JobExecutorPause pause = pausing.get(30, TimeUnit.SECONDS);
        assertTrue(pause.wasActive());
        assertFalse(executor.isActive());
        assertEquals(0, engine.getManagementService().createJobQuery().count(),
                "the in-flight job finished before the driver may run jobs");
        assertEquals(1, blocker.runs.get(), "the job ran once, on the executor; nothing for a driver to collide with");

        pause.close();
        assertTrue(executor.isActive(), "the executor runs again after the pause");
        String second = engine.getRuntimeService().startProcessInstanceByKey("pause").getId();
        long end = System.currentTimeMillis() + 30_000;
        while (engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(second).count() > 0
                && System.currentTimeMillis() < end) {
            Thread.sleep(50);
        }
        assertEquals(0, engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(second).count(),
                "a job after the pause runs on the task executor's fresh pool");
        assertEquals(2, blocker.runs.get());
    }

    @Test
    void pausingAStoppedExecutorIsANoOp() {
        JobExecutorPause pause = JobExecutorPause.pause(executor, Duration.ofSeconds(1));
        assertFalse(pause.wasActive());
        pause.close();
        assertFalse(executor.isActive());
    }

    private void startBlockedJob() throws InterruptedException {
        engine.getRuntimeService().startProcessInstanceByKey("pause");
        executor.start();
        assertTrue(blocker.started.await(30, TimeUnit.SECONDS), "the executor picked up the job");
    }
}

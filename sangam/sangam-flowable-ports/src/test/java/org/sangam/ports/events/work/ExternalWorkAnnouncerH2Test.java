// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.events.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.externaltask.LockedExternalTask;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;

/**
 * M-066 (decision 101): new external work is announced once per committed command that created it, after the commit,
 * through the given hint only; commands that create no external work (user tasks, fetch-and-lock, complete without a
 * follow-up external task) and rolled-back commands announce nothing; requests inside the window coalesce.
 */
class ExternalWorkAnnouncerH2Test {

    private static final String MODEL = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:operaton="http://operaton.org/schema/1.0/bpmn"
                         targetNamespace="t">
              <process id="ext" isExecutable="true" operaton:historyTimeToLive="1">
                <startEvent id="s"/>
                <sequenceFlow id="f1" sourceRef="s" targetRef="pay"/>
                <serviceTask id="pay" operaton:type="external" operaton:topic="pay"/>
                <sequenceFlow id="f2" sourceRef="pay" targetRef="e"/>
                <endEvent id="e"/>
              </process>
              <process id="human" isExecutable="true" operaton:historyTimeToLive="1">
                <startEvent id="hs"/>
                <sequenceFlow id="h1" sourceRef="hs" targetRef="review"/>
                <userTask id="review"/>
                <sequenceFlow id="h2" sourceRef="review" targetRef="he"/>
                <endEvent id="he"/>
              </process>
            </definitions>
            """;

    private ProcessEngine engine;
    private ExternalWorkAnnouncer announcer;
    /** Number of external tasks visible to a new query at the moment of each announcement (proves after-commit). */
    private final List<Long> visibleAtAnnouncement = new CopyOnWriteArrayList<>();

    private void start(Duration coalesce) {
        announcer = new ExternalWorkAnnouncer(
                () -> visibleAtAnnouncement.add(engine.getExternalTaskService().createExternalTaskQuery().count()), coalesce);
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:announce" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        cfg.setJobExecutorActivate(false);
        List<ProcessEnginePlugin> plugins = new ArrayList<>(cfg.getProcessEnginePlugins());
        plugins.add(announcer);
        cfg.setProcessEnginePlugins(plugins);
        engine = cfg.buildProcessEngine();
        engine.getRepositoryService().createDeployment().addString("announce.bpmn", MODEL).deploy();
    }

    @BeforeEach
    void reset() {
        visibleAtAnnouncement.clear();
    }

    @AfterEach
    void tearDown() {
        if (announcer != null) {
            announcer.close();
        }
        if (engine != null) {
            engine.close();
        }
    }

    private void awaitAnnouncements(long n) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (announcer.announcements() < n && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
    }

    /** Waits past the coalescing window and asserts nothing more was sent. */
    private void assertStill(long n) throws InterruptedException {
        Thread.sleep(300);
        assertEquals(n, announcer.announcements());
    }

    @Test
    void aStartThatCreatesAnExternalTaskIsAnnouncedOnceAfterCommit() throws InterruptedException {
        start(Duration.ZERO);
        engine.getRuntimeService().startProcessInstanceByKey("ext");
        awaitAnnouncements(1);
        assertStill(1);
        assertEquals(List.of(1L), visibleAtAnnouncement, "the task was committed and visible when it was announced");
    }

    @Test
    void userTasksFetchAndLockAndTheFinalCompleteAnnounceNothing() throws InterruptedException {
        start(Duration.ZERO);
        engine.getRuntimeService().startProcessInstanceByKey("human");
        assertStill(0);

        engine.getRuntimeService().startProcessInstanceByKey("ext");
        awaitAnnouncements(1);
        List<LockedExternalTask> locked = engine.getExternalTaskService().fetchAndLock(1, "w1").topic("pay", 10_000).execute();
        assertEquals(1, locked.size());
        engine.getExternalTaskService().complete(locked.get(0).getId(), "w1");
        assertStill(1);
    }

    @Test
    void aRolledBackCommandAnnouncesNothing() throws InterruptedException {
        start(Duration.ZERO);
        assertThrows(RuntimeException.class, () -> ((ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration())
                .getCommandExecutorTxRequired().execute(ctx -> {
                    engine.getRuntimeService().startProcessInstanceByKey("ext");
                    throw new IllegalStateException("roll back");
                }));
        assertStill(0);
        assertEquals(0, engine.getExternalTaskService().createExternalTaskQuery().count());
    }

    @Test
    void requestsInsideTheWindowCoalesceIntoOneTrailingAnnouncement() throws InterruptedException {
        start(Duration.ofSeconds(2));
        for (int i = 0; i < 5; i++) {
            engine.getRuntimeService().startProcessInstanceByKey("ext");
        }
        awaitAnnouncements(1);
        assertStill(1);
        assertEquals(List.of(5L), visibleAtAnnouncement, "the trailing announcement covers every task of the window");
    }

    @Test
    void aFailingHintNeverReachesTheCommandAndLaterRequestsStillGoOut() throws InterruptedException {
        AtomicInteger calls = new AtomicInteger();
        announcer = new ExternalWorkAnnouncer(() -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("broker down");
            }
        }, Duration.ZERO);
        announcer.request();
        Thread.sleep(200);
        assertEquals(0, announcer.announcements());
        announcer.request();
        awaitAnnouncements(1);
        assertEquals(1, announcer.announcements());
        assertTrue(calls.get() >= 2);
    }

    @Test
    void aNegativeWindowIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new ExternalWorkAnnouncer(() -> { }, Duration.ofMillis(-1)));
    }
}

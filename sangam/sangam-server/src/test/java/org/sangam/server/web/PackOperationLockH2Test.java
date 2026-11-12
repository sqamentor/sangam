// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.sangam.packs.spi.PackDeployer;
import org.sangam.packs.spi.PackDeployer.PackLifecycleException;
import org.sangam.packs.spi.PackDeployer.PackRejectedException;
import org.sangam.packs.spi.PackDescriptor;
import org.sangam.packs.spi.PackDescriptor.PackResource;
import org.sangam.packs.spi.PackOperationLock;
import org.sangam.packs.spi.PackRegistry;
import org.sangam.packs.spi.SangamPack;
import org.sangam.rest.support.TxSupport;
import org.sangam.server.bridge.PackOperationTxLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * SL-116 on the server (h2 profile, Spring transactions): deploy and rollback of one pack in one tenant are serialised
 * by the {@link PackOperationTxLock} the server wires into its one {@link PackDeployer}. The overlap is forced with
 * latches: thread 1 holds the lock (a nested section of the same lock) until thread 2 is seen waiting inside H2 for the
 * lock row, then releases it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "sangam.packs.operation.lock-timeout=PT30S")
@ActiveProfiles("h2")
@DirtiesContext
class PackOperationLockH2Test {

    static final String TENANT = "dev";
    static final Duration AWAIT = Duration.ofSeconds(30);

    @Autowired
    ProcessEngine engine;

    @Autowired
    PackOperationLock lock;

    @Autowired
    PackDeployer serverDeployer;

    @Autowired
    TxSupport tx;

    final ExecutorService pool = Executors.newFixedThreadPool(2);

    @AfterEach
    void stop() {
        pool.shutdownNow();
    }

    static SangamPack pack(String id, String version, String key, String label) {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                + " xmlns:operaton=\"http://operaton.org/schema/1.0/bpmn\" targetNamespace=\"urn:sangam:test\">"
                + "<process id=\"" + key + "\" name=\"" + label + "\" isExecutable=\"true\" operaton:historyTimeToLive=\"30\""
                + " operaton:versionTag=\"" + version + "\"><startEvent id=\"s\"/>"
                + "<sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"t\"/><userTask id=\"t\" name=\"" + label + "\"/>"
                + "<sequenceFlow id=\"f2\" sourceRef=\"t\" targetRef=\"e\"/><endEvent id=\"e\"/></process></definitions>";
        String path = "sl116/" + id + "/" + key + ".bpmn";
        PackDescriptor d = new PackDescriptor(id, version, Map.of("en", id), "test", "SL-116 lock test", List.of(key),
                List.of(new PackResource(PackResource.Kind.BPMN, path)), null, null, null, List.of("SL-116"), null,
                List.of(), null);
        return new SangamPack() {
            @Override
            public PackDescriptor descriptor() {
                return d;
            }

            @Override
            public InputStream open(String p) {
                return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
            }
        };
    }

    static PackRegistry registry(SangamPack... packs) {
        PackRegistry r = PackRegistry.discover(new ClassLoader(null) { });
        for (SangamPack p : packs) {
            r.add(p);
        }
        return r;
    }

    static String unique(String prefix) {
        return prefix + Long.toString(System.nanoTime(), 36);
    }

    PackDeployer deployer(PackOperationLock l, SangamPack... packs) {
        return new PackDeployer(engine, registry(packs), l);
    }

    long definitions(String key, String versionTag, String tenant) {
        return engine.getRepositoryService().createProcessDefinitionQuery().processDefinitionKey(key)
                .versionTag(versionTag).tenantIdIn(tenant).count();
    }

    /** Installs 1.0.0 then 2.0.0 of a fresh pack for the tenant; returns {id, key}. */
    String[] installTwoVersions(String tenant) {
        String id = unique("sl116.p");
        String key = unique("sl116k");
        deployer(lock, pack(id, "1.0.0", key, "v1")).deploy(List.of(id), tenant);
        deployer(lock, pack(id, "2.0.0", key, "v2")).deploy(List.of(id), tenant);
        return new String[]{id, key};
    }

    /** Thread 1: holds the lock of (tenant, pack) until {@code release}, then runs {@code then} inside it. */
    <T> Future<T> holdThen(String tenant, String packId, CountDownLatch held, CountDownLatch release,
                           java.util.function.Supplier<T> then) {
        return pool.submit(() -> lock.exclusive(tenant, packId, () -> {
            held.countDown();
            try {
                assertTrue(release.await(AWAIT.toSeconds(), TimeUnit.SECONDS), "release");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return then.get();
        }));
    }

    /** True once {@code t} waits inside H2 (a session with a blocker, or the thread parked in org.h2). */
    boolean waitingInH2(Thread t) throws Exception {
        try (Connection c = tx.dataSource().getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS WHERE BLOCKER_ID IS NOT NULL")) {
            rs.next();
            if (rs.getLong(1) > 0) {
                return true;
            }
        }
        Thread.State st = t.getState();
        if (st == Thread.State.WAITING || st == Thread.State.TIMED_WAITING || st == Thread.State.BLOCKED) {
            for (StackTraceElement f : t.getStackTrace()) {
                if (f.getClassName().startsWith("org.h2.")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Runs {@code work} on a new thread and returns once that thread waits for the lock inside H2. */
    <T> Future<T> startBlocked(java.util.concurrent.Callable<T> work) throws Exception {
        Thread[] runner = new Thread[1];
        CountDownLatch started = new CountDownLatch(1);
        Future<T> f = pool.submit(() -> {
            runner[0] = Thread.currentThread();
            started.countDown();
            return work.call();
        });
        assertTrue(started.await(AWAIT.toSeconds(), TimeUnit.SECONDS));
        long deadline = System.nanoTime() + AWAIT.toNanos();
        while (!waitingInH2(runner[0])) {
            assertFalse(f.isDone(), "the second operation finished while the first held the lock: not serialised");
            assertTrue(System.nanoTime() < deadline, "the second operation never waited for the lock");
            LockSupport.parkNanos(2_000_000L); // poll a condition, not a timing assumption
        }
        return f;
    }

    @Test
    void theServerDeployerUsesTheTransactionalLock() {
        assertInstanceOf(PackOperationTxLock.class, lock);
        assertSame(lock, serverDeployer.operationLock(), "onboarding, provisioning and the lifecycle API share it");
    }

    @Test
    void twoConcurrentRollbacksGetDistinctConsecutiveVersions() throws Exception {
        String[] p = installTwoVersions(TENANT);
        PackDeployer d = deployer(lock, pack(p[0], "2.0.0", p[1], "v2"));
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<PackDeployer.Result> first = holdThen(TENANT, p[0], held, release, () -> d.rollback(p[0], TENANT));
        assertTrue(held.await(AWAIT.toSeconds(), TimeUnit.SECONDS));
        Future<PackDeployer.Result> second = startBlocked(() -> d.rollback(p[0], TENANT));
        release.countDown();

        Set<String> versions = new TreeSet<>(List.of(first.get(60, TimeUnit.SECONDS).version(),
                second.get(60, TimeUnit.SECONDS).version()));
        assertEquals(Set.of("2.0.1", "2.0.2"), versions, "distinct consecutive versions, the second from the first's result");
        for (String tag : List.of("1.0.0", "2.0.0", "2.0.1", "2.0.2")) {
            assertEquals(1, definitions(p[1], tag, TENANT), "one definition per (key, versionTag): " + tag);
        }
    }

    @Test
    void aRollbackThatCannotGetTheLockInTimeIs409InProgress() throws Exception {
        String[] p = installTwoVersions(TENANT);
        PackDeployer impatient = deployer(PackOperationTxLock.of(tx, Duration.ofMillis(300)), pack(p[0], "2.0.0", p[1], "v2"));
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<Object> holder = holdThen(TENANT, p[0], held, release, () -> null);
        assertTrue(held.await(AWAIT.toSeconds(), TimeUnit.SECONDS));
        try {
            PackLifecycleException e = assertThrows(PackLifecycleException.class, () -> impatient.rollback(p[0], TENANT));
            assertEquals(PackOperationLock.PACK_OPERATION_IN_PROGRESS, e.code());
        } finally {
            release.countDown();
        }
        holder.get(60, TimeUnit.SECONDS);
        assertEquals(0, definitions(p[1], "2.0.1", TENANT), "nothing deployed by the refused rollback");
        assertEquals("2.0.1", impatient.rollback(p[0], TENANT).version(), "free again: the next rollback runs");
    }

    @Test
    void aRollbackRacingADeployIsSerialised() throws Exception {
        String[] p = installTwoVersions(TENANT);
        PackDeployer rollbacks = deployer(lock, pack(p[0], "2.0.0", p[1], "v2"));
        // a later release that reuses the number the rollback takes (2.0.1), with other content
        PackDeployer release201 = deployer(lock, pack(p[0], "2.0.1", p[1], "v3-release"));
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<PackDeployer.Result> rollback = holdThen(TENANT, p[0], held, release, () -> rollbacks.rollback(p[0], TENANT));
        assertTrue(held.await(AWAIT.toSeconds(), TimeUnit.SECONDS));
        Future<Object> deploy = startBlocked(() -> {
            try {
                return release201.deploy(List.of(p[0]), TENANT);
            } catch (PackRejectedException e) {
                return e;
            }
        });
        release.countDown();

        assertEquals("2.0.1", rollback.get(60, TimeUnit.SECONDS).version());
        Object outcome = deploy.get(60, TimeUnit.SECONDS);
        PackRejectedException refused = assertInstanceOf(PackRejectedException.class, outcome,
                "the deploy ran after the rollback committed 2.0.1, so its other content is refused");
        assertTrue(refused.violations().get(0).startsWith(PackDeployer.PACK_VERSION_IMMUTABLE), refused.getMessage());
        assertEquals(1, definitions(p[1], "2.0.1", TENANT), "never a second definition for 2.0.1");
    }

    @Test
    void anUninstallWaitsForAHeldLockAndSeesTheCommittedDeploy() throws Exception {
        String id = unique("sl116.un");
        String key = unique("sl116k");
        deployer(lock, pack(id, "1.0.0", key, "v1")).deploy(List.of(id), TENANT);
        PackDeployer v2 = deployer(lock, pack(id, "2.0.0", key, "v2"));
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // thread 1 holds the pack's lock and deploys 2.0.0 inside it
        Future<List<PackDeployer.Result>> deploy = holdThen(TENANT, id, held, release, () -> v2.deploy(List.of(id), TENANT));
        assertTrue(held.await(AWAIT.toSeconds(), TimeUnit.SECONDS));
        Future<PackDeployer.Uninstalled> uninstall = startBlocked(() -> v2.uninstall(id, TENANT));
        release.countDown();

        assertTrue(deploy.get(60, TimeUnit.SECONDS).get(0).newDeployment());
        assertEquals(2, uninstall.get(60, TimeUnit.SECONDS).deploymentIds().size(),
                "the uninstall ran after the deploy committed, so it removed 1.0.0 and 2.0.0");
        assertTrue(v2.installed(TENANT).stream().noneMatch(i -> i.packId().equals(id)));
        assertEquals(0, engine.getRepositoryService().createProcessDefinitionQuery().processDefinitionKey(key)
                .tenantIdIn(TENANT).count());
    }

    @Test
    void anotherPackAndAnotherTenantAreNotBlocked() throws Exception {
        String held1 = unique("sl116.held");
        String otherPack = unique("sl116.other");
        String key = unique("sl116k");
        String otherKey = unique("sl116k");
        // the main thread's calls use a short wait: a wrong block fails fast as PACK_OPERATION_IN_PROGRESS
        PackOperationLock impatient = PackOperationTxLock.of(tx, Duration.ofMillis(500));
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<Object> holder = holdThen(TENANT, held1, held, release, () -> null);
        assertTrue(held.await(AWAIT.toSeconds(), TimeUnit.SECONDS));
        try {
            assertTrue(deployer(impatient, pack(otherPack, "1.0.0", otherKey, "o")).deploy(List.of(otherPack), TENANT)
                    .get(0).newDeployment(), "another pack in the same tenant");
            assertTrue(deployer(impatient, pack(held1, "1.0.0", key, "h")).deploy(List.of(held1), "sl116-other")
                    .get(0).newDeployment(), "the same pack in another tenant");
        } finally {
            release.countDown();
        }
        holder.get(60, TimeUnit.SECONDS);
        assertEquals(1, definitions(otherKey, "1.0.0", TENANT));
        assertEquals(1, definitions(key, "1.0.0", "sl116-other"));
    }
}

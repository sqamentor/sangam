// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;

import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.spi.PackDeployer;
import org.sangam.packs.spi.PackDeployer.PackLifecycleException;
import org.sangam.packs.spi.PackDescriptor;
import org.sangam.packs.spi.PackDescriptor.PackResource;
import org.sangam.packs.spi.PackOperationLock;
import org.sangam.packs.spi.PackRegistry;
import org.sangam.packs.spi.SangamPack;

/** SL-116: deploy and rollback of one pack in one tenant scope run inside the {@link PackOperationLock}. */
class PackOperationLockTest {

    static ProcessEngine engine;

    @BeforeAll
    static void engine() {
        PackRuntime.reset();
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-pack-oplock;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        cfg.setHistory("full");
        cfg.setEnforceHistoryTimeToLive(true);
        engine = cfg.buildProcessEngine();
    }

    @AfterAll
    static void close() {
        org.operaton.bpm.engine.impl.util.ClockUtil.reset();
        if (engine != null) {
            engine.close();
        }
    }

    private static void tick() {
        org.operaton.bpm.engine.impl.util.ClockUtil.setCurrentTime(
                new java.util.Date(org.operaton.bpm.engine.impl.util.ClockUtil.getCurrentTime().getTime() + 1000));
    }

    private static SangamPack pack(String id, String version, String key, String label, List<String> dependsOn) {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="sangam-test">
                  <process id="%s" name="%s" isExecutable="true" operaton:historyTimeToLive="P30D" operaton:versionTag="%s">
                    <startEvent id="s"/>
                    <sequenceFlow id="f1" sourceRef="s" targetRef="t"/>
                    <userTask id="t" name="%s"/>
                    <sequenceFlow id="f2" sourceRef="t" targetRef="e"/>
                    <endEvent id="e"/>
                  </process>
                </definitions>
                """.formatted(key, label, version, label);
        String path = "oplock/" + id + "/" + key + ".bpmn";
        PackDescriptor d = new PackDescriptor(id, version, Map.of("en", id), "test", "operation lock test", List.of(key),
                List.of(new PackResource(PackResource.Kind.BPMN, path)), null, null, null, List.of("SL-116"), null,
                dependsOn, null);
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

    private static PackRegistry registry(SangamPack... packs) {
        PackRegistry r = PackRegistry.discover(new ClassLoader(null) { });
        for (SangamPack p : packs) {
            r.add(p);
        }
        return r;
    }

    /** Records every exclusive section (tenant|pack) and whether the work ran inside it. */
    static final class RecordingLock implements PackOperationLock {
        final List<String> sections = new ArrayList<>();
        int depth;
        boolean refuse;

        @Override
        public <T> T exclusive(String tenantId, String packId, Supplier<T> work) {
            sections.add(tenantId + "|" + packId);
            if (refuse) {
                throw new PackLifecycleException(PACK_OPERATION_IN_PROGRESS, "busy", List.of(packId));
            }
            depth++;
            try {
                return work.get();
            } finally {
                depth--;
            }
        }
    }

    @Test
    void theTwoArgumentConstructorSerialisesNothing() {
        assertSame(PackOperationLock.NONE, new PackDeployer(engine, registry()).operationLock());
        assertEquals("x", PackOperationLock.NONE.exclusive("t", "p", () -> "x"));
    }

    @Test
    void deployAndRollbackRunEveryPackInsideItsOwnSection() {
        String tenant = "ol-t1";
        RecordingLock lock = new RecordingLock();
        SangamPack base = pack("ol-base", "1.0.0", "olBase", "base", List.of());
        PackDeployer v1 = new PackDeployer(engine, registry(base, pack("ol-app", "1.0.0", "olApp", "v1", List.of("ol-base"))), lock);
        v1.deploy(List.of("ol-app"), tenant);
        assertEquals(List.of(tenant + "|ol-base", tenant + "|ol-app"), lock.sections, "one section per pack, dependencies first");
        tick();
        PackDeployer v2 = new PackDeployer(engine, registry(base, pack("ol-app", "2.0.0", "olApp", "v2", List.of("ol-base"))), lock);
        v2.deploy(List.of("ol-app"), tenant);
        tick();
        lock.sections.clear();

        assertEquals("2.0.1", v2.rollback("ol-app", tenant).version());
        assertEquals(List.of(tenant + "|ol-app"), lock.sections);
        assertEquals(0, lock.depth);
    }

    @Test
    void uninstallRunsInsideThePacksSectionAndARefusedLockUninstallsNothing() {
        String tenant = "ol-t3";
        RecordingLock lock = new RecordingLock();
        PackDeployer d = new PackDeployer(engine, registry(pack("ol-un", "1.0.0", "olUn", "un", List.of())), lock);
        d.deploy(List.of("ol-un"), tenant);

        lock.sections.clear();
        lock.refuse = true;
        assertEquals(PackOperationLock.PACK_OPERATION_IN_PROGRESS,
                assertThrows(PackLifecycleException.class, () -> d.uninstall("ol-un", tenant)).code());
        assertEquals(List.of(tenant + "|ol-un"), lock.sections);
        assertEquals(1, d.installed(tenant).size(), "nothing uninstalled");
        assertEquals(1, engine.getRepositoryService().createProcessDefinitionQuery().processDefinitionKey("olUn")
                .tenantIdIn(tenant).active().count(), "nothing suspended");

        lock.sections.clear();
        lock.refuse = false;
        assertEquals(1, d.uninstall("ol-un", tenant).deploymentIds().size());
        assertEquals(List.of(tenant + "|ol-un"), lock.sections);
        assertTrue(d.installed(tenant).isEmpty());
    }

    @Test
    void aRefusedLockDeploysNothing() {
        String tenant = "ol-t2";
        RecordingLock lock = new RecordingLock();
        PackDeployer d = new PackDeployer(engine, registry(pack("ol-busy", "1.0.0", "olBusy", "busy", List.of())), lock);
        lock.refuse = true;
        PackLifecycleException e = assertThrows(PackLifecycleException.class, () -> d.deploy(List.of("ol-busy"), tenant));
        assertEquals(PackOperationLock.PACK_OPERATION_IN_PROGRESS, e.code());
        assertTrue(d.installed(tenant).isEmpty(), "nothing deployed");
        assertEquals(PackOperationLock.PACK_OPERATION_IN_PROGRESS,
                assertThrows(PackLifecycleException.class, () -> d.rollback("ol-busy", tenant)).code());
    }
}

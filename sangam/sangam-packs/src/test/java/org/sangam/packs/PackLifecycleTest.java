// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

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
import org.sangam.packs.spi.PackRegistry;
import org.sangam.packs.spi.SangamPack;

/** SL-41: pack uninstall (refused for dependants and live instances, history kept) and rollback to the previous version. */
class PackLifecycleTest {

    static ProcessEngine engine;

    @BeforeAll
    static void engine() {
        PackRuntime.reset();
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-pack-lifecycle;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        cfg.setHistory("full");
        cfg.setEnforceHistoryTimeToLive(true);
        engine = cfg.buildProcessEngine();
    }

    /** Deployment times order versions; advance the engine clock so two deployments never share a millisecond. */
    private static void tick() {
        org.operaton.bpm.engine.impl.util.ClockUtil.setCurrentTime(
                new java.util.Date(org.operaton.bpm.engine.impl.util.ClockUtil.getCurrentTime().getTime() + 1000));
    }

    @AfterAll
    static void close() {
        org.operaton.bpm.engine.impl.util.ClockUtil.reset();
        if (engine != null) {
            engine.close();
        }
    }

    /** A one-task process; {@code label} makes versions differ in content. */
    private static String bpmn(String key, String label) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="sangam-test">
                  <process id="%s" name="%s" isExecutable="true" operaton:historyTimeToLive="P30D">
                    <startEvent id="s"/>
                    <sequenceFlow id="f1" sourceRef="s" targetRef="t"/>
                    <userTask id="t" name="%s"/>
                    <sequenceFlow id="f2" sourceRef="t" targetRef="e"/>
                    <endEvent id="e"/>
                  </process>
                </definitions>
                """.formatted(key, label, label);
    }

    private static SangamPack pack(String id, String version, String processKey, String label, List<String> dependsOn) {
        String path = "lifecycle/" + id + "/" + processKey + ".bpmn";
        String xml = bpmn(processKey, label);
        PackDescriptor d = new PackDescriptor(id, version, Map.of("en", id), "test", "lifecycle test", List.of(processKey),
                List.of(new PackResource(PackResource.Kind.BPMN, path)), null, null, null, List.of("SL-41"), null,
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

    @Test
    void uninstallIsRefusedForDependantsAndLiveInstancesThenRetiresKeepingHistory() {
        String tenant = "lc-t1";
        SangamPack base = pack("lc-base", "1.0.0", "lcBase", "base", List.of());
        SangamPack app = pack("lc-app", "1.0.0", "lcApp", "app", List.of("lc-base"));
        PackDeployer deployer = new PackDeployer(engine, registry(base, app));
        deployer.deploy(List.of("lc-app"), tenant);

        PackLifecycleException dep = assertThrows(PackLifecycleException.class, () -> deployer.uninstall("lc-base", tenant));
        assertEquals("PACK_HAS_DEPENDANTS", dep.code());
        assertEquals(List.of("lc-app"), dep.blockers());

        var rt = engine.getRuntimeService();
        String pi = rt.createProcessInstanceByKey("lcApp").processDefinitionTenantId(tenant).execute().getId();
        PackLifecycleException live = assertThrows(PackLifecycleException.class, () -> deployer.uninstall("lc-app", tenant));
        assertEquals("PACK_HAS_LIVE_INSTANCES", live.code());
        assertTrue(live.blockers().get(0).contains("1 process instance"), live.blockers().toString());
        assertEquals(1, engine.getRepositoryService().createProcessDefinitionQuery().processDefinitionKey("lcApp")
                .tenantIdIn(tenant).active().count(), "a refused uninstall changes nothing");

        var task = engine.getTaskService().createTaskQuery().processInstanceId(pi).singleResult();
        engine.getTaskService().complete(task.getId());
        PackDeployer.Uninstalled done = deployer.uninstall("lc-app", tenant);
        assertEquals(1, done.deploymentIds().size());
        assertEquals(0, engine.getRepositoryService().createProcessDefinitionQuery().processDefinitionKey("lcApp")
                .tenantIdIn(tenant).count(), "definitions retired");
        assertEquals(1, engine.getHistoryService().createHistoricProcessInstanceQuery().processInstanceId(pi).count(),
                "history is kept for audit");
        assertTrue(deployer.installed(tenant).stream().noneMatch(i -> i.packId().equals("lc-app")));

        deployer.uninstall("lc-base", tenant); // no dependant left
        assertTrue(deployer.installed(tenant).isEmpty());
        assertEquals("PACK_NOT_INSTALLED",
                assertThrows(PackLifecycleException.class, () -> deployer.uninstall("lc-base", tenant)).code());
    }

    @Test
    void uninstallInOneTenantLeavesAnotherTenantAlone() {
        SangamPack p = pack("lc-iso", "1.0.0", "lcIso", "iso", List.of());
        PackDeployer deployer = new PackDeployer(engine, registry(p));
        deployer.deploy(List.of("lc-iso"), "lc-a");
        deployer.deploy(List.of("lc-iso"), "lc-b");
        deployer.uninstall("lc-iso", "lc-a");
        assertEquals(0, engine.getRepositoryService().createProcessDefinitionQuery().processDefinitionKey("lcIso")
                .tenantIdIn("lc-a").count());
        assertEquals(1, engine.getRepositoryService().createProcessDefinitionQuery().processDefinitionKey("lcIso")
                .tenantIdIn("lc-b").count());
    }

    @Test
    void rollbackRedeploysThePreviousVersionWhileRunningInstancesKeepTheirs() {
        String tenant = "lc-t2";
        new PackDeployer(engine, registry(pack("lc-rb", "1.0.0", "lcRb", "v1-task", List.of()))).deploy(List.of("lc-rb"), tenant);
        PackDeployer v2 = new PackDeployer(engine, registry(pack("lc-rb", "2.0.0", "lcRb", "v2-task", List.of())));
        tick();
        v2.deploy(List.of("lc-rb"), tenant);
        tick();
        var rt = engine.getRuntimeService();
        String onV2 = rt.createProcessInstanceByKey("lcRb").processDefinitionTenantId(tenant).execute().getId();

        PackDeployer.Result r = v2.rollback("lc-rb", tenant);
        assertEquals("1.0.0", r.version());
        assertTrue(r.newDeployment());
        List<PackDeployer.Installed> installed = v2.installed(tenant);
        assertEquals(List.of("1.0.0", "2.0.0", "1.0.0"), installed.stream().map(PackDeployer.Installed::version).toList(),
                () -> engine.getRepositoryService().createDeploymentQuery().list().stream()
                        .map(x -> x.getName() + "/" + x.getTenantId() + "/" + x.getSource() + "/" + x.getDeploymentTime().getTime()).toList().toString());

        String newPi = rt.createProcessInstanceByKey("lcRb").processDefinitionTenantId(tenant).execute().getId();
        assertEquals("v1-task", engine.getTaskService().createTaskQuery().processInstanceId(newPi).singleResult().getName(),
                "new instances start on the previous version's model");
        assertEquals("v2-task", engine.getTaskService().createTaskQuery().processInstanceId(onV2).singleResult().getName(),
                "a running instance keeps its definition");
        assertEquals(3, engine.getRepositoryService().createProcessDefinitionQuery().processDefinitionKey("lcRb")
                .tenantIdIn(tenant).latestVersion().singleResult().getVersion());

        tick();
        // rolling back again returns to 2.0.0 (the most recent different version): rollback is itself reversible
        assertEquals("2.0.0", v2.rollback("lc-rb", tenant).version());
    }

    @Test
    void rollbackWithoutAPreviousVersionIsRefused() {
        PackDeployer d = new PackDeployer(engine, registry(pack("lc-one", "1.0.0", "lcOne", "one", List.of())));
        d.deploy(List.of("lc-one"), "lc-t3");
        assertEquals("PACK_NO_PREVIOUS_VERSION",
                assertThrows(PackLifecycleException.class, () -> d.rollback("lc-one", "lc-t3")).code());
        assertEquals("PACK_NOT_INSTALLED",
                assertThrows(PackLifecycleException.class, () -> d.rollback("lc-one", "lc-other")).code());
    }
}

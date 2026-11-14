// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;

import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.spi.PackDeployer;
import org.sangam.packs.spi.PackDeployer.PackRejectedException;
import org.sangam.packs.spi.PackDescriptor;
import org.sangam.packs.spi.PackDescriptor.PackResource;
import org.sangam.packs.spi.PackRegistry;
import org.sangam.packs.spi.SangamPack;

/**
 * SL-54: a pack version is immutable. Cross-pack calls are pinned by {@code versionTag} (packgen
 * {@code pin_cross_pack_calls}), and Operaton refuses a call whose (key, versionTag, tenant) matches more than one
 * definition, so the same {@code id@version} with different content is refused at deploy and every pinned call keeps
 * resolving to exactly one definition.
 */
class PackVersionImmutabilityTest {

    static ProcessEngine engine;

    @BeforeAll
    static void engine() {
        PackRuntime.reset();
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-pack-immutability;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        cfg.setHistory("full");
        cfg.setEnforceHistoryTimeToLive(true);
        PackTestEngines.configure(cfg); // decisions 138 and FEEL (sangam-feel): as SangamProcessEnginePlugin does
        engine = cfg.buildProcessEngine();
    }

    @AfterAll
    static void close() {
        org.operaton.bpm.engine.impl.util.ClockUtil.reset();
        if (engine != null) {
            engine.close();
        }
    }

    /** Deployment times order versions; advance the engine clock so two deployments never share a millisecond. */
    private static void tick() {
        org.operaton.bpm.engine.impl.util.ClockUtil.setCurrentTime(
                new java.util.Date(org.operaton.bpm.engine.impl.util.ClockUtil.getCurrentTime().getTime() + 1000));
    }

    /** A one-task process carrying {@code operaton:versionTag} = its pack version, as every generated process does. */
    private static String callee(String key, String versionTag, String label) {
        return """
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
                """.formatted(key, label, versionTag, label);
    }

    /** A process calling another pack's process pinned by versionTag, exactly as packgen pins cross-pack calls. */
    private static String caller(String key, String versionTag, String calledKey, String calledVersionTag) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="sangam-test">
                  <process id="%s" name="%s" isExecutable="true" operaton:historyTimeToLive="P30D" operaton:versionTag="%s">
                    <startEvent id="s"/>
                    <sequenceFlow id="f1" sourceRef="s" targetRef="c"/>
                    <callActivity id="c" calledElement="%s" operaton:calledElementBinding="versionTag" operaton:calledElementVersionTag="%s"/>
                    <sequenceFlow id="f2" sourceRef="c" targetRef="e"/>
                    <endEvent id="e"/>
                  </process>
                </definitions>
                """.formatted(key, key, versionTag, calledKey, calledVersionTag);
    }

    /** A pack of the given resources (path to XML), all BPMN. */
    private static SangamPack pack(String id, String version, Map<String, String> bpmnByPath, List<String> dependsOn) {
        List<PackResource> resources = new ArrayList<>();
        for (String path : bpmnByPath.keySet()) {
            resources.add(new PackResource(PackResource.Kind.BPMN, path));
        }
        PackDescriptor d = new PackDescriptor(id, version, Map.of("en", id), "test", "immutability test", List.of(),
                resources, null, null, null, List.of("SL-54"), null, dependsOn, null);
        return new SangamPack() {
            @Override
            public PackDescriptor descriptor() {
                return d;
            }

            @Override
            public InputStream open(String p) {
                return new ByteArrayInputStream(bpmnByPath.get(p).getBytes(StandardCharsets.UTF_8));
            }
        };
    }

    private static SangamPack calleePack(String id, String version, String key, String label) {
        return pack(id, version, Map.of("imm/" + id + "/" + key + ".bpmn", callee(key, version, label)), List.of());
    }

    private static SangamPack callerPack(String id, String key, String calleeId, String calleeKey, String calleeVersion) {
        return pack(id, "1.0.0", Map.of("imm/" + id + "/" + key + ".bpmn", caller(key, "1.0.0", calleeKey, calleeVersion)),
                List.of(calleeId));
    }

    private static PackRegistry registry(SangamPack... packs) {
        PackRegistry r = PackRegistry.discover(new ClassLoader(null) { });
        for (SangamPack p : packs) {
            r.add(p);
        }
        return r;
    }

    private static long definitions(String key, String versionTag, String tenant) {
        return engine.getRepositoryService().createProcessDefinitionQuery().processDefinitionKey(key)
                .versionTag(versionTag).tenantIdIn(tenant).count();
    }

    /** Starts the caller in the tenant and returns the name of the task the pinned call reached. */
    private static String calledTaskName(String callerKey, String tenant) {
        var rt = engine.getRuntimeService();
        String pi = rt.createProcessInstanceByKey(callerKey).processDefinitionTenantId(tenant).execute().getId();
        String sub = rt.createProcessInstanceQuery().superProcessInstanceId(pi).singleResult().getId();
        return engine.getTaskService().createTaskQuery().processInstanceId(sub).singleResult().getName();
    }

    @Test
    void identicalRedeployOfAVersionCreatesNothing() {
        String tenant = "imm-t1";
        SangamPack p = calleePack("imm-same", "1.0.0", "immSame", "same");
        PackDeployer deployer = new PackDeployer(engine, registry(p));
        assertTrue(deployer.deploy(List.of("imm-same"), tenant).get(0).newDeployment());
        tick();
        PackDeployer.Result again = new PackDeployer(engine, registry(calleePack("imm-same", "1.0.0", "immSame", "same")))
                .deploy(List.of("imm-same"), tenant).get(0);
        assertFalse(again.newDeployment(), "same content under the same version is filtered as a duplicate");
        assertEquals(1, deployer.installed(tenant).size());
        assertEquals(1, definitions("immSame", "1.0.0", tenant));
    }

    @Test
    void changedContentUnderTheSameVersionIsRefusedAndPinnedCallsKeepResolvingToOneDefinition() {
        String tenant = "imm-t2";
        SangamPack calleeV1 = calleePack("imm-callee", "1.0.0", "immCallee", "v1");
        SangamPack callerP = callerPack("imm-caller", "immCaller", "imm-callee", "immCallee", "1.0.0");
        new PackDeployer(engine, registry(calleeV1, callerP)).deploy(List.of("imm-caller"), tenant);
        assertEquals("v1", calledTaskName("immCaller", tenant));
        tick();

        // the same version shipped again with changed content (the SL-54 trigger): refused, nothing deployed
        SangamPack changed = calleePack("imm-callee", "1.0.0", "immCallee", "v1-changed");
        PackDeployer redeploy = new PackDeployer(engine, registry(changed, callerP));
        PackRejectedException e = assertThrows(PackRejectedException.class,
                () -> redeploy.deploy(List.of("imm-caller"), tenant));
        String violation = e.violations().get(0);
        assertTrue(violation.startsWith(PackDeployer.PACK_VERSION_IMMUTABLE + ": pack imm-callee@1.0.0"), violation);
        assertTrue(violation.contains("imm/imm-callee/immCallee.bpmn (changed)"), violation);
        assertTrue(violation.contains("sha256"), violation);
        assertEquals(1, definitions("immCallee", "1.0.0", tenant), "no second definition for the pinned version tag");
        assertEquals(1, redeploy.installed(tenant).stream().filter(i -> i.packId().equals("imm-callee")).count());
        assertEquals("v1", calledTaskName("immCaller", tenant), "the pinned call still resolves to exactly one definition");
        tick();

        // the change shipped as a new version deploys; callers pinned to 1.0.0 keep resolving to exactly one definition
        SangamPack calleeV11 = calleePack("imm-callee", "1.1.0", "immCallee", "v1-changed");
        new PackDeployer(engine, registry(calleeV11, callerP)).deploy(List.of("imm-caller"), tenant);
        assertEquals(1, definitions("immCallee", "1.0.0", tenant));
        assertEquals(1, definitions("immCallee", "1.1.0", tenant));
        assertEquals("v1", calledTaskName("immCaller", tenant));
    }

    @Test
    void addedOrRemovedResourceUnderTheSameVersionIsRefused() {
        String tenant = "imm-t3";
        Map<String, String> one = new LinkedHashMap<>();
        one.put("imm/imm-res/immResA.bpmn", callee("immResA", "1.0.0", "a"));
        new PackDeployer(engine, registry(pack("imm-res", "1.0.0", one, List.of()))).deploy(List.of("imm-res"), tenant);
        tick();

        Map<String, String> two = new LinkedHashMap<>(one);
        two.put("imm/imm-res/immResB.bpmn", callee("immResB", "1.0.0", "b"));
        PackDeployer added = new PackDeployer(engine, registry(pack("imm-res", "1.0.0", two, List.of())));
        String v = assertThrows(PackRejectedException.class, () -> added.deploy(List.of("imm-res"), tenant))
                .violations().get(0);
        assertTrue(v.contains("imm/imm-res/immResB.bpmn (added)"), v);
        assertEquals(0, engine.getRepositoryService().createProcessDefinitionQuery().processDefinitionKey("immResB")
                .tenantIdIn(tenant).count());
    }

    @Test
    void theRuleHoldsPerTenantScope() {
        // each tenant scope resolves version tags only within itself, so content may differ between scopes (GP-37)
        new PackDeployer(engine, registry(calleePack("imm-scope", "1.0.0", "immScope", "a")))
                .deploy(List.of("imm-scope"), "imm-a");
        PackDeployer.Result b = new PackDeployer(engine, registry(calleePack("imm-scope", "1.0.0", "immScope", "b")))
                .deploy(List.of("imm-scope"), "imm-b").get(0);
        assertTrue(b.newDeployment());
        assertEquals(1, definitions("immScope", "1.0.0", "imm-a"));
        assertEquals(1, definitions("immScope", "1.0.0", "imm-b"));
    }

    /** Every resource of a deployment, by name. */
    private static Map<String, byte[]> resources(String deploymentId) {
        var repo = engine.getRepositoryService();
        Map<String, byte[]> out = new java.util.TreeMap<>();
        for (String n : repo.getDeploymentResourceNames(deploymentId)) {
            try (InputStream in = repo.getResourceAsStream(deploymentId, n)) {
                out.put(n, in.readAllBytes());
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
        return out;
    }

    private static String deploymentOf(PackDeployer d, String tenant, String packId, String version) {
        return d.installed(tenant).stream().filter(i -> i.packId().equals(packId) && i.version().equals(version))
                .map(PackDeployer.Installed::deploymentId).reduce((a, b) -> {
                    throw new AssertionError("two deployments of " + packId + "@" + version);
                }).orElseThrow();
    }

    /** The resources with every occurrence of one version string replaced (the test models carry it nowhere else). */
    private static Map<String, String> withVersion(Map<String, byte[]> resources, String from, String to) {
        Map<String, String> out = new java.util.TreeMap<>();
        resources.forEach((n, b) -> out.put(n, new String(b, StandardCharsets.UTF_8).replace(from, to)));
        return out;
    }

    private static Map<String, String> text(Map<String, byte[]> resources) {
        Map<String, String> out = new java.util.TreeMap<>();
        resources.forEach((n, b) -> out.put(n, new String(b, StandardCharsets.UTF_8)));
        return out;
    }

    @Test
    void rollbackRollsForwardAndKeepsEveryPinnedCallResolvingToOneDefinition() {
        String tenant = "imm-t4";
        SangamPack callerP = callerPack("imm-rb-caller", "immRbCaller", "imm-rb", "immRb", "1.0.0");
        new PackDeployer(engine, registry(calleePack("imm-rb", "1.0.0", "immRb", "v1"), callerP))
                .deploy(List.of("imm-rb-caller"), tenant);
        tick();
        PackDeployer v2 = new PackDeployer(engine, registry(calleePack("imm-rb", "2.0.0", "immRb", "v2"), callerP));
        v2.deploy(List.of("imm-rb-caller"), tenant);
        tick();
        String dep100 = deploymentOf(v2, tenant, "imm-rb", "1.0.0");
        String dep200 = deploymentOf(v2, tenant, "imm-rb", "2.0.0");
        Map<String, byte[]> before100 = resources(dep100);
        Map<String, byte[]> before200 = resources(dep200);

        // the rollback to 1.0.0's content is a roll-forward to a new version above the highest one (2.0.0)
        PackDeployer.Result r = v2.rollback("imm-rb", tenant);
        assertEquals("2.0.1", r.version());
        assertTrue(r.newDeployment());
        assertEquals(List.of("1.0.0", "2.0.0", "2.0.1"), v2.installed(tenant).stream()
                .filter(i -> i.packId().equals("imm-rb")).map(PackDeployer.Installed::version).toList());

        // every (key, versionTag) still matches exactly one definition, and the pinned caller still resolves
        assertEquals(1, definitions("immRb", "1.0.0", tenant));
        assertEquals(1, definitions("immRb", "2.0.0", tenant));
        assertEquals(1, definitions("immRb", "2.0.1", tenant));
        assertEquals("v1", calledTaskName("immRbCaller", tenant), "the caller pinned to 1.0.0 resolves to one definition");

        // the rolled-forward version carries the previous content, apart from the version-bearing attributes
        Map<String, byte[]> rolled = resources(r.deploymentId());
        assertEquals(withVersion(before100, "1.0.0", "2.0.1"), text(rolled));
        // ... and new instances started by key start on it; a caller pinned to the new version binds to it as well
        assertEquals("v1", engine.getTaskService().createTaskQuery().processInstanceId(engine.getRuntimeService()
                .createProcessInstanceByKey("immRb").processDefinitionTenantId(tenant).execute().getId())
                .singleResult().getName());
        // (the class-path 2.0.0 is deployed again with it, as on a restart: same content, so it is reused)
        PackDeployer.Result restart = new PackDeployer(engine, registry(calleePack("imm-rb", "2.0.0", "immRb", "v2"),
                callerPack("imm-rb-caller2", "immRbCaller2", "imm-rb", "immRb", "2.0.1")))
                .deploy(List.of("imm-rb-caller2"), tenant).get(0);
        assertFalse(restart.newDeployment(), "a restart does not undo the rollback");
        assertEquals("v1", calledTaskName("immRbCaller2", tenant));

        // the original id@version deployments are untouched
        assertEquals(text(before100), text(resources(dep100)));
        assertEquals(text(before200), text(resources(dep200)));
        assertEquals(dep100, deploymentOf(v2, tenant, "imm-rb", "1.0.0"));
        assertEquals(dep200, deploymentOf(v2, tenant, "imm-rb", "2.0.0"));
        tick();

        // a second rollback is deterministic: the most recent different version (2.0.0) rolls forward to 2.0.2
        PackDeployer.Result again = v2.rollback("imm-rb", tenant);
        assertEquals("2.0.2", again.version());
        assertEquals(withVersion(before200, "2.0.0", "2.0.2"), text(resources(again.deploymentId())));
        for (String tag : List.of("1.0.0", "2.0.0", "2.0.1", "2.0.2")) {
            assertEquals(1, definitions("immRb", tag, tenant), tag);
        }
        assertEquals("v1", calledTaskName("immRbCaller", tenant));
        assertEquals("v1", calledTaskName("immRbCaller2", tenant));
    }

    @Test
    void rollbackRefusesAVersionThatIsNotMajorMinorPatch() {
        String tenant = "imm-t7";
        new PackDeployer(engine, registry(calleePack("imm-odd", "1.0", "immOdd", "a"))).deploy(List.of("imm-odd"), tenant);
        tick();
        PackDeployer d = new PackDeployer(engine, registry(calleePack("imm-odd", "1.1", "immOdd", "b")));
        d.deploy(List.of("imm-odd"), tenant);
        tick();
        PackDeployer.PackLifecycleException e = assertThrows(PackDeployer.PackLifecycleException.class,
                () -> d.rollback("imm-odd", tenant));
        assertEquals("PACK_VERSION_NOT_SEMVER", e.code());
        assertEquals(1, definitions("immOdd", "1.0", tenant), "nothing deployed");
    }

    // ------------------------------------------------------------------ canonical bytes: line endings (SL-54 Q3)

    /** A pack of raw files: {@code .bpmn} files are BPMN resources, everything else OTHER. */
    private static SangamPack rawPack(String id, String version, Map<String, byte[]> files) {
        List<PackResource> resources = new ArrayList<>();
        for (String path : files.keySet()) {
            resources.add(new PackResource(path.endsWith(".bpmn") ? PackResource.Kind.BPMN : PackResource.Kind.OTHER, path));
        }
        PackDescriptor d = new PackDescriptor(id, version, Map.of("en", id), "test", "immutability test", List.of(),
                resources, null, null, null, List.of("SL-54"), null, List.of(), null);
        return new SangamPack() {
            @Override
            public PackDescriptor descriptor() {
                return d;
            }

            @Override
            public InputStream open(String p) {
                return new ByteArrayInputStream(files.get(p));
            }
        };
    }

    private static byte[] lf(String s) {
        return s.replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] crlf(String s) {
        return s.replace("\r\n", "\n").replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void canonicalFormNormalisesLineEndingsOfTextAndKeepsBinaryByteExact() {
        byte[] mixed = "a\r\nb\rc\nd".getBytes(StandardCharsets.UTF_8);
        assertEquals("a\nb\nc\nd", new String(PackDeployer.canonical("x/model.bpmn", mixed), StandardCharsets.UTF_8));
        for (String text : List.of("m.bpmn", "d.dmn", "c.cmmn", "s.json", "f.form", "x.xml", "r.md", "n.txt", "UP.BPMN")) {
            assertTrue(PackDeployer.isTextResource(text), text);
        }
        byte[] binary = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', '\r'};
        assertFalse(PackDeployer.isTextResource("logo.png"));
        assertTrue(java.util.Arrays.equals(binary, PackDeployer.canonical("logo.png", binary)), "binary is byte-exact");

        String xml = callee("immSha", "1.0.0", "sha");
        assertEquals(PackDeployer.contentSha256(Map.of("p/immSha.bpmn", lf(xml))),
                PackDeployer.contentSha256(Map.of("p/immSha.bpmn", crlf(xml))), "line endings never change the checksum");
        assertFalse(PackDeployer.contentSha256(Map.of("logo.png", new byte[] {'a', '\r', '\n'}))
                .equals(PackDeployer.contentSha256(Map.of("logo.png", new byte[] {'a', '\n'}))), "binary bytes count");
    }

    @Test
    void theSamePackInCrlfAndLfIsTheSameVersionAndReusesItsDefinitions() {
        String tenant = "imm-t5";
        String xml = callee("immEol", "1.0.0", "eol");
        PackDeployer.Result first = new PackDeployer(engine, registry(
                rawPack("imm-eol", "1.0.0", Map.of("imm/imm-eol/immEol.bpmn", lf(xml))))).deploy(List.of("imm-eol"), tenant).get(0);
        assertTrue(first.newDeployment());
        tick();

        PackDeployer crlfDeployer = new PackDeployer(engine, registry(
                rawPack("imm-eol", "1.0.0", Map.of("imm/imm-eol/immEol.bpmn", crlf(xml)))));
        PackDeployer.Result again = crlfDeployer.deploy(List.of("imm-eol"), tenant).get(0);
        assertFalse(again.newDeployment(), "a CRLF checkout of the same pack is not a change");
        assertEquals(first.deploymentId(), again.deploymentId(), "the deployed definitions are reused");
        assertEquals(1, crlfDeployer.installed(tenant).size());
        assertEquals(1, definitions("immEol", "1.0.0", tenant), "no second definition for the version tag");
        tick();

        // a real change is still refused, whatever its line endings
        PackDeployer changed = new PackDeployer(engine, registry(
                rawPack("imm-eol", "1.0.0", Map.of("imm/imm-eol/immEol.bpmn", crlf(callee("immEol", "1.0.0", "eol-changed"))))));
        String v = assertThrows(PackRejectedException.class, () -> changed.deploy(List.of("imm-eol"), tenant))
                .violations().get(0);
        assertTrue(v.startsWith(PackDeployer.PACK_VERSION_IMMUTABLE), v);
        assertTrue(v.contains("imm/imm-eol/immEol.bpmn (changed)"), v);
        assertEquals(1, definitions("immEol", "1.0.0", tenant));
    }

    @Test
    void aBinaryResourceIsComparedByteExact() {
        String tenant = "imm-t6";
        String xml = callee("immBin", "1.0.0", "bin");
        Map<String, byte[]> v1 = new LinkedHashMap<>();
        v1.put("imm/imm-bin/immBin.bpmn", lf(xml));
        v1.put("imm/imm-bin/logo.png", new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n'});
        new PackDeployer(engine, registry(rawPack("imm-bin", "1.0.0", v1))).deploy(List.of("imm-bin"), tenant);
        tick();

        Map<String, byte[]> sameBinaryCrlfText = new LinkedHashMap<>(v1);
        sameBinaryCrlfText.put("imm/imm-bin/immBin.bpmn", crlf(xml));
        assertFalse(new PackDeployer(engine, registry(rawPack("imm-bin", "1.0.0", sameBinaryCrlfText)))
                .deploy(List.of("imm-bin"), tenant).get(0).newDeployment());

        Map<String, byte[]> lfBinary = new LinkedHashMap<>(v1);
        lfBinary.put("imm/imm-bin/logo.png", new byte[] {(byte) 0x89, 'P', 'N', 'G', '\n'});
        PackDeployer d = new PackDeployer(engine, registry(rawPack("imm-bin", "1.0.0", lfBinary)));
        String v = assertThrows(PackRejectedException.class, () -> d.deploy(List.of("imm-bin"), tenant))
                .violations().get(0);
        assertTrue(v.contains("imm/imm-bin/logo.png (changed)"), v);
    }
}

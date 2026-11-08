// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.fwt.engine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.operaton.bpm.engine.runtime.Job;
import org.sangam.core.layer.Jdbc;
import org.sangam.ports.content.ContentService;
import org.sangam.ports.content.FileSystemDocumentStore;
import org.sangam.ports.debug.DebuggerService;
import org.sangam.ports.support.EngineTx;
import org.sangam.ports.support.PortsProblem;
import org.sangam.ports.tenancy.ChangeTenantService;

/** Debugger (FW-38), content (FW-39/40), change tenant (FW-46), EngineTx (FW-23): FWT-042..045, 050, 056. */
class FwtServicesTest {

    FwtEngine f;

    @BeforeEach
    void setUp() {
        f = new FwtEngine();
    }

    @AfterEach
    void tearDown() {
        f.close();
    }

    static final String ASYNC = FwtEngine.defs("""
              <message id="m" name="poke"/>
              <process id="dbg" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="fork"/>
                <parallelGateway id="fork"/>
                <sequenceFlow id="f2" sourceRef="fork" targetRef="a"/>
                <serviceTask id="a" operaton:asyncBefore="true" operaton:expression="${true}"/>
                <sequenceFlow id="f3" sourceRef="a" targetRef="e1"/><endEvent id="e1"/>
                <sequenceFlow id="f4" sourceRef="fork" targetRef="tm"/>
                <intermediateCatchEvent id="tm"><timerEventDefinition><timeDuration>PT1H</timeDuration></timerEventDefinition></intermediateCatchEvent>
                <sequenceFlow id="f5" sourceRef="tm" targetRef="e2"/><endEvent id="e2"/>
                <sequenceFlow id="f6" sourceRef="fork" targetRef="rc"/>
                <receiveTask id="rc" messageRef="m"/><sequenceFlow id="f7" sourceRef="rc" targetRef="e3"/><endEvent id="e3"/>
              </process>""");

    private DebuggerService debugger() {
        return new DebuggerService(f.engine, FwtEngine.D, EngineTx.transactions(f.engine), Set.of("sbx"), true);
    }

    // FWT-042
    @Test
    void fwt042_debuggerRefusesNonSandboxTenant() {
        f.deploy("dbg.bpmn", ASYNC, "prod");
        String pid = f.engine.getRuntimeService().startProcessInstanceByKey("dbg").getId();
        DebuggerService d = debugger();
        Job async = f.engine.getManagementService().createJobQuery().processInstanceId(pid).messages().singleResult();
        Job timer = f.engine.getManagementService().createJobQuery().processInstanceId(pid).timers().singleResult();
        String exec = f.engine.getRuntimeService().createExecutionQuery().processInstanceId(pid).activityId("rc").singleResult().getId();
        for (Runnable r : new Runnable[] {() -> d.step(async.getId()), () -> d.fireTimer(timer.getId()),
                () -> d.setVariables(exec, Map.of("x", 1)), () -> d.sendMessage("poke", pid, Map.of())}) {
            PortsProblem p = assertThrows(PortsProblem.class, r::run);
            assertEquals("NOT_SANDBOX", p.code());
            assertEquals(403, p.status());
        }
        Job a2 = f.engine.getManagementService().createJobQuery().jobId(async.getId()).singleResult();
        Job t2 = f.engine.getManagementService().createJobQuery().jobId(timer.getId()).singleResult();
        assertNotNull(a2, "job untouched");
        assertNotNull(t2, "timer untouched");
        assertEquals(timer.getDuedate(), t2.getDuedate());
        assertEquals(async.getRetries(), a2.getRetries());
        assertTrue(f.engine.getRuntimeService().getVariables(pid).isEmpty());
        assertEquals(1, f.engine.getRuntimeService().createExecutionQuery().processInstanceId(pid).activityId("rc").count());
    }

    // FWT-043
    @Test
    void fwt043_resumeStopsAtThousandSteps() {
        f.deploy("loop.bpmn", FwtEngine.defs("""
              <process id="loop" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="t"/>
                <serviceTask id="t" operaton:asyncBefore="true" operaton:expression="${true}"/>
                <sequenceFlow id="f2" sourceRef="t" targetRef="t"/>
              </process>"""), "sbx");
        String pid = f.engine.getRuntimeService().startProcessInstanceByKey("loop").getId();
        assertEquals(DebuggerService.MAX_RESUME_STEPS, debugger().resume(pid, 1_000_000));
        assertEquals(1000, DebuggerService.MAX_RESUME_STEPS);
        assertEquals(1, f.engine.getManagementService().createJobQuery().processInstanceId(pid).count(), "still looping, stopped");
    }

    /** A lazily generated stream of {@code size} bytes (nothing is allocated up front). */
    static InputStream generated(long size) {
        return new InputStream() {
            long left = size;

            @Override
            public int read() {
                return left-- > 0 ? 'x' : -1;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (left <= 0) {
                    return -1;
                }
                int n = (int) Math.min(len, left);
                java.util.Arrays.fill(b, off, off + n, (byte) 'x');
                left -= n;
                return n;
            }
        };
    }

    private static long files(Path root) throws Exception {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(Files::isRegularFile).count();
        }
    }

    // FWT-044
    @Test
    void fwt044_uploadOverDefaultLimitRefusedNothingStored(@TempDir Path dir) throws Exception {
        ContentService cs = new ContentService(FwtEngine.D, EngineTx.transactions(f.engine), new FileSystemDocumentStore(dir), null, false);
        PortsProblem p = assertThrows(PortsProblem.class, () -> cs.create("t1", "big.bin", "application/octet-stream", "process", "p1",
                null, generated(300L * 1024 * 1024), "u"));
        assertEquals(413, p.status());
        assertEquals("PAYLOAD_TOO_LARGE", p.code());
        assertEquals(0, files(dir), "no object left in the store");
        assertEquals(0, f.count("SELECT COUNT(*) FROM CONTENT_ITEM"));
        assertEquals(0, f.count("SELECT COUNT(*) FROM CONTENT_VERSION"));
    }

    // FWT-045
    @Test
    void fwt045_openOtherTenantsItemIsNotFound(@TempDir Path dir) throws Exception {
        ContentService cs = new ContentService(FwtEngine.D, EngineTx.transactions(f.engine), new FileSystemDocumentStore(dir), null, false);
        var item = cs.create("tenantA", "a.txt", "text/plain", "process", "p1", null, new ByteArrayInputStream("hello".getBytes()), "u");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> cs.open("tenantB", item.id(), 0));
        assertTrue(e.getMessage().contains("not found"), e.getMessage());
        try (InputStream in = cs.open("tenantA", item.id(), 0)) {
            assertArrayEquals("hello".getBytes(), in.readAllBytes());
        }
    }

    // FWT-050
    @Test
    void fwt050_changeTenantItemWithoutTargetTenant() {
        f.deploy("one.bpmn", FwtEngine.defs("""
              <process id="one" isExecutable="true"><startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="t"/>
                <userTask id="t"/><sequenceFlow id="f2" sourceRef="t" targetRef="e"/><endEvent id="e"/></process>"""), "src");
        String pid = f.engine.getRuntimeService().startProcessInstanceByKey("one").getId();
        var handler = new ChangeTenantService(f.engine, f.plugin.links(), null).asBatchHandler();
        assertThrows(IllegalArgumentException.class, () -> handler.execute("src", pid, Map.of("user", "u")));
        assertEquals(0, f.engine.getRepositoryService().createDeploymentQuery().tenantIdIn("null").count(), "no deployment in tenant \"null\"");
        assertEquals(1, f.engine.getRepositoryService().createDeploymentQuery().count());
        assertEquals(1, f.engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(pid).count(), "instance not moved");
    }

    // FWT-056
    @Test
    void fwt056_productRowRolledBackWhenEngineCommandFails() {
        f.deploy("one.bpmn", FwtEngine.defs("""
              <process id="one" isExecutable="true"><startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="t"/>
                <userTask id="t"/><sequenceFlow id="f2" sourceRef="t" targetRef="e"/><endEvent id="e"/></process>"""));
        String pid = f.engine.getRuntimeService().startProcessInstanceByKey("one").getId();
        for (boolean engineWrites : new boolean[] {false, true}) {
            String lock = "fwt056-" + engineWrites;
            assertThrows(IllegalStateException.class, () -> EngineTx.execute(f.engine, cc -> {
                Jdbc.update(EngineTx.connection(), "INSERT INTO JOB_ACQUIRE_LOCK(lock_name, owner_id) VALUES (?, 'x')", lock);
                if (engineWrites) {
                    f.engine.getRuntimeService().setVariable(pid, "v", 1);
                }
                throw new IllegalStateException("engine command fails");
            }));
            assertEquals(0, f.count("SELECT COUNT(*) FROM JOB_ACQUIRE_LOCK WHERE lock_name = ?", lock),
                    "product row rolled back (engine writes: " + engineWrites + ")");
        }
        assertEquals(null, f.engine.getRuntimeService().getVariable(pid, "v"));
    }
}

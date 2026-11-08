// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.bpmn.parser.BpmnParseListener;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.operaton.bpm.engine.impl.util.ClockUtil;
import org.operaton.bpm.engine.runtime.Job;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.ports.PortsH2Engine;
import org.sangam.ports.support.EngineTx;
import org.sangam.ports.support.PortsProblem;

/** FC-44 debugger: breakpoints become wait states in sandbox tenants only; every action is sandbox-guarded. */
class DebuggerServiceH2Test {

    private static final String MODEL = """
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="t">
              <message id="msg" name="go"/>
              <process id="dbg" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="manual"/>
                <manualTask id="manual"/><sequenceFlow id="f2" sourceRef="manual" targetRef="fork"/>
                <parallelGateway id="fork"/><sequenceFlow id="f3" sourceRef="fork" targetRef="u"/>
                <userTask id="u"/><sequenceFlow id="f4" sourceRef="u" targetRef="wait"/>
                <intermediateCatchEvent id="wait"><messageEventDefinition messageRef="msg"/></intermediateCatchEvent>
                <sequenceFlow id="f5" sourceRef="wait" targetRef="timer"/>
                <intermediateCatchEvent id="timer"><timerEventDefinition><timeDuration>P10D</timeDuration></timerEventDefinition></intermediateCatchEvent>
                <sequenceFlow id="f6" sourceRef="timer" targetRef="e"/><endEvent id="e"/>
              </process>
            </definitions>""";

    private final AtomicReference<ProcessEngine> engineRef = new AtomicReference<>();
    private PortsH2Engine h2;
    private DebuggerService debugger;

    @BeforeEach
    void setUp() {
        Transactions lazyTx = new Transactions() {
            @Override
            public <T> T inTransaction(Function<java.sql.Connection, T> work) {
                return EngineTx.transactions(engineRef.get()).inTransaction(work);
            }
        };
        // the parse-side instance needs only the breakpoint table; the engine is built after the plugin is registered
        DebuggerService parseSide = new DebuggerService(null, Dialect.POSTGRESQL, lazyTx, Set.of("sbx"), true);
        ProcessEnginePlugin addListener = new ProcessEnginePlugin() {
            @Override
            public void preInit(ProcessEngineConfigurationImpl cfg) {
                List<BpmnParseListener> l = cfg.getCustomPostBPMNParseListeners() == null ? new ArrayList<>()
                        : new ArrayList<>(cfg.getCustomPostBPMNParseListeners());
                l.add(parseSide.parseListener());
                cfg.setCustomPostBPMNParseListeners(l);
            }

            @Override
            public void postInit(ProcessEngineConfigurationImpl cfg) {
            }

            @Override
            public void postProcessEngineBuild(ProcessEngine engine) {
            }
        };
        h2 = PortsH2Engine.start(null, addListener);
        engineRef.set(h2.engine);
        debugger = new DebuggerService(h2.engine, Dialect.POSTGRESQL, EngineTx.transactions(h2.engine), Set.of("sbx"), true);
    }

    @AfterEach
    void tearDown() {
        h2.close();
    }

    private String deployAndStart(String tenant) {
        h2.engine.getRepositoryService().createDeployment().addString("dbg.bpmn", MODEL).tenantId(tenant).deploy();
        return h2.engine.getRuntimeService().createProcessInstanceByKey("dbg").processDefinitionTenantId(tenant).execute().getId();
    }

    private String at(String pid) {
        return h2.engine.getRuntimeService().getActiveActivityIds(pid).toString();
    }

    @Test
    void refusesToExistOutsideASandboxProfile() {
        assertThrows(IllegalStateException.class, () -> new DebuggerService(h2.engine, Dialect.POSTGRESQL, null, Set.of("sbx"), false));
    }

    @Test
    void userTaskBreakpointStopsBeforeTheTaskAndStepRunsIt() {
        debugger.addBreakpoint("sbx", "dbg", "u");
        debugger.addBreakpoint("sbx", "dbg", "u"); // idempotent
        assertEquals(Set.of("u"), debugger.breakpoints("sbx", "dbg"));
        String pid = deployAndStart("sbx");
        List<Job> jobs = debugger.pausedAt(pid);
        assertEquals(1, jobs.size(), "stopped at the breakpoint: " + at(pid));
        assertEquals(0, h2.engine.getTaskService().createTaskQuery().processInstanceId(pid).count());
        debugger.step(jobs.get(0).getId());
        assertEquals(1, h2.engine.getTaskService().createTaskQuery().processInstanceId(pid).count());
    }

    /** Regression: breakpoints on manual tasks, parallel gateways and catch events were silently ignored. */
    @Test
    void breakpointsWorkOnEveryActivityKind() {
        debugger.addBreakpoint("sbx", "dbg", "manual");
        debugger.addBreakpoint("sbx", "dbg", "fork");
        debugger.addBreakpoint("sbx", "dbg", "wait");
        String pid = deployAndStart("sbx");
        assertEquals(1, debugger.pausedAt(pid).size(), "stopped before the manual task: " + at(pid));
        debugger.step(debugger.pausedAt(pid).get(0).getId());
        assertEquals(1, debugger.pausedAt(pid).size(), "stopped before the parallel gateway: " + at(pid));
        debugger.step(debugger.pausedAt(pid).get(0).getId());
        assertEquals(0, debugger.pausedAt(pid).size());
        h2.engine.getTaskService().complete(h2.engine.getTaskService().createTaskQuery().processInstanceId(pid).singleResult().getId());
        assertEquals(1, debugger.pausedAt(pid).size(), "stopped before the catch event: " + at(pid));
        debugger.removeBreakpoint("sbx", "dbg", "wait");
        assertEquals(Set.of("manual", "fork"), debugger.breakpoints("sbx", "dbg"));
    }

    @Test
    void catchEventsBehindAnEventBasedGatewayStayArmedByTheGateway() {
        String race = """
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="t">
                  <message id="m1" name="yes"/><message id="m2" name="no"/>
                  <process id="race" isExecutable="true">
                    <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="gw"/>
                    <eventBasedGateway id="gw"/>
                    <sequenceFlow id="f2" sourceRef="gw" targetRef="c1"/><sequenceFlow id="f3" sourceRef="gw" targetRef="c2"/>
                    <intermediateCatchEvent id="c1"><messageEventDefinition messageRef="m1"/></intermediateCatchEvent>
                    <intermediateCatchEvent id="c2"><messageEventDefinition messageRef="m2"/></intermediateCatchEvent>
                    <sequenceFlow id="f4" sourceRef="c1" targetRef="e1"/><endEvent id="e1"/>
                    <sequenceFlow id="f5" sourceRef="c2" targetRef="e2"/><endEvent id="e2"/>
                  </process>
                </definitions>""";
        debugger.addBreakpoint("sbx", "race", "gw");
        debugger.addBreakpoint("sbx", "race", "c1");
        h2.engine.getRepositoryService().createDeployment().addString("race.bpmn", race).tenantId("sbx").deploy();
        String pid = h2.engine.getRuntimeService().createProcessInstanceByKey("race").processDefinitionTenantId("sbx").execute().getId();
        assertEquals(1, debugger.resume(pid, 10), "the gateway breakpoint is the only stop");
        debugger.sendMessage("yes", pid, Map.of());
        assertEquals(0, h2.engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(pid).count());
    }

    @Test
    void sandboxActionsDriveTheInstance() {
        String pid = deployAndStart("sbx");
        String taskId = h2.engine.getTaskService().createTaskQuery().processInstanceId(pid).singleResult().getId();
        debugger.setVariables(pid, Map.of("x", 42));
        assertEquals(42, h2.engine.getRuntimeService().getVariable(pid, "x"));
        h2.engine.getTaskService().complete(taskId);
        debugger.sendMessage("go", pid, Map.of("y", "z"));
        assertEquals("z", h2.engine.getRuntimeService().getVariable(pid, "y"));
        List<Job> timers = debugger.pausedAt(pid);
        assertEquals(1, timers.size());
        assertTrue(timers.get(0).getDuedate().after(new Date(ClockUtil.getCurrentTime().getTime() + 9L * 24 * 3600 * 1000)));
        debugger.fireTimer(timers.get(0).getId());
        assertEquals(0, h2.engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(pid).count(), "ended");
    }

    @Test
    void resumeRunsTheRemainingJobs() {
        debugger.addBreakpoint("sbx", "dbg", "manual");
        debugger.addBreakpoint("sbx", "dbg", "u");
        String pid = deployAndStart("sbx");
        assertEquals(0, debugger.resume(pid, 0));
        assertEquals(2, debugger.resume(pid, 10));
        assertEquals(1, h2.engine.getTaskService().createTaskQuery().processInstanceId(pid).count());
    }

    @Test
    void productionTenantsAreNeitherAlteredNorReachable() {
        assertThrows(IllegalArgumentException.class, () -> debugger.addBreakpoint("prod", "dbg", "u"));
        assertThrows(IllegalArgumentException.class, () -> debugger.removeBreakpoint("prod", "dbg", "u"));
        // a breakpoint row for a non-sandbox tenant (e.g. planted directly) has no effect on its deployments
        EngineTx.execute(h2.engine, cc -> Jdbc.update(EngineTx.connection(),
                "INSERT INTO DEBUG_BREAKPOINT(tenant_id, definition_key, activity_id) VALUES ('prod','dbg','u')"));
        String pid = deployAndStart("prod");
        assertEquals(0, h2.engine.getManagementService().createJobQuery().processInstanceId(pid).count());
        String taskId = h2.engine.getTaskService().createTaskQuery().processInstanceId(pid).singleResult().getId();
        assertEquals(1, h2.engine.getTaskService().createTaskQuery().taskId(taskId).count());

        PortsProblem p = assertThrows(PortsProblem.class, () -> debugger.pausedAt(pid));
        assertEquals("NOT_SANDBOX", p.code());
        assertThrows(PortsProblem.class, () -> debugger.setVariables(pid, Map.of("x", 1)));
        assertThrows(PortsProblem.class, () -> debugger.sendMessage("go", pid, Map.of()));
        h2.engine.getTaskService().complete(taskId);
        h2.engine.getRuntimeService().createMessageCorrelation("go").processInstanceId(pid).correlate();
        String timer = h2.engine.getManagementService().createJobQuery().processInstanceId(pid).singleResult().getId();
        assertThrows(PortsProblem.class, () -> debugger.fireTimer(timer));
        assertThrows(PortsProblem.class, () -> debugger.step(timer));
        assertEquals(1, h2.engine.getManagementService().createJobQuery().jobId(timer).count(), "timer untouched");
        assertThrows(IllegalArgumentException.class, () -> debugger.step("missing"));
    }
}

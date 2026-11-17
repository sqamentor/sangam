// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.pnp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.cfg.StandaloneProcessEngineConfiguration;
import org.operaton.bpm.engine.task.Task;
import org.sangam.core.engine.RouteDecision;
import org.sangam.engine.SangamEnginePlugin;
import org.sangam.engine.clock.OperatonClock;
import org.sangam.engine.incident.RoutingIncidents;

/**
 * R8-pnp (IFMS report SANGAM-PNP-H2): a host that builds a <b>plain Operaton</b> engine switches to Sangam with the
 * dependency and configuration only. The host code below is the plain-Operaton host code of the IFMS probe
 * ({@code OperatonCompensationOrderProbeTest}: its own configuration, JDBC properties, {@code setBeans}); the only
 * Sangam line is the plugin in the plugin list, and the settings are {@code sangam.engine.*} properties.
 * <ul>
 *   <li>PNP-1: the engine is Sangam (strict gateway, SL-160 rule, frozen settings) without Sangam assembly code, and
 *       the host's own engine name, history and job-executor choice are kept;</li>
 *   <li>PNP-2: the IFMS model calling the host bean {@code ${recorder.record(..)}} deploys and runs once the host
 *       approves {@code recorder.record} by configuration, and is refused without it (fail closed);</li>
 *   <li>PNP-3: a no-match gateway in a called child is read, typed, by {@link RoutingIncidents#collect}.</li>
 * </ul>
 * Fails before R8-pnp: {@code SangamEnginePlugin}, {@code SangamEngineSettingsBinder} and {@code RoutingIncidents}
 * did not exist (a host had to assemble the engine through {@code SangamEngineConfigurer} and parse incidents itself).
 */
class DropInHostH2Test {

    /** The IFMS probe's in-engine recorder (a host bean, not a Sangam class). */
    public static final class Recorder {
        public final List<String> calls = Collections.synchronizedList(new ArrayList<>());

        public void record(String handler) {
            calls.add(handler);
        }
    }

    /** The IFMS compensation model (OperatonCompensationOrderProbeTest.model, service-task handlers). */
    static String compensation(String id) {
        String handler = "<serviceTask id=\"UNDO_%1$s\" isForCompensation=\"true\" "
                + "operaton:expression=\"${recorder.record('UNDO_%1$s')}\"/>";
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:operaton="http://operaton.org/schema/1.0/bpmn"
                             id="D-%1$s" targetNamespace="urn:probe">
                  <process id="%1$s" isExecutable="true">
                    <startEvent id="start"/>
                    <userTask id="RESERVE_FUNDS"/>
                    <boundaryEvent id="RC" attachedToRef="RESERVE_FUNDS"><compensateEventDefinition/></boundaryEvent>
                    %2$s
                    <userTask id="BOOK_LIABILITY"/>
                    <boundaryEvent id="BC" attachedToRef="BOOK_LIABILITY"><compensateEventDefinition/></boundaryEvent>
                    %3$s
                    <intermediateThrowEvent id="COMPENSATE"><compensateEventDefinition/></intermediateThrowEvent>
                    <endEvent id="END"/>
                    <sequenceFlow id="s1" sourceRef="start" targetRef="RESERVE_FUNDS"/>
                    <sequenceFlow id="s2" sourceRef="RESERVE_FUNDS" targetRef="BOOK_LIABILITY"/>
                    <sequenceFlow id="s3" sourceRef="BOOK_LIABILITY" targetRef="COMPENSATE"/>
                    <sequenceFlow id="s4" sourceRef="COMPENSATE" targetRef="END"/>
                    <association id="a1" associationDirection="One" sourceRef="RC" targetRef="UNDO_RESERVE_FUNDS"/>
                    <association id="a2" associationDirection="One" sourceRef="BC" targetRef="UNDO_BOOK_LIABILITY"/>
                  </process>
                </definitions>
                """.formatted(id, handler.formatted("RESERVE_FUNDS"), handler.formatted("BOOK_LIABILITY")).strip();
    }

    /** A parent that calls a child whose exclusive gateway has one condition and no default (SG-35 / X-13 shape). */
    static final String ROUTING = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" targetNamespace="urn:probe">
              <process id="PARENT" isExecutable="true">
                <startEvent id="ps"/>
                <sequenceFlow id="p1" sourceRef="ps" targetRef="call"/>
                <callActivity id="call" calledElement="CHILD"/>
                <sequenceFlow id="p2" sourceRef="call" targetRef="pe"/>
                <endEvent id="pe"/>
              </process>
              <process id="CHILD" isExecutable="true">
                <startEvent id="cs"/>
                <sequenceFlow id="c1" sourceRef="cs" targetRef="review"/>
                <userTask id="review"/>
                <sequenceFlow id="c2" sourceRef="review" targetRef="gw"/>
                <exclusiveGateway id="gw"/>
                <sequenceFlow id="big" sourceRef="gw" targetRef="ce">
                  <conditionExpression xsi:type="tFormalExpression">${amount &gt; 100}</conditionExpression>
                </sequenceFlow>
                <endEvent id="ce"/>
              </process>
            </definitions>
            """.strip();

    /** The plain Operaton host: its own configuration and beans; Sangam only as a plugin with properties. */
    static ProcessEngine host(String name, Recorder recorder, Properties sangam) {
        StandaloneProcessEngineConfiguration c = new StandaloneProcessEngineConfiguration();
        c.setProcessEngineName(name);
        c.setJdbcUrl("jdbc:h2:mem:" + name + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        c.setJdbcDriver("org.h2.Driver");
        c.setJdbcUsername("sa");
        c.setJdbcPassword("");
        c.setDatabaseSchemaUpdate("true");
        c.setHistory(ProcessEngineConfigurationImpl.HISTORY_FULL);
        c.setHistoryTimeToLive("P1D");
        c.setJobExecutorActivate(false);
        c.setMetricsEnabled(false);
        c.setDbMetricsReporterActivate(false);
        c.setBeans(Map.of("recorder", recorder));
        c.getProcessEnginePlugins().add(new SangamEnginePlugin(sangam)); // the one Sangam line
        return c.buildProcessEngine();
    }

    static Properties config(boolean approveRecorder) {
        Properties p = new Properties();
        p.setProperty("sangam.engine.system-audit-scope", "system:host");
        p.setProperty("sangam.engine.allow-multiple-engines-per-jvm", "true");
        p.setProperty("sangam.engine.timer-zone", "Asia/Kolkata");
        if (approveRecorder) {
            p.setProperty("sangam.engine.deploy-allowed-beans.*", "recorder.record");
        }
        return p;
    }

    static void complete(ProcessEngine e, String pi, String key) {
        Task t = e.getTaskService().createTaskQuery().processInstanceId(pi).taskDefinitionKey(key).singleResult();
        e.getTaskService().complete(t.getId());
    }

    static String name() {
        return "pnp" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    @Test
    void plainOperatonHostRunsSangamWithConfigurationOnly() {
        Recorder r = new Recorder();
        String name = name();
        ProcessEngine e = host(name, r, config(true));
        try {
            // PNP-1: the host's choices are kept; the engine is Sangam
            assertEquals(name, e.getName());
            ProcessEngineConfigurationImpl cfg = (ProcessEngineConfigurationImpl) e.getProcessEngineConfiguration();
            assertEquals(ProcessEngineConfigurationImpl.HISTORY_FULL, cfg.getHistory());
            SangamEnginePlugin plugin = (SangamEnginePlugin) cfg.getProcessEnginePlugins().get(0);
            assertNotNull(plugin.adapter(), "afterBuild ran");
            assertTrue(plugin.settings().isFrozen(), "M-098 freeze applies on the drop-in route too");
            assertEquals("Asia/Kolkata", plugin.settings().getTimerZone().getId());

            // PNP-2: the IFMS model with the host bean deploys and runs (approved by configuration)
            e.getRepositoryService().createDeployment().addString("s.bpmn", compensation("PROBE_S")).deploy();
            String pi = e.getRuntimeService().startProcessInstanceByKey("PROBE_S").getId();
            complete(e, pi, "RESERVE_FUNDS");
            complete(e, pi, "BOOK_LIABILITY");
            assertEquals(List.of("UNDO_BOOK_LIABILITY", "UNDO_RESERVE_FUNDS"), r.calls,
                    "host bean called; SG-08 reverse sequential compensation (Sangam behaviour)");

            // PNP-3: a no-match gateway in a called child: Sangam parks it with a typed, collectable incident
            e.getRepositoryService().createDeployment().addString("routing.bpmn", ROUTING).deploy();
            String parent = e.getRuntimeService().startProcessInstanceByKey("PARENT").getId();
            String child = e.getRuntimeService().createProcessInstanceQuery().superProcessInstanceId(parent)
                    .singleResult().getId();
            e.getRuntimeService().setVariable(child, "amount", 5);
            complete(e, child, "review");
            List<RoutingIncidents.Found> found = RoutingIncidents.collect(e, parent);
            assertEquals(1, found.size(), String.valueOf(found));
            RoutingIncidents.Found f = found.get(0);
            assertEquals(RouteDecision.Outcome.NO_MATCHING_ROUTE, f.outcome());
            assertEquals("gw", f.gatewayId());
            assertEquals(child, f.processInstanceId());
            assertEquals(parent, f.rootProcessInstanceId());
            assertEquals(found, RoutingIncidents.collect(e, parent), "same incident, same dedup key, on every read");
        } finally {
            e.close();
        }
    }

    @Test
    void hostBeanIsRefusedWithoutApproval() {
        ProcessEngine e = host(name(), new Recorder(), config(false));
        try {
            Exception x = assertThrows(Exception.class, () -> e.getRepositoryService().createDeployment()
                    .addString("s.bpmn", compensation("PROBE_X")).deploy());
            assertTrue(String.valueOf(x.getMessage()).contains("SL-160"), x.getMessage());
            assertTrue(String.valueOf(x.getMessage()).contains("recorder"), x.getMessage());
        } finally {
            e.close();
        }
    }

    @Test
    void wildcardApprovalRefusesTheStart() {
        Properties p = config(false);
        p.setProperty("sangam.engine.deploy-allowed-beans.*", "recorder.*");
        Exception x = assertThrows(Exception.class, () -> host(name(), new Recorder(), p).close());
        assertTrue(chain(x).contains("SL160-ALLOW-ENTRY"), chain(x));
    }

    @Test
    void unknownSettingRefusesTheStart() {
        Properties p = config(false);
        p.setProperty("sangam.engine.deploy-alowed-beans.*", "recorder.record"); // typo
        IllegalArgumentException x = assertThrows(IllegalArgumentException.class, () -> new SangamEnginePlugin(p));
        assertTrue(x.getMessage().contains("deploy-alowed-beans"), x.getMessage());
    }

    @Test
    void closedDropInEngineFreesItsSlot() {
        ProcessEngine a = host(name(), new Recorder(), config(false));
        a.close();
        int live = OperatonClock.liveEngines();
        ProcessEngine b = host(name(), new Recorder(), config(false));
        try {
            assertEquals(live, OperatonClock.liveEngines(), "the closed engine's slot was released, the new one taken");
        } finally {
            b.close();
        }
    }

    @Test
    void routingIncidentCodeIsNeverGuessed() {
        assertEquals(RouteDecision.Outcome.AMBIGUOUS_ROUTE, RoutingIncidents.outcome("AMBIGUOUS_ROUTE: gw x").orElseThrow());
        assertTrue(RoutingIncidents.outcome("SOMETHING_ELSE: x").isEmpty());
        assertTrue(RoutingIncidents.outcome("no code").isEmpty());
        assertNull(RoutingIncidents.code(null));
        assertEquals("SOMETHING_ELSE", RoutingIncidents.code("SOMETHING_ELSE: x"));
    }

    private static String chain(Throwable t) {
        StringBuilder b = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            b.append(c).append(" | ");
        }
        return b.toString();
    }
}

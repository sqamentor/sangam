// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.task.Task;

import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.spi.PackDeployer;

/**
 * Owner decision 222: the delegation table is consulted with the case type the deployed pack manifest declares for
 * the sanction ({@code ruleCaseType}), never with a {@code caseType} variable a case was started with. Synthetic pack
 * {@code test.rct}: the limit table gives SETTLEMENT a limit of 1,000.00 and RESTRUCTURING 1,000,000.00 for the same
 * seat role, and the manifest declares SETTLEMENT; version 1.1.0 declares no case type.
 */
class RuleCaseTypeListenerTest {

    static final String TENANT = PackTestTenant.ID;
    static ProcessEngine engine;

    @BeforeAll
    static void setUp() {
        PackRuntime.reset();
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-rule-case-type;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        PackTestEngines.configure(cfg); // decisions 138 and FEEL (sangam-feel): as SangamProcessEnginePlugin does
        engine = cfg.buildProcessEngine();
        deployPack("1.0.0", ",\"ruleCaseType\":\"SETTLEMENT\"");
    }

    @AfterAll
    static void tearDown() {
        engine.close();
        PackRuntime.reset();
    }

    static void deployPack(String version, String ruleCaseType) {
        String manifest = """
                {"id":"test.rct","version":"%s","currency":"INR","powers":{"rc_sanction":
                {"monetary":true,"currency":"INR","limitTable":"rct-powers"%s}}}
                """.formatted(version, ruleCaseType);
        engine.getRepositoryService().createDeployment().tenantId(TENANT).name("pack:test.rct@" + version)
                .source(PackDeployer.SOURCE)
                .addString("packs/test-rct/rct-case.bpmn", CASE)
                .addString("packs/test-rct/rct-sanction.bpmn", SANCTION)
                .addString("packs/test-rct/rct-powers.dmn", POWERS)
                .addString("packs/test-rct/pack.json", manifest)
                .deploy();
    }

    static final String CASE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="http://sangam.org/test">
              <process id="rct-case" isExecutable="true" operaton:historyTimeToLive="30">
                <startEvent id="rc_start"/>
                <callActivity id="rc_sanction" calledElement="rct-sanction">
                  <extensionElements><operaton:in variables="all"/><operaton:out variables="all"/></extensionElements>
                </callActivity>
                <endEvent id="rc_end"/>
                <sequenceFlow id="f1" sourceRef="rc_start" targetRef="rc_sanction"/>
                <sequenceFlow id="f2" sourceRef="rc_sanction" targetRef="rc_end"/>
              </process>
            </definitions>
            """;

    static final String SANCTION = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="http://sangam.org/test">
              <process id="rct-sanction" isExecutable="true" operaton:historyTimeToLive="30">
                <startEvent id="sn_start"/>
                <userTask id="sn_sanction" operaton:candidateGroups="BRANCHMGR">
                  <extensionElements>
                    <operaton:taskListener event="complete" class="org.sangam.packs.core.PowerLimitGuardListener"/>
                  </extensionElements>
                </userTask>
                <endEvent id="sn_end"/>
                <sequenceFlow id="s1" sourceRef="sn_start" targetRef="sn_sanction"/>
                <sequenceFlow id="s2" sourceRef="sn_sanction" targetRef="sn_end"/>
              </process>
            </definitions>
            """;

    static final String POWERS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" xmlns:operaton="http://operaton.org/schema/1.0/dmn" id="d_rct" name="RCT powers"
                         namespace="http://sangam.org/test">
              <decision operaton:historyTimeToLive="30" id="rct-powers" name="RCT powers">
                <decisionTable id="t_rct" hitPolicy="FIRST">
                  <input id="i0" label="Seat role"><inputExpression id="ie0" typeRef="string"><text>seatRole</text></inputExpression></input>
                  <input id="i1" label="Case type"><inputExpression id="ie1" typeRef="string"><text>caseType</text></inputExpression></input>
                  <output id="o0" name="powerLimit" typeRef="number"/>
                  <output id="o1" name="allowed" typeRef="boolean"/>
                  <rule id="r0"><inputEntry id="r0i0"><text>"BRANCHMGR"</text></inputEntry><inputEntry id="r0i1"><text>"SETTLEMENT"</text></inputEntry>
                    <outputEntry id="r0o0"><text>100000</text></outputEntry><outputEntry id="r0o1"><text>true</text></outputEntry></rule>
                  <rule id="r1"><inputEntry id="r1i0"><text>"BRANCHMGR"</text></inputEntry><inputEntry id="r1i1"><text>"RESTRUCTURING"</text></inputEntry>
                    <outputEntry id="r1o0"><text>100000000</text></outputEntry><outputEntry id="r1o1"><text>true</text></outputEntry></rule>
                  <rule id="r2"><inputEntry id="r2i0"><text>-</text></inputEntry><inputEntry id="r2i1"><text>-</text></inputEntry>
                    <outputEntry id="r2o0"><text>0</text></outputEntry><outputEntry id="r2o1"><text>false</text></outputEntry></rule>
                </decisionTable>
              </decision>
            </definitions>
            """;

    /** Starts a case of the latest deployed version and returns its sanction task. */
    static Task sanctionTask(Map<String, Object> start) {
        ProcessInstance pi = engine.getRuntimeService().createProcessInstanceByKey("rct-case").processDefinitionTenantId(
                TENANT).setVariables(start).execute();
        ProcessInstance sub = engine.getRuntimeService().createProcessInstanceQuery().superProcessInstanceId(pi.getId())
                .singleResult();
        return engine.getTaskService().createTaskQuery().processInstanceId(sub.getId()).singleResult();
    }

    static void approve(Task task) {
        Map<String, Object> v = new HashMap<>();
        v.put("decision", "approve");
        v.put("actingPerson", "officer-1");
        v.put("actingSeat", "BRANCHMGR");
        engine.getTaskService().complete(task.getId(), v);
    }

    static Map<String, Object> start(String caseType, long amountMinor) {
        Map<String, Object> v = new HashMap<>();
        if (caseType != null) {
            v.put("caseType", caseType);
        }
        v.put("amount", amountMinor);
        return v;
    }

    @Test
    void aStartVariableCannotMoveTheSanctionToTheRowOfAnotherCaseType() {
        // 5,000.00 is within the RESTRUCTURING limit but over the SETTLEMENT limit the manifest declares
        Task task = sanctionTask(start("RESTRUCTURING", 500_000L));
        ProcessEngineException ex = assertThrows(ProcessEngineException.class, () -> approve(task));
        assertTrue(ex.getMessage().contains("422 POWER_LIMIT_EXCEEDED"), ex.getMessage());
        assertEquals(1, engine.getTaskService().createTaskQuery().taskId(task.getId()).count(), "the task stays open");
    }

    @Test
    void theDeclaredCaseTypeAppliesWhateverTheCaseSays() {
        Task within = sanctionTask(start("RESTRUCTURING", 50_000L)); // 500.00: within the SETTLEMENT limit
        approve(within);
        assertEquals(0, engine.getTaskService().createTaskQuery().taskId(within.getId()).count());

        Task noVariable = sanctionTask(start(null, 50_000L));
        approve(noVariable);
        assertEquals(0, engine.getTaskService().createTaskQuery().taskId(noVariable.getId()).count());
        assertNull(engine.getHistoryService().createHistoricVariableInstanceQuery()
                .processInstanceId(noVariable.getProcessInstanceId()).variableName("caseType").singleResult(),
                "the listener writes no caseType into the case");
    }

    @Test
    void aManifestWithoutACaseTypeConsultsTheTableWithoutOne() {
        deployPack("1.1.0", "");
        try {
            // the table has no row for an absent case type and this seat, so the default row (no power) applies,
            // even though the case says RESTRUCTURING
            Task task = sanctionTask(start("RESTRUCTURING", 50_000L));
            ProcessEngineException ex = assertThrows(ProcessEngineException.class, () -> approve(task));
            assertTrue(ex.getMessage().contains("422 POWER_LIMIT_EXCEEDED"), ex.getMessage());
        } finally {
            engine.getRepositoryService().createDeploymentQuery().deploymentName("pack:test.rct@1.1.0").list()
                    .forEach(d -> engine.getRepositoryService().deleteDeployment(d.getId(), true));
        }
    }
}

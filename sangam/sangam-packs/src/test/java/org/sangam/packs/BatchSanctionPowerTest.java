// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * Integrator ruling R2 (owner rules E1-E3): a batch sanction (dbt-batch db_sanction / db_retry_bsanction, dormancy
 * dv_bsanction, dividend cd_bsanction) is monetary; its amount is the sealed batch total and the officer's delegated
 * limit applies to it. Synthetic pack {@code test.bsn}: the case starts with {@code amount=0} (the shipped packs' "no
 * amount" default), seals its items, and the sanction seat's limit is 1,000.00. Before the fix the seal left
 * {@code amount} at 0, so a batch of any total passed the limit.
 */
class BatchSanctionPowerTest {

    static final String TENANT = PackTestTenant.ID;
    static ProcessEngine engine;

    @BeforeAll
    static void setUp() {
        PackRuntime.reset();
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-batch-sanction-power;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        PackTestEngines.configure(cfg);
        engine = cfg.buildProcessEngine();
        String manifest = """
                {"id":"test.bsn","version":"1.0.0","currency":"INR","powers":{"bs_sanction":
                {"monetary":true,"currency":"INR","limitTable":"bsn-powers"}}}
                """;
        engine.getRepositoryService().createDeployment().tenantId(TENANT).name("pack:test.bsn@1.0.0")
                .source(PackDeployer.SOURCE)
                .addString("packs/test-bsn/bsn-case.bpmn", CASE)
                .addString("packs/test-bsn/bsn-sanction.bpmn", RuleCaseTypeListenerTest.SANCTION
                        .replace("rct-sanction", "bsn-sanction"))
                .addString("packs/test-bsn/bsn-powers.dmn", POWERS)
                .addString("packs/test-bsn/pack.json", manifest)
                .deploy();
    }

    @AfterAll
    static void tearDown() {
        engine.close();
        engine = null;
        PackRuntime.reset();
    }

    static final String CASE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="http://sangam.org/test">
              <process id="bsn-case" isExecutable="true" operaton:historyTimeToLive="30">
                <startEvent id="bs_start"/>
                <serviceTask id="bs_seal" operaton:class="org.sangam.packs.core.BatchDelegate">
                  <extensionElements>
                    <operaton:field name="op"><operaton:string>SEAL</operaton:string></operaton:field>
                    <operaton:field name="currency"><operaton:string>INR</operaton:string></operaton:field>
                    <operaton:field name="purpose"><operaton:string>test batch</operaton:string></operaton:field>
                    <operaton:field name="itemsVar"><operaton:string>items</operaton:string></operaton:field>
                  </extensionElements>
                </serviceTask>
                <callActivity id="bs_sanction" calledElement="bsn-sanction">
                  <extensionElements><operaton:in variables="all"/><operaton:out variables="all"/></extensionElements>
                </callActivity>
                <endEvent id="bs_end"/>
                <sequenceFlow id="f1" sourceRef="bs_start" targetRef="bs_seal"/>
                <sequenceFlow id="f2" sourceRef="bs_seal" targetRef="bs_sanction"/>
                <sequenceFlow id="f3" sourceRef="bs_sanction" targetRef="bs_end"/>
              </process>
            </definitions>
            """;

    static final String POWERS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" xmlns:operaton="http://operaton.org/schema/1.0/dmn" id="d_bsn" name="BSN powers"
                         namespace="http://sangam.org/test">
              <decision operaton:historyTimeToLive="30" id="bsn-powers" name="BSN powers">
                <decisionTable id="t_bsn" hitPolicy="FIRST">
                  <input id="i0" label="Seat role"><inputExpression id="ie0" typeRef="string"><text>seatRole</text></inputExpression></input>
                  <output id="o0" name="powerLimit" typeRef="number"/>
                  <output id="o1" name="allowed" typeRef="boolean"/>
                  <rule id="r0"><inputEntry id="r0i0"><text>"BRANCHMGR"</text></inputEntry>
                    <outputEntry id="r0o0"><text>100000</text></outputEntry><outputEntry id="r0o1"><text>true</text></outputEntry></rule>
                  <rule id="r1"><inputEntry id="r1i0"><text>-</text></inputEntry>
                    <outputEntry id="r1o0"><text>0</text></outputEntry><outputEntry id="r1o1"><text>false</text></outputEntry></rule>
                </decisionTable>
              </decision>
            </definitions>
            """;

    static Task sealedBatchSanction(long... itemAmountsMinor) {
        StringBuilder items = new StringBuilder("[");
        for (int i = 0; i < itemAmountsMinor.length; i++) {
            items.append(i == 0 ? "" : ",").append("{\"key\":\"B-").append(i).append("\",\"amount\":")
                    .append(itemAmountsMinor[i]).append(",\"payeeAccount\":\"1000").append(i).append("@SBIN0000001\"}");
        }
        Map<String, Object> v = new HashMap<>();
        v.put("amount", 0L); // the shipped batch packs' "no amount" default before the seal
        v.put("items", items.append("]").toString());
        ProcessInstance pi = engine.getRuntimeService().createProcessInstanceByKey("bsn-case")
                .processDefinitionTenantId(TENANT).setVariables(v).execute();
        ProcessInstance sub = engine.getRuntimeService().createProcessInstanceQuery().superProcessInstanceId(pi.getId())
                .singleResult();
        return engine.getTaskService().createTaskQuery().processInstanceId(sub.getId()).singleResult();
    }

    @Test
    void aBatchTotalAboveTheOfficersLimitIsRefused() {
        // two items of 600.00 each: each within 1,000.00, the sealed total 1,200.00 above it
        Task task = sealedBatchSanction(60_000L, 60_000L);
        ProcessEngineException ex = assertThrows(ProcessEngineException.class,
                () -> RuleCaseTypeListenerTest.approveOn(engine, task));
        assertTrue(ex.getMessage().contains("422 POWER_LIMIT_EXCEEDED"), ex.getMessage());
        assertEquals(1, engine.getTaskService().createTaskQuery().taskId(task.getId()).count(), "the task stays open");
    }

    @Test
    void aBatchTotalWithinTheOfficersLimitIsSanctioned() {
        Task task = sealedBatchSanction(30_000L, 40_000L);
        RuleCaseTypeListenerTest.approveOn(engine, task);
        assertEquals(0, engine.getTaskService().createTaskQuery().taskId(task.getId()).count());
    }
}

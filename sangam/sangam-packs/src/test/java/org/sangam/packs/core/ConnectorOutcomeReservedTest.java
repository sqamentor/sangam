// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.engine.runtime.ProcessInstance;

import org.sangam.packs.PackTestEngines;
import org.sangam.packs.PackTestTenant;
import org.sangam.packs.port.ConnectorGateway;
import org.sangam.packs.port.ConnectorResult;
import org.sangam.packs.port.PackRuntime;

/**
 * R7-it (62ee53db follow-up): the connector step's own outcome variables ({@code <prefix>Status}, {@code ReasonCode},
 * {@code Ref}, {@code Attempt}, {@code OperationId}) are never overwritten by a returned field of the same name. The
 * party registry returned {@code status}, which replaced {@code payeeStatus} (the call outcome), so every payee check
 * read the registry's status instead of COMPLETED. A colliding returned field is kept as {@code <prefix>Returned<Key>}.
 */
class ConnectorOutcomeReservedTest {

    static ProcessEngine engine;

    static final String BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="http://sangam.org/test">
              <process id="outcome-reserved" isExecutable="true" operaton:historyTimeToLive="30">
                <startEvent id="s"/>
                <serviceTask id="call" operaton:class="org.sangam.packs.core.ConnectorCallDelegate">
                  <extensionElements>
                    <operaton:field name="connector"><operaton:string>test.party</operaton:string></operaton:field>
                    <operaton:field name="operation"><operaton:string>lookup</operaton:string></operaton:field>
                    <operaton:field name="inputs"><operaton:string>payeeId</operaton:string></operaton:field>
                    <operaton:field name="resultPrefix"><operaton:string>payee</operaton:string></operaton:field>
                  </extensionElements>
                </serviceTask>
                <userTask id="wait"/>
                <endEvent id="e"/>
                <sequenceFlow id="f1" sourceRef="s" targetRef="call"/>
                <sequenceFlow id="f2" sourceRef="call" targetRef="wait"/>
                <sequenceFlow id="f3" sourceRef="wait" targetRef="e"/>
              </process>
            </definitions>
            """;

    @BeforeAll
    static void setUp() {
        PackRuntime.reset();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "INACTIVE");
        data.put("reasonCode", "REGISTRY_SAYS");
        data.put("ref", "registry-ref");
        data.put("attempt", 9);
        data.put("operationId", "registry-op");
        data.put("name", "A Payee");
        ConnectorGateway gateway = (ConnectorGateway) Proxy.newProxyInstance(ConnectorGateway.class.getClassLoader(),
                new Class<?>[] {ConnectorGateway.class}, (p, m, a) -> {
                    if (m.getName().equals("query")) {
                        return ConnectorResult.completed("ext-1", data);
                    }
                    throw new UnsupportedOperationException(m.getName());
                });
        PackRuntime.install(PackRuntime.defaults().withGateway(gateway));
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-outcome-reserved;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        PackTestEngines.configure(cfg);
        engine = cfg.buildProcessEngine();
        engine.getRepositoryService().createDeployment().tenantId(PackTestTenant.ID)
                .addString("outcome-reserved.bpmn", BPMN).deploy();
    }

    @AfterAll
    static void tearDown() {
        engine.close();
        // R8-heap2: a test class stays loaded for the whole run; its statics must not keep a closed engine
        engine = null;
        PackRuntime.reset();
    }

    @Test
    void aReturnedFieldNeverOverwritesTheStepsOutcome() {
        ProcessInstance pi = engine.getRuntimeService().createProcessInstanceByKey("outcome-reserved")
                .processDefinitionTenantId(PackTestTenant.ID).businessKey("OR-1")
                .setVariables(Map.of("payeeId", "P-1")).execute();
        Map<String, Object> v = engine.getRuntimeService().getVariables(pi.getId());
        assertEquals("COMPLETED", v.get("payeeStatus"), "the call outcome");
        assertNull(v.get("payeeReasonCode"), "a completed call has no reason code");
        assertEquals("ext-1", v.get("payeeRef"));
        assertEquals("INACTIVE", v.get("payeeReturnedStatus"), "the registry's status is kept under its own name");
        assertEquals("REGISTRY_SAYS", v.get("payeeReturnedReasonCode"));
        assertEquals("registry-ref", v.get("payeeReturnedRef"));
        assertEquals(9, v.get("payeeReturnedAttempt"));
        assertEquals("registry-op", v.get("payeeReturnedOperationId"));
        assertEquals("A Payee", v.get("payeeName"), "other fields keep their usual name");
    }
}

// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;

import org.sangam.packs.PackTestEngines;
import org.sangam.packs.PackTestTenant;
import org.sangam.packs.port.ConnectorRequest;
import org.sangam.packs.port.ConnectorResult;
import org.sangam.packs.port.PackAuditEvent;
import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.sim.SimulatorConnectorGateway;

/**
 * Decision 238: for a step whose officer signs at the task with the browser DSC signer, SignatureDelegate records the
 * evidence DscSignatureGuardListener verified and never calls {@code dsc.sign}; without that evidence the signature is
 * FAILED. A flow without the browser signer keeps calling {@code dsc.sign}.
 */
class SignatureDelegateDscEvidenceTest {

    static final String TENANT = PackTestTenant.ID;
    static ProcessEngine engine;
    final List<String> connectors = new ArrayList<>();
    final List<PackAuditEvent> audit = new ArrayList<>();

    static String bpmn(String id, boolean browserSigner) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="http://sangam.org/test">
                  <process id="%s" isExecutable="true" operaton:historyTimeToLive="30">
                    <startEvent id="s"/>
                    <serviceTask id="sign" operaton:class="org.sangam.packs.core.SignatureDelegate">
                      <extensionElements>
                        <operaton:field name="op"><operaton:string>REQUEST</operaton:string></operaton:field>
                        <operaton:field name="purpose"><operaton:string>sanction</operaton:string></operaton:field>
                        %s
                      </extensionElements>
                    </serviceTask>
                    <endEvent id="e"/>
                    <sequenceFlow id="f1" sourceRef="s" targetRef="sign"/>
                    <sequenceFlow id="f2" sourceRef="sign" targetRef="e"/>
                  </process>
                </definitions>
                """.formatted(id, browserSigner
                ? "<operaton:field name=\"browserSigner\"><operaton:string>true</operaton:string></operaton:field>"
                : "");
    }

    @BeforeAll
    static void setUp() {
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-sign-evidence;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        PackTestEngines.configure(cfg); // decisions 138 and FEEL (sangam-feel), as production
        engine = cfg.buildProcessEngine();
        engine.getRepositoryService().createDeployment().tenantId(TENANT).name("sign-evidence")
                .addString("browser.bpmn", bpmn("sign-browser", true))
                .addString("agent.bpmn", bpmn("sign-agent", false)).deploy();
    }

    @AfterAll
    static void tearDown() {
        engine.close();
        PackRuntime.reset();
    }

    @BeforeEach
    void ports() {
        PackRuntime.reset();
        PackRuntime.install(PackRuntime.ports().withAudit(audit::add).withGateway(new SimulatorConnectorGateway() {
            @Override
            public ConnectorResult submit(ConnectorRequest request) {
                connectors.add(request.connector());
                return super.submit(request);
            }
        }));
    }

    static String run(String key, Map<String, Object> vars) {
        return engine.getRuntimeService().createProcessInstanceByKey(key).processDefinitionTenantId(TENANT)
                .setVariables(vars).execute().getId();
    }

    static Object var(String pi, String name) {
        var h = engine.getHistoryService().createHistoricVariableInstanceQuery().processInstanceId(pi)
                .variableName(name).list();
        return h.isEmpty() ? null : h.get(0).getValue();
    }

    static Map<String, Object> evidence() {
        Map<String, Object> v = new HashMap<>();
        v.put("signMethod", "dsc");
        v.put(DscSignatureGuardListener.EVIDENCE_TASK, "task-9");
        v.put(DscSignatureGuardListener.EVIDENCE_DOCUMENT, "doc-1");
        v.put(DscSignatureGuardListener.EVIDENCE_TYPE, "SANCTION_NOTE");
        v.put(DscSignatureGuardListener.EVIDENCE_SIGNER, "secretary-1");
        return v;
    }

    @Test
    void theBrowserSignerStepRecordsTheVerifiedEvidenceWithoutCallingDscSign() {
        String pi = run("sign-browser", evidence());
        assertTrue(connectors.isEmpty(), "no connector call: " + connectors);
        assertEquals("COMPLETED", var(pi, "signatureStatus"));
        assertEquals("DSC_BROWSER:task-9:doc-1", var(pi, "signatureEvidence"));
        PackAuditEvent applied = audit.stream().filter(e -> "SIGNATURE_APPLIED".equals(e.type())).findFirst()
                .orElseThrow();
        assertEquals("DSC_BROWSER_VERIFIED", applied.details().get("evidence"));
        assertEquals("secretary-1", applied.details().get("signer"));
        assertNull(engine.getHistoryService().createHistoricVariableInstanceQuery().processInstanceId(pi)
                .variableName(DscSignatureGuardListener.EVIDENCE_TASK).singleResult().getValue(),
                "the evidence is used once");
    }

    @Test
    void withoutEvidenceTheBrowserSignerStepFailsAndStillCallsNothing() {
        String pi = run("sign-browser", Map.of("signMethod", "dsc"));
        assertTrue(connectors.isEmpty(), "no fallback to dsc.sign: " + connectors);
        assertEquals("FAILED", var(pi, "signatureStatus"));
        assertTrue(audit.stream().anyMatch(e -> "SIGNATURE_FAILED".equals(e.type())
                && "DSC_EVIDENCE_MISSING".equals(e.details().get("reason"))));
    }

    @Test
    void aFlowWithoutTheBrowserSignerStillUsesTheDscSignConnector() {
        run("sign-agent", evidence());
        assertEquals(List.of("dsc.sign"), connectors);
    }

    @Test
    void eSignOnABrowserSignerStepStillGoesToTheEsignConnector() {
        Map<String, Object> v = evidence();
        v.put("signMethod", "esign");
        run("sign-browser", v);
        assertEquals(List.of("esign.sign"), connectors);
    }
}

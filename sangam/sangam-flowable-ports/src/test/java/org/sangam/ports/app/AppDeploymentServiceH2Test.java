// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.ports.PortsH2Engine;
import org.sangam.ports.bpmn.SangamModelValidator;
import org.sangam.ports.events.EventRegistry;
import org.sangam.ports.forms.SangamFormService;
import org.sangam.ports.support.EngineTx;

/** FC-41 app deployment: validate, approve (R43), deploy all-or-nothing in one engine transaction, idempotent, retire. */
class AppDeploymentServiceH2Test {

    private static final String BPMN = """
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="t">
              <process id="claimFlow" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="f" sourceRef="s" targetRef="t"/><userTask id="t"/>
              </process>
            </definitions>""";
    private static final String EVENT = "{\"key\":\"claimFiled\",\"payload\":[{\"name\":\"amount\",\"type\":\"long\"}]}";
    private static final String CHANNEL = "{\"key\":\"claimHook\",\"channelType\":\"webhook\",\"type\":\"inbound\","
            + "\"channelEventKeyDetection\":{\"fixedValue\":\"claimFiled\"},\"messageIdJsonPointer\":\"/id\"}";
    private static final String FORM = "{\"key\":\"claimForm\",\"fields\":[{\"id\":\"amount\",\"type\":\"long\"}]}";

    private PortsH2Engine h2;
    private EventRegistry events;
    private final AtomicInteger approvals = new AtomicInteger();
    private AppDeploymentService svc;

    @BeforeEach
    void setUp() {
        h2 = PortsH2Engine.start(null);
        events = h2.plugin.eventRegistry();
        svc = service((tenant, bundle, by) -> {
            approvals.incrementAndGet();
            assertEquals(64, bundle.bundleSha256().length());
            return "approver-of-" + by;
        });
    }

    @AfterEach
    void tearDown() {
        h2.close();
    }

    private AppDeploymentService service(AppDeploymentService.ApprovalPolicy policy) {
        return new AppDeploymentService(h2.engine, Dialect.POSTGRESQL, new SangamModelValidator(new SangamModelValidator.Options(false)),
                policy, new SangamFormService(h2.engine, Dialect.POSTGRESQL, EngineTx.transactions(h2.engine)), events, null);
    }

    private static AppBundle bundle(String manifest, String... kv) {
        Map<String, byte[]> m = new LinkedHashMap<>();
        m.put("sangam-app.json", manifest.getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1].getBytes(StandardCharsets.UTF_8));
        }
        return AppBundle.of(m);
    }

    private static AppBundle full() {
        return bundle("{\"key\":\"claims\",\"groupsAccess\":[\"adjusters\"]}", "claim.bpmn", BPMN, "claimFiled.event", EVENT,
                "claimHook.channel", CHANNEL, "claim.form", FORM);
    }

    private long count(String sql, Object... p) throws SQLException {
        try (Connection c = h2.connection()) {
            return Jdbc.one(c, sql, rs -> rs.getLong(1), p).orElse(0L);
        }
    }

    @Test
    void deploysEveryModelAsOneUnitInTheTenantAndRecordsTheAppVersion() throws Exception {
        AppDeploymentService.AppDeployment d = svc.deploy("acc", full(), "kim");
        assertEquals(1, d.version());
        assertEquals("approver-of-kim", d.approvedBy());
        assertNotNull(d.engineDeploymentId());
        var pd = h2.engine.getRepositoryService().createProcessDefinitionQuery().deploymentId(d.engineDeploymentId()).singleResult();
        assertEquals("claimFlow", pd.getKey());
        assertEquals("acc", pd.getTenantId());
        assertEquals("claims", h2.engine.getRepositoryService().createDeploymentQuery().deploymentId(d.engineDeploymentId()).singleResult().getName());
        assertTrue(events.event("acc", "claimFiled").isPresent());
        assertTrue(events.channel("acc", "claimHook").isPresent());
        assertTrue(events.event("ops", "claimFiled").isEmpty(), "R38: definitions are bound to the deploying tenant");
        assertEquals(1, count("SELECT COUNT(*) FROM EVT_EVENT_DEF WHERE app_deployment_id = ?", d.id()));
        assertEquals(1, count("SELECT COUNT(*) FROM EVT_CHANNEL_DEF WHERE app_deployment_id = ?", d.id()));
        assertEquals(1, count("SELECT COUNT(*) FROM FORM_DEF WHERE app_deployment_id = ? AND form_key = 'claimForm'", d.id()));

        List<AppDeploymentService.AppDeployment> listed = svc.list("acc", "claims");
        assertEquals(1, listed.size());
        assertEquals(d.bundleSha256(), listed.get(0).bundleSha256());
        assertEquals(List.of("adjusters"), listed.get(0).manifest().groupsAccess());
        assertEquals("ACTIVE", listed.get(0).state());
    }

    @Test
    void theSameBundleAgainIsIdempotentAndAChangedOneIsTheNextVersion() throws Exception {
        AppDeploymentService.AppDeployment first = svc.deploy("acc", full(), "kim");
        AppDeploymentService.AppDeployment again = svc.deploy("acc", full(), "lee");
        assertEquals(first.id(), again.id());
        assertEquals(1, approvals.get(), "no second approval for an identical bundle");
        assertEquals(1, h2.engine.getRepositoryService().createDeploymentQuery().count());

        AppDeploymentService.AppDeployment second = svc.deploy("acc", bundle("{\"key\":\"claims\",\"groupsAccess\":[\"adjusters\"]}",
                "claim.bpmn", BPMN.replace("<userTask id=\"t\"/>", "<userTask id=\"t\" name=\"Review\"/>")), "kim");
        assertEquals(2, second.version());
        assertEquals(2, svc.list("acc", "claims").size());
        assertEquals(1, svc.deploy("ops", full(), "kim").version(), "versions count per tenant");
    }

    @Test
    void aManifestOnlyChangeIsANewApprovedVersion() {
        AppDeploymentService.AppDeployment first = svc.deploy("acc", full(), "kim");
        AppBundle widened = bundle("{\"key\":\"claims\",\"groupsAccess\":[\"adjusters\",\"everyone\"]}", "claim.bpmn", BPMN,
                "claimFiled.event", EVENT, "claimHook.channel", CHANNEL, "claim.form", FORM);
        AppDeploymentService.AppDeployment second = svc.deploy("acc", widened, "kim");
        assertNotEquals(first.id(), second.id(), "an access change is not silently swallowed as 'same bundle'");
        assertEquals(2, approvals.get());
        assertEquals(List.of("adjusters", "everyone"), svc.list("acc", "claims").get(0).manifest().groupsAccess());
    }

    @Test
    void aRefusedApprovalOrAValidatorErrorDeploysNothing() throws Exception {
        AppDeploymentService refusing = service((t, b, by) -> {
            throw new SecurityException("four-eyes approval missing");
        });
        assertThrows(SecurityException.class, () -> refusing.deploy("acc", full(), "kim"));
        IllegalArgumentException invalid = assertThrows(IllegalArgumentException.class, () -> svc.deploy("acc",
                bundle("{\"key\":\"bad\"}", "broken.bpmn", "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\"><process id=\"p\""),
                "kim"));
        assertTrue(invalid.getMessage().contains("rejected"), invalid.getMessage());
        assertEquals(0, approvals.get(), "validation runs before approval");
        assertEquals(0, h2.engine.getRepositoryService().createDeploymentQuery().count());
        assertEquals(0, count("SELECT COUNT(*) FROM APP_DEPLOYMENT"));
        assertEquals(0, count("SELECT COUNT(*) FROM EVT_EVENT_DEF"));
    }

    @Test
    void aFailureLateInTheDeploymentRollsEverythingBack() throws Exception {
        // the event and the engine deployment are written before the channel, which is invalid (inbound "http")
        AppBundle b = bundle("{\"key\":\"claims\"}", "claim.bpmn", BPMN, "a.event", EVENT,
                "z.channel", "{\"key\":\"bad\",\"channelType\":\"http\",\"type\":\"inbound\"}");
        assertThrows(IllegalArgumentException.class, () -> svc.deploy("acc", b, "kim"));
        assertEquals(0, h2.engine.getRepositoryService().createDeploymentQuery().count(), "no engine deployment");
        assertEquals(0, count("SELECT COUNT(*) FROM EVT_EVENT_DEF"), "no event definition");
        assertEquals(0, count("SELECT COUNT(*) FROM APP_DEPLOYMENT"));
        events.invalidate();
        assertTrue(events.event("acc", "claimFiled").isEmpty());
    }

    @Test
    void retireSuspendsTheDefinitionsButRunningInstancesContinue() throws Exception {
        AppDeploymentService.AppDeployment d = svc.deploy("acc", full(), "kim");
        String pi = h2.engine.getRuntimeService().createProcessInstanceByKey("claimFlow").processDefinitionTenantId("acc").execute().getId();
        svc.retire(d.id());
        assertEquals("RETIRED", svc.list("acc", "claims").get(0).state());
        var pd = h2.engine.getRepositoryService().createProcessDefinitionQuery().deploymentId(d.engineDeploymentId()).singleResult();
        assertTrue(pd.isSuspended());
        assertThrows(RuntimeException.class, () -> h2.engine.getRuntimeService().createProcessInstanceByKey("claimFlow")
                .processDefinitionTenantId("acc").execute(), "no new starts");
        assertEquals(1, h2.engine.getTaskService().createTaskQuery().processInstanceId(pi).active().count(), "running instance continues");

        AppDeploymentService.AppDeployment redeployed = svc.deploy("acc", full(), "kim");
        assertEquals(2, redeployed.version(), "a retired version is not returned as the idempotent match");
    }

    @Test
    void anAppWithoutEngineModelsHasNoEngineDeployment() throws Exception {
        AppDeploymentService.AppDeployment d = svc.deploy(null, bundle("{\"key\":\"eventsOnly\"}", "e.event", EVENT), "kim");
        assertEquals(null, d.engineDeploymentId());
        assertTrue(events.event("", "claimFiled").isPresent(), "default tenant");
        assertEquals(1, count("SELECT COUNT(*) FROM APP_DEPLOYMENT WHERE tenant_id = ''"));
    }
}

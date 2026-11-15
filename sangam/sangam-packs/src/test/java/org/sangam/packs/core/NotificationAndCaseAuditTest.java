// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.engine.runtime.ProcessInstance;

import org.sangam.packs.port.PackAuditEvent;
import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.sim.LoggingAuditSink;

/**
 * Owner decision 299, the three X.4.0.3 events of sangam-packs: {@code CASE_CREATED} at case creation
 * ({@link PackStartListener}), {@code NOTIFICATION_SENT} when the gateway confirms a notification and
 * {@code NOTIFICATION_SUPPRESSED_DUPLICATE} when the same notification operation was already accepted in the case
 * ({@link NotifyDelegate}). Written for the test phase (not run here).
 */
class NotificationAndCaseAuditTest {

    private static final String TENANT = "t-notify";
    static ProcessEngine engine;
    static int seq;

    private static final String NS = "xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:operaton=\"http://operaton.org/schema/1.0/bpmn\""
            + " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" targetNamespace=\"t\"";

    private static String field(String name, String value) {
        return "<operaton:field name=\"" + name + "\"><operaton:string>" + value
                + "</operaton:string></operaton:field>";
    }

    /**
     * A pack-like case: the start listener, then notification step {@code n1} (in-app, template t1), then, while
     * {@code again} is true, a user task that leads back to the same step at the same case version.
     */
    private static String process(String id, String initialState) {
        return "<definitions " + NS + "><process id=\"" + id + "\" isExecutable=\"true\" operaton:historyTimeToLive=\"30\"><extensionElements>"
                + "<operaton:executionListener event=\"start\" class=\"" + PackStartListener.class.getName() + "\">"
                + field("packId", "test-pack") + field("packVersion", "1.0.0")
                + (initialState == null ? "" : field("initialState", initialState))
                + "</operaton:executionListener></extensionElements><startEvent id=\"s\"/>"
                + "<serviceTask id=\"n1\" operaton:class=\"" + NotifyDelegate.class.getName() + "\"><extensionElements>"
                + field("channel", "inapp") + field("template", "t1") + "</extensionElements></serviceTask>"
                + "<exclusiveGateway id=\"g\" default=\"toEnd\"/><userTask id=\"u1\"/><endEvent id=\"e\"/>"
                + "<sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"n1\"/>"
                + "<sequenceFlow id=\"f2\" sourceRef=\"n1\" targetRef=\"g\"/>"
                + "<sequenceFlow id=\"toTask\" sourceRef=\"g\" targetRef=\"u1\">"
                + "<conditionExpression xsi:type=\"tFormalExpression\">${again}"
                + "</conditionExpression></sequenceFlow>"
                + "<sequenceFlow id=\"toEnd\" sourceRef=\"g\" targetRef=\"e\"/>"
                + "<sequenceFlow id=\"back\" sourceRef=\"u1\" targetRef=\"n1\"/></process></definitions>";
    }

    @BeforeAll
    static void setUp() {
        PackRuntime.reset();
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-notify-audit;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        cfg.setHistory("full");
        org.sangam.packs.PackTestEngines.configure(cfg);
        engine = cfg.buildProcessEngine();
        engine.getRepositoryService().createDeployment().tenantId(TENANT)
                .addString("notify.bpmn", process("notify-case", null))
                .addString("draft.bpmn", process("notify-draft", "DRAFT"))
                .deploy();
    }

    @AfterAll
    static void tearDown() {
        engine.close();
        PackRuntime.reset();
    }

    @BeforeEach
    void freshPorts() {
        PackRuntime.reset(); // a new simulator gateway and audit sink per test
    }

    private static ProcessInstance start(String key, Map<String, Object> extra) {
        Map<String, Object> v = new HashMap<>(Map.of("applicantRef", "citizen-1", "again", true));
        v.putAll(extra);
        return engine.getRuntimeService().createProcessInstanceByKey(key).businessKey("NOTIFY-" + (++seq))
                .processDefinitionTenantId(TENANT).setVariables(v).execute();
    }

    private static List<PackAuditEvent> timeline(ProcessInstance pi) {
        // history, so the timeline is found after the case has ended too
        String bk = engine.getHistoryService().createHistoricProcessInstanceQuery().processInstanceId(pi.getId())
                .singleResult().getBusinessKey();
        return ((LoggingAuditSink) PackRuntime.audit()).timeline(bk);
    }

    private static List<String> types(ProcessInstance pi, String prefix) {
        return timeline(pi).stream().map(PackAuditEvent::type).filter(t -> t.startsWith(prefix)).toList();
    }

    /** Completes the case's open task with {@code again}, which re-enters step n1 at the same case version. */
    private static void loopBack(ProcessInstance pi, boolean again) {
        String task = engine.getTaskService().createTaskQuery().processInstanceId(pi.getId()).singleResult().getId();
        engine.getTaskService().complete(task, Map.of("again", again));
    }

    @Test
    void caseCreatedIsWrittenOnceAtCreationBeforeTheInitialStateEvent() {
        ProcessInstance pi = start("notify-case", Map.of());
        List<String> types = timeline(pi).stream().map(PackAuditEvent::type).toList();
        assertEquals(1, types.stream().filter("CASE_CREATED"::equals).count(), types.toString());
        assertTrue(types.indexOf("CASE_CREATED") < types.indexOf("CASE_SUBMITTED"), types.toString());
        PackAuditEvent created = timeline(pi).stream().filter(e -> e.type().equals("CASE_CREATED")).findFirst()
                .orElseThrow();
        assertEquals("test-pack@1.0.0", created.details().get("package"));
        assertEquals("SUBMITTED", created.details().get("initialState"));
        assertEquals("test-pack@1.0.0", created.packageVersion());
        // a case that starts as a draft: CASE_CREATED, then CASE_DRAFT_SAVED (X.4.0.3 DRAFT row)
        ProcessInstance draft = start("notify-draft", Map.of());
        List<String> d = timeline(draft).stream().map(PackAuditEvent::type).toList();
        assertTrue(d.indexOf("CASE_CREATED") >= 0 && d.indexOf("CASE_CREATED") < d.indexOf("CASE_DRAFT_SAVED"),
                d.toString());
        assertEquals("DRAFT", timeline(draft).stream().filter(e -> e.type().equals("CASE_CREATED")).findFirst()
                .orElseThrow().details().get("initialState"));
    }

    @Test
    void aConfirmedNotificationIsQueuedAndSentAndARepeatIsSuppressedNotResent() {
        ProcessInstance pi = start("notify-case", Map.of());
        assertEquals(List.of("NOTIFICATION_QUEUED", "NOTIFICATION_SENT"), types(pi, "NOTIFICATION_"));
        PackAuditEvent sent = timeline(pi).stream().filter(e -> e.type().equals("NOTIFICATION_SENT")).findFirst()
                .orElseThrow();
        assertEquals("inapp", sent.details().get("channel"));
        assertEquals("t1", sent.details().get("template"));
        assertEquals("citizen-1", sent.details().get("recipient"));
        String opId = sent.details().get("operationId").toString();
        assertNotNull(engine.getRuntimeService().getVariable(pi.getId(), NotifyDelegate.NOTIFIED_PREFIX + opId),
                "the case records the accepted operation");
        // the same step again at the same case version: the same operationId, suppressed, not submitted again
        int submitted = journal().size();
        loopBack(pi, false);
        assertEquals(List.of("NOTIFICATION_QUEUED", "NOTIFICATION_SENT", "NOTIFICATION_SUPPRESSED_DUPLICATE"),
                types(pi, "NOTIFICATION_"));
        assertEquals(submitted, journal().size(), "a suppressed duplicate reaches no gateway");
        PackAuditEvent dup = timeline(pi).stream().filter(e -> e.type().equals("NOTIFICATION_SUPPRESSED_DUPLICATE"))
                .findFirst().orElseThrow();
        assertEquals(opId, dup.details().get("operationId"));
        assertNotNull(dup.details().get("firstAcceptedAt"));
    }

    @Test
    void aPendingNotificationIsQueuedButNotYetSent() {
        ProcessInstance pi = start("notify-case", Map.of("sim_notify_inapp", "PENDING"));
        assertEquals(List.of("NOTIFICATION_QUEUED"), types(pi, "NOTIFICATION_"));
        loopBack(pi, false);
        assertEquals(List.of("NOTIFICATION_QUEUED", "NOTIFICATION_SUPPRESSED_DUPLICATE"), types(pi, "NOTIFICATION_"));
    }

    @Test
    void aFailedNotificationIsNeitherSentNorRecordedSoARepeatIsNotSuppressed() {
        ProcessInstance pi = start("notify-case", Map.of("sim_notify_inapp", "FAILED_TEMPLATE_NOT_REGISTERED"));
        assertEquals(List.of("NOTIFICATION_FAILED"), types(pi, "NOTIFICATION_"));
        assertTrue(engine.getRuntimeService().getVariables(pi.getId()).keySet().stream()
                .noneMatch(k -> k.startsWith(NotifyDelegate.NOTIFIED_PREFIX)), "a failure is not recorded as sent");
        loopBack(pi, false);
        assertEquals(List.of("NOTIFICATION_FAILED", "NOTIFICATION_FAILED"), types(pi, "NOTIFICATION_"));
        assertNull(engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(pi.getId())
                .singleResult(), "the business step is never failed by a notification (J-P2-E08)");
    }

    private static List<org.sangam.packs.port.ConnectorRequest> journal() {
        return ((org.sangam.packs.sim.SimulatorConnectorGateway) PackRuntime.gateway()).journal().stream()
                .filter(r -> r.connector().startsWith("notify.")).toList();
    }
}

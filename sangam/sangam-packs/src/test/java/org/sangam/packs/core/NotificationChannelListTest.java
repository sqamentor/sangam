// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

import org.sangam.packs.port.ConnectorRequest;
import org.sangam.packs.port.PackAuditEvent;
import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.sim.LoggingAuditSink;
import org.sangam.packs.sim.SimulatorConnectorGateway;

/**
 * Owner decision 312: a channel list such as {@code inapp,email} is split, and one notification is sent per channel,
 * each to its own connector with its own operationId and its own NOTIFICATION_SENT, whoever the caller is (the notify
 * step, an SLA escalation or an SLA breach notice). Decision 313, synchronous path: a gateway that completes the
 * submit itself gives exactly one NOTIFICATION_SENT per notification.
 */
class NotificationChannelListTest {

    private static final String TENANT = "t-channels";
    static ProcessEngine engine;
    static int seq;

    private static final String NS = "xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:operaton=\"http://operaton.org/schema/1.0/bpmn\" targetNamespace=\"t\"";

    private static String field(String name, String value) {
        return "<operaton:field name=\"" + name + "\"><operaton:string>" + value
                + "</operaton:string></operaton:field>";
    }

    /** Start, one service task {@code step} of {@code delegate} with {@code fields}, a user task, end. */
    private static String process(String id, Class<?> delegate, String fields) {
        return "<definitions " + NS + "><process id=\"" + id + "\" isExecutable=\"true\""
                + " operaton:historyTimeToLive=\"30\"><startEvent id=\"s\"/>"
                + "<serviceTask id=\"step\" operaton:class=\"" + delegate.getName() + "\"><extensionElements>" + fields
                + "</extensionElements></serviceTask><userTask id=\"u1\"/><endEvent id=\"e\"/>"
                + "<sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"step\"/>"
                + "<sequenceFlow id=\"f2\" sourceRef=\"step\" targetRef=\"u1\"/>"
                + "<sequenceFlow id=\"f3\" sourceRef=\"u1\" targetRef=\"e\"/></process></definitions>";
    }

    @BeforeAll
    static void setUp() {
        PackRuntime.reset();
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-notify-channels;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        cfg.setHistory("full");
        org.sangam.packs.PackTestEngines.configure(cfg);
        engine = cfg.buildProcessEngine();
        engine.getRepositoryService().createDeployment().tenantId(TENANT)
                .addString("escalate.bpmn", process("sla-escalate", SlaDelegate.class, field("op", "BREACH")))
                .addString("breach.bpmn", process("sla-breach", SlaDelegate.class,
                        field("op", "BREACH") + field("maxEscalations", "0")))
                .addString("list.bpmn", process("notify-list", NotifyDelegate.class,
                        field("channel", "inapp, ,email,inapp") + field("template", "t1")))
                .addString("none.bpmn", process("notify-none", NotifyDelegate.class,
                        field("channel", " , ") + field("template", "t1")))
                .deploy();
    }

    @AfterAll
    static void tearDown() {
        engine.close();
        // R8-heap2: a test class stays loaded for the whole run; its statics must not keep a closed engine
        engine = null;
        PackRuntime.reset();
    }

    @BeforeEach
    void freshPorts() {
        PackRuntime.reset(); // a new simulator gateway and audit sink per test
    }

    private static ProcessInstance start(String key) {
        Map<String, Object> v = new HashMap<>(Map.of("applicantRef", "citizen-1", "currentSeat", "seat-clerk",
                "escalationSeat", "seat-officer"));
        return engine.getRuntimeService().createProcessInstanceByKey(key).businessKey("CHANNELS-" + (++seq))
                .processDefinitionTenantId(TENANT).setVariables(v).execute();
    }

    private static List<PackAuditEvent> events(ProcessInstance pi, String type) {
        return ((LoggingAuditSink) PackRuntime.audit()).timeline(pi.getBusinessKey()).stream()
                .filter(e -> e.type().equals(type)).toList();
    }

    private static List<String> types(ProcessInstance pi) {
        return ((LoggingAuditSink) PackRuntime.audit()).timeline(pi.getBusinessKey()).stream()
                .map(PackAuditEvent::type).filter(t -> t.startsWith("NOTIFICATION_")).toList();
    }

    /** The notification requests that reached the gateway, as {@code connector:template:recipient}. */
    private static List<String> submitted() {
        return ((SimulatorConnectorGateway) PackRuntime.gateway()).journal().stream()
                .filter(r -> r.connector().startsWith("notify."))
                .map(r -> r.connector() + ":" + r.payload().get("template") + ":" + r.payload().get("recipient"))
                .toList();
    }

    private static List<ConnectorRequest> requests() {
        return ((SimulatorConnectorGateway) PackRuntime.gateway()).journal().stream()
                .filter(r -> r.connector().startsWith("notify.")).toList();
    }

    @Test
    void anSlaEscalationSendsOneNotificationPerChannelOfItsList() {
        ProcessInstance pi = start("sla-escalate");
        assertEquals(List.of("notify.inapp:sla-escalated:seat-officer", "notify.email:sla-escalated:seat-officer",
                "notify.sms:status-delayed-escalated:citizen-1"), submitted());
        assertEquals(3, requests().stream().map(ConnectorRequest::operationId).distinct().count(),
                "each notification has its own operationId");
        List<PackAuditEvent> sent = events(pi, "NOTIFICATION_SENT");
        assertEquals(List.of("inapp", "email", "sms"), sent.stream().map(e -> e.details().get("channel")).toList(),
                "each channel has its own NOTIFICATION_SENT");
        assertEquals(requests().stream().map(ConnectorRequest::operationId).toList(),
                sent.stream().map(e -> e.details().get("operationId")).toList());
        assertTrue(events(pi, "NOTIFICATION_FAILED").isEmpty());
    }

    @Test
    void anSlaBreachNoticeSendsOneNotificationPerChannelOfItsList() {
        ProcessInstance pi = start("sla-breach");
        assertEquals(List.of("notify.inapp:sla-breached:seat-clerk", "notify.email:sla-breached:seat-clerk"),
                submitted());
        assertEquals(List.of("inapp", "email"),
                events(pi, "NOTIFICATION_SENT").stream().map(e -> e.details().get("channel")).toList());
        assertEquals(2, requests().stream().map(ConnectorRequest::operationId).distinct().count());
    }

    @Test
    void blankEntriesAreIgnoredAndAChannelNamedTwiceIsSentOnce() {
        ProcessInstance pi = start("notify-list");
        assertEquals(List.of("notify.inapp:t1:citizen-1", "notify.email:t1:citizen-1"), submitted());
        assertEquals(List.of("NOTIFICATION_QUEUED", "NOTIFICATION_SENT", "NOTIFICATION_QUEUED", "NOTIFICATION_SENT",
                "NOTIFICATION_SUPPRESSED_DUPLICATE"), types(pi));
        PackAuditEvent dup = events(pi, "NOTIFICATION_SUPPRESSED_DUPLICATE").get(0);
        assertEquals("inapp", dup.details().get("channel"));
        assertEquals(events(pi, "NOTIFICATION_SENT").get(0).details().get("operationId"),
                dup.details().get("operationId"));
        // decision 313, synchronous path: exactly one NOTIFICATION_SENT per notification
        assertEquals(2, events(pi, "NOTIFICATION_SENT").stream().map(e -> e.details().get("operationId")).distinct()
                .count());
    }

    @Test
    void aListThatNamesNoChannelSendsNothingAndSaysSoWithoutFailingTheStep() {
        ProcessInstance pi = start("notify-none");
        assertTrue(submitted().isEmpty());
        assertEquals(List.of("NOTIFICATION_FAILED"), types(pi));
        assertEquals(NotifyDelegate.NO_CHANNEL, events(pi, "NOTIFICATION_FAILED").get(0).details().get("reason"));
        assertNotNull(engine.getTaskService().createTaskQuery().processInstanceId(pi.getId()).singleResult(),
                "the business step went on (J-P2-E08)");
    }
}

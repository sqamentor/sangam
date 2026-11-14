// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.flowable;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.sangam.ops.flowable.FlowableSnapshot.JobKind;
import org.sangam.ops.flowable.InstancePlan.Finding;
import org.sangam.ops.flowable.InstancePlan.Severity;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Decision 169: the differences found when the migration was checked against the Flowable source (reference copy,
 * {@code modules/}, file and line cited in {@link FlowableTables}, {@link FlowableModelListeners},
 * {@link FlowableInventory} and {@link StateClassifier}). Each test pins one source fact. Written, NOT_RUN.
 */
class FlowableSourceEvidenceTest {

    private static Map<String, Object> job(String handler, String element, String cfg) {
        Map<String, Object> j = new HashMap<>();
        j.put(FlowableTables.HANDLER_TYPE, handler);
        j.put(FlowableTables.ELEMENT_ID, element);
        j.put(FlowableTables.HANDLER_CFG, cfg);
        return j;
    }

    /** TimerEventHandler.java:50-63, and ELEMENT_ID_ added without backfill (upgradestep 6500 to 6501, lines 4-10). */
    @Test
    void timerActivityComesFromElementIdElseFromTheHandlerConfiguration() {
        assertEquals("cooling", FlowableInventory.elementId(job("trigger-timer", "cooling", "{\"activityId\":\"other\"}")),
                "ELEMENT_ID_ wins when present");
        assertEquals("cooling", FlowableInventory.elementId(job("trigger-timer", null, "{\"activityId\":\"cooling\"}")));
        assertEquals("cooling", FlowableInventory.elementId(job("trigger-timer", null, "cooling")), "legacy plain text");
        assertEquals("{\"timerEndDate\":\"x\"}", FlowableInventory.elementId(job("trigger-timer", null, "{\"timerEndDate\":\"x\"}")),
                "JSON without activityId is the configuration itself, as in Flowable");
        assertNull(FlowableInventory.elementId(job("trigger-timer", null, "{\"activityId\":null}")), "explicit null stays null");
        assertNull(FlowableInventory.elementId(job("async-continuation", null, "cooling")), "only a trigger-timer is read");
        assertNull(FlowableInventory.elementId(job("trigger-timer", null, null)));
    }

    @Test
    void legacyTimerRowsOfTheCorpusStillMigrate() {
        FlowableSnapshot snap = new FlowableInventory(FlowableCorpus.supported(), FlowableCorpus.ZONE).read(FlowableCorpus.TENANT);
        Map<String, String> elements = snap.instances().stream().flatMap(i -> i.jobs().stream())
                .collect(Collectors.toMap(FlowableSnapshot.Job::id, j -> String.valueOf(j.elementId())));
        assertEquals("cooling", elements.get("j3"), "JSON configuration");
        assertEquals("chooseTimer", elements.get("j7"), "legacy plain configuration");
    }

    /** ContinueProcessOperation.java:187, TakeOutgoingSequenceFlowsOperation.java:133, TriggerTimerEventJobHandler:31. */
    @Test
    void eachJobTableHasOneHandlerThatIsRecreated() {
        assertEquals("async-continuation", StateClassifier.expectedHandler(JobKind.ASYNC));
        assertEquals("async-continuation", StateClassifier.expectedHandler(JobKind.SUSPENDED_ASYNC));
        assertEquals("trigger-timer", StateClassifier.expectedHandler(JobKind.TIMER));
        assertEquals("trigger-timer", StateClassifier.expectedHandler(JobKind.SUSPENDED_TIMER));
        assertEquals("external-worker-complete", StateClassifier.expectedHandler(JobKind.EXTERNAL));
        assertNull(StateClassifier.expectedHandler(JobKind.DEADLETTER));
    }

    @Test
    void sourceCheckRejectionsCarryOnlyTheirCause() {
        Map<String, InstancePlan> plans = classify(FlowableCorpus.rejected());
        assertTrue(codes(plans.get("r17"), Severity.REJECT).contains(StateClassifier.UNSUPPORTED_JOB_HANDLER));
        assertFalse(plans.get("r17").startActivities().contains("dispatch"), "an async-after job is not an asyncBefore wait");
        assertFalse(codes(plans.get("r18"), Severity.REJECT).contains(StateClassifier.BINARY_VARIABLE), "longString is long text");
        assertFalse(codes(plans.get("r10"), Severity.REJECT).contains(StateClassifier.UNSUPPORTED_VARIABLE_TYPE), "json has its own code");
        plans.forEach((id, p) -> {
            boolean listenerCase = Set.of("r21", "r22", "r23").contains(id);
            assertEquals(listenerCase, p.findings().stream().anyMatch(x -> x.code().startsWith("SOURCE_")),
                    id + ": only r21 to r23 have source listener findings " + p.findings());
        });
    }

    // ------------------------------------------------------------------ listeners (ExecutionEntityManagerImpl, TaskHelper)

    private static final String SOURCE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn"
                         xmlns:activiti="http://activiti.org/bpmn" targetNamespace="urn:sangam:test">
              <process id="other">
                <userTask id="review"><extensionElements><flowable:taskListener event="delete" class="x.Other"/></extensionElements></userTask>
              </process>
              <process id="order">
                <extensionElements>%s</extensionElements>
                <userTask id="review"><extensionElements>
                  <flowable:taskListener event="create" class="x.A"/>
                  <flowable:taskListener event="delete" class="x.B"/>
                </extensionElements></userTask>
                <subProcess id="sub">
                  <userTask id="approve"><extensionElements><activiti:taskListener event="all" class="x.C"/></extensionElements></userTask>
                </subProcess>
                <userTask id="quiet"><extensionElements><flowable:taskListener event="complete" class="x.D"/></extensionElements></userTask>
              </process>
            </definitions>
            """;

    private static byte[] source(String processExtensions) {
        return SOURCE.formatted(processExtensions).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void deleteAndAllTaskListenersAndProcessEventListenersAreFound() {
        FlowableModelListeners.Found f = FlowableModelListeners.parse(source(
                "<flowable:eventListener class=\"x.E\"/><flowable:eventListener events=\"TASK_DELETED\" class=\"x.F\"/>"),
                "order", "order.bpmn20.xml");
        assertTrue(f.read(), String.valueOf(f.problem()));
        assertEquals(List.of("create", "delete"), f.taskListeners().get("review"));
        assertTrue(f.deleteListener("review"));
        assertTrue(f.deleteListener("approve"), "event all, Activiti namespace, inside a subprocess");
        assertFalse(f.deleteListener("quiet"), "complete only");
        assertEquals(List.of("(all)", "TASK_DELETED"), f.processListeners());
        assertFalse(f.taskListeners().containsKey("other"), "another process of the same resource is not read");
    }

    @Test
    void unreadableOrMissingModelsAreReportedNotGuessed() {
        assertFalse(FlowableModelListeners.parse(null, "order", "r.xml").read());
        assertFalse(FlowableModelListeners.parse(source(""), "missing", "r.xml").read());
        assertFalse(FlowableModelListeners.parse("<not-xml".getBytes(StandardCharsets.UTF_8), "order", "r.xml").read());
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY e SYSTEM \"file:///nonexistent-sangam\">]>"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\"><process id=\"order\">&e;</process></definitions>";
        FlowableModelListeners.Found f = assertDoesNotThrow(() -> FlowableModelListeners.parse(xxe.getBytes(StandardCharsets.UTF_8), "order", "r.xml"));
        assertTrue(!f.read() || f.taskListeners().isEmpty(), "no external entity is resolved");
    }

    /** The supported corpus with a deployed model whose listeners fire on end (delete on review, all on approve). */
    static FlowableRows withListeners(String processExtensions) {
        Map<String, List<Map<String, Object>>> tables = new LinkedHashMap<>();
        FlowableRows base = FlowableCorpus.supported();
        FlowableTables.ALL.forEach(t -> tables.put(t, base.all(t)));
        return new FlowableRows.Fixture(tables, processExtensions == null ? Map.of()
                : Map.of("dep-100/order.bpmn20.xml", source(processExtensions)));
    }

    /** Owner decision 231: without an acknowledgement the listeners reject; with one for the same fingerprint, WARN. */
    @Test
    void sourceListenersRejectUntilTheDefinitionIsAcknowledged() {
        FlowableRows rows = withListeners("<flowable:eventListener class=\"x.E\"/>");
        Map<String, InstancePlan> plans = classify(rows, Map.of());
        InstancePlan s1 = plans.get("s1");
        assertEquals(InstancePlan.Status.REJECTED, s1.status());
        assertTrue(codes(s1, Severity.REJECT).containsAll(Set.of(StateClassifier.SOURCE_DELETE_LISTENER,
                StateClassifier.SOURCE_EVENT_LISTENER)), s1.findings().toString());
        assertTrue(codes(plans.get("s4"), Severity.REJECT).contains(StateClassifier.SOURCE_DELETE_LISTENER), "approve: all");
        assertFalse(codes(plans.get("s2"), Severity.REJECT).contains(StateClassifier.SOURCE_DELETE_LISTENER), "no open task");
        assertTrue(codes(plans.get("s2"), Severity.REJECT).contains(StateClassifier.SOURCE_EVENT_LISTENER), "process listener");

        FlowableInventory.Definition def = new FlowableInventory(rows, FlowableCorpus.ZONE).definition("order:1:100",
                FlowableCorpus.TENANT).orElseThrow();
        assertTrue(def.listeners().firesOnEnd());
        Map<String, InstancePlan> acked = classify(rows, Map.of("order:1:100", def.listeners().fingerprint()));
        acked.values().forEach(p -> assertEquals(InstancePlan.Status.SUPPORTED, p.status(), p.sourceInstanceId() + " " + p.findings()));
        assertTrue(codes(acked.get("s1"), Severity.WARN).containsAll(Set.of(StateClassifier.SOURCE_DELETE_LISTENER,
                StateClassifier.SOURCE_EVENT_LISTENER)), acked.get("s1").findings().toString());

        Map<String, InstancePlan> stale = classify(rows, Map.of("order:1:100", "0".repeat(64)));
        assertEquals(InstancePlan.Status.REJECTED, stale.get("s1").status(), "an acknowledgement of other listeners does not count");
        assertTrue(stale.get("s1").findings().stream().anyMatch(x -> x.message().contains("acknowledge again")));
        assertEquals(InstancePlan.Status.REJECTED, classify(rows, Map.of("order:9:999", def.listeners().fingerprint()))
                .get("s1").status(), "only the acknowledged definition id counts");
    }

    @Test
    void anUnreadModelAlwaysRejectsEvenWhenAcknowledged() {
        FlowableRows none = withListeners(null);
        String fp = FlowableModelListeners.Found.unread("x").fingerprint();
        classify(none, Map.of("order:1:100", fp)).values().forEach(p -> {
            assertEquals(InstancePlan.Status.REJECTED, p.status(), p.sourceInstanceId());
            assertTrue(codes(p, Severity.REJECT).contains(StateClassifier.SOURCE_MODEL_UNREAD), p.sourceInstanceId());
        });
        classify(FlowableCorpus.supported()).values().forEach(p -> assertTrue(p.findings().stream()
                .noneMatch(x -> x.code().startsWith("SOURCE_")), p.sourceInstanceId() + " " + p.findings()));
    }

    @Test
    void theFingerprintCoversExactlyTheListenersSeen() {
        FlowableModelListeners.Found a = FlowableModelListeners.parse(source(""), "order", "r.xml");
        FlowableModelListeners.Found b = FlowableModelListeners.parse(source(""), "order", "r.xml");
        FlowableModelListeners.Found c = FlowableModelListeners.parse(source("<flowable:eventListener class=\"x.E\"/>"), "order", "r.xml");
        assertEquals(a.fingerprint(), b.fingerprint());
        assertNotEquals(a.fingerprint(), c.fingerprint());
        assertEquals(64, a.fingerprint().length());
        assertTrue(FlowableModelListeners.parse(source(""), "other", "r.xml").firesOnEnd(), "delete listener of process other");
        assertFalse(FlowableModelListeners.parse(FlowableCorpus.supported().resource("dep-100", "order.bpmn20.xml"), "order",
                "order.bpmn20.xml").firesOnEnd(), "create and assignment listeners do not fire on end");
    }

    @Test
    void cutOverReportNamesWhatTheModelCannotShow() {
        String note = FlowableMigrationPipeline.CUT_OVER_LISTENER_NOTE;
        assertTrue(note.contains("engine-wide event listeners"), note);
        assertTrue(note.contains("EndProcessInstanceInterceptor"), note);
        assertTrue(note.contains("SOURCE_DELETE_LISTENER"), note);
    }

    /** ACT_GE_BYTEARRAY (flowable.h2.create.common.sql:8-15) read by deployment and name, nothing else. */
    @Test
    void jdbcReadsTheDeployedResourceBytes() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (Connection c = ds.getConnection()) {
            c.createStatement().execute("CREATE TABLE ACT_GE_BYTEARRAY (ID_ VARCHAR(64) PRIMARY KEY, REV_ INTEGER, NAME_ VARCHAR(255),"
                    + " DEPLOYMENT_ID_ VARCHAR(64), BYTES_ BLOB, GENERATED_ BOOLEAN)");
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO ACT_GE_BYTEARRAY VALUES (?, 1, ?, ?, ?, FALSE)")) {
                ps.setString(1, "b1");
                ps.setString(2, "order.bpmn20.xml");
                ps.setString(3, "dep-100");
                ps.setBytes(4, source(""));
                ps.executeUpdate();
            }
            FlowableRows rows = FlowableRows.jdbc(ds);
            assertArrayEquals(source(""), rows.resource("dep-100", "order.bpmn20.xml"));
            assertNull(rows.resource("dep-100", "other.bpmn20.xml"));
            assertNull(rows.resource(null, "order.bpmn20.xml"));
        }
    }

    private static Map<String, InstancePlan> classify(FlowableRows rows) {
        return classify(rows, Map.of());
    }

    private static Map<String, InstancePlan> classify(FlowableRows rows, Map<String, String> acknowledged) {
        FlowableSnapshot snap = new FlowableInventory(rows, FlowableCorpus.ZONE).read(FlowableCorpus.TENANT);
        StateClassifier c = new StateClassifier(FlowableCorpus.MAPPINGS, FlowableCorpus.model(), 3, acknowledged);
        return snap.instances().stream().map(i -> c.classify(i, FlowableCorpus.NOW))
                .collect(Collectors.toMap(InstancePlan::sourceInstanceId, p -> p));
    }

    private static Set<String> codes(InstancePlan p, Severity s) {
        return p.findings().stream().filter(f -> f.severity() == s).map(Finding::code).collect(Collectors.toSet());
    }
}

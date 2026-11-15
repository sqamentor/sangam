// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.engine.runtime.Job;
import org.operaton.bpm.engine.task.IdentityLink;
import org.operaton.bpm.engine.task.Task;

import org.sangam.packs.PackDriver.Answer;
import org.sangam.packs.core.CaseRecordDelegate;
import org.sangam.packs.port.PackAuditEvent;
import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.sim.LoggingAuditSink;
import org.sangam.packs.spi.PackDeployer;
import org.sangam.packs.spi.PackRegistry;

/**
 * Owner decision 299, the three X.4.0.3 Records (R61) events of the shipped packs, each written once per business
 * action in the engine transaction of that action: {@code RECORD_REVIEWED} after the record review task rr_decide,
 * {@code RECORD_TRANSFERRED_NAI} only when the National Archives transfer rr_nai (core execution) is settled as
 * succeeded, and {@code RTI_REQUEST_LINKED} in the p2 event subprocess p2_rti (message {@code sangam.rtiLinked})
 * before its legal hold (SANGAM.md J-P2-E10, AU-03). Written for the test phase (not run here).
 */
class RecordsCatalogueEventsTest {

    static ProcessEngine engine;
    static PackDriver driver;
    static int seq;

    @BeforeAll
    static void setUp() {
        PackRuntime.reset();
        // no configuration profile in this test: bound directly, so the simulator journal of a record holds only what
        // the record itself requested (the default binder reads config.profile through the gateway at every start)
        org.sangam.packs.port.ProfileBindings.install((tenant, pack, businessKey) -> null);
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-records-events;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        cfg.setHistory("full");
        PackTestEngines.configure(cfg);
        engine = cfg.buildProcessEngine();
        new PackDeployer(engine, PackRegistry.discover()).deployAll(PackTestTenant.ID);
        // a process of this test only: the transfer step without a settled transfer
        engine.getRepositoryService().createDeployment().tenantId(PackTestTenant.ID)
                .addString("unsettled.bpmn", unsettledTransfer()).deploy();
        driver = new PackDriver(engine);
    }

    @AfterAll
    static void tearDown() {
        engine.close();
        PackRuntime.reset();
        org.sangam.packs.port.ProfileBindings.install(null); // back to the default binder
    }

    private static String unsettledTransfer() {
        return "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                + " xmlns:operaton=\"http://operaton.org/schema/1.0/bpmn\" targetNamespace=\"t\">"
                + "<process id=\"records-unsettled\" isExecutable=\"true\" operaton:historyTimeToLive=\"30\"><startEvent id=\"s\"/>"
                + "<serviceTask id=\"d\" operaton:class=\"" + CaseRecordDelegate.class.getName() + "\">"
                + "<extensionElements><operaton:field name=\"op\"><operaton:string>NAI_TRANSFERRED</operaton:string>"
                + "</operaton:field></extensionElements></serviceTask><endEvent id=\"e\"/>"
                + "<sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"d\"/>"
                + "<sequenceFlow id=\"f2\" sourceRef=\"d\" targetRef=\"e\"/></process></definitions>";
    }

    private static List<PackAuditEvent> timeline(String bk) {
        return ((LoggingAuditSink) PackRuntime.audit()).timeline(bk);
    }

    private static List<PackAuditEvent> events(String bk, String type) {
        return timeline(bk).stream().filter(e -> e.type().equals(type)).toList();
    }

    /** Acting as the holder of the task's candidate seat today (as PilotScenarioTest does). */
    private static Answer asSeat(Task t, Object... kv) {
        String seat = engine.getTaskService().getIdentityLinksForTask(t.getId()).stream()
                .map(IdentityLink::getGroupId).filter(java.util.Objects::nonNull).findFirst().orElse("none");
        String person = PackRuntime.seats().holderOf(PackTestTenant.ID, seat, java.time.LocalDate.now()).orElse("user." + seat);
        return Answer.of(person, seat, kv);
    }

    /** Opens a closed record (category C, ten years) on the retention schedule. */
    private static String openRecord(Map<String, Object> extra) {
        String bk = "REC/C/2016/" + (++seq);
        Map<String, Object> v = new HashMap<>(Map.of("fileNo", bk, "subject", "Establishment file, closed 2016",
                "retentionCategory", "C", "retentionYears", 10, "initiator", "rec.officer",
                "actingSeat", "RECORD-OFFICER", "officeId", "REC-1"));
        v.putAll(extra);
        engine.getRuntimeService().createProcessInstanceByKey("sangam-record-retention").businessKey(bk)
                .setVariables(v).processDefinitionTenantId(PackTestTenant.ID).execute();
        return bk;
    }

    /** The review is due (the schedule's timer, years ahead, fired now). */
    private static void reviewDue(String bk) {
        String pid = engine.getRuntimeService().createProcessInstanceQuery().processDefinitionKey(
                "sangam-record-retention").processInstanceBusinessKey(bk).singleResult().getId();
        Job timer = engine.getManagementService().createJobQuery().processInstanceId(pid).timers()
                .activityId("rr_review").singleResult();
        assertNotNull(timer, "the record waits for its review date");
        engine.getManagementService().executeJob(timer.getId());
    }

    /** Completes the record's review tasks with these decisions, one each, then stops. */
    private static List<String> review(String bk, String... decisions) {
        Deque<String> next = new ArrayDeque<>(List.of(decisions));
        return driver.run(bk, t -> "rr_decide".equals(t.getTaskDefinitionKey()) && !next.isEmpty()
                ? asSeat(t, "reviewDecision", next.poll(), "remarks", "reviewed on the schedule") : null, 30);
    }

    private static boolean ended(String bk) {
        return engine.getRuntimeService().createProcessInstanceQuery().processInstanceBusinessKey(bk).count() == 0;
    }

    @Test
    void eachReviewIsRecordedOnceAndASettledTransferOnceInItsTransaction() {
        String bk = openRecord(Map.of());
        reviewDue(bk);
        assertEquals(List.of("rr_decide"), review(bk, "retain"));
        List<PackAuditEvent> reviewed = events(bk, "RECORD_REVIEWED");
        assertEquals(1, reviewed.size(), timeline(bk).toString());
        assertEquals(bk, reviewed.get(0).details().get("fileNo"));
        assertEquals("retain", reviewed.get(0).details().get("decision"));
        assertEquals("C", reviewed.get(0).details().get("retentionCategory"));
        assertEquals("P5Y", reviewed.get(0).details().get("nextReviewPeriod"), "retained: reviewed again in 5 years");
        assertNotNull(reviewed.get(0).actorSeatId(), "the reviewing seat is on the event");
        assertTrue(events(bk, "RECORD_TRANSFERRED_NAI").isEmpty());
        // the next review transfers the record; the simulator acknowledges the transfer at once
        reviewDue(bk);
        assertEquals(List.of("rr_decide"), review(bk, "transferNai"));
        assertTrue(ended(bk), "transferred");
        reviewed = events(bk, "RECORD_REVIEWED");
        assertEquals(2, reviewed.size(), "one per review");
        assertEquals("transferNai", reviewed.get(1).details().get("decision"));
        assertEquals(null, reviewed.get(1).details().get("nextReviewPeriod"));
        List<PackAuditEvent> transferred = events(bk, "RECORD_TRANSFERRED_NAI");
        assertEquals(1, transferred.size(), timeline(bk).toString());
        assertEquals(bk, transferred.get(0).details().get("fileNo"));
        assertEquals("archive.nai", transferred.get(0).details().get("connector"));
        assertEquals(driver.var(bk, "opExternalRef"), transferred.get(0).details().get("naiRef"));
        assertNotNull(transferred.get(0).operationId(), "the transfer's operationId is on the event");
        List<String> types = timeline(bk).stream().map(PackAuditEvent::type).toList();
        assertTrue(types.lastIndexOf("EXT_OP_ACKNOWLEDGED") < types.indexOf("RECORD_TRANSFERRED_NAI"),
                types.toString());
    }

    @Test
    void aQueuedTransferIsNotRecordedUntilItsResultSettlesIt() {
        String bk = openRecord(Map.of("sim_archive_nai", "PENDING"));
        reviewDue(bk);
        review(bk, "transferNai");
        assertFalse(ended(bk), "the record waits for the transfer's outcome, it is not ended as transferred");
        assertTrue(events(bk, "RECORD_TRANSFERRED_NAI").isEmpty(), "requested is not transferred");
        var child = engine.getRuntimeService().createProcessInstanceQuery()
                .processDefinitionKey("sangam-core-execution").processInstanceBusinessKey(bk).singleResult();
        Object opId = engine.getRuntimeService().getVariable(child.getId(), "operationId");
        assertNotNull(opId);
        // the inbox delivers the settled result (PackMessages: correlated on operationId)
        engine.getRuntimeService().createMessageCorrelation("sangam.operationResult")
                .tenantId(PackTestTenant.ID).processInstanceVariableEquals("operationId", opId)
                .setVariable("resultStatus", "COMPLETED").setVariable("resultExternalRef", "NAI/ACC/2026/0077")
                .correlate();
        assertTrue(ended(bk));
        List<PackAuditEvent> transferred = events(bk, "RECORD_TRANSFERRED_NAI");
        assertEquals(1, transferred.size(), timeline(bk).toString());
        assertEquals("NAI/ACC/2026/0077", transferred.get(0).details().get("naiRef"));
        assertEquals(opId, transferred.get(0).operationId());
    }

    @Test
    void aFailedTransferIsNotRecordedAndTheRecordReturnsToReview() {
        String bk = openRecord(Map.of("sim_archive_nai", "FAILED:NAI_REFUSED"));
        reviewDue(bk);
        assertEquals(List.of("rr_decide"), review(bk, "transferNai"));
        assertTrue(events(bk, "RECORD_TRANSFERRED_NAI").isEmpty());
        assertFalse(ended(bk));
        Task again = engine.getTaskService().createTaskQuery().processInstanceBusinessKey(bk).singleResult();
        assertEquals("rr_decide", again.getTaskDefinitionKey(), "the officer reviews the record again");
        assertEquals(1, events(bk, "RECORD_REVIEWED").size());
        assertEquals(List.of("rr_decide"), review(bk, "retain"));
        assertEquals(2, events(bk, "RECORD_REVIEWED").size());
        assertTrue(events(bk, "RECORD_TRANSFERRED_NAI").isEmpty());
    }

    @Test
    void aReviewWithoutAKnownDecisionIsRefusedAndRecordsNothing() {
        String bk = openRecord(Map.of());
        reviewDue(bk);
        Task t = engine.getTaskService().createTaskQuery().processInstanceBusinessKey(bk).singleResult();
        Answer a = asSeat(t);
        Map<String, Object> local = new HashMap<>(Map.of("actingPerson", a.person(), "actingSeat", a.seat()));
        local.put(PackDriver.DSC_DOCUMENT, PackDriver.SIMULATED_DSC.sign(engine, t, a.person()));
        engine.getTaskService().setVariablesLocal(t.getId(), local);
        assertThrows(ProcessEngineException.class, () -> engine.getTaskService().complete(t.getId(),
                Map.of("reviewDecision", "weed", "remarks", "not a decision of the review")));
        assertTrue(events(bk, "RECORD_REVIEWED").isEmpty(), "nothing recorded for a refused review");
        assertNotNull(engine.getTaskService().createTaskQuery().taskId(t.getId()).singleResult(), "still open");
        assertTrue(((org.sangam.packs.sim.SimulatorConnectorGateway) PackRuntime.gateway()).journal().stream()
                .noneMatch(r -> bk.equals(r.businessKey())), "no transfer and no destruction was requested");
    }

    @Test
    void theTransferStepRefusesATransferThatIsNotSettled() {
        for (String status : new String[] {"PENDING", "UNKNOWN", "FAILED", "NEW"}) {
            ProcessEngineException e = assertThrows(ProcessEngineException.class, () -> engine.getRuntimeService()
                    .createProcessInstanceByKey("records-unsettled").businessKey("UNSETTLED-" + status)
                    .processDefinitionTenantId(PackTestTenant.ID).setVariables(Map.of("opStatus", status))
                    .execute());
            assertTrue(e.getMessage().contains("NAI_TRANSFER_NOT_SETTLED"), e.getMessage());
            assertTrue(events("UNSETTLED-" + status, "RECORD_TRANSFERRED_NAI").isEmpty(), status);
        }
    }

    @Test
    void eachLinkedRtiRequestIsRecordedOnceBeforeItsLegalHold() {
        String bk = "B-2/99/2026-Estt-" + (++seq);
        engine.getRuntimeService().createProcessInstanceByKey("sangam-p2-file-movement").businessKey(bk)
                .processDefinitionTenantId(PackTestTenant.ID).setVariables(PilotScenarioTest.vars(
                        "fileNo", bk, "subject", "Sanction of honorarium", "subjectHead", "ESTABLISHMENT",
                        "noting", "Proposal for approval.", "draftLetter", "Draft order.", "actingSeat", "DA-B2",
                        "initiator", "p2.da", "officeId", "SEC-B2"))
                .execute();
        driver.runJobs();
        for (String ref : List.of("RTI/2026/0042", "RTI/2026/0043")) {
            engine.getRuntimeService().createMessageCorrelation("sangam.rtiLinked").tenantId(PackTestTenant.ID)
                    .processInstanceBusinessKey(bk).setVariable("rtiRequestRef", ref).correlate();
        }
        List<PackAuditEvent> linked = events(bk, "RTI_REQUEST_LINKED");
        assertEquals(2, linked.size(), "one per linked request");
        assertEquals(List.of("RTI/2026/0042", "RTI/2026/0043"),
                linked.stream().map(e -> e.details().get("rtiRequestRef")).toList());
        assertEquals(bk, linked.get(0).details().get("fileNo"));
        List<String> types = timeline(bk).stream().map(PackAuditEvent::type).toList();
        assertTrue(types.indexOf("RTI_REQUEST_LINKED") < types.indexOf("LEGAL_HOLD_SET"), types.toString());
        assertEquals(Boolean.TRUE, driver.var(bk, "legalHold"), "the RTI keeps the file from weeding");
    }
}

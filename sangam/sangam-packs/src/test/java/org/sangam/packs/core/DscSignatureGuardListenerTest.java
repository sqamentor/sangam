// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.engine.task.Task;

import org.sangam.packs.PackTestEngines;
import org.sangam.packs.PackTestTenant;
import org.sangam.packs.port.DocumentSlotBindings;
import org.sangam.packs.port.PackAuditEvent;
import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.port.SignatureVerifications;

/**
 * Decisions 140, 165, 227, 236 and 237: a task that needs a DSC signature completes only when the server verified a
 * signature of exactly the step's required document (its slot as it was when the task was created) by the person
 * completing it, at that task. Fail closed: no adapter, no document in the slot, a slot changed during the task,
 * another document, another signer, another task, a false answer or an exception. On success the evidence is written
 * for SignatureDelegate (decision 238). Decision 241: a status of ENROLMENT_REVOKED refuses with
 * 422 DSC_ENROLMENT_REVOKED; NOT_SIGNED, no answer and a failing query keep 422 DSC_SIGNATURE_NOT_VERIFIED.
 */
class DscSignatureGuardListenerTest {

    static final String TENANT = PackTestTenant.ID;
    static final String SIGNER = "secretary-1";
    static final String SLOT = "sanctionDocumentId";
    static ProcessEngine engine;
    final List<PackAuditEvent> audit = new ArrayList<>();
    final List<String> asked = new ArrayList<>();
    final List<String> bindingsAsked = new ArrayList<>();
    /** Decision 385 (2): the documents the (simulated) document route bound into the slot, by document id. */
    static final java.util.Set<String> ROUTE_BOUND = java.util.Set.of("doc-1", "doc-2");

    static final String FIELDS = """
            <operaton:field name="documentSlot"><operaton:string>sanctionDocumentId</operaton:string></operaton:field>
            <operaton:field name="documentType"><operaton:string>SANCTION_NOTE</operaton:string></operaton:field>
            """;

    static final String BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="http://sangam.org/test">
              <process id="dsc-guard" isExecutable="true" operaton:historyTimeToLive="30">
                <startEvent id="s"/>
                <userTask id="sanction" operaton:candidateGroups="SECRETARY">
                  <extensionElements>
                    <operaton:taskListener event="create" class="org.sangam.packs.core.DscSignatureGuardListener">
                      %1$s
                    </operaton:taskListener>
                    <operaton:taskListener event="complete" class="org.sangam.packs.core.DscSignatureGuardListener">
                      <operaton:field name="decisionVar"><operaton:string>sanctionDecision</operaton:string></operaton:field>
                      <operaton:field name="approveValues"><operaton:string>approve</operaton:string></operaton:field>
                      <operaton:field name="requiredVar"><operaton:string>signatureRequired</operaton:string></operaton:field>
                      <operaton:field name="skipVar"><operaton:string>deemedDecision</operaton:string></operaton:field>
                      %1$s
                    </operaton:taskListener>
                  </extensionElements>
                </userTask>
                <endEvent id="e"/>
                <sequenceFlow id="f1" sourceRef="s" targetRef="sanction"/>
                <sequenceFlow id="f2" sourceRef="sanction" targetRef="e"/>
              </process>
            </definitions>
            """.formatted(FIELDS);

    @BeforeAll
    static void setUp() {
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-dsc-guard;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        PackTestEngines.configure(cfg); // decisions 138 and FEEL (sangam-feel), as production
        engine = cfg.buildProcessEngine();
        engine.getRepositoryService().createDeployment().tenantId(TENANT).name("dsc-guard")
                .addString("dsc-guard.bpmn", BPMN).deploy();
    }

    @AfterAll
    static void tearDown() {
        engine.close();
        PackRuntime.reset();
    }

    @BeforeEach
    void ports() {
        PackRuntime.reset();
        PackRuntime.install(PackRuntime.ports().withAudit(audit::add).withSlotBindings(routeBound()));
    }

    /** Decision 385 (2): the route bound {@link #ROUTE_BOUND} into this slot, with this type, in this tenant. */
    DocumentSlotBindings routeBound() {
        return (tenant, processInstance, slot, type, document) -> {
            bindingsAsked.add(tenant + "|" + processInstance + "|" + slot + "|" + type + "|" + document);
            return TENANT.equals(tenant) && SLOT.equals(slot) && "SANCTION_NOTE".equals(type)
                    && ROUTE_BOUND.contains(document);
        };
    }

    @AfterEach
    void resetPorts() {
        PackRuntime.reset();
    }

    /** The port answering true only for {@code (TENANT, taskId, documentId, signer)}, recording every question. */
    void verifiedOnly(String taskId, String documentId, String signer) {
        PackRuntime.install(PackRuntime.ports().withSignatures((tenant, task, doc, who) -> {
            asked.add(tenant + "|" + task + "|" + doc + "|" + who);
            return TENANT.equals(tenant) && taskId.equals(task) && documentId.equals(doc) && signer.equals(who);
        }));
    }

    static Task start(boolean required, String method, String slotDocument) {
        Map<String, Object> v = new HashMap<>();
        v.put("signatureRequired", required);
        v.put("signMethod", method);
        v.put("deemedDecision", false);
        if (slotDocument != null) {
            v.put(SLOT, slotDocument);
        }
        String pi = engine.getRuntimeService().createProcessInstanceByKey("dsc-guard").processDefinitionTenantId(TENANT)
                .setVariables(v).execute().getId();
        return engine.getTaskService().createTaskQuery().processInstanceId(pi).singleResult();
    }

    static void complete(Task task, String decision, String clientDocument) {
        Map<String, Object> local = new HashMap<>();
        local.put("actingPerson", SIGNER);
        local.put("actingSeat", "SECRETARY");
        if (clientDocument != null) {
            local.put(DscSignatureGuardListener.CLIENT_DOCUMENT, clientDocument);
        }
        engine.getTaskService().setVariablesLocal(task.getId(), local);
        engine.getTaskService().complete(task.getId(), Map.of("sanctionDecision", decision));
    }

    static boolean open(Task task) {
        return engine.getTaskService().createTaskQuery().taskId(task.getId()).count() == 1;
    }

    static Object var(Task task, String name) {
        var h = engine.getHistoryService().createHistoricVariableInstanceQuery()
                .processInstanceId(task.getProcessInstanceId()).variableName(name).list();
        return h.isEmpty() ? null : h.get(0).getValue();
    }

    void assertRefused(Task task, String decision, String clientDocument) {
        ProcessEngineException ex = assertThrows(ProcessEngineException.class,
                () -> complete(task, decision, clientDocument));
        assertTrue(ex.getMessage().contains(DscSignatureGuardListener.REFUSAL), ex.getMessage());
        assertTrue(open(task), "the task stays open");
        assertTrue(audit.stream().anyMatch(e -> "DSC_SIGNATURE_NOT_VERIFIED".equals(e.type())), "refusal audited");
    }

    @Test
    void theCreatedTaskRecordsTheRequiredDocumentOfItsSlot() {
        Task task = start(true, "dsc", "doc-1");
        assertEquals("doc-1", engine.getTaskService().getVariableLocal(task.getId(),
                DscSignatureGuardListener.REQUIRED_DOCUMENT));
        assertEquals(SLOT, engine.getTaskService().getVariableLocal(task.getId(), DscSignatureGuardListener.SLOT));
    }

    @Test
    void aVerifiedSignatureOfTheRequiredDocumentByTheCompleterLetsTheApprovalCompleteWithEvidence() {
        Task task = start(true, "dsc", "doc-1");
        verifiedOnly(task.getId(), "doc-1", SIGNER);
        complete(task, "approve", "doc-1");
        assertFalse(open(task), "completed");
        assertEquals(List.of(TENANT + "|" + task.getId() + "|doc-1|" + SIGNER), asked,
                "asked for the engine tenant, this task, the slot's document and the completing person");
        assertTrue(audit.stream().anyMatch(e -> "DSC_SIGNATURE_VERIFIED".equals(e.type())));
        assertEquals(task.getId(), var(task, DscSignatureGuardListener.EVIDENCE_TASK));
        assertEquals("doc-1", var(task, DscSignatureGuardListener.EVIDENCE_DOCUMENT));
        assertEquals("SANCTION_NOTE", var(task, DscSignatureGuardListener.EVIDENCE_TYPE));
        assertEquals(SIGNER, var(task, DscSignatureGuardListener.EVIDENCE_SIGNER));
    }

    @Test
    void withoutAnAdapterTheDefaultRefuses() {
        assertSame(SignatureVerifications.DENY, PackRuntime.signatures(), "the default port denies");
        assertRefused(start(true, "dsc", "doc-1"), "approve", null);
    }

    @Test
    void anEmptySlotOrASlotChangedDuringTheTaskIsRefused() {
        Task empty = start(true, "dsc", null);
        verifiedOnly(empty.getId(), "doc-1", SIGNER);
        engine.getRuntimeService().setVariable(empty.getProcessInstanceId(), SLOT, "doc-1"); // after creation
        assertRefused(empty, "approve", "doc-1");

        Task changed = start(true, "dsc", "doc-1");
        verifiedOnly(changed.getId(), "doc-2", SIGNER);
        engine.getRuntimeService().setVariable(changed.getProcessInstanceId(), SLOT, "doc-2");
        assertRefused(changed, "approve", "doc-2");
    }

    @Test
    void anotherDocumentAnotherSignerOrAnotherTasksSignatureIsRefused() {
        Task task = start(true, "DSC", "doc-1");
        Task other = start(true, "dsc", "doc-1");
        verifiedOnly(task.getId(), "doc-1", SIGNER);
        assertRefused(task, "approve", "doc-2"); // the client names a document that is not the required one
        verifiedOnly(task.getId(), "doc-1", "another-officer"); // decision 237: signed by someone else
        assertRefused(task, "approve", null);
        verifiedOnly(other.getId(), "doc-1", SIGNER); // a signature verified at another task
        assertRefused(task, "approve", null);
    }

    @Test
    void aFailingCheckIsARefusal() {
        Task task = start(true, "dsc", "doc-1");
        PackRuntime.install(PackRuntime.ports().withSignatures((tenant, t, doc, who) -> {
            throw new IllegalStateException("verification store unavailable");
        }));
        assertRefused(task, "approve", null);
    }

    /** A port answering {@code status} with {@code answer} for every question; hasVerifiedSignature must not decide. */
    void statusOnly(SignatureVerifications.Status answer) {
        PackRuntime.install(PackRuntime.ports().withSignatures(new SignatureVerifications() {
            @Override
            public boolean hasVerifiedSignature(String tenant, String task, String doc, String who) {
                return answer == Status.VERIFIED;
            }

            @Override
            public Status status(String tenant, String task, String doc, String who) {
                asked.add(tenant + "|" + task + "|" + doc + "|" + who);
                return answer;
            }
        }));
    }

    @Test
    void theDefaultStatusFollowsHasVerifiedSignature() {
        SignatureVerifications yes = (tenant, task, doc, who) -> true;
        assertEquals(SignatureVerifications.Status.VERIFIED, yes.status(TENANT, "t", "d", SIGNER));
        assertEquals(SignatureVerifications.Status.NOT_SIGNED,
                SignatureVerifications.DENY.status(TENANT, "t", "d", SIGNER), "DENY stays NOT_SIGNED");
    }

    @Test
    void decision241AStatusVerifiedCompletesWithEvidence() {
        Task task = start(true, "dsc", "doc-1");
        statusOnly(SignatureVerifications.Status.VERIFIED);
        complete(task, "approve", "doc-1");
        assertFalse(open(task), "completed");
        assertEquals(List.of(TENANT + "|" + task.getId() + "|doc-1|" + SIGNER), asked);
        assertEquals("doc-1", var(task, DscSignatureGuardListener.EVIDENCE_DOCUMENT));
    }

    @Test
    void decision241ARevokedEnrolmentRefusesWithItsOwnCode() {
        Task task = start(true, "dsc", "doc-1");
        statusOnly(SignatureVerifications.Status.ENROLMENT_REVOKED);
        ProcessEngineException ex = assertThrows(ProcessEngineException.class, () -> complete(task, "approve", null));
        assertTrue(ex.getMessage().contains(DscSignatureGuardListener.REFUSAL_REVOKED), ex.getMessage());
        assertFalse(ex.getMessage().contains(DscSignatureGuardListener.REFUSAL), ex.getMessage());
        assertTrue(open(task), "the task stays open");
        assertTrue(audit.stream().anyMatch(e -> "DSC_ENROLMENT_REVOKED".equals(e.type())), "refusal audited");
        assertNull(var(task, DscSignatureGuardListener.EVIDENCE_TASK), "no evidence for a revoked enrolment");
    }

    @Test
    void decision241NotSignedOrNoAnswerKeepsTheGenericRefusal() {
        Task notSigned = start(true, "dsc", "doc-1");
        statusOnly(SignatureVerifications.Status.NOT_SIGNED);
        assertRefused(notSigned, "approve", null);
        Task noAnswer = start(true, "dsc", "doc-1");
        statusOnly(null);
        assertRefused(noAnswer, "approve", null);
    }

    @Test
    void decision241AFailingStatusQueryIsTheGenericRefusal() {
        Task task = start(true, "dsc", "doc-1");
        PackRuntime.install(PackRuntime.ports().withSignatures(new SignatureVerifications() {
            @Override
            public boolean hasVerifiedSignature(String tenant, String t, String doc, String who) {
                return true;
            }

            @Override
            public Status status(String tenant, String t, String doc, String who) {
                throw new IllegalStateException("verification store unavailable");
            }
        }));
        assertRefused(task, "approve", null);
    }

    @Test
    void decision241ARevokedEnrolmentIsNotAskedWhenTheDocumentIsWrong() {
        Task task = start(true, "dsc", "doc-1");
        statusOnly(SignatureVerifications.Status.ENROLMENT_REVOKED);
        assertRefused(task, "approve", "doc-2"); // another document: the generic refusal, the port is not asked
        assertTrue(asked.isEmpty(), "not asked");
    }

    @Test
    void aStepThatNeedsNoDscSignatureIsNotChecked() {
        PackRuntime.install(PackRuntime.ports().withSignatures((tenant, t, doc, who) -> {
            throw new AssertionError("not asked");
        }));
        Task esign = start(true, "esign", null);
        complete(esign, "approve", null);
        assertFalse(open(esign), "eSign: decided by the signature step after the task");
        Task notRequired = start(false, "dsc", null);
        complete(notRequired, "approve", null);
        assertFalse(open(notRequired));
        Task rejected = start(true, "dsc", null);
        complete(rejected, "reject", null);
        assertFalse(open(rejected), "a rejection signs nothing");
        assertNull(var(rejected, DscSignatureGuardListener.EVIDENCE_TASK), "no evidence without a check");
    }

    @Test
    void aDeemedDecisionIsNeverSigned() {
        Task task = start(true, "dsc", null);
        engine.getRuntimeService().setVariable(task.getProcessInstanceId(), "deemedDecision", true);
        complete(task, "approve", null);
        assertFalse(open(task));
    }

    @Test
    void aSignatureOrEvidenceInAVariableNeverCounts() {
        Task task = start(true, "dsc", "doc-1");
        engine.getRuntimeService().setVariable(task.getProcessInstanceId(), "signatureStatus", "COMPLETED");
        engine.getRuntimeService().setVariable(task.getProcessInstanceId(), DscSignatureGuardListener.EVIDENCE_TASK,
                task.getId());
        assertRefused(task, "approve", "doc-1");
    }

    // ---------------------------------------------------------------- decision 385 (2), layer 1

    @Test
    void decision385TheCreateListenerAsksWhetherTheRouteBoundTheSlotsDocument() {
        Task task = start(true, "dsc", "doc-1");
        assertEquals(List.of(TENANT + "|" + List.of(task.getProcessInstanceId()) + "|" + SLOT + "|SANCTION_NOTE|doc-1"),
                bindingsAsked, "the engine tenant, this instance, the slot, the step's type and the slot's document");
        assertEquals("doc-1", engine.getTaskService().getVariableLocal(task.getId(),
                DscSignatureGuardListener.REQUIRED_DOCUMENT));
        assertNull(engine.getTaskService().getVariableLocal(task.getId(), DscSignatureGuardListener.UNBOUND));
    }

    @Test
    void decision385ASlotValueTheRouteDidNotBindIsAnEmptySlotEvenWithAVerifiedSignature() {
        // the bypass: a caller wrote the slot (process start, task completion, variable set, message) and signed it
        Task task = start(true, "dsc", "doc-forged");
        assertNull(engine.getTaskService().getVariableLocal(task.getId(), DscSignatureGuardListener.REQUIRED_DOCUMENT),
                "not captured");
        assertEquals(Boolean.TRUE, engine.getTaskService().getVariableLocal(task.getId(),
                DscSignatureGuardListener.UNBOUND));
        verifiedOnly(task.getId(), "doc-forged", SIGNER);
        ProcessEngineException ex = assertThrows(ProcessEngineException.class,
                () -> complete(task, "approve", "doc-forged"));
        assertTrue(ex.getMessage().contains(DscSignatureGuardListener.REFUSAL), ex.getMessage());
        assertTrue(ex.getMessage().contains("not uploaded through the document route"), ex.getMessage());
        assertTrue(open(task));
        assertTrue(asked.isEmpty(), "the signature port is not asked about an unbound document");
    }

    @Test
    void decision385WithoutABindingPortNothingIsBound() {
        PackRuntime.reset();
        PackRuntime.install(PackRuntime.ports().withAudit(audit::add));
        assertSame(DocumentSlotBindings.NONE, PackRuntime.slotBindings(), "the default binds nothing");
        Task task = start(true, "dsc", "doc-1");
        verifiedOnly(task.getId(), "doc-1", SIGNER);
        assertNull(engine.getTaskService().getVariableLocal(task.getId(), DscSignatureGuardListener.REQUIRED_DOCUMENT));
        assertRefused(task, "approve", "doc-1");
    }

    @Test
    void decision385AFailingBindingCheckIsAnEmptySlot() {
        PackRuntime.install(PackRuntime.ports().withSlotBindings((tenant, pi, slot, type, doc) -> {
            throw new IllegalStateException("audit store down");
        }));
        Task task = start(true, "dsc", "doc-1");
        verifiedOnly(task.getId(), "doc-1", SIGNER);
        assertNull(engine.getTaskService().getVariableLocal(task.getId(), DscSignatureGuardListener.REQUIRED_DOCUMENT));
        assertRefused(task, "approve", "doc-1");
    }

    @Test
    void decision385ACaptureWrittenToTheTaskAfterCreateIsAskedAgainAtCompletion() {
        // the slot was empty at create; slot and capture are then written past the route, and the forgery is signed
        Task task = start(true, "dsc", null);
        engine.getRuntimeService().setVariable(task.getProcessInstanceId(), SLOT, "doc-forged");
        engine.getTaskService().setVariableLocal(task.getId(), DscSignatureGuardListener.REQUIRED_DOCUMENT, "doc-forged");
        verifiedOnly(task.getId(), "doc-forged", SIGNER);
        ProcessEngineException ex = assertThrows(ProcessEngineException.class,
                () -> complete(task, "approve", "doc-forged"));
        assertTrue(ex.getMessage().contains("not uploaded through the document route"), ex.getMessage());
        assertTrue(open(task));
        assertTrue(asked.isEmpty(), "the signature port is not asked about an unbound document");
    }

    @Test
    void decision385ABoundDocumentIsAskedAtCreateAndAtCompletion() {
        Task task = start(true, "dsc", "doc-1");
        verifiedOnly(task.getId(), "doc-1", SIGNER);
        complete(task, "approve", "doc-1");
        assertFalse(open(task));
        assertEquals(2, bindingsAsked.size(), () -> "create and complete: " + bindingsAsked);
    }

    @Test
    void decision385AStepThatNeedsNoSignatureStillCompletesWithAnUnboundSlot() {
        Task task = start(false, "dsc", "doc-forged");
        complete(task, "approve", null);
        assertFalse(open(task), "only a DSC signing step is refused");
    }
}

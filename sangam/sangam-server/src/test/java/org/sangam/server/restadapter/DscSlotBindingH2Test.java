// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.restadapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.task.Task;
import org.sangam.assembly.packs.AuditDocumentSlotBindings;
import org.sangam.assembly.packs.PackDocumentSlotNames;
import org.sangam.core.engine.DocumentSlotNames;
import org.sangam.core.layer.Jdbc;
import org.sangam.packs.core.DscSignatureGuardListener;
import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.spi.PackDeployer;
import org.sangam.packs.spi.PackRegistry;
import org.sangam.ports.content.ContentService;
import org.sangam.ports.content.FileSystemDocumentStore;
import org.sangam.ports.support.EngineTx;
import org.sangam.rest.adapter.jdbc.JdbcAuditAdapter;
import org.sangam.rest.adapter.operaton.OperatonTaskAdapter;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.DocumentPort;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.TaskPort;
import org.sangam.rest.support.DocumentSlots;
import org.sangam.rest.support.RestProperties;
import org.sangam.server.bridge.OperatonDocumentSlots;
import org.sangam.server.bridge.PortsDocumentBridge;
import org.sangam.server.config.PacksWiring;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

/**
 * Owner decisions 382 and 385 (2), end to end on H2 with the server's own pieces: the document route
 * ({@link DocumentSlots} over {@link OperatonDocumentSlots}, the store {@link PortsDocumentBridge} and its audit
 * {@link JdbcAuditAdapter}), the DSC gate's binding check ({@link AuditDocumentSlotBindings}) and the REST task adapter.
 * A caller case uploads the sanction note on its prepare task, the call copies the slot into the gate's process, and the
 * gate completes over a verified signature of exactly that document. The bypass (a slot written by a task completion)
 * is refused twice: by the adapter ({@code 422 DOCUMENT_SLOT_VARIABLE}, layer 2) and, written past the adapter, by the
 * gate itself (an unbound slot is an empty slot, layer 1).
 */
class DscSlotBindingH2Test {

    static final String T = "t1";
    static final String OFFICER = "officer-1";
    static final String SIGNER = "secretary-1";
    static final String SLOT = "sanctionDocumentId";
    static final byte[] PDF = "%PDF-1.4\n%sangam test note\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    static final RequestContext OFFICER_CTX = new RequestContext(T, OFFICER, Set.of("clerks"), null, null, null, null,
            null, "OIDC");

    static final String CALLER = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="http://sangam.org/test">
              <process id="slot-caller" isExecutable="true" operaton:historyTimeToLive="30">
                <startEvent id="s"/>
                <userTask id="prepare" name="Prepare the document to sign" operaton:assignee="officer-1"/>
                <callActivity id="call" calledElement="slot-gate">
                  <extensionElements><operaton:in variables="all"/></extensionElements>
                </callActivity>
                <endEvent id="e"/>
                <sequenceFlow id="f1" sourceRef="s" targetRef="prepare"/>
                <sequenceFlow id="f2" sourceRef="prepare" targetRef="call"/>
                <sequenceFlow id="f3" sourceRef="call" targetRef="e"/>
              </process>
            </definitions>
            """;

    static final String FIELDS = """
            <operaton:field name="documentSlot"><operaton:string>sanctionDocumentId</operaton:string></operaton:field>
            <operaton:field name="documentType"><operaton:string>SANCTION_NOTE</operaton:string></operaton:field>
            """;

    static final String GATE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="http://sangam.org/test">
              <process id="slot-gate" isExecutable="true" operaton:historyTimeToLive="30">
                <startEvent id="s"/>
                <userTask id="sanction" operaton:assignee="secretary-1">
                  <extensionElements>
                    <operaton:taskListener event="create" class="org.sangam.packs.core.DscSignatureGuardListener">
                      %1$s
                    </operaton:taskListener>
                    <operaton:taskListener event="complete" class="org.sangam.packs.core.DscSignatureGuardListener">
                      <operaton:field name="decisionVar"><operaton:string>sanctionDecision</operaton:string></operaton:field>
                      <operaton:field name="approveValues"><operaton:string>approve</operaton:string></operaton:field>
                      <operaton:field name="requiredVar"><operaton:string>signatureRequired</operaton:string></operaton:field>
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

    static RestEngineFixture fx;
    static Path store;
    static JdbcAuditAdapter audit;
    static PortsDocumentBridge documents;
    static DocumentSlots slots;
    static OperatonTaskAdapter tasks;

    @BeforeAll
    static void setUp() throws Exception {
        fx = new RestEngineFixture("dsc385");
        // the deployment the pack deployer makes: the document route reads the pack (sangam.core: SANCTION_NOTE is
        // sanctionDocumentId) from the deployment name and source
        fx.engine.getRepositoryService().createDeployment().tenantId(T).name("pack:sangam.core@0.0.0-test")
                .source(PackDeployer.SOURCE).addString("slot-caller.bpmn", CALLER).addString("slot-gate.bpmn", GATE)
                .deploy();
        RestProperties props = new RestProperties();
        audit = new JdbcAuditAdapter(fx.tx, fx.engine.getHistoryService(), fx.engine.getRuntimeService(), props);
        store = Files.createTempDirectory("dsc385-store");
        ContentService content = new ContentService(fx.tx.dialect(), EngineTx.transactions(fx.engine),
                new FileSystemDocumentStore(store), null, false);
        documents = new PortsDocumentBridge(content, fx.engine.getTaskService(), fx.engine.getHistoryService(), audit,
                props, fx.tx);
        PermissionPort taskWriters = new PermissionPort() {
            @Override
            public boolean has(RequestContext ctx, String permission) {
                return true;
            }

            @Override
            public Set<String> permissions(RequestContext ctx) {
                return Set.of();
            }

            @Override
            public boolean mayReplay(RequestContext ctx, String commandType, String resourceRef) {
                return false;
            }
        };
        slots = new DocumentSlots(new OperatonDocumentSlots(fx.engine.getRuntimeService(), fx.engine.getTaskService(),
                fx.engine.getRepositoryService(), PackRegistry.discover(), fx.tx), taskWriters);
        tasks = new OperatonTaskAdapter(fx.engine.getTaskService(), fx.engine.getRuntimeService(),
                fx.engine.getHistoryService(), fx.engine.getFormService(), fx.tx, audit, props);
    }

    @AfterAll
    static void tearDown() {
        fx.close();
        PackRuntime.reset();
        DocumentSlotNames.reset();
        // R9-statics: a test class stays loaded for the whole run; its statics must not pin what they held
        fx = null;
        audit = null;
        documents = null;
        slots = null;
        tasks = null;
    }

    final List<String> signed = new java.util.ArrayList<>();

    @BeforeEach
    void ports() {
        PackRuntime.reset();
        // the server's binding check; a signature port that verifies only what this test signed
        PackRuntime.install(PackRuntime.ports()
                .withSlotBindings(bindings())
                .withSignatures((tenant, task, doc, who) -> signed.contains(tenant + "|" + task + "|" + doc + "|" + who)));
        PackRegistry registry = PackRegistry.discover();
        PackDocumentSlotNames.install(registry);
    }

    @AfterEach
    void resetPorts() {
        PackRuntime.reset();
        DocumentSlotNames.reset();
    }

    /** The server's binding check and consumption, as PacksWiring builds it. */
    static AuditDocumentSlotBindings bindings() {
        return new AuditDocumentSlotBindings(fx.tx.joinedTransactions(),
                org.sangam.server.config.PacksWiring::engineCommand, new org.sangam.core.layer.AuditChain(fx.tx.dialect()));
    }

    static String startCaller() {
        Map<String, Object> v = new HashMap<>();
        v.put("signatureRequired", true);
        v.put("signMethod", "dsc");
        return fx.engine.getRuntimeService().createProcessInstanceByKey("slot-caller").processDefinitionTenantId(T)
                .setVariables(v).execute().getId();
    }

    static Task openTask(String processInstanceId) {
        return fx.engine.getTaskService().createTaskQuery().processInstanceId(processInstanceId).singleResult();
    }

    static String childOf(String caller) {
        return fx.engine.getRuntimeService().createProcessInstanceQuery().superProcessInstanceId(caller).singleResult()
                .getId();
    }

    /** The officer uploads the sanction note on the prepare task through the document route. */
    static DocumentPort.Document upload(Task prepare) {
        return slots.upload(OFFICER_CTX, documents, new DocumentPort.UploadRequest("note.pdf", "application/pdf", PDF,
                prepare.getId(), null, null, Map.of()), "SANCTION_NOTE", false);
    }

    void sign(Task gate, String documentId) {
        signed.add(T + "|" + gate.getId() + "|" + documentId + "|" + SIGNER);
    }

    static void completeGate(Task gate) {
        fx.engine.getTaskService().setVariablesLocal(gate.getId(), Map.of("actingPerson", SIGNER,
                "actingSeat", "SECRETARY"));
        fx.engine.getTaskService().complete(gate.getId(), Map.of("sanctionDecision", "approve"));
    }

    @Test
    void theNoteUploadedOnTheCallersPrepareTaskIsSignedAndTheGateCompletes() {
        String caller = startCaller();
        DocumentPort.Document note = upload(openTask(caller));
        assertEquals(note.id(), fx.engine.getRuntimeService().getVariable(caller, SLOT), "the route filled the slot");
        fx.engine.getTaskService().complete(openTask(caller).getId());
        String child = childOf(caller);
        Task gate = openTask(child);
        assertEquals(note.id(), fx.engine.getTaskService().getVariableLocal(gate.getId(),
                DscSignatureGuardListener.REQUIRED_DOCUMENT), "bound on the caller, captured on the gate's instance");
        assertTrue(bindings()
                .isBound(T, List.of(child, caller), SLOT, "SANCTION_NOTE", note.id()));
        sign(gate, note.id());
        completeGate(gate);
        assertEquals(0, fx.engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(caller).count(),
                "the gate and the caller ended");
    }

    @Test
    void decision390ASignedGateConsumesTheBindingOnTheCallersChainInItsCompletion() {
        String caller = startCaller();
        DocumentPort.Document note = upload(openTask(caller));
        fx.engine.getTaskService().complete(openTask(caller).getId());
        String child = childOf(caller);
        Task gate = openTask(child);
        sign(gate, note.id());
        completeGate(gate);
        List<String> consumed = fx.tx.withConnection(c -> Jdbc.query(c, "SELECT p.payload FROM AUDIT_EVENT e"
                + " JOIN AUDIT_PAYLOAD p ON p.event_id = e.event_id WHERE e.tenant_id = ? AND e.chain_id = ?"
                + " AND e.event_type = ?", rs -> rs.getString(1), T, caller, AuditDocumentSlotBindings.CONSUMED));
        assertEquals(1, consumed.size(), "one consumption, on the chain where the route bound the slot");
        Map<?, ?> p = readJson(consumed.get(0));
        assertEquals(SLOT, p.get("slot"));
        assertEquals(note.id(), p.get("document_id"));
        assertEquals("SANCTION_NOTE", p.get("document_type"));
        assertEquals(child, p.get("gate_instance"));
        assertEquals(gate.getId(), p.get("task_id"));
        assertFalse(bindings().isBound(T, List.of("another-gate", caller), SLOT, "SANCTION_NOTE", note.id()),
                "another gate instance finds the slot consumed");
        assertTrue(bindings().isBound(T, List.of(child, caller), SLOT, "SANCTION_NOTE", note.id()),
                "the gate instance that signed it still finds it bound (its other levels)");
    }

    @Test
    void decision390ABindingOlderThanAnotherGateInstancesConsumptionIsNotBoundUntilTheRouteBindsAgain() {
        String caller = startCaller();
        Task prepare = openTask(caller);
        DocumentPort.Document note = upload(prepare);
        AuditDocumentSlotBindings b = bindings();
        assertThrows(IllegalStateException.class, () -> b.consume(T, List.of("gate-a", caller), SLOT, "NOTING",
                note.id(), "task-a"), "a binding of another type cannot be consumed");
        b.consume(T, List.of("gate-a", caller), SLOT, "SANCTION_NOTE", note.id(), "task-a");
        assertTrue(b.isBound(T, List.of("gate-a", caller), SLOT, "SANCTION_NOTE", note.id()), "own gate instance");
        assertFalse(b.isBound(T, List.of("gate-b", caller), SLOT, "SANCTION_NOTE", note.id()), "another gate instance");
        assertFalse(b.isBound(T, List.of(caller), SLOT, "SANCTION_NOTE", note.id()), "the prepare task of the caller");
        assertThrows(IllegalStateException.class, () -> b.consume(T, List.of("gate-b", caller), SLOT, "SANCTION_NOTE",
                note.id(), "task-b"), "a consumed binding cannot be consumed by another gate instance");
        // a new version through the route is a newer binding of the same document
        slots.newVersion(OFFICER_CTX, documents, note.id(), null, new DocumentPort.UploadRequest("note-v2.pdf",
                "application/pdf", PDF, prepare.getId(), null, null, Map.of()), "SANCTION_NOTE", false);
        assertTrue(b.isBound(T, List.of("gate-b", caller), SLOT, "SANCTION_NOTE", note.id()), "bound again");
        b.consume(T, List.of("gate-b", caller), SLOT, "SANCTION_NOTE", note.id(), "task-b");
        assertFalse(b.isBound(T, List.of("gate-a", caller), SLOT, "SANCTION_NOTE", note.id()),
                "gate a's binding ended with gate b's consumption");
        // a replacing upload is a newer binding of another document
        DocumentPort.Document fresh = slots.upload(OFFICER_CTX, documents, new DocumentPort.UploadRequest("note3.pdf",
                "application/pdf", PDF, prepare.getId(), null, null, Map.of()), "SANCTION_NOTE", true);
        assertTrue(b.isBound(T, List.of("gate-c", caller), SLOT, "SANCTION_NOTE", fresh.id()));
        assertFalse(b.isBound(T, List.of("gate-c", caller), SLOT, "SANCTION_NOTE", note.id()));
    }

    @Test
    void decision390AnErasedConsumptionIsNotBoundFailClosed() {
        String caller = startCaller();
        DocumentPort.Document note = upload(openTask(caller));
        AuditDocumentSlotBindings b = bindings();
        b.consume(T, List.of("gate-a", caller), SLOT, "SANCTION_NOTE", note.id(), "task-a");
        fx.tx.withConnection(c -> Jdbc.update(c, "UPDATE AUDIT_PAYLOAD SET erased_at = CURRENT_TIMESTAMP WHERE event_id IN"
                + " (SELECT event_id FROM AUDIT_EVENT WHERE chain_id = ? AND event_type = ?)", caller,
                AuditDocumentSlotBindings.CONSUMED));
        assertFalse(b.isBound(T, List.of("gate-a", caller), SLOT, "SANCTION_NOTE", note.id()),
                "an erased consumption cannot show whose it was");
    }

    static Map<?, ?> readJson(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void layer2TheAdapterRefusesASlotNamedInATaskCompletion() {
        String caller = startCaller();
        Task prepare = openTask(caller);
        ApiProblem p = assertThrows(ApiProblem.class, () -> tasks.complete(OFFICER_CTX, prepare.getId(),
                new TaskPort.CompleteTaskRequest(null, null, null, null, null, Map.of(SLOT, "doc-forged"), null, null,
                        null)));
        assertEquals(422, p.status());
        assertEquals("DOCUMENT_SLOT_VARIABLE", p.code(), p.detail());
        assertNull(fx.engine.getRuntimeService().getVariable(caller, SLOT), "nothing written");
        assertEquals(prepare.getId(), openTask(caller).getId(), "the task stays open");
    }

    @Test
    void layer1ASlotWrittenPastTheRouteIsAnEmptySlotAtTheGate() {
        String caller = startCaller();
        // an uploaded document that the route did not put into the slot (no documentType), then named in a completion
        DocumentPort.Document plain = documents.upload(OFFICER_CTX, new DocumentPort.UploadRequest("other.pdf",
                "application/pdf", PDF, openTask(caller).getId(), null, null, Map.of()));
        fx.engine.getTaskService().complete(openTask(caller).getId(), Map.of(SLOT, plain.id()));
        Task gate = openTask(childOf(caller));
        assertNull(fx.engine.getTaskService().getVariableLocal(gate.getId(),
                DscSignatureGuardListener.REQUIRED_DOCUMENT), "not captured");
        sign(gate, plain.id());
        ProcessEngineException e = assertThrows(ProcessEngineException.class, () -> completeGate(gate));
        assertTrue(e.getMessage().contains(DscSignatureGuardListener.REFUSAL), e.getMessage());
        assertTrue(e.getMessage().contains("not uploaded through the document route"), e.getMessage());
    }

    @Test
    void aReplacedSlotBindsOnlyTheNewDocument() {
        String caller = startCaller();
        Task prepare = openTask(caller);
        DocumentPort.Document first = upload(prepare);
        DocumentPort.Document second = slots.upload(OFFICER_CTX, documents, new DocumentPort.UploadRequest("note2.pdf",
                "application/pdf", PDF, prepare.getId(), null, null, Map.of()), "SANCTION_NOTE", true);
        AuditDocumentSlotBindings b = bindings();
        assertTrue(b.isBound(T, List.of(caller), SLOT, "SANCTION_NOTE", second.id()));
        assertFalse(b.isBound(T, List.of(caller), SLOT, "SANCTION_NOTE", first.id()), "the latest binding decides");
    }

    @Test
    void anotherTypeTenantOrSlotOrAnErasedPayloadIsNotBound() {
        String caller = startCaller();
        DocumentPort.Document note = upload(openTask(caller));
        AuditDocumentSlotBindings b = bindings();
        assertTrue(b.isBound(T, List.of(caller), SLOT, "SANCTION_NOTE", note.id()));
        assertFalse(b.isBound(T, List.of(caller), SLOT, "NOTING", note.id()), "another type");
        assertFalse(b.isBound("t2", List.of(caller), SLOT, "SANCTION_NOTE", note.id()), "another tenant");
        assertFalse(b.isBound(T, List.of(caller), "notingDocumentId", "SANCTION_NOTE", note.id()), "another slot");
        assertFalse(b.isBound(T, List.of(caller), SLOT, "SANCTION_NOTE", null), "no document");
        // a payload erased under retention (DPDP) no longer shows what it bound: not bound, fail closed
        fx.tx.withConnection(c -> Jdbc.update(c, "UPDATE AUDIT_PAYLOAD SET erased_at = CURRENT_TIMESTAMP WHERE event_id IN"
                + " (SELECT event_id FROM AUDIT_EVENT WHERE chain_id = ?)", caller));
        assertFalse(b.isBound(T, List.of(caller), SLOT, "SANCTION_NOTE", note.id()));
    }

    @Test
    void aSlotNameIsRefusedOnEveryOtherCallerVariableWriteOfTheAdapters() {
        String caller = startCaller();
        ApiProblem p = assertThrows(ApiProblem.class, () -> tasks.returnTask(OFFICER_CTX, openTask(caller).getId(),
                new TaskPort.ReturnTaskRequest("send back", null, null, Map.of(SLOT, "doc-forged"), null, null, null,
                        null)));
        assertEquals("DOCUMENT_SLOT_VARIABLE", p.code(), p.detail());
    }

    /** The server's own installer (PacksWiring), as the Spring context runs it. */
    static void runInstaller(DefaultListableBeanFactory beans) throws Exception {
        MockEnvironment env = new MockEnvironment().withProperty("sangam.security.production", "false");
        env.setActiveProfiles("h2");
        new PacksWiring().sangamPackRuntimeInstaller(fx.tx, env,
                beans.getBeanProvider(org.sangam.security.SangamSecurity.class),
                beans.getBeanProvider(org.sangam.core.engine.SangamClock.class),
                beans.getBeanProvider(org.sangam.core.time.BusinessClock.class),
                beans.getBeanProvider(org.sangam.config.profile.ProfileResolver.class),
                beans.getBeanProvider(org.sangam.integration.dsc.DscSigningFlow.class),
                beans.getBeanProvider(ProcessEngine.class), PackRegistry.discover()).afterPropertiesSet();
    }

    @Test
    void theServerInstallerWiresBothLayers() throws Exception {
        PackRuntime.reset();
        DocumentSlotNames.reset();
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("engine", fx.engine);
        runInstaller(beans);
        assertTrue(PackRuntime.slotBindings() instanceof AuditDocumentSlotBindings, "layer 1: the document route audit");
        assertTrue(DocumentSlotNames.names().containsAll(Set.of("sanctionDocumentId", "notingDocumentId")),
                () -> "layer 2: the slot names the packs declare: " + DocumentSlotNames.names());
        String caller = startCaller();
        ApiProblem p = assertThrows(ApiProblem.class, () -> tasks.complete(OFFICER_CTX, openTask(caller).getId(),
                new TaskPort.CompleteTaskRequest(null, null, null, null, null, Map.of("notingDocumentId", "doc-forged"),
                        null, null, null)));
        assertEquals("DOCUMENT_SLOT_VARIABLE", p.code(), p.detail());
    }

    @Test
    void withoutAnEngineTheInstallerBindsNothing() throws Exception {
        PackRuntime.reset();
        runInstaller(new DefaultListableBeanFactory());
        assertEquals(org.sangam.packs.port.DocumentSlotBindings.NONE, PackRuntime.slotBindings(), "fail closed");
    }
}

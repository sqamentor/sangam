// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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

/**
 * Owner decisions 387 (9) and 390: the prepare task before a call into a DSC gate completes only over slots whose
 * document the document route bound and no gate instance consumed, asked with the task's own instance first (so every
 * gate instance's consumption counts). Fail closed: an empty slot, an unbound or consumed document, a failing check, a
 * malformed or missing pair list.
 */
class DocumentSlotReadyListenerTest {

    static final String TENANT = PackTestTenant.ID;
    static ProcessEngine engine;
    final List<PackAuditEvent> audit = new ArrayList<>();
    final List<String> asked = new ArrayList<>();

    static String bpmn(String id, String slots) {
        String fields = slots == null ? "" : "<operaton:field name=\"slots\"><operaton:string>" + slots
                + "</operaton:string></operaton:field>";
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="http://sangam.org/test">
                  <process id="%1$s" isExecutable="true" operaton:historyTimeToLive="30">
                    <startEvent id="s"/>
                    <userTask id="prepare" operaton:candidateGroups="DA">
                      <extensionElements>
                        <operaton:taskListener event="complete" class="org.sangam.packs.core.DocumentSlotReadyListener">%2$s</operaton:taskListener>
                      </extensionElements>
                    </userTask>
                    <endEvent id="e"/>
                    <sequenceFlow id="f1" sourceRef="s" targetRef="prepare"/>
                    <sequenceFlow id="f2" sourceRef="prepare" targetRef="e"/>
                  </process>
                </definitions>
                """.formatted(id, fields);
    }

    @BeforeAll
    static void setUp() {
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-slot-ready;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        PackTestEngines.configure(cfg);
        engine = cfg.buildProcessEngine();
        engine.getRepositoryService().createDeployment().tenantId(TENANT).name("slot-ready")
                .addString("two.bpmn", bpmn("slot-ready", "notingDocumentId:NOTING,sanctionDocumentId:SANCTION_NOTE"))
                .addString("none.bpmn", bpmn("slot-ready-none", null))
                .addString("bad.bpmn", bpmn("slot-ready-bad", "notingDocumentId"))
                .deploy();
    }

    @AfterAll
    static void tearDown() {
        engine.close();
        // R8-heap2: a test class stays loaded for the whole run; its statics must not keep a closed engine
        engine = null;
        PackRuntime.reset();
    }

    @AfterEach
    void reset() {
        PackRuntime.reset();
    }

    /** Binds only the documents in {@code ready}, recording every question; a null set makes the check fail. */
    void bindings(Set<String> ready) {
        PackRuntime.reset();
        PackRuntime.install(PackRuntime.ports().withAudit(audit::add).withSlotBindings(new DocumentSlotBindings() {
            @Override
            public boolean isBound(String tenant, List<String> chain, String slot, String type, String doc) {
                asked.add(tenant + "|" + chain + "|" + slot + "|" + type + "|" + doc);
                if (ready == null) {
                    throw new IllegalStateException("audit store down");
                }
                return ready.contains(doc);
            }

            @Override
            public void consume(String tenant, List<String> chain, String slot, String type, String doc, String t) {
                throw new AssertionError("a prepare task never consumes");
            }
        }));
    }

    static Task start(String key, String noting, String sanction) {
        Map<String, Object> v = new HashMap<>();
        if (noting != null) {
            v.put("notingDocumentId", noting);
        }
        if (sanction != null) {
            v.put("sanctionDocumentId", sanction);
        }
        String pi = engine.getRuntimeService().createProcessInstanceByKey(key).processDefinitionTenantId(TENANT)
                .setVariables(v).execute().getId();
        return engine.getTaskService().createTaskQuery().processInstanceId(pi).singleResult();
    }

    static boolean open(Task t) {
        return engine.getTaskService().createTaskQuery().taskId(t.getId()).count() == 1;
    }

    void assertRefused(Task t, String detail) {
        ProcessEngineException e = assertThrows(ProcessEngineException.class,
                () -> engine.getTaskService().complete(t.getId()));
        // the engine wraps a listener's exception (ENGINE-03051); the REST layer reads the refusal from its message
        assertTrue(e.getMessage().contains(DocumentSlotReadyListener.REFUSAL + ": "), e.getMessage());
        assertTrue(e.getMessage().contains(detail), e.getMessage());
        assertTrue(open(t), "the prepare task stays open");
    }

    @Test
    void slotsBoundAndNotConsumedLetThePrepareTaskComplete() {
        bindings(Set.of("n-1", "s-1"));
        Task t = start("slot-ready", "n-1", "s-1");
        engine.getTaskService().complete(t.getId());
        assertFalse(open(t));
        assertEquals(List.of(TENANT + "|" + List.of(t.getProcessInstanceId()) + "|notingDocumentId|NOTING|n-1",
                TENANT + "|" + List.of(t.getProcessInstanceId()) + "|sanctionDocumentId|SANCTION_NOTE|s-1"), asked,
                "each pair asked with this task's instance first, its slot, its type and the slot's document");
    }

    @Test
    void aConsumedOrUnboundDocumentIsRefusedAndAudited() {
        // the port answers false for a document a gate instance already signed, as for one the route never bound
        bindings(Set.of("n-1"));
        Task t = start("slot-ready", "n-1", "s-old");
        assertRefused(t, "the SANCTION_NOTE document in sanctionDocumentId was already signed");
        assertTrue(audit.stream().anyMatch(e -> "DOCUMENT_SLOT_NOT_READY".equals(e.type())
                && "sanctionDocumentId".equals(e.details().get("slot"))), "refusal audited");
    }

    @Test
    void anEmptySlotIsRefused() {
        bindings(Set.of("n-1"));
        assertRefused(start("slot-ready", "n-1", null), "no SANCTION_NOTE document in sanctionDocumentId");
    }

    @Test
    void aFailingCheckIsARefusal() {
        bindings(null);
        assertRefused(start("slot-ready", "n-1", "s-1"), "was already signed");
    }

    @Test
    void aMissingOrMalformedPairListRefuses() {
        bindings(Set.of("n-1"));
        assertRefused(start("slot-ready-none", "n-1", null), "names no document slot");
        assertRefused(start("slot-ready-bad", "n-1", null), "malformed slot pair 'notingDocumentId'");
    }
}

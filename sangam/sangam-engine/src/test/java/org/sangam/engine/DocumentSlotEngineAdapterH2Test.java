// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.task.Task;
import org.sangam.core.engine.DocumentSlotNames;

/**
 * Owner decision 387 (1): the engine adapter is the one port every host and module writes through (REST work and task
 * adapters, the connector worker, the Quarkus host); it refuses a declared DSC document slot on a start, a task or work
 * completion and a message correlation before the engine writes anything, so only the document route sets a slot.
 */
class DocumentSlotEngineAdapterH2Test {

    static final String SLOT = "sanctionDocumentId";

    private static byte[] model() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="t">
                  <message id="m" name="slotMsg"/>
                  <process id="slots" isExecutable="true">
                    <startEvent id="s"/>
                    <sequenceFlow id="f1" sourceRef="s" targetRef="t1"/>
                    <userTask id="t1"/>
                    <sequenceFlow id="f2" sourceRef="t1" targetRef="w"/>
                    <intermediateCatchEvent id="w"><messageEventDefinition messageRef="m"/></intermediateCatchEvent>
                    <sequenceFlow id="f3" sourceRef="w" targetRef="e"/>
                    <endEvent id="e"/>
                  </process>
                </definitions>""";
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    private static void withEngine(Consumer<OperatonEngineAdapter> test) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:d" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope("system:platform");
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true");
        try (OperatonEngineAdapter a = SangamEngineFactory.build(ds, s, "h2")) {
            a.deploy("t1", "slots", Map.of("slots.bpmn", model()));
            DocumentSlotNames.install(() -> Set.of(SLOT));
            test.accept(a);
        }
    }

    @AfterEach
    void reset() {
        DocumentSlotNames.reset();
    }

    private static long instances(OperatonEngineAdapter a) {
        return a.processEngine().getRuntimeService().createProcessInstanceQuery().count();
    }

    @Test
    void aStartNamingASlotIsRefusedAndStartsNothing() {
        withEngine(a -> {
            DocumentSlotNames.Refused e = assertThrows(DocumentSlotNames.Refused.class,
                    () -> a.startProcess("t1", "slots", "BK-1", Map.of(SLOT, "forged", "amount", 5)));
            assertEquals(Set.of(SLOT), e.variables());
            assertEquals(0, instances(a), "nothing was started");
            assertTrue(a.startProcess("t1", "slots", "BK-2", Map.of("amount", 5)) != null, "other names pass");
        });
    }

    @Test
    void aTaskCompletionNamingASlotIsRefusedAndTheTaskStays() {
        withEngine(a -> {
            String pid = a.startProcess("t1", "slots", "BK-1", Map.of());
            Task t = a.processEngine().getTaskService().createTaskQuery().processInstanceId(pid).singleResult();
            assertThrows(DocumentSlotNames.Refused.class, () -> a.completeTask(t.getId(), Map.of(SLOT, "forged")));
            assertEquals(1, a.processEngine().getTaskService().createTaskQuery().taskId(t.getId()).count());
            assertEquals(null, a.processEngine().getRuntimeService().getVariable(pid, SLOT));
            a.completeTask(t.getId(), Map.of("decision", "ok"));
            assertEquals(0, a.processEngine().getTaskService().createTaskQuery().taskId(t.getId()).count());
        });
    }

    @Test
    void aCorrelationNamingASlotIsRefusedAndTheInstanceStillWaits() {
        withEngine(a -> {
            String pid = a.startProcess("t1", "slots", "BK-1", Map.of());
            Task t = a.processEngine().getTaskService().createTaskQuery().processInstanceId(pid).singleResult();
            a.completeTask(t.getId(), Map.of());
            assertThrows(DocumentSlotNames.Refused.class,
                    () -> a.correlateMessage("t1", "slotMsg", "BK-1", Map.of(SLOT, "forged")));
            assertEquals(1, instances(a), "still waiting for the message");
            assertEquals(null, a.processEngine().getRuntimeService().getVariable(pid, SLOT));
            a.correlateMessage("t1", "slotMsg", "BK-1", Map.of("reply", "ok"));
            assertEquals(0, instances(a), "the message with other names went through");
        });
    }
}

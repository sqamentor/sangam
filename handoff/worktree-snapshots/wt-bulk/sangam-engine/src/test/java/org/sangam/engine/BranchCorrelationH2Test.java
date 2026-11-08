// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.MismatchingMessageCorrelationException;

/**
 * Regression test for D-35 {@code correlateMessageToBranch} found by the gov-c acceptance ports (E16-c GP-46, GP-49,
 * E17-c D-LAND-ACQUISITION): in a parallel multi-instance <b>sub-process</b> the branch variable {@code loopCounter} is
 * local to the branch's scope execution, while the message wait sits on an execution nested inside that scope. Before
 * the fix the call matched no execution ({@code MismatchingMessageCorrelationException}).
 */
class BranchCorrelationH2Test {

    private static final String HEAD = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" targetNamespace="t">
              <message id="m" name="branchMsg"/>
            """;

    /** Parallel MI sub-process (3 branches); inside: one message catch, or (ambiguous) two parallel catches. */
    private static byte[] model(String id, boolean twoWaits) {
        String inner = twoWaits
                ? """
                  <startEvent id="is"/>
                  <sequenceFlow id="i1" sourceRef="is" targetRef="fork"/>
                  <parallelGateway id="fork"/>
                  <sequenceFlow id="i2" sourceRef="fork" targetRef="w1"/>
                  <sequenceFlow id="i3" sourceRef="fork" targetRef="w2"/>
                  <intermediateCatchEvent id="w1"><messageEventDefinition messageRef="m"/></intermediateCatchEvent>
                  <intermediateCatchEvent id="w2"><messageEventDefinition messageRef="m"/></intermediateCatchEvent>
                  <sequenceFlow id="i4" sourceRef="w1" targetRef="ie1"/>
                  <sequenceFlow id="i5" sourceRef="w2" targetRef="ie2"/>
                  <endEvent id="ie1"/><endEvent id="ie2"/>
                  """
                : """
                  <startEvent id="is"/>
                  <sequenceFlow id="i1" sourceRef="is" targetRef="w"/>
                  <intermediateCatchEvent id="w"><messageEventDefinition messageRef="m"/></intermediateCatchEvent>
                  <sequenceFlow id="i2" sourceRef="w" targetRef="ie"/>
                  <endEvent id="ie"/>
                  """;
        String xml = HEAD + "<process id=\"" + id + "\" isExecutable=\"true\">"
                + "<startEvent id=\"s\"/><sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"sub\"/>"
                + "<subProcess id=\"sub\"><multiInstanceLoopCharacteristics isSequential=\"false\">"
                + "<loopCardinality xsi:type=\"tFormalExpression\">3</loopCardinality></multiInstanceLoopCharacteristics>"
                + inner + "</subProcess>"
                + "<sequenceFlow id=\"f2\" sourceRef=\"sub\" targetRef=\"after\"/><userTask id=\"after\"/>"
                + "<sequenceFlow id=\"f3\" sourceRef=\"after\" targetRef=\"e\"/><endEvent id=\"e\"/></process></definitions>";
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    private static void withEngine(Consumer<OperatonEngineAdapter> test) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:b" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamEngineSettings s = new SangamEngineSettings();
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true");
        try (OperatonEngineAdapter a = SangamEngineFactory.build(ds, s, "h2")) {
            test.accept(a);
        }
    }

    private static long waiting(OperatonEngineAdapter a, String pid) {
        return a.processEngine().getRuntimeService().createExecutionQuery().processInstanceId(pid)
                .messageEventSubscriptionName("branchMsg").count();
    }

    @Test
    void deliversToTheWaitBelowTheBranchScope() {
        withEngine(a -> {
            a.deploy("t1", "one", Map.of("one.bpmn", model("one", false)));
            String pid = a.startProcess("t1", "one", "BK-1", Map.of());
            assertEquals(3, waiting(a, pid));
            String got = a.correlateMessageToBranch("t1", "branchMsg", pid, "loopCounter", 1, Map.of("answer", "x"));
            assertTrue(got != null, "an execution received the message");
            assertEquals(2, waiting(a, pid), "exactly the addressed branch moved on");
            // branch 1 is done; a second delivery to it finds nothing
            assertThrows(MismatchingMessageCorrelationException.class,
                    () -> a.correlateMessageToBranch("t1", "branchMsg", pid, "loopCounter", 1, Map.of()));
            a.correlateMessageToBranch("t1", "branchMsg", pid, "loopCounter", 0, Map.of());
            a.correlateMessageToBranch("t1", "branchMsg", pid, "loopCounter", 2, Map.of());
            assertEquals(0, waiting(a, pid));
            assertEquals(1, a.processEngine().getTaskService().createTaskQuery().processInstanceId(pid)
                    .taskDefinitionKey("after").count(), "all branches done, flow continues");
        });
    }

    @Test
    void twoWaitsBelowOneBranchAreAnExplicitError() {
        withEngine(a -> {
            a.deploy("t1", "two", Map.of("two.bpmn", model("two", true)));
            String pid = a.startProcess("t1", "two", "BK-2", Map.of());
            assertEquals(6, waiting(a, pid));
            MismatchingMessageCorrelationException e = assertThrows(MismatchingMessageCorrelationException.class,
                    () -> a.correlateMessageToBranch("t1", "branchMsg", pid, "loopCounter", 0, Map.of()));
            assertTrue(e.getMessage().contains("more than one execution"), e.getMessage());
            assertEquals(6, waiting(a, pid), "nothing was delivered");
        });
    }

    @Test
    void wrongTenantIsRefused() {
        withEngine(a -> {
            a.deploy("t1", "one", Map.of("one.bpmn", model("one", false)));
            String pid = a.startProcess("t1", "one", "BK-3", Map.of());
            assertThrows(MismatchingMessageCorrelationException.class,
                    () -> a.correlateMessageToBranch("t2", "branchMsg", pid, "loopCounter", 0, Map.of()));
            assertEquals(3, waiting(a, pid), "nothing was delivered");
        });
    }
}

// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.restadapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.delegate.DelegateTask;
import org.operaton.bpm.engine.delegate.TaskListener;
import org.sangam.rest.adapter.jdbc.JdbcAuditAdapter;
import org.sangam.rest.adapter.operaton.OperatonTaskAdapter;
import org.sangam.rest.adapter.operaton.TaskIndex;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.TaskPort;
import org.sangam.rest.support.RestProperties;
import org.sangam.server.config.TaskIndexWiring;

/**
 * M-042 (decision 82) on the real engine (H2): a per-process rule copies amount, currency and reference into typed
 * task-local variables at task creation; GET /tasks sorts by them through Operaton's typed variable ordering inside the
 * visibility-filtered task query, returns them on each row, and refuses an amount sort without a single-currency
 * filter. The listener here stands in for the OpsWiring global listener and calls the same recorder.
 */
class TaskIndexSortH2Test {

    static final String T = "t1";
    static final String RULES = """
            [{"id":"bill-index","events":["create"],"processKey":"bill.*","handler":"sangamTaskIndex",
              "taskIndex":{"amount":"billAmountMinor","currency":"billCurrency","reference":"billNo"}}]""";
    static final String BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="m042">
              <process id="billIndex" isExecutable="true" operaton:historyTimeToLive="1">
                <startEvent id="s"/>
                <sequenceFlow id="f1" sourceRef="s" targetRef="review"/>
                <userTask id="review" operaton:candidateGroups="clerks">
                  <extensionElements>
                    <operaton:taskListener event="create"
                        class="org.sangam.server.restadapter.TaskIndexSortH2Test$IndexListener"/>
                  </extensionElements>
                </userTask>
                <sequenceFlow id="f2" sourceRef="review" targetRef="e"/>
                <endEvent id="e"/>
              </process>
            </definitions>
            """;
    static final RequestContext CTX = new RequestContext(T, "anil", Set.of("clerks"), null, null, null, null, null,
            "OIDC");

    /** Calls the recorder the {@code sangamTaskIndex} handler bean wraps, for rule {@code bill-index}. */
    public static class IndexListener implements TaskListener {
        static TaskIndex.Recorder recorder;

        @Override
        public void notify(DelegateTask task) {
            recorder.record(task, "bill-index");
        }
    }

    static RestEngineFixture fx;
    static OperatonTaskAdapter tasks;

    @BeforeAll
    static void setUp() {
        IndexListener.recorder = TaskIndexWiring.recorder(RULES);
        fx = new RestEngineFixture("m042");
        fx.engine.getRepositoryService().createDeployment().tenantId(T).addString("bill.bpmn", BPMN).deploy();
        start(500L, "INR", "B-3");
        start(120, "inr", "B-1");                 // Integer minor units; the currency is normalised
        start(300L, "USD", "B-2");
        start(null, null, "B-0");                 // reference only
        RestProperties props = new RestProperties();
        JdbcAuditAdapter audit = new JdbcAuditAdapter(fx.tx, fx.engine.getHistoryService(),
                fx.engine.getRuntimeService(), props);
        tasks = new OperatonTaskAdapter(fx.engine.getTaskService(), fx.engine.getRuntimeService(),
                fx.engine.getHistoryService(), fx.engine.getFormService(), fx.tx, audit, props);
    }

    static void start(Object amount, String currency, String reference) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("billAmountMinor", amount);
        vars.put("billCurrency", currency);
        vars.put("billNo", reference);
        fx.engine.getRuntimeService().createProcessInstanceByKey("billIndex").processDefinitionTenantId(T)
                .setVariables(vars).execute();
    }

    @AfterAll
    static void tearDown() {
        fx.close();
        // R9-statics: a test class stays loaded for the whole run; its statics must not pin what they held
        IndexListener.recorder = null;
        fx = null;
        tasks = null;
    }

    static List<TaskPort.Task> list(String sortBy, String order, String currency) {
        return tasks.list(CTX, new TaskPort.TaskFilter(null, null, null, null, null, null, null, null, "billIndex",
                null, null, sortBy, order, List.of(), currency), new Page.PageRequest(0, 50)).items();
    }

    @Test
    void anAmountSortIsWithinOneCurrencyAndCarriesTheIndexValues() {
        List<TaskPort.Task> asc = list("amount", "asc", "INR");
        // decision 211: MoneyAmount text in major units (INR has 2 fraction digits), sorted on the stored minor units
        assertEquals(List.of("1.20", "5.00"), asc.stream().map(TaskPort.Task::amount).toList());
        assertEquals(List.of("B-1", "B-3"), asc.stream().map(TaskPort.Task::reference).toList());
        assertTrue(asc.stream().allMatch(t -> "INR".equals(t.currency())));
        assertEquals(List.of("5.00", "1.20"), list("amount", "desc", "INR").stream().map(TaskPort.Task::amount).toList());
        assertEquals(List.of("3.00"), list("amount", "asc", "USD").stream().map(TaskPort.Task::amount).toList());
        assertEquals(List.of("1.20", "5.00"), list("amount,created", "asc,desc", "INR").stream()
                .map(TaskPort.Task::amount).toList(), "multi-key sort keeps amount first");
    }

    @Test
    void anAmountSortWithoutOneCurrencyIsRefused() {
        assertEquals("VALIDATION_FAILED", assertThrows(ApiProblem.class, () -> list("amount", "asc", null)).code());
        assertEquals("VALIDATION_FAILED", assertThrows(ApiProblem.class, () -> list("created,amount", null, null))
                .code());
        assertEquals("VALIDATION_FAILED", assertThrows(ApiProblem.class, () -> list("amount", "asc", "inr")).code(),
                "the filter takes the ISO code as is");
    }

    @Test
    void aReferenceSortOrdersTheIndexedReferences() {
        List<String> refs = list("reference", "asc", null).stream().map(TaskPort.Task::reference).toList();
        assertEquals(4, refs.size(), "every task, with or without an amount");
        assertEquals(List.of("B-0", "B-1", "B-2", "B-3"), refs);
        assertEquals(List.of("B-3", "B-2", "B-1", "B-0"),
                list("reference", "desc", null).stream().map(TaskPort.Task::reference).toList());
        TaskPort.Task onlyReference = list("reference", "asc", null).get(0);
        assertNull(onlyReference.amount());
        assertNull(onlyReference.currency());
    }

    @Test
    void theIndexIsTypedAndPlatformManaged() {
        String id = list("amount", "asc", "INR").get(0).id();
        Object amount = fx.engine.getTaskService().getVariableLocal(id, TaskIndex.AMOUNT);
        assertEquals(Long.class, amount.getClass(), "a LONG task variable, as orderByTaskVariable(…, LONG) expects");
        assertEquals("INR", fx.engine.getTaskService().getVariableLocal(id, TaskIndex.CURRENCY));
    }

    @Test
    void aNonIntegralAmountFailsTheTaskCreation() {
        assertThrows(RuntimeException.class, () -> start("1.5", "INR", "B-9"), "a decimal string is not minor units");
        assertThrows(RuntimeException.class, () -> start(100L, null, "B-8"), "an amount needs its currency");
        assertTrue(list("reference", "asc", null).stream().noneMatch(t -> "B-9".equals(t.reference())
                || "B-8".equals(t.reference())));
    }
}

// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.engine.runtime.ProcessInstance;

import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.port.VerifiedPayees;

/**
 * Item 10 (owner E1, E3, M2, M4): the payroll pays every employee separately, under one sanction of the sealed salary
 * register. {@link BatchItemsDelegate} builds one item per employee: net pay = gross - PF employee - ESI employee -
 * other deductions (exact, whole minor units), paid to the verified registry account of the employee; anything
 * unknown is held. The shipped payroll seals the items before its sanction and fans out one child payment per
 * employee, which is released only under that sanction.
 */
class SalaryBatchTest {

    static ProcessEngine engine;
    private static final String TENANT = "t-salary";
    private static final Path PACK = Path.of("src/main/resources/packs/sec-hr-payroll");

    @BeforeAll
    static void setUp() {
        PackRuntime.reset();
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-salary-batch;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        cfg.setHistory("full");
        org.sangam.packs.PackTestEngines.configure(cfg);
        engine = cfg.buildProcessEngine();
        String f = "<operaton:field name=\"%s\"><operaton:string>%s</operaton:string></operaton:field>";
        String p = "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                + " xmlns:operaton=\"http://operaton.org/schema/1.0/bpmn\" targetNamespace=\"t\">"
                + "<process id=\"items\" isExecutable=\"true\" operaton:historyTimeToLive=\"30\"><startEvent id=\"s\"/>"
                + "<serviceTask id=\"b\" operaton:class=\"" + BatchItemsDelegate.class.getName() + "\">"
                + "<extensionElements>" + String.format(f, "source", "register") + String.format(f, "keyField",
                "employeeId") + String.format(f, "amount", "grossWages - pfEmployee - esiEmployee - otherDeductions")
                + String.format(f, "payeeKey", "employeeId") + String.format(f, "itemsVar", "salaryItems")
                + "</extensionElements></serviceTask><userTask id=\"w\"/><endEvent id=\"e\"/>"
                + "<sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"b\"/><sequenceFlow id=\"f2\" sourceRef=\"b\""
                + " targetRef=\"w\"/><sequenceFlow id=\"f3\" sourceRef=\"w\" targetRef=\"e\"/></process></definitions>";
        engine.getRepositoryService().createDeployment().tenantId(TENANT).addString("items.bpmn", p).deploy();
    }

    @AfterEach
    void restore() {
        VerifiedPayees.install(null);
    }

    @AfterAll
    static void tearDown() {
        engine.close();
        engine = null;
        PackRuntime.reset();
    }

    private static Map<String, Object> emp(String id, long gross, long pf, long esi, Long other) {
        Map<String, Object> m = new HashMap<>();
        m.put("employeeId", id);
        m.put("grossWages", gross);
        m.put("pfEmployee", pf);
        m.put("esiEmployee", esi);
        if (other != null) {
            m.put("otherDeductions", other);
        }
        return m;
    }

    private static ProcessInstance run(List<Map<String, Object>> register) {
        Map<String, Object> v = new HashMap<>();
        if (register != null) {
            v.put("register", Vars.toJson(register));
        }
        return engine.getRuntimeService().createProcessInstanceByKey("items").processDefinitionTenantId(TENANT)
                .setVariables(v).execute();
    }

    private static String held(Runnable r) {
        String m = assertThrows(ProcessEngineException.class, r::run).getMessage();
        assertTrue(m.startsWith(MoneyOutApproval.HELD), m);
        return m;
    }

    @Test
    void oneItemPerEmployeeWithNetPayToTheRegistryAccount() throws IOException {
        // E001 1400050 - 150000 - 9400 - 0 = 1240650; E002 3200000 - 300000 - 22500 - 50000 = 2827500
        ProcessInstance pi = run(List.of(emp("E001", 1400050, 150000, 9400, 0L),
                emp("E002", 3200000, 300000, 22500, 50000L)));
        Object raw = engine.getRuntimeService().getVariable(pi.getId(), "salaryItems");
        String json = raw instanceof byte[] b ? new String(b, StandardCharsets.UTF_8) : String.valueOf(raw);
        List<Map<String, Object>> items = new com.fasterxml.jackson.databind.ObjectMapper().readValue(json,
                new com.fasterxml.jackson.core.type.TypeReference<>() { });
        assertEquals(2, items.size());
        assertEquals("E001", items.get(0).get("key"));
        assertEquals(1240650L, ((Number) items.get(0).get("amount")).longValue());
        assertEquals(2827500L, ((Number) items.get(1).get("amount")).longValue());
        // the registry's verified account (simulator: 000123456789@SIMB0000001), never a typed one
        assertEquals("000123456789@SIMB0000001", items.get(0).get("payeeAccount"));
        assertEquals("SIM-PV-E002", items.get(1).get("payeeVersionId"));
    }

    @Test
    void unknownAmountsAndPayeesAreHeld() {
        assertTrue(held(() -> run(null)).contains("absent or empty"));
        assertTrue(held(() -> run(List.of())).contains("absent or empty"));
        assertTrue(held(() -> run(List.of(emp("E001", 1000, 100, 10, null)))).contains("otherDeductions"),
                "no other-deductions figure: held, never taken as zero");
        assertTrue(held(() -> run(List.of(emp("E001", 1000, 600, 400, 0L)))).contains("not positive"));
        assertTrue(held(() -> run(List.of(emp("E001", 1000, 1, 1, 0L), emp("E001", 2000, 1, 1, 0L))))
                .contains("twice"));
        VerifiedPayees.install(VerifiedPayees.NONE);
        assertTrue(held(() -> run(List.of(emp("E001", 1000, 1, 1, 0L)))).contains("not verified"));
    }

    @Test
    void theShippedPayrollSealsAndPaysEachEmployeeUnderTheBatchSanction() throws IOException {
        String parent = Files.readString(PACK.resolve("sangam-sec-hr-payroll.bpmn"), StandardCharsets.UTF_8);
        int items = parent.indexOf("id=\"pr_salaryItems\"");
        int seal = parent.indexOf("id=\"pr_seal\"");
        assertTrue(items > 0 && seal > 0, "salary items and their seal");
        assertTrue(parent.contains("sourceRef=\"pr_make\" targetRef=\"pr_salaryItems\"")
                && parent.contains("sourceRef=\"pr_salaryItems\" targetRef=\"pr_seal\"")
                && parent.contains("sourceRef=\"pr_seal\" targetRef=\"pr_approve\""),
                "maker -> items -> seal -> batch sanction");
        assertTrue(parent.contains("targetRef=\"pr_open\"><conditionExpression xsi:type=\"tFormalExpression\">"
                + "${sanctionOutcome == 'SANCTIONED'}"), "children start only when the batch is sanctioned");
        assertTrue(parent.contains("<operaton:string>sangam-sec-hr-salary-payment</operaton:string>"));
        assertTrue(!parent.contains("id=\"pr_pay\""), "no single payment of the payroll total");
        assertTrue(parent.contains("grossWages - pfEmployee - esiEmployee - otherDeductions"));
        String child = Files.readString(PACK.resolve("sangam-sec-hr-salary-payment.bpmn"), StandardCharsets.UTF_8);
        assertTrue(child.contains("sourceRef=\"sp_release\" targetRef=\"sp_pay\""),
                "each child payment is released under the batch sanction first");
        assertTrue(child.contains(BatchItemReleaseDelegate.class.getName()));
        assertTrue(Files.readString(PACK.resolve("pack.json")).contains("sangam-sec-hr-salary-payment.bpmn"));
    }
}

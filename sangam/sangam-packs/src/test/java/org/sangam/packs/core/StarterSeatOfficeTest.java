// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.engine.task.IdentityLink;

import org.sangam.packs.port.PackAuditEvent;
import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.port.SeatHolding;
import org.sangam.packs.sim.InMemorySeatDirectory;
import org.sangam.packs.sim.LoggingAuditSink;

/**
 * SANGAM.md IX.5 rule 7 (owner decision 2026-11-21, P-6 Step 2, "Starter seat's office"): a case started without an
 * {@code officeId} takes the office of the seat that starts it ({@code actingSeat} / {@code initiatorSeat}, which the
 * REST layer sets from {@code X-Sangam-Seat}), audited as {@code CASE_OFFICE_ASSIGNED}. A supplied office is kept.
 * Without either, nothing is invented and the first seat step is still refused with {@code 422 SEAT_UNRESOLVED}.
 */
class StarterSeatOfficeTest {

    static final String T = "t-starter-office";
    static ProcessEngine engine;
    static int seq;

    static final String BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="starteroffice">
              <process id="starterOffice" isExecutable="true" operaton:historyTimeToLive="1">
                <extensionElements>
                  <operaton:executionListener event="start" class="org.sangam.packs.core.PackStartListener">
                    <operaton:field name="packId"><operaton:string>test-pack</operaton:string></operaton:field>
                    <operaton:field name="packVersion"><operaton:string>1.0.0</operaton:string></operaton:field>
                  </operaton:executionListener>
                </extensionElements>
                <startEvent id="s"/>
                <sequenceFlow id="f1" sourceRef="s" targetRef="resolve"/>
                <serviceTask id="resolve" operaton:class="org.sangam.packs.core.SeatResolutionDelegate">
                  <extensionElements>
                    <operaton:field name="op"><operaton:string>ROLE</operaton:string></operaton:field>
                    <operaton:field name="role"><operaton:string>SO</operaton:string></operaton:field>
                    <operaton:field name="targetVar"><operaton:string>seat</operaton:string></operaton:field>
                  </extensionElements>
                </serviceTask>
                <sequenceFlow id="f2" sourceRef="resolve" targetRef="act"/>
                <userTask id="act" operaton:candidateGroups="${seat}"/>
                <sequenceFlow id="f3" sourceRef="act" targetRef="e"/>
                <endEvent id="e"/>
              </process>
            </definitions>
            """;

    @BeforeAll
    static void setUp() {
        PackRuntime.reset();
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-starter-office;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        cfg.setHistory("full");
        org.sangam.packs.PackTestEngines.configure(cfg);
        engine = cfg.buildProcessEngine();
        engine.getRepositoryService().createDeployment().tenantId(T).addString("starter.bpmn", BPMN).deploy();
    }

    @BeforeEach
    void seats() {
        PackRuntime.reset();   // a fresh audit sink and directory per test
        InMemorySeatDirectory dir = (InMemorySeatDirectory) PackRuntime.seats();
        dir.addOffice(T, "DEPT", null);
        dir.addOffice(T, "OFFICE-A", "DEPT");
        dir.addOffice(T, "SECTION-A1", "OFFICE-A");
        dir.addOffice(T, "OFFICE-B", "DEPT");
        dir.addSeat(T, "SO-A", "SO", "OFFICE-A", null);
        dir.addSeat(T, "SO-B", "SO", "OFFICE-B", null);
        dir.addSeat(T, "CLERK-A1", "CLERK", "SECTION-A1", "SO-A");
        dir.addSeat(T, "CLERK-B", "CLERK", "OFFICE-B", "SO-B");
        LocalDate from = LocalDate.of(2026, 1, 1);
        for (String s : List.of("SO-A", "SO-B", "CLERK-A1", "CLERK-B")) {
            dir.addHolding(T, new SeatHolding(s, s.toLowerCase(), SeatHolding.Basis.SUBSTANTIVE, null, from, null));
        }
    }

    @AfterAll
    static void tearDown() {
        engine.close();
        engine = null;
        PackRuntime.reset();
    }

    record Started(String pi, String bk, List<String> groups) {
        Object var(String name) {
            return engine.getRuntimeService().getVariable(pi, name);
        }

        List<PackAuditEvent> assigned() {
            return ((LoggingAuditSink) PackRuntime.audit()).timeline(bk).stream()
                    .filter(e -> e.type().equals("CASE_OFFICE_ASSIGNED")).toList();
        }
    }

    static Started start(Map<String, Object> vars) {
        String bk = "STARTER-" + (++seq);
        String pi = engine.getRuntimeService().createProcessInstanceByKey("starterOffice").businessKey(bk)
                .processDefinitionTenantId(T).setVariables(new HashMap<>(vars)).execute().getId();
        String task = engine.getTaskService().createTaskQuery().processInstanceId(pi).singleResult().getId();
        List<String> groups = engine.getTaskService().getIdentityLinksForTask(task).stream()
                .map(IdentityLink::getGroupId).filter(java.util.Objects::nonNull).toList();
        return new Started(pi, bk, groups);
    }

    static void refused(Map<String, Object> vars) {
        long before = engine.getRuntimeService().createProcessInstanceQuery().processDefinitionKey("starterOffice").count();
        ProcessEngineException e = assertThrows(ProcessEngineException.class, () -> start(vars));
        assertTrue(e.getMessage().contains("422 SEAT_UNRESOLVED"), e.getMessage());
        assertEquals(before, engine.getRuntimeService().createProcessInstanceQuery().processDefinitionKey("starterOffice")
                .count(), "nothing started");
    }

    @Test
    void aCaseWithoutAnOfficeTakesTheOfficeOfTheStartingSeatAndIsAudited() {
        // the REST start sets actingSeat and initiatorSeat from X-Sangam-Seat (OperatonProcessAdapter.start)
        Started s = start(Map.of("actingSeat", "CLERK-B", "initiatorSeat", "CLERK-B"));
        assertEquals("OFFICE-B", s.var("officeId"));
        assertEquals(List.of("SO-B"), s.groups());
        assertEquals(1, s.assigned().size(), s.assigned().toString());
        Map<String, Object> d = s.assigned().get(0).details();
        assertEquals("OFFICE-B", d.get("officeId"));
        assertEquals("CLERK-B", d.get("seat"));
        assertEquals("starter seat", d.get("basis"));
    }

    @Test
    void aSectionStarterIsServedByTheNearestAncestorSeat() {
        // a start form or catalogue start carries only the starter's seat
        Started s = start(Map.of("actingSeat", "CLERK-A1"));
        assertEquals("SECTION-A1", s.var("officeId"));
        assertEquals(List.of("SO-A"), s.groups());
    }

    @Test
    void aSuppliedOfficeIsKeptAndNotReassigned() {
        Started s = start(Map.of("actingSeat", "CLERK-B", "officeId", "OFFICE-A"));
        assertEquals("OFFICE-A", s.var("officeId"));
        assertEquals(List.of("SO-A"), s.groups());
        assertEquals(List.of(), s.assigned());
    }

    @Test
    void aBlankOfficeCountsAsNone() {
        Started s = start(Map.of("actingSeat", "CLERK-B", "officeId", " "));
        assertEquals("OFFICE-B", s.var("officeId"));
        assertEquals(List.of("SO-B"), s.groups());
    }

    @Test
    void neitherAnOfficeNorAStarterSeatWithAnOfficeIsStillRefused() {
        refused(Map.of());                                  // a citizen start: no seat at all
        refused(Map.of("actingSeat", "NO-SUCH-SEAT"));      // a seat the directory does not know has no office
        refused(Map.of("officeId", "", "actingSeat", ""));
    }
}

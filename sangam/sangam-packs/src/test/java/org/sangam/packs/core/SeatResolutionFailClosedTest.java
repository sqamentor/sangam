// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.engine.task.IdentityLink;

import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.port.SeatHolding;
import org.sangam.packs.sim.InMemorySeatDirectory;

/**
 * P-6 finding 4 (IX.5 rules 1 and 2, J-P1-E07): a step is routed to a seat (designation + office), never to a role
 * code. {@link SeatResolutionDelegate} resolves the seat of the role that serves the case office (nearest ancestor);
 * with none it takes the pack's {@code fallbackSeat}; with none of either it refuses the step with
 * {@code 422 SEAT_UNRESOLVED}, so nothing is ever offered to every holder of the role. {@link InMemorySeatDirectory}
 * never matches a seat of another office: no office, or no seat up to the root, is no seat.
 */
class SeatResolutionFailClosedTest {

    static final String T = "t-office-fc";
    static ProcessEngine engine;
    static InMemorySeatDirectory dir;

    static final String BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="officefc">
              <process id="%s" isExecutable="true" operaton:historyTimeToLive="1">
                <startEvent id="s"/>
                <sequenceFlow id="f1" sourceRef="s" targetRef="resolve"/>
                <serviceTask id="resolve" operaton:class="org.sangam.packs.core.SeatResolutionDelegate">
                  <extensionElements>
                    <operaton:field name="op"><operaton:string>%s</operaton:string></operaton:field>
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
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-office-fc;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        cfg.setHistory("full");
        engine = cfg.buildProcessEngine();
        engine.getRepositoryService().createDeployment().tenantId(T)
                .addString("role.bpmn", BPMN.formatted("fcRole", "ROLE"))
                .addString("candidates.bpmn", BPMN.formatted("fcCandidates", "CANDIDATES"))
                .addString("roles.bpmn", BPMN.formatted("fcRoles", "ROLES")).deploy();
        dir = (InMemorySeatDirectory) PackRuntime.seats();
        // DEPT > OFFICE-A > SECTION-A1; DEPT > OFFICE-B; OFFICE-EMPTY (own root, no SO seat anywhere above it)
        dir.addOffice(T, "DEPT", null);
        dir.addOffice(T, "OFFICE-A", "DEPT");
        dir.addOffice(T, "SECTION-A1", "OFFICE-A");
        dir.addOffice(T, "OFFICE-B", "DEPT");
        dir.addOffice(T, "OFFICE-EMPTY", null);
        dir.addSeat(T, "SO-A", "SO", "OFFICE-A", null);
        dir.addSeat(T, "SO-B", "SO", "OFFICE-B", null);
        dir.addSeat(T, "DESK", "DESK", "DEPT", null);
        LocalDate from = LocalDate.of(2026, 1, 1);
        dir.addHolding(T, new SeatHolding("SO-A", "so.a", SeatHolding.Basis.SUBSTANTIVE, null, from, null));
        dir.addHolding(T, new SeatHolding("SO-B", "so.b", SeatHolding.Basis.SUBSTANTIVE, null, from, null));
        dir.addHolding(T, new SeatHolding("DESK", "desk", SeatHolding.Basis.SUBSTANTIVE, null, from, null));
    }

    @AfterAll
    static void tearDown() {
        engine.close();
        engine = null;
        dir = null;
        PackRuntime.reset();
    }

    /** The candidate groups of the waiting task of a started case. */
    static List<String> start(String key, Map<String, Object> vars) {
        String pi = engine.getRuntimeService().createProcessInstanceByKey(key).processDefinitionTenantId(T)
                .setVariables(new HashMap<>(vars)).execute().getId();
        String task = engine.getTaskService().createTaskQuery().processInstanceId(pi).singleResult().getId();
        return engine.getTaskService().getIdentityLinksForTask(task).stream().map(IdentityLink::getGroupId)
                .filter(java.util.Objects::nonNull).toList();
    }

    static ProcessEngineException refused(String key, Map<String, Object> vars) {
        long before = engine.getRuntimeService().createProcessInstanceQuery().processDefinitionKey(key).count();
        ProcessEngineException e = assertThrows(ProcessEngineException.class, () -> start(key, vars));
        assertTrue(e.getMessage().contains("422 SEAT_UNRESOLVED"), e.getMessage());
        assertEquals(before, engine.getRuntimeService().createProcessInstanceQuery().processDefinitionKey(key).count(),
                "nothing started: no task waits for the role");
        return e;
    }

    // ------------------------------------------------------------------ ROLE

    @Test
    void theSeatOfTheCaseOfficeGetsTheStep() {
        assertEquals(List.of("SO-A"), start("fcRole", Map.of("officeId", "OFFICE-A")));
        assertEquals(List.of("SO-B"), start("fcRole", Map.of("officeId", "OFFICE-B")));
    }

    @Test
    void theNearestAncestorSeatServesASection() {
        assertEquals(List.of("SO-A"), start("fcRole", Map.of("officeId", "SECTION-A1")));
    }

    @Test
    void aPackFallbackSeatIsKeptWhenNoSeatResolves() {
        assertEquals(List.of("DESK"), start("fcRole", Map.of("fallbackSeat", "DESK")));
        assertEquals(List.of("DESK"), start("fcRole", Map.of("officeId", "OFFICE-EMPTY", "fallbackSeat", "DESK")));
    }

    @Test
    void noOfficeAndNoFallbackSeatIsRefusedNeverARolePool() {
        ProcessEngineException e = refused("fcRole", Map.of());
        assertTrue(e.getMessage().contains("SO"), "the refusal names the role: " + e.getMessage());
    }

    @Test
    void anOfficeWithoutASeatOfTheRoleUpToTheRootIsRefused() {
        refused("fcRole", Map.of("officeId", "OFFICE-EMPTY"));
        refused("fcRole", Map.of("officeId", "NO-SUCH-OFFICE"));
    }

    // ------------------------------------------------------------------ CANDIDATES (S07, J-P1-E07)

    @Test
    void noEligibleCheckerTakesThePackFallbackSeatElseIsRefused() {
        assertEquals(List.of("SO-B"), start("fcCandidates", Map.of("officeId", "OFFICE-B")));
        assertEquals(List.of("DESK"), start("fcCandidates", Map.of("officeId", "OFFICE-EMPTY", "fallbackSeat", "DESK")));
        refused("fcCandidates", Map.of("officeId", "OFFICE-EMPTY"));
        refused("fcCandidates", Map.of());
    }

    // ------------------------------------------------------------------ ROLES (recommendation chain)

    @Test
    void aRecommendationChainNeverHoldsARoleCode() {
        assertEquals(List.of("SO-A", "DESK"), start("fcRoles", Map.of("officeId", "OFFICE-A",
                "recommendationRoles", "SO,DESK")));
        assertEquals(List.of("SO-A", "DESK"), start("fcRoles", Map.of("officeId", "OFFICE-A",
                "recommendationRoles", "SO,X", "fallbackSeat", "DESK")));
        ProcessEngineException e = refused("fcRoles", Map.of("officeId", "OFFICE-A", "recommendationRoles", "SO,X"));
        assertTrue(e.getMessage().contains("X"), "the refusal names the role: " + e.getMessage());
    }

    // ------------------------------------------------------------------ the simulator directory

    @Test
    void theSimulatorNeverMatchesASeatOfAnotherOffice() {
        assertEquals(Optional.empty(), dir.seatForRole(T, "SO", null));
        assertEquals(Optional.empty(), dir.seatForRole(T, "SO", ""));
        assertEquals(Optional.empty(), dir.seatForRole(T, "SO", "OFFICE-EMPTY"));
        assertEquals(Optional.of("SO-A"), dir.seatForRole(T, "SO", "SECTION-A1"));
        assertEquals(List.of(), dir.eligibleSeats(T, "SO", null, LocalDate.of(2026, 6, 1)));
        assertEquals(List.of("SO-B"), dir.eligibleSeats(T, "SO", "OFFICE-B", LocalDate.of(2026, 6, 1)));
    }
}

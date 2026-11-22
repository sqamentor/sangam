// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.cmmn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.ports.PortsH2Engine;
import org.sangam.ports.cmmn.PlanningService.Caller;
import org.sangam.ports.cmmn.PlanningService.PlanningItem;

/**
 * Owner ruling C2: CMMN 1.1 planning table. {@link PlanningService#applicable} lists the discretionary items of a
 * stage's planning table that the caller may plan: the item's {@code applicabilityRule} conditions hold for the case
 * and, when it names {@code authorizedRoleRefs}, the caller holds one of those roles (role name = group id).
 */
class PlanningServiceH2Test {

    private static final String CASE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL" targetNamespace="t" id="d">
              <case id="planCase">
                <casePlanModel id="plan" autoComplete="false">
                  <planItem id="PI_main" definitionRef="main"/>
                  <planningTable id="pt">
                    <discretionaryItem id="DI_high" definitionRef="high" applicabilityRuleRefs="arBig" authorizedRoleRefs="rApprover"/>
                    <discretionaryItem id="DI_note" definitionRef="note" authorizedRoleRefs="rClerk"/>
                    <discretionaryItem id="DI_open" definitionRef="open"/>
                    <applicabilityRule id="arBig"><condition>${amount > 1000}</condition></applicabilityRule>
                  </planningTable>
                  <humanTask id="main" name="Main"/>
                  <humanTask id="high" name="High value review"/>
                  <humanTask id="note" name="File note"/>
                  <humanTask id="open" name="Open item"/>
                </casePlanModel>
                <caseRoles id="roles">
                  <role id="rApprover" name="approver"/>
                  <role id="rClerk" name="clerk"/>
                </caseRoles>
              </case>
            </definitions>
            """;

    private PortsH2Engine h2;
    private PlanningService svc;

    @BeforeEach
    void setUp() {
        h2 = PortsH2Engine.start(null);
        h2.engine.getRepositoryService().createDeployment().addString("plan.cmmn", CASE).deploy();
        svc = new PlanningService(h2.engine);
    }

    @AfterEach
    void tearDown() {
        h2.close();
    }

    private List<String> ids(String caseId, Caller caller) {
        return svc.applicable(caseId, caller).stream().map(PlanningItem::discretionaryItemId).sorted().toList();
    }

    @Test
    void applicabilityRuleAndAuthorizedRolesFilterTheList() {
        String small = h2.engine.getCaseService().createCaseInstanceByKey("planCase", Map.of("amount", 500)).getId();
        String big = h2.engine.getCaseService().createCaseInstanceByKey("planCase", Map.of("amount", 5000)).getId();

        assertEquals(List.of("DI_open"), ids(small, new Caller("u1", Set.of("approver"))), "rule false for 500");
        assertEquals(List.of("DI_high", "DI_open"), ids(big, new Caller("u1", Set.of("approver"))), "rule true, role held");
        assertEquals(List.of("DI_note", "DI_open"), ids(big, new Caller("u2", Set.of("clerk"))), "clerk only");
        assertEquals(List.of("DI_open"), ids(big, new Caller("u3", Set.of())), "no role: unrestricted items only");
    }

    @Test
    void itemCarriesItsDefinitionAndRoles() {
        String big = h2.engine.getCaseService().createCaseInstanceByKey("planCase", Map.of("amount", 5000)).getId();
        PlanningItem high = svc.applicable(big, new Caller("u1", Set.of("approver"))).stream()
                .filter(i -> "DI_high".equals(i.discretionaryItemId())).findFirst().orElseThrow();
        assertEquals("high", high.definitionId());
        assertEquals("High value review", high.name());
        assertTrue(high.authorizedRoles().contains("approver"));
    }

    @Test
    void unknownCaseOrNoCallerGivesNothing() {
        assertTrue(svc.applicable("no-such-case", new Caller("u1", Set.of("approver"))).isEmpty());
        String big = h2.engine.getCaseService().createCaseInstanceByKey("planCase", Map.of("amount", 5000)).getId();
        assertTrue(svc.applicable(big, null).isEmpty());
    }
}

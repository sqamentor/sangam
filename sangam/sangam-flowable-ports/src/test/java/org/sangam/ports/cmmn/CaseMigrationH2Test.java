// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.cmmn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.repository.CaseDefinition;
import org.operaton.bpm.engine.runtime.CaseExecution;
import org.operaton.bpm.engine.task.Task;
import org.sangam.ports.PortsH2Engine;
import org.sangam.ports.cmmn.CaseMigrationService.CaseMigrationException;
import org.sangam.ports.cmmn.CaseMigrationService.MigrationRecord;

/**
 * PATCHES.md P-5 / FLOWABLE-INTO-OPERATON FC-19 native, integrator ruling A1: a running case instance is rebound to
 * another version of its case definition when every active plan item and every sentry it holds exists in the target;
 * otherwise the call is rejected per item before anything changes. Each migration leaves an audit row.
 */
class CaseMigrationH2Test {

    private static String caseXml(String aId, String extra) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL" targetNamespace="t" id="d">
                  <case id="migCase">
                    <casePlanModel id="plan" autoComplete="false">
                      <planItem id="%1$s" definitionRef="a"/>
                      <planItem id="PI_b" definitionRef="b">
                        <entryCriterion id="ec" sentryRef="s"/>
                      </planItem>
                      %2$s
                      <sentry id="s">
                        <planItemOnPart id="op" sourceRef="%1$s"><standardEvent>complete</standardEvent></planItemOnPart>
                      </sentry>
                      <humanTask id="a" name="A"/>
                      <humanTask id="b" name="B"/>
                    </casePlanModel>
                  </case>
                </definitions>
                """.formatted(aId, extra);
    }

    private PortsH2Engine h2;
    private CaseMigrationService svc;

    @BeforeEach
    void setUp() {
        h2 = PortsH2Engine.start(null);
        svc = new CaseMigrationService(h2.engine);
    }

    @AfterEach
    void tearDown() {
        h2.close();
    }

    private String deploy(String xml) {
        h2.engine.getRepositoryService().createDeployment().addString("mig.cmmn", xml).deploy();
        return h2.engine.getRepositoryService().createCaseDefinitionQuery().caseDefinitionKey("migCase")
                .latestVersion().singleResult().getId();
    }

    private List<CaseExecution> executions(String caseId) {
        return h2.engine.getCaseService().createCaseExecutionQuery().caseInstanceId(caseId).list();
    }

    @Test
    void activeCaseIsRebound_sentriesStillFire_andAuditIsWritten() {
        String v1 = deploy(caseXml("PI_a", ""));
        String c = h2.engine.getCaseService().createCaseInstanceById(v1).getId();
        String v2 = deploy(caseXml("PI_a", "<planItem id=\"PI_new\" definitionRef=\"b\"/>"));

        MigrationRecord rec = svc.migrate(c, v2, "officer-1", "rule change R-17");

        for (CaseExecution e : executions(c)) {
            assertEquals(v2, e.getCaseDefinitionId(), "execution " + e.getActivityId() + " rebound");
        }
        Task a = h2.engine.getTaskService().createTaskQuery().caseInstanceId(c).singleResult();
        assertEquals(v2, a.getCaseDefinitionId(), "task rebound");
        assertEquals(v1, rec.sourceDefinitionId());
        assertEquals(v2, rec.targetDefinitionId());
        assertEquals("officer-1", rec.migratedBy());
        assertEquals("rule change R-17", rec.reason());

        h2.engine.getTaskService().complete(a.getId());
        assertTrue(h2.engine.getTaskService().createTaskQuery().caseInstanceId(c).list().stream()
                .anyMatch(t -> "PI_b".equals(t.getTaskDefinitionKey())), "sentry of the target definition fired");

        List<MigrationRecord> audit = svc.history(c);
        assertEquals(1, audit.size());
        assertEquals("officer-1", audit.get(0).migratedBy());
    }

    @Test
    void missingPlanItemInTargetIsRejectedPerItem_andNothingChanges() {
        String v1 = deploy(caseXml("PI_a", ""));
        String c = h2.engine.getCaseService().createCaseInstanceById(v1).getId();
        String v2 = deploy(caseXml("PI_renamed", ""));

        CaseMigrationException ex = assertThrows(CaseMigrationException.class,
                () -> svc.migrate(c, v2, "officer-1", "rename"));
        assertTrue(ex.problems().stream().anyMatch(p -> p.contains("PI_a")), "names the missing plan item: " + ex.problems());
        for (CaseExecution e : executions(c)) {
            assertEquals(v1, e.getCaseDefinitionId(), "unchanged after rejection");
        }
        assertTrue(svc.history(c).isEmpty(), "no audit row for a rejected migration");
    }

    @Test
    void reasonAndActorAreRequired_andOtherCaseKeysAreRejected() {
        String v1 = deploy(caseXml("PI_a", ""));
        String c = h2.engine.getCaseService().createCaseInstanceById(v1).getId();
        String v2 = deploy(caseXml("PI_a", ""));
        assertThrows(CaseMigrationException.class, () -> svc.migrate(c, v2, null, "x"));
        assertThrows(CaseMigrationException.class, () -> svc.migrate(c, v2, "officer-1", " "));

        h2.engine.getRepositoryService().createDeployment().addString("other.cmmn",
                caseXml("PI_a", "").replace("migCase", "otherCase")).deploy();
        CaseDefinition other = h2.engine.getRepositoryService().createCaseDefinitionQuery().caseDefinitionKey("otherCase").singleResult();
        assertThrows(CaseMigrationException.class, () -> svc.migrate(c, other.getId(), "officer-1", "x"));
    }
}

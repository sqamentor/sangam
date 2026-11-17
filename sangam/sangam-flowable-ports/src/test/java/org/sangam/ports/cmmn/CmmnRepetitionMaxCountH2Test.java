// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.cmmn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.task.Task;
import org.sangam.ports.PortsH2Engine;

/**
 * PATCHES.md P-4 / FLOWABLE-INTO-OPERATON FC-57: repetition with a maximum instance count
 * ({@code sangam:maxInstanceCount} on {@code repetitionRule}). Operaton 2.2.0 repeats a plan item for as long as the
 * rule's condition holds; the Sangam attribute caps the number of instances the plan item has in its stage.
 */
class CmmnRepetitionMaxCountH2Test {

    private static String caseXml(String key, String ruleAttrs) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL"
                             xmlns:operaton="http://operaton.org/schema/1.0/cmmn"
                             xmlns:sangam="http://sangam.org/schema/1.0/cmmn" targetNamespace="t" id="d">
                  <case id="%s">
                    <casePlanModel id="plan" autoComplete="false">
                      <planItem id="PI_review" definitionRef="review">
                        <itemControl id="ic">
                          <repetitionRule id="rr" operaton:repeatOnStandardEvent="complete" %s/>
                        </itemControl>
                      </planItem>
                      <humanTask id="review" name="Review"/>
                    </casePlanModel>
                  </case>
                </definitions>
                """.formatted(key, ruleAttrs);
    }

    private PortsH2Engine h2;

    @BeforeEach
    void setUp() {
        h2 = PortsH2Engine.start(null);
    }

    @AfterEach
    void tearDown() {
        h2.close();
    }

    /** Completes the case's single open review task; returns how many review tasks are open afterwards. */
    private long completeOne(String caseId) {
        Task t = h2.engine.getTaskService().createTaskQuery().caseInstanceId(caseId).singleResult();
        h2.engine.getTaskService().complete(t.getId());
        return h2.engine.getTaskService().createTaskQuery().caseInstanceId(caseId).count();
    }

    @Test
    void maxInstanceCountStopsRepetitionAfterTheThirdInstance() {
        h2.engine.getRepositoryService().createDeployment()
                .addString("max.cmmn", caseXml("maxCase", "sangam:maxInstanceCount=\"3\"")).deploy();
        String c = h2.engine.getCaseService().createCaseInstanceByKey("maxCase").getId();
        assertEquals(1, h2.engine.getTaskService().createTaskQuery().caseInstanceId(c).count(), "instance 1");
        assertEquals(1, completeOne(c), "instance 2 created on complete of 1");
        assertEquals(1, completeOne(c), "instance 3 created on complete of 2");
        assertEquals(0, completeOne(c), "no instance 4: max count 3 reached");
    }

    @Test
    void withoutMaxCountRepetitionContinues() {
        h2.engine.getRepositoryService().createDeployment().addString("open.cmmn", caseXml("openCase", "")).deploy();
        String c = h2.engine.getCaseService().createCaseInstanceByKey("openCase").getId();
        for (int i = 0; i < 4; i++) {
            assertEquals(1, completeOne(c), "repetition without a cap keeps creating instances");
        }
    }

    @Test
    void maxInstanceCountBelowOneIsRejectedAtDeploy() {
        assertThrows(ProcessEngineException.class, () -> h2.engine.getRepositoryService().createDeployment()
                .addString("bad.cmmn", caseXml("badCase", "sangam:maxInstanceCount=\"0\"")).deploy());
    }
}

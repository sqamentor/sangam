// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.cmmn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.operaton.bpm.engine.ProcessEngineException;
import org.sangam.ports.PortsH2Engine;
import org.sangam.ports.cmmn.CaseFileItemService.CaseFileItemState;

/**
 * Owner ruling C1 (option A): a CMMN 1.1 {@code caseFileItem} is a typed case variable named by the item; its
 * lifecycle state (AVAILABLE / DISCARDED) is kept in the engine-written table {@code PORT_CASE_FILE_ITEM};
 * {@code caseFileItemOnPart} with {@code create}, {@code update}, {@code delete} or {@code replace} fires the sentry
 * through Operaton's own variable on-part evaluation. {@code addChild}, {@code removeChild}, {@code addReference} and
 * {@code removeReference} are refused at deploy.
 */
class CaseFileItemH2Test {

    private static String caseXml(String key, String event) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL" targetNamespace="t" id="d">
                  <caseFileItemDefinition id="cfidApp" name="Application" definitionType="http://www.omg.org/spec/CMMN/DefinitionType/Unspecified"/>
                  <case id="%1$s">
                    <caseFileModel id="cfm">
                      <caseFileItem id="cfiApp" name="application" definitionRef="cfidApp"/>
                    </caseFileModel>
                    <casePlanModel id="plan" autoComplete="false">
                      <planItem id="PI_review" definitionRef="review">
                        <entryCriterion id="ec" sentryRef="s"/>
                      </planItem>
                      <sentry id="s">
                        <caseFileItemOnPart id="op" sourceRef="cfiApp"><standardEvent>%2$s</standardEvent></caseFileItemOnPart>
                      </sentry>
                      <humanTask id="review" name="Review application"/>
                    </casePlanModel>
                  </case>
                </definitions>
                """.formatted(key, event);
    }

    private PortsH2Engine h2;
    private CaseFileItemService svc;

    @BeforeEach
    void setUp() {
        h2 = PortsH2Engine.start(null);
        svc = new CaseFileItemService(h2.engine);
    }

    @AfterEach
    void tearDown() {
        h2.close();
    }

    private String startCase(String key, String event) {
        h2.engine.getRepositoryService().createDeployment().addString(key + ".cmmn", caseXml(key, event)).deploy();
        return h2.engine.getCaseService().createCaseInstanceByKey(key).getId();
    }

    private long reviews(String caseId) {
        return h2.engine.getTaskService().createTaskQuery().caseInstanceId(caseId).count();
    }

    @Test
    void createFiresTheSentry_andStateIsAvailable() {
        String c = startCase("cfCreate", "create");
        assertEquals(0, reviews(c), "no review before the item exists");
        assertTrue(svc.states(c).isEmpty());
        h2.engine.getCaseService().setVariable(c, "application", "{\"id\":\"A-1\"}");
        assertEquals(1, reviews(c), "create fired the sentry");
        List<CaseFileItemState> s = svc.states(c);
        assertEquals(1, s.size());
        assertEquals("cfiApp", s.get(0).itemId());
        assertEquals("AVAILABLE", s.get(0).state());
    }

    @Test
    void updateAndReplaceFireOnUpdate() {
        for (String ev : List.of("update", "replace")) {
            String c = startCase("cf_" + ev, ev);
            h2.engine.getCaseService().setVariable(c, "application", "v1");
            assertEquals(0, reviews(c), ev + ": create does not fire");
            h2.engine.getCaseService().setVariable(c, "application", "v2");
            assertEquals(1, reviews(c), ev + ": second write fires");
        }
    }

    @Test
    void deleteFires_andStateIsDiscarded() {
        String c = startCase("cfDelete", "delete");
        h2.engine.getCaseService().setVariable(c, "application", "v1");
        h2.engine.getCaseService().removeVariable(c, "application");
        assertEquals(1, reviews(c), "delete fired the sentry");
        assertEquals("DISCARDED", svc.states(c).get(0).state());
    }

    @ParameterizedTest
    @ValueSource(strings = {"addChild", "removeChild", "addReference", "removeReference"})
    void childAndReferenceTransitionsAreRefusedAtDeploy(String event) {
        ProcessEngineException ex = assertThrows(ProcessEngineException.class, () -> h2.engine.getRepositoryService()
                .createDeployment().addString("bad.cmmn", caseXml("cfBad", event)).deploy());
        assertTrue(String.valueOf(ex.getMessage()).contains(event) || String.valueOf(ex.getCause()).contains(event),
                "error names the transition: " + ex);
    }
}

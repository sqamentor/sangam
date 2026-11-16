// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.history.HistoricTaskInstance;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.engine.task.IdentityLink;
import org.operaton.bpm.engine.task.Task;

import org.sangam.packs.PackDriver.Answer;
import org.sangam.packs.core.DscSignatureGuardListener;
import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.spi.PackDeployer;
import org.sangam.packs.spi.PackRegistry;

/**
 * Owner decisions 387 (9) and 390 (option a), the acceptance test: a DSC gate reached again in a return loop signs a
 * document uploaded for that round, never the one signed in the earlier round. Round 1: the first recommendation level
 * signs the noting (the gate instance consumes its binding), the second level of the same gate instance still finds it
 * bound and returns the file to the initiator. The initiator revises and resubmits; the prepare task refuses the
 * consumed slot until the noting is uploaded again (the driver uploads it, as the document route would), and both
 * levels of round 2 sign the new noting.
 */
class GateRoundSlotTest {

    static ProcessEngine engine;
    static PackDriver driver;

    @BeforeAll
    static void setUp() {
        PackRuntime.reset();
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-gate-rounds;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        cfg.setHistory("full");
        PackTestEngines.configure(cfg);
        engine = cfg.buildProcessEngine();
        new PackDeployer(engine, PackRegistry.discover()).deployAll(PackTestTenant.ID);
        driver = new PackDriver(engine);
    }

    @AfterAll
    static void tearDown() {
        engine.close();
    }

    static Answer as(Task t, boolean initiator, Object... kv) {
        String seat = engine.getTaskService().getIdentityLinksForTask(t.getId()).stream()
                .map(IdentityLink::getGroupId).filter(java.util.Objects::nonNull).findFirst().orElse("none");
        String person = initiator
                ? String.valueOf(engine.getRuntimeService().getVariable(t.getProcessInstanceId(), "initiator"))
                : PackRuntime.seats().holderOf(PackTestTenant.ID, seat, java.time.LocalDate.now()).orElse("user." + seat);
        return Answer.of(person, seat, kv);
    }

    @Test
    void aGateReachedAgainAfterAReturnSignsADocumentOfTheNewRound() {
        String bk = "B-2/15/2026-Estt";
        Map<String, Object> v = new HashMap<>();
        for (Object[] kv : new Object[][] {{"fileNo", bk}, {"subject", "Sanction of honorarium"},
                {"subjectHead", "ESTABLISHMENT"}, {"noting", "Proposal for approval."}, {"draftLetter", "Draft order."},
                {"actingSeat", "DA-B2"}, {"initiator", "p2.da"}, {"officeId", "SEC-B2"}}) {
            v.put((String) kv[0], kv[1]);
        }
        engine.getRuntimeService().createProcessInstanceByKey("sangam-p2-file-movement").businessKey(bk)
                .setVariables(v).processDefinitionTenantId(PackTestTenant.ID).execute();
        int[] levels = {0};
        List<String> done = driver.run(bk, t -> switch (t.getTaskDefinitionKey()) {
            case "p2_movementPrepare" -> as(t, true);
            // round 1: the first level recommends (signs), the second returns the file; round 2: both levels recommend
            case "rc_level" -> as(t, false, "recDecision", levels[0]++ == 1 ? "return" : "recommend");
            case "p2_revise" -> as(t, true, "p2_reviseDecision", "resubmit", "remarks", "noting corrected");
            default -> null;
        }, 40);
        assertEquals(List.of("p2_movementPrepare", "rc_level", "rc_level", "p2_revise", "p2_movementPrepare",
                "rc_level", "rc_level"), done.subList(0, 7));
        List<HistoricTaskInstance> gates = engine.getHistoryService().createHistoricTaskInstanceQuery()
                .processInstanceBusinessKeyLike(bk + "%").taskDefinitionKey("rc_level")
                .orderByHistoricActivityInstanceStartTime().asc().list();
        assertEquals(4, gates.size(), "two levels in each round");
        String first = required(gates.get(0));
        String second = required(gates.get(2));
        assertNotEquals(null, first);
        assertEquals(first, required(gates.get(1)), "the second level of the same gate instance signs the same noting");
        assertNotEquals(null, second);
        assertNotEquals(first, second, "the gate of round 2 signed the noting left from round 1");
        assertEquals(second, required(gates.get(3)));
    }

    static String required(HistoricTaskInstance t) {
        var h = engine.getHistoryService().createHistoricVariableInstanceQuery().taskIdIn(t.getId())
                .variableName(DscSignatureGuardListener.REQUIRED_DOCUMENT).singleResult();
        return h == null || h.getValue() == null ? null : h.getValue().toString();
    }
}

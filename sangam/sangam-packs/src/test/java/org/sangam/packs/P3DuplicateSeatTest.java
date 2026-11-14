// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.engine.task.IdentityLink;
import org.operaton.bpm.engine.task.Task;
import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.sim.LoggingAuditSink;
import org.sangam.packs.spi.PackDeployer;
import org.sangam.packs.spi.PackRegistry;

/**
 * M-010d (SL-134): the possible-duplicate decision of P3 ({@code p3_duplicate}, J-P3-E06) is routed to its own seat,
 * the pack default {@code duplicateSeat} (DOCVERIFIER-A2, the district verifier pool), not to the role code
 * {@code DOCVERIFIER} and not to the block's scrutiny seat. The core scrutiny task {@code sc_scrutiny} keeps its
 * segregation of duties (every earlier actor, by person and by seat), so a decision on the scrutiny seat itself would
 * block every "proceed" at scrutiny; with duplicateSeat different from scrutinySeat the case goes on.
 *
 * <p>The simulator's beneficiary registry reports a beneficiary as a duplicate when another case already registered
 * the same beneficiary and scheme, so each test first runs one case of the beneficiary to its first person step.
 */
class P3DuplicateSeatTest {

    static final String KEY = "sangam-p3-grant-application";
    static ProcessEngine engine;
    static PackDriver driver;
    static int seq;

    @BeforeAll
    static void setUp() {
        PackRuntime.reset();
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-m010d;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        cfg.setHistory("full");
        PackTestEngines.configure(cfg); // decisions 138 and FEEL (sangam-feel): as SangamProcessEnginePlugin does
        engine = cfg.buildProcessEngine();
        new PackDeployer(engine, PackRegistry.discover()).deployAll(PackTestTenant.ID);
        driver = new PackDriver(engine);
    }

    @AfterAll
    static void tearDown() {
        engine.close();
        PackRuntime.reset();
    }

    /** The P3 acceptance application (gov1.py), block A1, for {@code beneficiaryId}. */
    static String start(String beneficiaryId) {
        String bk = "M010D/" + beneficiaryId + "/" + (++seq);
        Map<String, Object> v = AllPacksSmokeTest.bag();
        v.putAll(Map.of("applicantName", "Sita Devi", "applicantRef", "mobile:98XXXXXX10", "schemeId", "HOUSING-GRANT",
                "beneficiaryId", beneficiaryId, "bankAccount", "3210987654", "ifsc", "PUNB0123400", "income", 120000,
                "age", 41, "socialCategory", "SC", "landHolding", 0.5));
        v.putAll(Map.of("district", "A", "officeId", "BLOCK-A1", "actingSeat", "DEO-A1", "initiator", "p3.deo-a1",
                "instalmentInterval", "PT1S"));
        engine.getRuntimeService().createProcessInstanceByKey(KEY).businessKey(bk).setVariables(v)
                .processDefinitionTenantId(PackTestTenant.ID).execute();
        return bk;
    }

    /** A first case of the beneficiary, run to its first person step, so that the next one is a possible duplicate. */
    static void registerFirst(String beneficiaryId) {
        String first = start(beneficiaryId);
        driver.run(first, t -> null, 40);
    }

    static String group(Task t) {
        return engine.getTaskService().getIdentityLinksForTask(t.getId()).stream().map(IdentityLink::getGroupId)
                .filter(Objects::nonNull).findFirst().orElse(null);
    }

    static boolean sodBlocked(String bk) {
        return ((LoggingAuditSink) PackRuntime.audit()).timeline(bk).stream()
                .anyMatch(e -> "SOD_VIOLATION_BLOCKED".equals(e.type()));
    }

    @Test
    void proceedOnADuplicateReachesScrutinyWithoutASodViolation() {
        registerFirst("VAULT-M010D-1");
        String bk = start("VAULT-M010D-1");
        FormAnswers form = new FormAnswers(engine);
        Map<String, String> groups = new HashMap<>();
        List<String> done = driver.run(bk, t -> {
            String key = t.getTaskDefinitionKey();
            if (!key.equals("p3_duplicate") && !key.equals("sc_scrutiny")) {
                return null;
            }
            groups.put(key, group(t));
            return form.answer(t);   // the holder of the task's seat, affirmative decision (proceed / accept)
        }, 80);

        assertEquals("DOCVERIFIER-A2", groups.get("p3_duplicate"), "duplicateSeat, not the role code DOCVERIFIER");
        assertEquals("DOCVERIFIER-A1", groups.get("sc_scrutiny"), "scrutinySeat: the verifier of the case's block");
        assertNotEquals(groups.get("p3_duplicate"), groups.get("sc_scrutiny"));
        List<String> expected = List.of("p3_duplicate", "sc_scrutiny");
        assertEquals(expected, done.subList(0, Math.min(2, done.size())), "proceed, then scrutiny completes: " + done);
        assertFalse(sodBlocked(bk), "no SOD_VIOLATION_BLOCKED on " + bk);
    }

    @Test
    void scrutinyStillRefusesTheSeatThatDecidedTheDuplicate() {
        registerFirst("VAULT-M010D-2");
        String bk = start("VAULT-M010D-2");
        FormAnswers form = new FormAnswers(engine);
        List<String> seen = new ArrayList<>();
        RuntimeException e = assertThrows(RuntimeException.class, () -> driver.run(bk, t -> {
            String key = t.getTaskDefinitionKey();
            seen.add(key);
            if (key.equals("p3_duplicate")) {
                return form.answer(t);
            }
            if (key.equals("sc_scrutiny")) {
                PackDriver.Answer a = form.answer(t);
                // the duplicate seat's holder acting at scrutiny: segregation of duties is unchanged and refuses it
                return new PackDriver.Answer("p3.docverifier-a2", "DOCVERIFIER-A2", a.vars());
            }
            return null;
        }, 80));
        assertTrue(seen.contains("sc_scrutiny"), "reached scrutiny: " + seen);
        StringBuilder messages = new StringBuilder();
        for (Throwable c = e; c != null; c = c.getCause()) {
            messages.append(c.getMessage()).append(" | ");
        }
        assertTrue(messages.toString().contains("422 SOD_VIOLATION"), messages.toString());
    }
}

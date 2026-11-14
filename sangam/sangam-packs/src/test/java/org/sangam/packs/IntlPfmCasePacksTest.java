// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.sim.SimulatorConnectorGateway;
import org.sangam.packs.spi.PackDeployer;
import org.sangam.packs.spi.PackRegistry;

/**
 * M-134 (owner decision 108): the worldwide PFM packs {@code gov.intl.pfm-breach-case} (PFM-039, PFM-CH-7) and
 * {@code gov.intl.pfm-suspense-ageing} (PFM-051). Country values are SYNTHETIC placeholders, so readiness fails until a
 * country deploys a confirmed override; nothing is reported or posted before the person's step.
 */
class IntlPfmCasePacksTest {

    static ProcessEngine engine;
    static PackDriver driver;
    static int seq;

    @BeforeAll
    static void setUp() {
        PackRuntime.reset();
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-m134;DB_CLOSE_DELAY=-1");
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
    }

    static String run(String key) {
        String bk = "M134/" + key + "/" + (++seq);
        Map<String, Object> v = AllPacksSmokeTest.bag();
        engine.getRuntimeService().createProcessInstanceByKey(key).businessKey(bk).setVariables(v)
                .processDefinitionTenantId(PackTestTenant.ID).execute();
        driver.run(bk, t -> null, 40);
        return bk;
    }

    static List<String> openTasks(String bk) {
        return engine.getTaskService().createTaskQuery().processInstanceBusinessKeyLike(bk + "%").list().stream()
                .map(t -> t.getTaskDefinitionKey()).toList();
    }

    static boolean called(String bk, String connector) {
        return ((SimulatorConnectorGateway) PackRuntime.gateway()).journal().stream()
                .anyMatch(r -> bk.equals(r.businessKey()) && connector.equals(r.connector()));
    }

    static String pack(String dir) throws java.io.IOException {
        return Files.readString(Path.of("src", "main", "resources", "packs", dir, "pack.json"), StandardCharsets.UTF_8);
    }

    @Test
    void countryValuesAreDeclaredPlaceholders() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        for (String[] c : new String[][]{{"gov-intl-pfm-breach-case", "intl-pfm-breach-reporting"},
                {"gov-intl-pfm-suspense-ageing", "intl-pfm-suspense-ageing"}}) {
            var placeholders = json.readTree(pack(c[0])).path("placeholders");
            assertEquals(1, placeholders.size(), c[0] + " placeholders");
            assertEquals(c[1], placeholders.get(0).path("decision").asText(), c[0]);
            assertTrue(placeholders.get(0).path("rule").asText().startsWith("SYNTHETIC"), c[0]);
        }
    }

    @Test
    void aBreachIsInvestigatedBeforeAnyReportAndHasADeadline() {
        String bk = run("sangam-intl-pfm-breach-case");
        assertEquals(List.of("bc_investigate"), openTasks(bk), "the investigator acts first");
        assertFalse(called(bk, "authority.report"), "reported before the investigation");
        assertTrue(driver.var(bk, "breachReportDueAt") != null, "the report deadline is set from DMN");
        assertEquals(0, engine.getRuntimeService().createIncidentQuery().count(), "incidents");
    }

    @Test
    void aSuspenseItemWaitsForClearanceUnderAnSlaAndIsNotPostedUnasked() {
        String bk = run("sangam-intl-pfm-suspense-ageing");
        assertEquals(List.of("su_clear"), openTasks(bk), "the clerk clears the item");
        assertFalse(called(bk, "ledger.reclassify"), "posted before a clearance decision");
        assertTrue(driver.var(bk, "su_clearDueAt") != null, "the clearance period is guarded by an SLA");
        assertEquals(0, engine.getRuntimeService().createIncidentQuery().count(), "incidents");
    }
}

// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.engine.repository.DeploymentBuilder;

import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.port.ProfileBindings;
import org.sangam.packs.port.SeatHolding;
import org.sangam.packs.sim.InMemorySeatDirectory;
import org.sangam.packs.spi.PackDeployer;
import org.sangam.packs.spi.PackDescriptor;
import org.sangam.packs.spi.PackRegistry;
import org.sangam.packs.spi.SangamPack;

/**
 * Change adoption (sangam/SECTOR-CONFORMANCE.md part C): a sector change is configuration (a new pack version with a
 * changed DMN, seat data with an effective date, a profile version), adopted without code; cases already running
 * keep the version they started with.
 */
class ChangeAdoptionTest {

    static ProcessEngine engine;
    static PackDriver driver;
    static PackRegistry registry;
    static final String PAY = "sec.bank.payment-dual-auth";

    @BeforeAll
    static void setUp() {
        PackRuntime.reset();
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-change;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        cfg.setHistory("full");
        engine = cfg.buildProcessEngine();
        registry = PackRegistry.discover();
        new PackDeployer(engine, registry).deployAll(null);
        driver = new PackDriver(engine);
    }

    @AfterAll
    static void tearDown() {
        engine.close();
    }

    @AfterEach
    void restoreBinder() {
        ProfileBindings.install(null);
    }

    static String read(SangamPack p, String path) {
        try (InputStream in = p.open(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Version 1.1 of the payment pack: the two-level band starts at 10 lakh instead of 25 lakh (data only). */
    static void deployLowerTwoLevelBand() {
        SangamPack p = registry.find(PAY).orElseThrow();
        DeploymentBuilder b = engine.getRepositoryService().createDeployment().name("pack:" + PAY + "@1.1.0")
                .source("sangam-packs");
        for (PackDescriptor.PackResource r : p.descriptor().resources()) {
            if (!r.model()) {
                continue;
            }
            String xml = read(p, r.path());
            if (r.path().endsWith("sec-bank-pay-limits.dmn")) {
                String changed = xml.replace("<text>&gt; 2500000</text>", "<text>&gt; 1000000</text>")
                        .replace("operaton:versionTag=\"1.0.0\"", "operaton:versionTag=\"1.1.0\"");
                assertTrue(!changed.equals(xml), "band row present in the shipped table");
                xml = changed;
            }
            b.addInputStream(r.path(), new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        }
        b.deploy();
    }

    static String start(String bk, double amount) {
        java.util.Map<String, Object> v = AllPacksSmokeTest.bag();
        v.put("amount", amount);
        return engine.getRuntimeService().createProcessInstanceByKey("sangam-sec-bank-payment").businessKey(bk)
                .setVariables(v).processDefinitionWithoutTenantId().execute().getId();
    }

    @Test
    void newRuleVersionAndApprovalLevelApplyToNewCasesOnly() {
        // in flight: started under 1.0.0, parked at the asynchronous beneficiary check before the limits rule
        start("CHG/PAY/old", 2_000_000);
        deployLowerTwoLevelBand();
        start("CHG/PAY/new", 2_000_000);
        FormAnswers a = new FormAnswers(engine);
        List<String> oldDone = driver.run("CHG/PAY/old", a::answer, 60);
        List<String> newDone = driver.run("CHG/PAY/new", a::answer, 60);
        assertTrue(driver.ended("CHG/PAY/old") && driver.ended("CHG/PAY/new"), oldDone + " / " + newDone);
        assertEquals(1, ((Number) driver.var("CHG/PAY/old", "sec_bank_pay_limits_version")).intValue());
        assertEquals(1, ((Number) driver.var("CHG/PAY/old", "authLevels")).intValue(), "old rule: checker alone");
        assertEquals(1, oldDone.stream().filter("sn_sanction"::equals).count());
        assertEquals(2, ((Number) driver.var("CHG/PAY/new", "sec_bank_pay_limits_version")).intValue());
        assertEquals(2, ((Number) driver.var("CHG/PAY/new", "authLevels")).intValue(), "new rule: second level");
        assertEquals(2, newDone.stream().filter("sn_sanction"::equals).count(), "new approval level adopted");
    }

    @Test
    void delegationTakesEffectOnItsDateWithoutRedeploy() {
        InMemorySeatDirectory seats = (InMemorySeatDirectory) PackRuntime.seats();
        String seat = "PAY-AUTH";
        LocalDate today = LocalDate.now();
        String substantive = seats.holderOf(null, seat, today).orElse(null);
        seats.addHolding(InMemorySeatDirectory.DEFAULT_TENANT, new SeatHolding(seat, "delegate.person",
                SeatHolding.Basis.DELEGATION, "ORDER/2026/9", today.plusDays(1), today.plusDays(10)));
        assertEquals(substantive, seats.holderOf(null, seat, today).orElse(null), "not before its date");
        assertTrue(seats.holding(null, "delegate.person", seat, today.plusDays(1)).isPresent(), "from its date");
        assertTrue(seats.holding(null, "delegate.person", seat, today.plusDays(11)).isEmpty(), "ends on its date");
    }

    @Test
    void runningCaseKeepsItsProfileBinding() {
        // profile = locale, fiscal calendar, document types, connector bindings (sangam-config, effective-dated)
        ProfileBindings.install((t, pack, bk) -> "profile@1#aaaaaaaaaaaa");
        start("CHG/PROF/old", 100_000);
        ProfileBindings.install((t, pack, bk) -> "profile@2#bbbbbbbbbbbb");
        start("CHG/PROF/new", 100_000);
        FormAnswers a = new FormAnswers(engine);
        driver.run("CHG/PROF/old", a::answer, 60);
        driver.run("CHG/PROF/new", a::answer, 60);
        assertEquals("profile@1#aaaaaaaaaaaa", driver.var("CHG/PROF/old", "profileVersion"));
        assertEquals("profile@2#bbbbbbbbbbbb", driver.var("CHG/PROF/new", "profileVersion"));
    }
}

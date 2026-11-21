// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.sangam.packs.port.PackAuditEvent;
import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.sim.LoggingAuditSink;
import org.sangam.packs.sim.SimulatorConnectorGateway;
import org.sangam.packs.spi.PackDeployer;
import org.sangam.packs.spi.PackRegistry;

/**
 * SL-113: an availability check on money (savings in the source line, funds at the control dimension, the mother
 * sanction balance) fails CLOSED. Before, the IFMS packs defaulted {@code fundsAvailable} to 999999999999, so a check
 * that FAILED, never ran or answered without an amount still let the case go on to sanction and execution.
 *
 * <p>Now (packgen {@code common.availability_query} / {@code availability_conditions}): the result is cleared before every
 * query, so no default, start variable or earlier answer stands in for it; a query that does not complete with an amount
 * rejects the case with the coded reason {@code FUNDS_NOT_CONFIRMED} (SL-55 catalogue: a check that is unconfirmed or
 * returns no amount, not an external operation that failed), and a completed amount below the request
 * takes the pack's own shortfall path. The simulator answers {@code ledger.available} from the directive variable
 * {@code sim_ledger_available}.
 */
class FundsCheckFailClosedTest {

    static final long AMOUNT = 15_000_000L; // 1,50,000 rupees in paise
    static final long SPOOF = 1_000_000_000_000_000L;

    static ProcessEngine engine;
    static PackDriver driver;
    static int seq;

    @BeforeAll
    static void setUp() {
        PackRuntime.reset();
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-sl113;DB_CLOSE_DELAY=-1");
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
        // R8-heap2: a test class stays loaded for the whole run; its statics must not keep a closed engine
        engine = null;
        driver = null;
    }

    /** Starts {@code key} with the smoke bag plus {@code kv}; runs it by configuration; returns the business key. */
    static String run(String key, Object... kv) {
        String bk = "SL113/" + key + "/" + (++seq);
        Map<String, Object> v = AllPacksSmokeTest.bag();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i + 1] == null) {
                v.remove(kv[i].toString());
            } else {
                v.put(kv[i].toString(), kv[i + 1]);
            }
        }
        engine.getRuntimeService().createProcessInstanceByKey(key).businessKey(bk).setVariables(PackStartOffice.apply(key, v))
                .processDefinitionTenantId(PackTestTenant.ID).execute();
        driver.run(bk, new FormAnswers(engine)::answer, 80);
        return bk;
    }

    static List<PackAuditEvent> audit(String bk) {
        return ((LoggingAuditSink) PackRuntime.audit()).timeline(bk);
    }

    static boolean called(String bk, String connector) {
        return ((SimulatorConnectorGateway) PackRuntime.gateway()).journal().stream()
                .anyMatch(r -> bk.equals(r.businessKey()) && connector.equals(r.connector()));
    }

    static boolean taskDone(String bk, String taskKey) {
        return audit(bk).stream().anyMatch(e -> "TASK_COMPLETED".equals(e.type())
                && taskKey.equals(e.details().get("task")));
    }

    static void assertRejectedBeforeSanction(String bk, String code, String execution) {
        assertTrue(driver.ended(bk), bk + ": case still running");
        assertEquals(0, engine.getRuntimeService().createIncidentQuery().count(), "incidents");
        assertTrue(String.valueOf(driver.var(bk, "rejectionReasonCodes")).contains(code),
                bk + ": coded reason " + driver.var(bk, "rejectionReasonCodes") + ", expected " + code);
        assertTrue(audit(bk).stream().anyMatch(e -> "CASE_REJECTED".equals(e.type())), bk + ": no CASE_REJECTED");
        assertTrue(audit(bk).stream().noneMatch(e -> "CASE_APPROVED".equals(e.type())), bk + ": approved");
        assertFalse(taskDone(bk, "sn_sanction"), bk + ": reached sanction");
        assertFalse(called(bk, execution), bk + ": reached execution " + execution);
    }

    // ------------------------------------------------------------------ re-appropriation (savings in the source line)

    static final String REAPP = "sangam-ifms-reappropriation";

    /** The check FAILED; a spoofed start variable must not stand in for it. */
    @Test
    void reappropriationSavingsCheckFailedIsRejectedEvenWithASpoofedStartValue() {
        String bk = run(REAPP, "amount", AMOUNT, "fundsAvailable", SPOOF, "sim_ledger_available", "FAILED:LEDGER_DOWN");
        assertRejectedBeforeSanction(bk, "FUNDS_NOT_CONFIRMED", "ledger.transfer");
        assertFalse(taskDone(bk, "ra_fa"), "FA consultation reached");
    }

    /** The check answered without an amount (the bare simulator): no default stands in. */
    @Test
    void reappropriationSavingsCheckWithoutAnAmountIsRejected() {
        String bk = run(REAPP, "amount", AMOUNT, "sim_ledger_available", null);
        assertRejectedBeforeSanction(bk, "FUNDS_NOT_CONFIRMED", "ledger.transfer");
    }

    /** One paisa short: the shortfall path, even though the start variables claimed plenty. */
    @Test
    void reappropriationOnePaisaShortIsSavingsNotAvailable() {
        String bk = run(REAPP, "amount", AMOUNT, "fundsAvailable", SPOOF,
                "sim_ledger_available", "available=" + (AMOUNT - 1));
        assertRejectedBeforeSanction(bk, "SAVINGS_NOT_AVAILABLE", "ledger.transfer");
    }

    /** Exactly the amount available: the case passes the gate to the FA consultation (no rejection at the gate). */
    @Test
    void reappropriationWithExactSavingsPassesTheGate() {
        String bk = run(REAPP, "amount", AMOUNT, "sim_ledger_available", "available=" + AMOUNT);
        assertTrue(taskDone(bk, "ra_fa"), bk + ": FA consultation not reached");
        Object codes = driver.var(bk, "rejectionReasonCodes");
        assertFalse("FUNDS_NOT_CONFIRMED".equals(codes) || "SAVINGS_NOT_AVAILABLE".equals(codes), bk + ": rejected " + codes);
    }

    // ------------------------------------------------------------------ SNA-SPARSH (mother sanction balance)

    static final String SNA = "sangam-ifms-sna-sparsh-release";

    @ParameterizedTest
    @ValueSource(strings = {"FAILED:LEDGER_DOWN", "UNKNOWN", "-"})
    void snaMotherSanctionCheckNotCompletedIsRejected(String directive) {
        String bk = run(SNA, "amount", AMOUNT, "fundsAvailable", SPOOF,
                "sim_ledger_available", "-".equals(directive) ? null : directive);
        assertRejectedBeforeSanction(bk, "FUNDS_NOT_CONFIRMED", "rail.ekuber");
        assertFalse(taskDone(bk, "ss_pd"), "daily sanction reached");
    }

    @Test
    void snaExhaustedMotherSanctionIsNoActiveMotherSanction() {
        String bk = run(SNA, "amount", AMOUNT, "sim_ledger_available", "available=0");
        assertRejectedBeforeSanction(bk, "NO_ACTIVE_MOTHER_SANCTION", "rail.ekuber");
    }

    // ------------------------------------------------------------------ IFMS bill (funds at the control dimension)

    static final String BILL = "sangam-ifms-bill";

    @Test
    void billFundsCheckFailedIsRejectedAndNothingIsEncumbered() {
        // the bill's own validations pass (simulator directives), so the funds gate is what decides
        String bk = run(BILL, "amount", AMOUNT, "fundsAvailable", SPOOF, "sim_ledger_available", "FAILED:LEDGER_DOWN",
                "sim_config_documentType", "valid=true", "sim_config_classification", "valid=true");
        assertFalse(String.valueOf(driver.var(bk, "rejectionReasonCodes")).contains("BILL_INVALID"), "validation refused");
        assertRejectedBeforeSanction(bk, "FUNDS_NOT_CONFIRMED", "ledger.encumber");
    }

    // ------------------------------------------------------------------ the shipped models

    static final Pattern DEFAULTS = Pattern.compile(
            "<operaton:field name=\"defaults\"><operaton:string>([^<]*)</operaton:string>");

    /** No IFMS model seeds a value for an availability result, and every availability query is cleared first. */
    @Test
    void noShippedIfmsModelSeedsAnAvailabilityResult() throws IOException {
        for (String m : List.of("ifms-reappropriation/sangam-ifms-reappropriation.bpmn",
                "ifms-sna-sparsh/sangam-ifms-sna-sparsh-release.bpmn", "ifms-bill/sangam-ifms-bill.bpmn")) {
            String xml = Files.readString(Path.of("src", "main", "resources", "packs").resolve(m), StandardCharsets.UTF_8);
            Matcher d = DEFAULTS.matcher(xml);
            while (d.find()) {
                assertFalse(d.group(1).matches("(^|.*;)fundsAvailable=.*"), m + ": default seeds fundsAvailable: " + d.group(1));
            }
            assertTrue(xml.contains("<operaton:string>fundsAvailable=null</operaton:string>"), m + ": no clear step");
            assertTrue(xml.contains("${fundsStatus != 'COMPLETED' || fundsAvailable == null}"), m + ": no fail-closed branch");
        }
    }
}

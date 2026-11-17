// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.spi.PackDeployer;
import org.sangam.packs.spi.PackRegistry;

/**
 * Decision 223 over the shipped packs (packgen payroll.py): PF and ESI are computed per employee from the frozen wage
 * register, never from aggregate wages; the PF wage ceiling and ESI eligibility come from the versioned rules DMN,
 * which ships as a placeholder until verified; and a payroll case computes every share per employee and the totals
 * as exact sums of the per-employee shares. Round 4: the rules and the rounding policies are selected by the wage
 * period of the frozen register (fields periodFrom and periodTo), not the business date; ESI continuation and the
 * low-wage exemption come from the versioned rules; gross wages (and the payroll amount) are the exact sum of the
 * register, never one case figure. The expected amounts are ASSUMPTIONS from the UNVERIFIED, SYNTHETIC_DEMO_ONLY rows,
 * not statutory expected results (decision 288).
 */
class PayrollPerEmployeePackTest {

    static final Path PACKS = Path.of("src", "main", "resources", "packs");
    static final ObjectMapper JSON = new ObjectMapper();
    static final Pattern MONEY_TASK = Pattern.compile(
            "operaton:class=\"org\\.sangam\\.packs\\.core\\.MoneyCalculationDelegate\"><extensionElements>(.*?)"
                    + "</extensionElements>", Pattern.DOTALL);
    static final Pattern SET_TASK = Pattern.compile(
            "operaton:class=\"org\\.sangam\\.packs\\.core\\.SetVariablesDelegate\"><extensionElements>(.*?)"
                    + "</extensionElements>", Pattern.DOTALL);
    static final Pattern FIELD = Pattern.compile(
            "<operaton:field name=\"([^\"]+)\"><operaton:(?:string|expression)>([^<]*)</operaton:");
    /** A PF or ESI wage base or rate: a calculation that reads one is a PF / ESI calculation. */
    static final Pattern PF_ESI = Pattern.compile("\\b(pf|esi)(Wages|Employee\\w*|Employer\\w*)\\b");

    static ProcessEngine engine;
    static PackDriver driver;

    static Map<String, String> fields(String ext) {
        Map<String, String> f = new HashMap<>();
        Matcher m = FIELD.matcher(ext);
        while (m.find()) {
            f.put(m.group(1), m.group(2).replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&"));
        }
        return f;
    }

    static String text(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void noPfOrEsiCalculationUsesAggregateWages() throws IOException {
        List<String> problems = new ArrayList<>();
        int perEmployee = 0;
        List<Path> bpmns;
        try (Stream<Path> s = Files.walk(PACKS)) {
            bpmns = s.filter(p -> p.toString().endsWith(".bpmn")).sorted().toList();
        }
        for (Path bpmn : bpmns) {
            String xml = text(bpmn);
            Matcher m = MONEY_TASK.matcher(xml);
            while (m.find()) {
                Map<String, String> f = fields(m.group(1));
                String formula = f.getOrDefault("formula", "");
                if (!PF_ESI.matcher(formula).find()) {
                    continue;
                }
                if ("SUM".equals(f.get("op")) && !formula.contains("Wages")) {
                    continue; // the exact sum of per-employee shares
                }
                if (!"EACH".equals(f.get("op")) || f.get("items") == null || f.get("items").isBlank()) {
                    problems.add(PACKS.relativize(bpmn) + ": PF / ESI calculation '" + formula
                            + "' is not per employee (op EACH over the wage register)");
                    continue;
                }
                perEmployee++;
                if (!"inputsWagePeriodFrom".equals(f.get("periodFrom"))
                        || !"inputsWagePeriodTo".equals(f.get("periodTo"))) {
                    problems.add(PACKS.relativize(bpmn) + ": PF / ESI share not selected by the wage period: " + f);
                }
                // decision 256: continuation from the rules row's contribution periods and the register's coverage
                // start, never a register flag
                if (formula.contains("esiWages") && !"esiCoverageStart@esiContinuation@esiContributionPeriodStarts"
                        .equals(f.get("continuation"))) {
                    problems.add(PACKS.relativize(bpmn) + ": ESI share without continuation from the rules: " + f);
                }
                // decision 264: the joiner rules column; decision 270: the register's continuation end, renamed;
                // decision 271: the round 6 split by days is gone
                if (formula.contains("esiWages") && !("esiMidPeriodJoiner".equals(f.get("joiner"))
                        && "esiContinuationEndDate".equals(f.get("continuationEnd")))) {
                    problems.add(PACKS.relativize(bpmn) + ": ESI share without the decision 264 and 270 coverage"
                            + " fields: " + f);
                }
                if (f.containsKey("periodSplit") || f.containsKey("coverageEnd")) {
                    problems.add(PACKS.relativize(bpmn) + ": the round 6 fields periodSplit and coverageEnd are gone"
                            + " (decisions 270 and 271): " + f);
                }
                if (f.containsKey("continuedBy")) {
                    problems.add(PACKS.relativize(bpmn) + ": the round 4 field continuedBy is gone: " + f);
                }
                // decision 271: the average daily wage of the employee's wage basis, from the rules
                // decision 295: rule 2(1)(h)(i), the full-period wage over the rated days of the employee's rating
                boolean exempts = "esiFullPeriodWages/esiRating<=esiLowWageDailyLimit".equals(f.get("exemptWhen"))
                        && "esiLowWageComparison".equals(f.get("exemptComparison"))
                        && "esiWageBasis@esiAverageDailyWage@esiAverageDailyWageStatus".equals(f.get("exemptBasis"));
                if (formula.contains("esiEmployee") != exempts) {
                    problems.add(PACKS.relativize(bpmn) + ": the low-wage exemption applies to the ESI employee share"
                            + " only: " + f);
                }
                // decision 293: a PF share works on the wages of a calendar month (EPF Scheme 2026 para 18(4))
                if (formula.contains("pfWages") != "CALENDAR_MONTH".equals(f.get("periodUnit"))) {
                    problems.add(PACKS.relativize(bpmn) + ": periodUnit CALENDAR_MONTH on the PF shares only: " + f);
                }
                if (formula.contains("pfWages") && !"pfWages<=pfWageCeiling".equals(f.get("cap"))) {
                    problems.add(PACKS.relativize(bpmn) + ": PF share without the wage ceiling: " + f);
                }
                if (formula.contains("esiWages") && !"esiWages<=esiWageLimit".equals(f.get("eligibleWhen"))) {
                    problems.add(PACKS.relativize(bpmn) + ": ESI share without eligibility: " + f);
                }
                if (f.get("rules") == null || !Files.exists(bpmn.getParent().resolve(f.get("rules") + ".dmn"))) {
                    problems.add(PACKS.relativize(bpmn) + ": versioned rules DMN not shipped: " + f);
                }
            }
            Matcher sv = SET_TASK.matcher(xml);
            while (sv.find()) {
                for (String spec : fields(sv.group(1)).values()) {
                    if (spec.matches("(?s).*(^|;)\\s*(pfWages|esiWages|grossWages)\\s*=.*")) {
                        problems.add(PACKS.relativize(bpmn) + ": aggregate PF / ESI wages set as a case value: " + spec);
                    }
                }
            }
        }
        assertEquals(4, perEmployee, "per-employee PF / ESI shares found");
        assertTrue(problems.isEmpty(), problems.size() + " problem(s):\n" + String.join("\n", problems));
    }

    /** The cells of output column {@code name} of a generated decision table, one per rule, in rule order. */
    private static List<String> column(String dmn, String name) {
        Matcher out = Pattern.compile("<output id=\"[^\"]*_o(\\d+)\"[^>]* name=\"" + Pattern.quote(name) + "\"")
                .matcher(dmn);
        assertTrue(out.find(), "column " + name);
        Matcher cell = Pattern.compile("<outputEntry id=\"[^\"]*_r\\d+_o" + out.group(1)
                + "\"><text>(.*?)</text></outputEntry>", Pattern.DOTALL).matcher(dmn);
        List<String> cells = new ArrayList<>();
        while (cell.find()) {
            cells.add(cell.group(1));
        }
        return cells;
    }

    @Test
    void theWageRulesAreVersionedAndBlockedUntilVerified() throws IOException {
        Path dir = PACKS.resolve("sec-hr-payroll");
        String dmn = text(dir.resolve("sec-hr-wage-rules.dmn"));
        assertTrue(dmn.contains("<text>date(asOfDate)</text>"), "rules are selected by a date (the wage period day)");
        assertTrue(dmn.contains("hitPolicy=\"UNIQUE\""), "one row in force at a time");
        // decision 288: evidence and use replace the single status column (a correction of round 9, which left the
        // old column name here although the assertion below forbids it)
        for (String col : List.of("ruleId", "ruleVersion", "evidenceStatus", "permittedUse", "evidenceNote",
                "pfWageCeiling", "esiWageLimit",
                "esiContinuation", "esiLowWageDailyLimit", "esiLowWageComparison", "esiContributionPeriodStarts",
                "periodRuleBasis", "source", "provision", "effectiveFrom", "effectiveTo")) {
            assertTrue(dmn.contains("name=\"" + col + "\""), "column " + col);
        }
        // decisions 255 and 256: every demo row takes the rule of the wage period's first day, exempts at or below
        // the daily limit and has the April and October contribution periods
        for (String v : List.of("FIRST_DAY", "AT_OR_BELOW", "04-01,10-01")) {
            assertEquals(2, dmn.split("<text>\"" + v + "\"</text>", -1).length - 1, v + " in both rows");
        }
        assertTrue(!dmn.contains("\"LAST_DAY\"") && !dmn.contains("\"BELOW\""), "no demo override");
        // decision 264: both rows cover a joiner to the end of the contribution period; decision 271: the split by
        // days is gone and both rows define the average daily wage per wage basis, marked UNVERIFIED
        for (String col : List.of("esiMidPeriodJoiner", "esiAverageDailyWage", "esiAverageDailyWageStatus")) {
            assertTrue(dmn.contains("name=\"" + col + "\""), "column " + col);
        }
        assertTrue(!dmn.contains("esiPeriodSplit") && !dmn.contains("\"BY_DAYS\""), "no split by days (decision 271)");
        // decision 295: TIME_RATED is defined by rule 2(1)(h)(i), the full-period wage over the rated days
        for (String v : List.of("COVERED_TO_PERIOD_END", "TIME_RATED=FULL_PERIOD_WAGE_BY_RATING",
                "SYNTHETIC_DEMO_ONLY")) {
            assertEquals(2, dmn.split("<text>\"" + v + "\"</text>", -1).length - 1, v + " in both rows");
        }
        // decision 288: each row's evidence is UNVERIFIED and its use SYNTHETIC_DEMO_ONLY. Owner decision 310
        // (2026-11-13) replaces the round 9 expectation that the average daily wage definition is UNVERIFIED too: that
        // definition alone is VERIFIED, citing rule 2(1)(h)(i) and its effective date; nothing else in a row is.
        assertTrue(dmn.contains("name=\"evidenceStatus\"") && dmn.contains("name=\"permittedUse\"")
                && !dmn.contains("name=\"status\""), "evidence and use are separate columns (decision 288)");
        assertEquals(List.of("\"UNVERIFIED\"", "\"UNVERIFIED\""), column(dmn, "evidenceStatus"),
                "no rules row is VERIFIED until its source text is read");
        assertEquals(List.of("\"SYNTHETIC_DEMO_ONLY\"", "\"SYNTHETIC_DEMO_ONLY\""), column(dmn, "permittedUse"),
                "no rules row is for PRODUCTION (decision 310: the permitted use stays SYNTHETIC_DEMO_ONLY)");
        assertEquals(List.of("\"VERIFIED\"", "\"VERIFIED\""), column(dmn, "esiAverageDailyWageStatus"),
                "decision 310: the average daily wage definition is VERIFIED from rule 2(1)(h)(i)");
        for (String cited : column(dmn, "esiAverageDailyWageSource")) {
            assertTrue(cited.contains("G.S.R. 344(E)") && cited.contains("rule 2(1)(h)(i)")
                    && cited.contains("in force from 2026-05-08") && cited.contains("SYNTHETIC_DEMO_ONLY"), cited);
        }
        assertEquals(2, column(dmn, "esiAverageDailyWageSource").size());
        assertEquals(2, dmn.split("<text>\"UNVERIFIED\"</text>", -1).length - 1, "only the two rows' own evidence");
        assertEquals(2, dmn.split("<text>\"VERIFIED\"</text>", -1).length - 1,
                "VERIFIED is the average daily wage definition of each row and nothing else");
        assertTrue(!dmn.contains("\"PRODUCTION\"<"), "no rules row is for PRODUCTION");
        JsonNode pack = JSON.readTree(dir.resolve("pack.json").toFile());
        List<String> placeholders = new ArrayList<>();
        pack.path("placeholders").forEach(p -> placeholders.add(p.path("decision").asText()));
        assertTrue(placeholders.contains("sec-hr-wage-rules"), "the UNVERIFIED, SYNTHETIC_DEMO_ONLY rules block go-live");
        for (String p : List.of("sec-hr-pf-rounding", "sec-hr-esi-employee-rounding", "sec-hr-esi-employer-rounding")) {
            assertTrue(placeholders.contains(p), p + ": its SYNTHETIC_DEMO_ONLY rows block go-live by the same path");
        }
        String bpmn = text(dir.resolve("sangam-sec-hr-payroll.bpmn"));
        Matcher make = Pattern.compile("<userTask id=\"pr_make\".*?</userTask>", Pattern.DOTALL).matcher(bpmn);
        assertTrue(make.find() && !make.group().contains("grossWages"), "gross wages are not keyed by the maker");
        Matcher gross = MONEY_TASK.matcher(bpmn);
        boolean total = false;
        while (gross.find()) {
            Map<String, String> f = fields(gross.group(1));
            total |= "grossWages".equals(f.get("result")) && "TOTAL".equals(f.get("op"))
                    && "inputsRegister".equals(f.get("items")) && "grossWages".equals(f.get("formula"));
        }
        assertTrue(total, "gross wages are the exact sum of the frozen register (op TOTAL)");
        String rates = text(dir.resolve("sec-hr-statutory.dmn"));
        assertTrue(!rates.contains("pfWageCeiling") && !rates.contains("esiWageLimit"),
                "the ceiling and the limit live only in the versioned rules");
    }

    // ---- a payroll case ------------------------------------------------------------------------------------------

    @BeforeAll
    static void setUp() {
        PackRuntime.reset();
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-payroll-223;DB_CLOSE_DELAY=-1");
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
        PackRuntime.reset();
    }

    @Test
    void aPayrollCaseComputesEveryShareFromTheDemoRegister() throws IOException {
        String bk = "D223/payroll/1";
        // the payroll starts on a timer; the case is started at its first step with the generic bag, whose
        // sim_hris_inputs is the shipped demo register (demo-answers.properties)
        engine.getRuntimeService().createProcessInstanceByKey("sangam-sec-hr-payroll").businessKey(bk)
                .processDefinitionTenantId(PackTestTenant.ID).startBeforeActivity("pr_defaults")
                .setVariables(AllPacksSmokeTest.bag()).execute();
        driver.run(bk, t -> null, 40);
        assertEquals(0, engine.getRuntimeService().createIncidentQuery().count(), "incidents");
        String root = driver.rootId(bk);
        Map<String, Object> v = engine.getRuntimeService().getVariables(root);
        // ASSUMPTIONS, not statutory expected results (decision 288): every amount below follows the shipped rows,
        // which are SYNTHETIC_DEMO_ONLY (PF HALF_UP and ESI CEILING to the rupee, the ceiling, the limit,
        // continuation and the exemption); they check the arithmetic, not what any law requires.
        // wage period 2026-10-01 to 2026-10-31 of the demo register (row demo-pf25k: ceiling 2500000, ESI limit
        // 2100000, continuation CONTRIBUTION_PERIOD, low-wage limit 17600 a day; minor units)
        // E001 1250050, 31 days: PF 12 % = 150006 -> HALF_UP to 100 = 150000 each share; ESI 0.75 % = 9375.375 ->
        //      CEILING 9400, 3.25 % = 40626.625 -> 40700
        // E002 3000000, coverage since 2024-01-15: PF base capped at 2500000 -> 300000 each share; ESI above the
        //      limit but covered when the contribution period began (1 October 2026): 22500 and 97500
        // E003 450000, monthly rated, full-period wage 450000 / 26 = 17307.69 a day <= 17600 (decision 295; the demo
        //      assumes no leave): ESI employee share exempt (0),
        //      employer 14625 -> 14700
        // gross wages 1400050 + 3200000 + 500000 = 5100050, the payroll amount
        assertEquals(5100050L, ((Number) v.get("grossWages")).longValue());
        assertEquals(5100050L, ((Number) v.get("amount")).longValue());
        assertEquals(504000L, v.get("pfEmployeeAmount"));
        assertEquals(504000L, v.get("pfEmployerAmount"));
        assertEquals(1008000L, v.get("pfAmount"));
        assertEquals(31900L, v.get("esiEmployeeAmount"));
        assertEquals(152900L, v.get("esiEmployerAmount"));
        assertEquals(184800L, v.get("esiAmount"));
        assertEquals("2026-10-01", v.get("inputsWagePeriodFrom"));
        assertEquals("2026-10-31", v.get("inputsWagePeriodTo"));
        assertEquals("UNVERIFIED", v.get("pfEmployeeAmountRulesEvidence"));
        assertEquals("SYNTHETIC_DEMO_ONLY", v.get("pfEmployeeAmountRulesUse"));
        // decision 288: the PF rounding row epf-2026-18-5 is UNVERIFIED and SYNTHETIC_DEMO_ONLY
        assertEquals("sec-hr-pf-rounding@epf-2026-18-5", v.get("pfEmployeeAmountPolicy"));
        assertEquals("UNVERIFIED", v.get("pfEmployeeAmountPolicyEvidence"));
        assertEquals("SYNTHETIC_DEMO_ONLY", v.get("pfEmployeeAmountPolicyUse"));
        assertEquals("sec-hr-wage-rules@demo-pf25k", v.get("pfEmployeeAmountRules"));
        // decision 296: the ESI rounding rows are VERIFIED from 8 May 2026 (rule 19(1)(a) and (b) read) but stay
        // SYNTHETIC_DEMO_ONLY, since payroll stays blocked by the unverified ESI rules (decision 298)
        assertEquals("sec-hr-esi-employee-rounding@ss-2026-r19-1-b", v.get("esiEmployeeAmountPolicy"));
        assertEquals("sec-hr-esi-employer-rounding@ss-2026-r19-1-a", v.get("esiEmployerAmountPolicy"));
        assertEquals("VERIFIED", v.get("esiEmployeeAmountPolicyEvidence"));
        assertEquals("SYNTHETIC_DEMO_ONLY", v.get("esiEmployeeAmountPolicyUse"));
        assertEquals("VERIFIED", v.get("esiEmployerAmountPolicyEvidence"));
        assertEquals("SYNTHETIC_DEMO_ONLY", v.get("esiEmployerAmountPolicyUse"));
        assertEquals("UNVERIFIED", v.get("esiEmployeeAmountRulesEvidence"));
        assertTrue(v.get("inputsRegister") instanceof byte[], "the register is a bytes JSON document");
        List<Map<String, Object>> register = JSON.readValue(new String((byte[]) v.get("inputsRegister"),
                StandardCharsets.UTF_8), new TypeReference<>() { });
        long pfEmployee = 0;
        long esiEmployer = 0;
        for (Map<String, Object> e : register) {
            pfEmployee += ((Number) e.get("pfEmployee")).longValue();
            esiEmployer += ((Number) e.get("esiEmployer")).longValue();
        }
        assertEquals(504000L, pfEmployee, "the total is the exact sum of the employees");
        assertEquals(152900L, esiEmployer);
        // decision 272: E001's unrounded amounts and rounding differences stay on the register
        Map<String, Object> e001 = register.stream().filter(e -> "E001".equals(e.get("employeeId"))).findFirst()
                .orElseThrow();
        assertEquals("9375.375", e001.get("esiEmployeeExact"));
        assertEquals("24.625", e001.get("esiEmployeeRoundingDifference"));
        assertEquals("40626.625", e001.get("esiEmployerExact"));
        assertEquals("73.375", e001.get("esiEmployerRoundingDifference"));
        assertTrue(engine.getTaskService().createTaskQuery().processInstanceBusinessKey(bk)
                .taskDefinitionKey("pr_make").count() == 1, "the maker reviews the computed register");
    }
}

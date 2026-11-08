// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.money;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Build guard for SD-398 / SG-20 (money is integer minor units, rounded once per document):
 * <ol>
 *   <li><b>No binary floating point on the money path.</b> A {@code src/main} Java line that mentions an amount-like
 *       name (amount, balance, limit, price, tariff, fee, penalty, interest, total, budget, cost, ...) together with
 *       {@code double}/{@code float} (type, cast, {@code doubleValue()}, {@code Double.parseDouble}) fails;</li>
 *   <li><b>No decimal money columns.</b> A money-named column of the persistence catalogue
 *       ({@code sangam-persistence/tools/generate_changelogs.py}) or of a module {@code *Schema.java} must use the
 *       {@code {money}} token (BIGINT minor units), never {@code {dec}} / {@code {num24_6}} / NUMERIC / DECIMAL;</li>
 *   <li><b>No per-line rounding in billing.</b> {@code setScale} / {@code RoundingMode} may not appear in
 *       {@code sangam-billing/src/main} (the one rounding step is {@code Money.round*}).</li>
 * </ol>
 * Every justified exception is on an allow-list with its reason and exact hit count; a new hit fails, and so does an
 * entry that no longer matches (the lists cannot go stale).
 */
class MoneyGuardTest {

    static final String MONEY_WORDS = "(?i:amount|amt\\b|balance|limit|price|tariff|fee\\b|fees\\b|penalt|interest|"
            + "total|budget|cost|charge|payable|receivable|paise|money|salary|grant|fund|outstanding|arrear|refund|"
            + "collected|expended|allocated|released|committed)";
    static final Pattern MONEY = Pattern.compile(MONEY_WORDS);
    static final Pattern FLOAT = Pattern.compile(
            "\\b(?:double|float|Double|Float)\\b|\\.doubleValue\\s*\\(|\\.floatValue\\s*\\(|parseDouble|parseFloat");
    static final Pattern DECIMAL_TOKEN = Pattern.compile("\\{dec}|\\{num\\d+_\\d+}|NUMERIC\\s*\\(|DECIMAL\\s*\\(|NUMBER\\s*\\(\\d+\\s*,");
    /** Column names that are money in a schema (names, not words in prose). */
    static final Pattern MONEY_COLUMN = Pattern.compile(
            "(?i)^(?:.*_)?(?:amount|amt|balance|balance_after|delta|total|distributed_amt|committed|committed_amt|"
                    + "encumbered|consumed|cost|budget|price|fee|penalty|interest|allocated|released|expended|"
                    + "acquisition_value|residual_value|functional_amount|debit_amt|credit_amt)$");

    /** Rule 1: path -> allowed hits (with reason). */
    static final Map<String, Integer> FLOAT_ALLOWED = new TreeMap<>();
    /** Rule 2: TABLE.column -> reason (decimal column with a money-like name that is NOT money). */
    static final Map<String, String> COLUMN_ALLOWED = new TreeMap<>();

    static {
        // --- rule 1: ratios / scores / telemetry, not money (each a dimensionless or physical double) ---
        FLOAT_ALLOWED.put("sangam-field/src/main/java/org/sangam/field/schedule/RouteOrdering.java", 1);         // route distance total (km)
        FLOAT_ALLOWED.put("sangam-flowable-ports/src/main/java/org/sangam/ports/sla/SlaService.java", 1);         // breach ratio of task counts
        FLOAT_ALLOWED.put("sangam-packs/src/main/java/org/sangam/packs/core/CumulativeThresholdDelegate.java", 1); // utilisation percent (display ratio)
        FLOAT_ALLOWED.put("sangam-programme/src/main/java/org/sangam/programme/period/PeriodService.java", 1);    // elapsed fraction of a period (days)
        FLOAT_ALLOWED.put("sangam-registry/src/main/java/org/sangam/registry/party/PartyMatcher.java", 2);        // match score
        FLOAT_ALLOWED.put("sangam-rest/src/main/java/org/sangam/rest/adapter/jdbc/JdbcReconciliationSupport.java", 1); // outbox backlog % of a count budget
        FLOAT_ALLOWED.put("sangam-rest/src/main/java/org/sangam/rest/adapter/operaton/OperatonInsightsAdapter.java", 1); // AI token cost units (not money)
        FLOAT_ALLOWED.put("sangam-rest/src/main/java/org/sangam/rest/port/InsightsPort.java", 1);                 // AI token cost units / budget (not money)
        FLOAT_ALLOWED.put("sangam-rest/src/main/java/org/sangam/rest/port/OpsPort.java", 1);                      // outbox used percent
        // --- rule 2: decimal columns whose names look like money but are not, or are pending conversion ---
        COLUMN_ALLOWED.put("REG_METER_READING.delta", "meter reading increment (kWh, litres, km), not money");
    }

    static Path root() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("sangam-core"))) {
            p = p.getParent();
        }
        assertTrue(p != null, "repository root (with sangam-core) not found");
        return p;
    }

    static List<Path> mainJava(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(x -> x.toString().endsWith(".java"))
                    .filter(x -> x.toString().replace('\\', '/').contains("/src/main/"))
                    .filter(x -> !x.toString().replace('\\', '/').contains("/target/"))
                    .filter(x -> !x.toString().replace('\\', '/').contains("/node_modules/")).sorted().toList();
        }
    }

    static boolean comment(String code) {
        return code.startsWith("*") || code.startsWith("//") || code.startsWith("/*");
    }

    @Test
    void noFloatingPointOnTheMoneyPath() throws IOException {
        Path root = root();
        Map<String, Integer> found = new TreeMap<>();
        Map<String, List<String>> lines = new TreeMap<>();
        for (Path f : mainJava(root)) {
            String rel = root.relativize(f).toString().replace('\\', '/');
            List<String> src = Files.readAllLines(f);
            for (int i = 0; i < src.size(); i++) {
                String code = src.get(i).strip();
                if (comment(code)) {
                    continue;
                }
                String noStrings = code.replaceAll("\"(?:[^\"\\\\]|\\\\.)*\"", "\"\"");
                if (FLOAT.matcher(noStrings).find() && MONEY.matcher(noStrings).find()) {
                    found.merge(rel, 1, Integer::sum);
                    lines.computeIfAbsent(rel, k -> new ArrayList<>()).add((i + 1) + ": " + code);
                }
            }
        }
        List<String> violations = new ArrayList<>();
        found.forEach((path, n) -> {
            Integer ok = FLOAT_ALLOWED.get(path);
            if (ok == null || n > ok) {
                violations.add(path + " (" + n + " hit(s), allowed " + (ok == null ? 0 : ok) + "): " + lines.get(path));
            }
        });
        assertEquals(List.of(), violations, "Money is org.sangam.core.money.Money (long minor units) or an exact "
                + "BigDecimal, never double/float (SD-398). A justified non-money double goes on MoneyGuardTest.FLOAT_ALLOWED.");
        List<String> stale = new ArrayList<>();
        FLOAT_ALLOWED.forEach((path, n) -> {
            if (!n.equals(found.getOrDefault(path, 0))) {
                stale.add(path + " allowed " + n + ", found " + found.getOrDefault(path, 0));
            }
        });
        assertEquals(List.of(), stale, "stale MoneyGuardTest.FLOAT_ALLOWED entries: tighten them");
    }

    /** ("name", "{token}", ...) tuples of the generator catalogue, with the table they belong to. */
    static final Pattern TABLE = Pattern.compile("\"(\\w+)\",\\s*\\[");
    static final Pattern COLUMN = Pattern.compile("\\(\"(\\w+)\",\\s*\"(\\{\\w+})\"");
    /** Lines of a module DDL text block: {@code name  TYPE ...}. */
    static final Pattern DDL_TABLE = Pattern.compile("CREATE TABLE (\\w+)");
    static final Pattern DDL_COLUMN = Pattern.compile("^\\s*(\\w+)\\s+(\\S.*)$");

    @Test
    void moneyColumnsAreIntegerMinorUnits() throws IOException {
        Path root = root();
        Map<String, String> decimalMoney = new TreeMap<>();
        // the generator catalogue (the five-dialect baseline is generated from it)
        Path gen = root.resolve("sangam-persistence/tools/generate_changelogs.py");
        String table = null;
        for (String line : Files.readAllLines(gen)) {
            Matcher t = TABLE.matcher(line);
            if (t.find() && line.strip().startsWith("(\"0")) {
                table = t.group(1);
            }
            Matcher c = COLUMN.matcher(line);
            while (table != null && c.find()) {
                if (MONEY_COLUMN.matcher(c.group(1)).matches() && DECIMAL_TOKEN.matcher(c.group(2)).find()) {
                    decimalMoney.put(table + "." + c.group(1), gen.getFileName() + " " + c.group(2));
                }
            }
        }
        // module-owned portable DDL (*Schema.java), copied into the catalogue by import_module_schemas.py
        for (Path f : mainJava(root)) {
            if (!f.getFileName().toString().endsWith("Schema.java")) {
                continue;
            }
            String ddlTable = null;
            for (String line : Files.readAllLines(f)) {
                Matcher t = DDL_TABLE.matcher(line);
                if (t.find()) {
                    ddlTable = t.group(1);
                    continue;
                }
                Matcher c = DDL_COLUMN.matcher(line);
                if (ddlTable != null && c.matches() && MONEY_COLUMN.matcher(c.group(1)).matches()
                        && DECIMAL_TOKEN.matcher(c.group(2)).find()) {
                    decimalMoney.put(ddlTable + "." + c.group(1), f.getFileName() + " " + c.group(2).strip());
                }
            }
        }
        List<String> violations = new ArrayList<>();
        decimalMoney.forEach((col, where) -> {
            if (!COLUMN_ALLOWED.containsKey(col)) {
                violations.add(col + " (" + where + ")");
            }
        });
        assertEquals(List.of(), violations, "money columns must be {money} = BIGINT minor units + a currency (SD-398); "
                + "a decimal column that is not money goes on MoneyGuardTest.COLUMN_ALLOWED with its reason");
        List<String> stale = new ArrayList<>();
        COLUMN_ALLOWED.keySet().forEach(col -> {
            if (!decimalMoney.containsKey(col)) {
                stale.add(col);
            }
        });
        assertEquals(List.of(), stale, "stale MoneyGuardTest.COLUMN_ALLOWED entries: remove them");
    }

    @Test
    void billingRoundsOnlyThroughMoney() throws IOException {
        Path root = root();
        List<String> hits = new ArrayList<>();
        for (Path f : mainJava(root)) {
            String rel = root.relativize(f).toString().replace('\\', '/');
            if (!rel.startsWith("sangam-billing/")) {
                continue;
            }
            List<String> src = Files.readAllLines(f);
            for (int i = 0; i < src.size(); i++) {
                String code = src.get(i).strip();
                if (!comment(code) && code.matches(".*(\\.setScale\\s*\\(|\\bRoundingMode\\.(?:HALF|UP|DOWN|CEILING|FLOOR)).*")) {
                    hits.add(rel + ":" + (i + 1) + ": " + code);
                }
            }
        }
        assertEquals(List.of(), hits, "billing must not round per line; round the document once via Money.roundDocument");
    }

    @Test
    void patternsCatchTheForbiddenForms() {
        for (String s : List.of("double amount = 1;", "float fee;", "x.amount().doubleValue()", "Double.parseDouble(total)",
                "(double) balance")) {
            assertTrue(FLOAT.matcher(s).find() && MONEY.matcher(s).find(), s);
        }
        assertFalse(FLOAT.matcher("long amountMinor = 1;").find());
        for (String c : List.of("amount", "balance_after", "committed_amt", "functional_amount", "total")) {
            assertTrue(MONEY_COLUMN.matcher(c).matches(), c);
        }
        assertFalse(MONEY_COLUMN.matcher("weight").matches());
        assertTrue(DECIMAL_TOKEN.matcher("{num24_6}").find());
        assertTrue(DECIMAL_TOKEN.matcher("NUMERIC(24,6) NOT NULL").find());
        assertFalse(DECIMAL_TOKEN.matcher("{money}").find());
    }
}

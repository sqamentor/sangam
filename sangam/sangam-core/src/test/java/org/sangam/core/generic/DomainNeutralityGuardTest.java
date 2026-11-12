// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.generic;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * SL-104 guard (ADR-D-20 generic platform): product Java carries no country default. Over every reactor module's
 * {@code src/main/java}, comments stripped, it finds IANA zone ids, fixed non-UTC offsets, ISO 4217 currency literals,
 * country defaults ({@code getProperty(key, "XX")}, {@code xx-YY} tags), zone abbreviations and Indian or US system
 * names. Protocol adapters are allow-listed ({@code connector/gov}, {@code sim/}, {@code sangam-connectors-health}:
 * ARCHITECTURE.md, sangam-integration connectors with simulator profiles). Pending items sit on a shrink-only ratchet
 * with their reason: a new hit fails, and so does a ratchet entry whose hit is gone (remove it). Equivalent standalone
 * scan for evidence without a test run: {@code domain_neutrality_scan.py} (same lexer, categories and lists).
 * Run from sangam-core, so the reactor root is the parent folder.
 */
class DomainNeutralityGuardTest {

    private static final Pattern ZONE_ID = Pattern.compile(
            "^(Africa|America|Antarctica|Arctic|Asia|Atlantic|Australia|Europe|Indian|Pacific)/[A-Za-z0-9_+\\-/]+$");
    private static final Pattern OFFSET_CALL = Pattern.compile(
            "ZoneOffset\\.of(Hours|HoursMinutes|HoursMinutesSeconds|TotalSeconds)\\(\\s*-?\\s*[1-9]");
    private static final Pattern OFFSET_LITERAL = Pattern.compile("^[+-](0\\d|1[0-8]):?[0-5]\\d$");
    private static final Pattern PROPERTY_DEFAULT = Pattern.compile("getProperty\\(\\s*\"[^\"]*\"\\s*,\\s*\"([A-Z]{2})\"\\s*\\)");
    private static final Pattern LANGUAGE_TAG = Pattern.compile("^[a-z]{2,3}[-_]([A-Z]{2})$");
    private static final Pattern TZ_ABBREV = Pattern.compile("\\b(IST|EST|PST|CST|EDT|PDT)\\b");
    private static final Pattern SYSTEM_NAMES = Pattern.compile(
            "(?i)\\b(aadhaar|gstin|upi|npci|pfms|ifms|digilocker|limbs|cctns|ecourts|umang|ssn|irs|medicare|medicaid)\\b");
    /**
     * ISO 4217 codes that are not a country default: "ALL" is the English word (also the Albanian lek); "XXX" means
     * "no currency involved" (a neutral default) and "XTS" is the code reserved for testing.
     */
    private static final Set<String> NOT_A_CURRENCY = Set.of("ALL", "XXX", "XTS");

    /** Pending items: "path|category" -> reason. Shrink-only; empty since SL-104 Q6a moved the last ones to pack data. */
    private static final Map<String, String> RATCHET = Map.of();

    record Hit(String path, String category, int line, String text) {
        String key() {
            return path + "|" + category;
        }
    }

    record Lexed(String code, List<int[]> literalLines, List<String> literals) {
    }

    @Test
    void productJavaCarriesNoCountryDefault() throws IOException {
        Path root = Path.of("..").toAbsolutePath().normalize();
        Set<String> currencies = Currency.getAvailableCurrencies().stream().map(Currency::getCurrencyCode)
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> countries = new TreeSet<>(Arrays.asList(Locale.getISOCountries()));
        List<Hit> hits = new ArrayList<>();
        int files = 0;
        List<Path> modules;
        try (Stream<Path> s = Files.list(root)) {
            modules = s.filter(p -> p.getFileName().toString().startsWith("sangam-") && Files.isDirectory(p)).sorted().toList();
        }
        for (Path m : modules) {
            Path base = m.resolve("src/main/java");
            if (!Files.isDirectory(base)) {
                continue;
            }
            List<Path> javaFiles;
            try (Stream<Path> s = Files.walk(base)) {
                javaFiles = s.filter(p -> p.toString().endsWith(".java"))
                        .filter(p -> noTargetDir(base, p)).sorted().toList();
            }
            for (Path p : javaFiles) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                files++;
                if (allowListed(rel)) {
                    continue;
                }
                scanFile(rel, Files.readString(p, StandardCharsets.UTF_8), currencies, countries, hits);
            }
        }
        assertTrue(files > 1000, "the scan must see the reactor's product sources; saw " + files + " under " + root);
        Set<String> seen = new LinkedHashSet<>();
        List<String> violations = new ArrayList<>();
        for (Hit h : hits) {
            seen.add(h.key());
            if (!RATCHET.containsKey(h.key())) {
                violations.add(h.category() + " " + h.path() + ":" + h.line() + "  |  " + h.text());
            }
        }
        List<String> stale = RATCHET.keySet().stream().filter(k -> !seen.contains(k)).sorted().toList();
        assertTrue(violations.isEmpty(), "SL-104: country defaults in product Java (use the tenant profile or"
                + " configuration, fail loudly when missing):\n" + String.join("\n", violations));
        assertTrue(stale.isEmpty(), "SL-104 ratchet entries that no longer match (fixed: remove them): " + stale);
    }

    private static boolean noTargetDir(Path base, Path p) {
        for (Path part : base.relativize(p)) {
            if (part.toString().startsWith("target")) {
                return false;
            }
        }
        return true;
    }

    static boolean allowListed(String rel) {
        return rel.contains("/connector/gov/") || rel.contains("/sim/") || rel.startsWith("sangam-connectors-health/");
    }

    static void scanFile(String rel, String text, Set<String> currencies, Set<String> countries, List<Hit> hits) {
        Lexed lx = lex(text);
        for (int i = 0; i < lx.literals().size(); i++) {
            String v = lx.literals().get(i);
            int ln = lx.literalLines().get(i)[0];
            if (ZONE_ID.matcher(v).matches()) {
                hits.add(new Hit(rel, "zone-id", ln, v));
            }
            if (OFFSET_LITERAL.matcher(v).matches() && !Set.of("+0000", "-0000").contains(v.replace(":", ""))) {
                hits.add(new Hit(rel, "fixed-offset", ln, v));
            }
            if (currencies.contains(v) && !NOT_A_CURRENCY.contains(v)) {
                hits.add(new Hit(rel, "currency", ln, v));
            }
            Matcher t = LANGUAGE_TAG.matcher(v);
            if (t.matches() && countries.contains(t.group(1))) {
                hits.add(new Hit(rel, "country", ln, v));
            }
        }
        String[] lines = lx.code().split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int no = i + 1;
            if (OFFSET_CALL.matcher(line).find()) {
                hits.add(new Hit(rel, "fixed-offset", no, line.strip()));
            }
            Matcher pd = PROPERTY_DEFAULT.matcher(line);
            while (pd.find()) {
                if (countries.contains(pd.group(1))) {
                    hits.add(new Hit(rel, "country", no, line.strip()));
                }
            }
            if (TZ_ABBREV.matcher(line).find()) {
                hits.add(new Hit(rel, "tz-abbrev", no, line.strip()));
            }
            if (SYSTEM_NAMES.matcher(line).find()) {
                hits.add(new Hit(rel, "system-name", no, line.strip()));
            }
        }
    }

    /** Code with comments blanked (newlines kept), plus the string literals and their start lines. */
    static Lexed lex(String text) {
        StringBuilder out = new StringBuilder(text.length());
        List<String> lits = new ArrayList<>();
        List<int[]> litLines = new ArrayList<>();
        int i = 0;
        int n = text.length();
        int line = 1;
        while (i < n) {
            char ch = text.charAt(i);
            if (text.startsWith("//", i)) {
                int j = text.indexOf('\n', i);
                j = j < 0 ? n : j;
                out.append(" ".repeat(j - i));
                i = j;
            } else if (text.startsWith("/*", i)) {
                int j = text.indexOf("*/", i + 2);
                j = j < 0 ? n : j + 2;
                String seg = text.substring(i, j);
                out.append(seg.replaceAll("[^\n]", " "));
                line += count(seg);
                i = j;
            } else if (text.startsWith("\"\"\"", i)) {
                int j = text.indexOf("\"\"\"", i + 3);
                j = j < 0 ? n : j + 3;
                String seg = text.substring(i, j);
                lits.add(seg.length() >= 6 ? seg.substring(3, seg.length() - 3) : "");
                litLines.add(new int[]{line});
                out.append(seg);
                line += count(seg);
                i = j;
            } else if (ch == '"') {
                int j = i + 1;
                StringBuilder buf = new StringBuilder();
                while (j < n && text.charAt(j) != '"' && text.charAt(j) != '\n') {
                    if (text.charAt(j) == '\\' && j + 1 < n) {
                        buf.append(text, j, j + 2);
                        j += 2;
                    } else {
                        buf.append(text.charAt(j));
                        j++;
                    }
                }
                j = Math.min(j + 1, n);
                lits.add(buf.toString());
                litLines.add(new int[]{line});
                out.append(text, i, j);
                i = j;
            } else if (ch == '\'') {
                int j = i + 1;
                while (j < n && text.charAt(j) != '\'' && text.charAt(j) != '\n') {
                    j += text.charAt(j) == '\\' ? 2 : 1;
                }
                j = Math.min(j + 1, n);
                out.append(text, i, j);
                i = j;
            } else {
                if (ch == '\n') {
                    line++;
                }
                out.append(ch);
                i++;
            }
        }
        return new Lexed(out.toString(), litLines, lits);
    }

    private static int count(String s) {
        int c = 0;
        for (int k = 0; k < s.length(); k++) {
            if (s.charAt(k) == '\n') {
                c++;
            }
        }
        return c;
    }

    /** The guard itself must catch every category (so the empty result above means something). */
    @Test
    void theScanCatchesEachCategory() {
        Set<String> cur = Set.of("INR", "USD", "ALL", "XXX");
        Set<String> cty = Set.of("IN", "US");
        String src = """
                class X {
                    ZoneId z = ZoneId.of("Asia/Kolkata");
                    LocalDate d = LocalDate.ofInstant(now, ZoneOffset.ofHoursMinutes(5, 30));
                    String c = "INR";
                    String scope = env.getProperty("k", "IN");
                    String tag = "hi-IN";
                    static final ZoneId IST = z;
                    String conn = "digilocker";
                    String quiet = "ALL"; String none = "XXX"; // Asia/Kolkata INR pfms in a comment
                    /* "Asia/Kolkata" "USD" IST in a block comment */
                }
                """;
        List<Hit> hits = new ArrayList<>();
        scanFile("sangam-x/src/main/java/X.java", src, cur, cty, hits);
        Map<String, Integer> byCategory = new LinkedHashMap<>();
        hits.forEach(h -> byCategory.merge(h.category(), 1, Integer::sum));
        assertTrue(byCategory.keySet().containsAll(Set.of("zone-id", "fixed-offset", "currency", "country", "tz-abbrev",
                "system-name")), "every category found: " + byCategory);
        assertTrue(hits.stream().noneMatch(h -> h.line() >= 10), "comments are ignored: " + hits);
        assertTrue(hits.stream().noneMatch(h -> h.text().equals("ALL") || h.text().equals("XXX")), "neutral codes: " + hits);
        assertTrue(allowListed("sangam-integration/src/main/java/org/sangam/integration/connector/gov/EsignConnector.java"));
        assertTrue(allowListed("sangam-packs/src/main/java/org/sangam/packs/sim/WorkingDayCalendar.java"));
        assertTrue(allowListed("sangam-connectors-health/src/main/java/org/sangam/connectors/health/fhir/Fhir.java"));
    }
}
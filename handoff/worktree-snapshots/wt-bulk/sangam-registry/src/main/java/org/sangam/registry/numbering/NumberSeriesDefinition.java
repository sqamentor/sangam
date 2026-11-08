// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry.numbering;

import org.sangam.registry.RegistryException;
import org.sangam.registry.support.Codes;
import org.sangam.registry.support.Maps;

import java.time.LocalDate;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A number series, as configuration:
 * <pre>{@code
 * { "seriesKey": "TL", "pattern": "{prefix}/{scope}/{fy}/{seq:6}{check}", "prefix": "TL",
 *   "gapPolicy": "GAP_FREE", "reset": "FISCAL_YEAR", "fiscalStartMonth": 4, "start": 1, "check": "LUHN" }
 * }</pre>
 * <ul>
 *   <li>{@code gapPolicy}: {@code GAP_FREE} (one locked counter row per series, scope and period; every number is
 *       recorded, voided numbers keep their row and reason, so the sequence has no unexplained gap) or
 *       {@code UNIQUE_ONLY} (numbers are unique but may skip; allocated from cached blocks without a row lock);</li>
 *   <li>{@code reset}: {@code NEVER}, {@code YEARLY}, {@code FISCAL_YEAR}, {@code MONTHLY}, {@code DAILY};</li>
 *   <li>pattern tokens: {@code {seq}} / {@code {seq:W}} (zero-padded to W), {@code {prefix}}, {@code {scope}},
 *       {@code {yyyy}}, {@code {yy}}, {@code {MM}}, {@code {dd}}, {@code {fy}} (e.g. {@code 2026-27}),
 *       {@code {fyStart}}, {@code {period}}, {@code {attr:name}} (a value supplied at allocation) and {@code {check}}
 *       (check characters of everything before it: {@code LUHN} one digit, {@code MOD97} two digits).</li>
 * </ul>
 */
public record NumberSeriesDefinition(String seriesKey, String pattern, String prefix, String gapPolicy, String reset,
                                     int fiscalStartMonth, long start, int blockSize, String check, Long maxValue) {

    private static final Pattern TOKEN = Pattern.compile("\\{([A-Za-z]+)(?::([A-Za-z0-9_.-]+))?}");

    public static NumberSeriesDefinition from(Map<String, Object> m) {
        String key = Maps.required(m, "seriesKey");
        if (key.length() > 64) {
            throw RegistryException.invalid("seriesKey longer than 64 characters");
        }
        String gp = Maps.str(m, "gapPolicy", "GAP_FREE").toUpperCase();
        if (!gp.equals("GAP_FREE") && !gp.equals("UNIQUE_ONLY")) {
            throw RegistryException.invalid("gapPolicy must be GAP_FREE or UNIQUE_ONLY");
        }
        String reset = Maps.str(m, "reset", "NEVER").toUpperCase();
        if (!java.util.Set.of("NEVER", "YEARLY", "FISCAL_YEAR", "MONTHLY", "DAILY").contains(reset)) {
            throw RegistryException.invalid("unknown reset " + reset);
        }
        String pattern = Maps.str(m, "pattern", "{prefix}{seq:6}");
        if (!pattern.contains("{seq")) {
            throw RegistryException.invalid("pattern must contain {seq} or {seq:W}");
        }
        int fsm = Maps.integer(m, "fiscalStartMonth", 1);
        if (fsm < 1 || fsm > 12) {
            throw RegistryException.invalid("fiscalStartMonth must be 1..12");
        }
        return new NumberSeriesDefinition(key, pattern, Maps.str(m, "prefix", ""), gp, reset, fsm,
                Maps.lng(m, "start") == null ? 1L : Maps.lng(m, "start"), Maps.integer(m, "blockSize", 50),
                Maps.str(m, "check", "NONE").toUpperCase(), Maps.lng(m, "maxValue"));
    }

    public Map<String, Object> toMap() {
        return Maps.of("seriesKey", seriesKey, "pattern", pattern, "prefix", prefix, "gapPolicy", gapPolicy,
                "reset", reset, "fiscalStartMonth", fiscalStartMonth, "start", start, "blockSize", blockSize,
                "check", check, "maxValue", maxValue);
    }

    public boolean gapFree() {
        return "GAP_FREE".equals(gapPolicy);
    }

    /** The counter period of a date. */
    public String periodKey(LocalDate d) {
        return switch (reset) {
            case "YEARLY" -> Integer.toString(d.getYear());
            case "FISCAL_YEAR" -> "FY" + fiscalStartYear(d);
            case "MONTHLY" -> d.toString().substring(0, 7);
            case "DAILY" -> d.toString();
            default -> "-";
        };
    }

    public int fiscalStartYear(LocalDate d) {
        return d.getMonthValue() >= fiscalStartMonth ? d.getYear() : d.getYear() - 1;
    }

    /** Formats one number. */
    public String format(long seq, String scope, LocalDate d, Map<String, ?> attrs) {
        int fy = fiscalStartYear(d);
        String fyLabel = fiscalStartMonth == 1 ? Integer.toString(fy)
                : fy + "-" + String.format("%02d", (fy + 1) % 100);
        Matcher m = TOKEN.matcher(pattern);
        StringBuilder out = new StringBuilder();
        int lastEnd = 0;
        while (m.find()) {
            String tok = m.group(1);
            String arg = m.group(2);
            String before = out + pattern.substring(lastEnd, m.start());
            lastEnd = m.end();
            String rep = switch (tok) {
                case "seq" -> arg == null ? Long.toString(seq) : String.format("%0" + Integer.parseInt(arg) + "d", seq);
                case "prefix" -> prefix;
                case "scope" -> scope == null ? "" : scope;
                case "yyyy" -> Integer.toString(d.getYear());
                case "yy" -> String.format("%02d", d.getYear() % 100);
                case "MM" -> String.format("%02d", d.getMonthValue());
                case "dd" -> String.format("%02d", d.getDayOfMonth());
                case "fy" -> fyLabel;
                case "fyStart" -> Integer.toString(fy);
                case "period" -> periodKey(d);
                case "attr" -> {
                    Object v = attrs == null || arg == null ? null : attrs.get(arg);
                    if (v == null) {
                        throw RegistryException.invalid("number pattern needs attribute " + arg);
                    }
                    yield v.toString();
                }
                case "check" -> switch (check) {
                    case "LUHN" -> Integer.toString(Codes.luhn(before));
                    case "MOD97" -> Codes.mod97(before);
                    default -> "";
                };
                default -> throw RegistryException.invalid("unknown number pattern token {" + tok + "}");
            };
            m.appendReplacement(out, Matcher.quoteReplacement(rep));
        }
        m.appendTail(out);
        if (out.length() > 255) {
            throw RegistryException.invalid("formatted number longer than 255 characters");
        }
        return out.toString();
    }
}

// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.catalogue;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Number series (document numbers, tokens, voucher numbers): unique per (scope, series, unit, reset period), with the
 * reset rule and format from configuration, e.g. a token unique per office per day
 * ({@code {id: TOKEN, reset: DAILY, format: "{unit}/{yyyyMMdd}/{n:5}"}}). The JDBC implementation allocates under a
 * row lock so numbers are never duplicated across nodes.
 */
public interface NumberSeries {

    enum Reset { NEVER, DAILY, MONTHLY, FISCAL_YEAR }

    /** Allocates the next number of the series for the unit on the date. */
    long next(String scope, String series, String unit, String period);

    static String period(Reset reset, LocalDate d, int fiscalStartYear) {
        return switch (reset) {
            case NEVER -> "-";
            case DAILY -> d.toString();
            case MONTHLY -> d.toString().substring(0, 7);
            case FISCAL_YEAR -> Integer.toString(fiscalStartYear);
        };
    }

    /** Formats {@code {unit}}, {@code {period}}, {@code {yyyyMMdd}} and {@code {n:W}} (zero-padded to W). */
    static String format(String pattern, String unit, String period, LocalDate d, long n) {
        String out = pattern.replace("{unit}", unit).replace("{period}", period)
                .replace("{yyyyMMdd}", d.toString().replace("-", ""));
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{n(?::(\\d+))?}").matcher(out);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            int w = m.group(1) == null ? 1 : Integer.parseInt(m.group(1));
            m.appendReplacement(b, String.format("%0" + w + "d", n));
        }
        m.appendTail(b);
        return b.toString();
    }

    /** In-memory series (single node). */
    final class InMemory implements NumberSeries {
        private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

        @Override
        public long next(String scope, String series, String unit, String period) {
            return counters.computeIfAbsent(scope + "|" + series + "|" + unit + "|" + period, k -> new AtomicLong())
                    .incrementAndGet();
        }
    }
}

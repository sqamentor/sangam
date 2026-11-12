// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.time;

import java.time.LocalDate;
import java.util.Arrays;

/**
 * The one fiscal-year calculation of Sangam (X1-03, SL-164: it was copied in four modules). A fiscal year is named by
 * the calendar year in which it starts, and starts on {@code startDay} of {@code startMonth}. No start is assumed
 * (SL-125): every caller passes the start it was configured with, from its own source (the tenant profile's
 * {@code locale.fiscal} in sangam-config, the explicit {@code fiscalStartMonth} fields in sangam-registry and
 * sangam-programme, which have no start day and so pass 1).
 *
 * <p><b>Labels, one documented format per purpose</b> (every label below is byte-identical to what each module
 * emitted before X1-03):
 * <ul>
 *   <li>tenant fiscal year (profile {@code locale.fiscal.label}, ledger and billing periods): the configured
 *       {@link LabelFormat};</li>
 *   <li>number series token {@code {fy}}: {@link LabelFormat#START_END2}; token {@code {fyStart}}: {@link LabelFormat#START};
 *       counter period of a {@code FISCAL_YEAR} reset: {@code "FY"} + {@link LabelFormat#START};</li>
 *   <li>programme reporting year ({@code PG_PERIOD.fiscal_year}): {@code "FY"} + {@link LabelFormat#START_END2}.</li>
 * </ul>
 */
public final class FiscalYears {

    private FiscalYears() {
    }

    /** Label formats of a fiscal year, by their configuration code. */
    public enum LabelFormat {
        /** {@code 2026-27}; a year that ends in the year it starts (January 1 start) is just {@code 2026}. */
        START_END2("START-END2"),
        /** The start year: {@code 2026}. */
        START("START"),
        /** The end year: {@code 2027} (FY2027 written as 2027). */
        END("END"),
        /** {@code 2026/2027}. */
        START_SLASH_END("START/END");

        private final String code;

        LabelFormat(String code) {
            this.code = code;
        }

        /** The configuration code ({@code START-END2}, {@code START}, {@code END}, {@code START/END}). */
        public String code() {
            return code;
        }

        /** The format with this configuration code; {@link IllegalArgumentException} for an unknown one. */
        public static LabelFormat of(String code) {
            return Arrays.stream(values()).filter(f -> f.code.equals(code)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("unknown fiscal label format " + code));
        }
    }

    /** Refuses a start outside month 1..12, day 1..28 (day 28 at most, so every year has the start day). */
    public static void requireStart(int startMonth, int startDay) {
        if (startMonth < 1 || startMonth > 12 || startDay < 1 || startDay > 28) {
            throw new IllegalArgumentException("fiscal start must be month 1..12, day 1..28");
        }
    }

    /** The start year of the fiscal year that contains {@code d}. */
    public static int startYearOf(LocalDate d, int startMonth, int startDay) {
        requireStart(startMonth, startDay);
        return d.isBefore(LocalDate.of(d.getYear(), startMonth, startDay)) ? d.getYear() - 1 : d.getYear();
    }

    /** First day of the fiscal year that starts in {@code startYear}. */
    public static LocalDate start(int startYear, int startMonth, int startDay) {
        requireStart(startMonth, startDay);
        return LocalDate.of(startYear, startMonth, startDay);
    }

    /** Last day (inclusive) of the fiscal year that starts in {@code startYear}. */
    public static LocalDate end(int startYear, int startMonth, int startDay) {
        return start(startYear, startMonth, startDay).plusYears(1).minusDays(1);
    }

    /** Last day (inclusive) of the fiscal year that contains {@code d}. */
    public static LocalDate endOfYearContaining(LocalDate d, int startMonth, int startDay) {
        return end(startYearOf(d, startMonth, startDay), startMonth, startDay);
    }

    /** The label of the fiscal year that starts in {@code startYear}, in {@code format}. */
    public static String label(int startYear, int startMonth, int startDay, LabelFormat format) {
        int endYear = end(startYear, startMonth, startDay).getYear();
        return switch (format) {
            case START -> Integer.toString(startYear);
            case END -> Integer.toString(endYear);
            case START_SLASH_END -> startYear + "/" + endYear;
            case START_END2 -> startYear == endYear ? Integer.toString(startYear)
                    : startYear + "-" + String.format("%02d", endYear % 100);
        };
    }
}

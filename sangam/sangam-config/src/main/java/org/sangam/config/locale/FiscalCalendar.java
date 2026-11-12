// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.locale;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;

import org.sangam.core.time.FiscalYears;

/**
 * A fiscal-year calendar from configuration: any start month and day, any time zone, any label pattern. Examples:
 * April-March (start month 4), January-December (1), July-June (7), October-September (10).
 *
 * @param startMonth  1..12
 * @param startDay    1..28
 * @param zone        the zone of every cut-off and closing instant
 * @param labelFormat {@code START-END2} (2026-27), {@code START} (2026), {@code END} (FY2027 written as 2027),
 *                    {@code START/END} (2026/2027)
 * @param adjustmentPeriods extra closing periods after period 12 (13, 14 ...), e.g. supplementary March cuts or
 *                    audit-adjustment periods (PFM-074); labelled {@code <year>/P13}
 */
public record FiscalCalendar(int startMonth, int startDay, ZoneId zone, String labelFormat, int adjustmentPeriods) {

    /** The label formats {@link FiscalYear#label()} knows. */
    public static final java.util.Set<String> LABEL_FORMATS = java.util.Arrays.stream(FiscalYears.LabelFormat.values())
            .map(FiscalYears.LabelFormat::code).collect(java.util.stream.Collectors.toUnmodifiableSet());

    /**
     * SL-125 (fail closed): no part of a fiscal calendar is assumed. The start month, start day and label format are
     * always stated; only the zone may be inherited (from the locale zone, by {@link #from}).
     */
    public FiscalCalendar {
        FiscalYears.requireStart(startMonth, startDay);
        if (zone == null) {
            throw new IllegalArgumentException("fiscal calendar needs a zone");
        }
        if (labelFormat == null || !LABEL_FORMATS.contains(labelFormat)) {
            throw new IllegalArgumentException("fiscal label must be one of " + new java.util.TreeSet<>(LABEL_FORMATS)
                    + ", was " + labelFormat);
        }
        if (adjustmentPeriods < 0 || adjustmentPeriods > 4) {
            throw new IllegalArgumentException("adjustment periods must be 0..4");
        }
    }

    public FiscalCalendar(int startMonth, int startDay, ZoneId zone, String labelFormat) {
        this(startMonth, startDay, zone, labelFormat, 0);
    }

    /**
     * From a profile map {@code {startMonth: 4, startDay: 1, zone: Asia/Kolkata, label: START-END2}}: empty when the map
     * is absent or empty (no fiscal year configured; never a calendar-year default, SL-125). Inside a present map
     * {@code startMonth}, {@code startDay} and {@code label} are required ({@link IllegalArgumentException} naming the
     * path); {@code zone} defaults to {@code defaultZone} (the locale zone) and {@code adjustmentPeriods} to 0 (none).
     */
    public static java.util.Optional<FiscalCalendar> from(Map<String, Object> m, ZoneId defaultZone) {
        if (m == null || m.isEmpty()) {
            return java.util.Optional.empty();
        }
        Object z = m.get("zone");
        return java.util.Optional.of(new FiscalCalendar(required(m, "startMonth"), required(m, "startDay"),
                z == null ? defaultZone : ZoneId.of(z.toString()), requiredText(m, "label"),
                Integer.parseInt(String.valueOf(m.getOrDefault("adjustmentPeriods", 0)))));
    }

    private static int required(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new IllegalArgumentException("locale.fiscal." + key + " is required");
        }
        try {
            return Integer.parseInt(v.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("locale.fiscal." + key + " must be a whole number, was '" + v + "'");
        }
    }

    private static String requiredText(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new IllegalArgumentException("locale.fiscal." + key + " is required");
        }
        return v.toString().trim();
    }

    /** The fiscal year containing a date, identified by the calendar year in which it starts ({@link FiscalYears}). */
    public FiscalYear yearOf(LocalDate d) {
        return new FiscalYear(this, FiscalYears.startYearOf(d, startMonth, startDay));
    }

    public FiscalYear yearOf(Instant i) {
        return yearOf(LocalDate.ofInstant(i, zone));
    }

    public FiscalYear year(int startYear) {
        return new FiscalYear(this, startYear);
    }

    /** Parses a label in this calendar's format, or a plain start year. */
    public FiscalYear parse(String label) {
        String s = label.trim();
        int sep = s.indexOf('-') > 0 ? s.indexOf('-') : s.indexOf('/');
        int y = Integer.parseInt(sep > 0 ? s.substring(0, sep) : s.replaceAll("\\D", ""));
        return new FiscalYear(this, "END".equals(labelFormat) && sep < 0 && startMonth != 1 ? y - 1 : y);
    }

    /** One fiscal year of a calendar. */
    public record FiscalYear(FiscalCalendar calendar, int startYear) implements Comparable<FiscalYear> {

        public LocalDate start() {
            return FiscalYears.start(startYear, calendar.startMonth, calendar.startDay);
        }

        public LocalDate end() {
            return FiscalYears.end(startYear, calendar.startMonth, calendar.startDay);
        }

        public boolean contains(LocalDate d) {
            return !d.isBefore(start()) && !d.isAfter(end());
        }

        /** Period index 1..12 (month of the fiscal year). */
        public int period(LocalDate d) {
            int idx = (d.getYear() - startYear) * 12 + d.getMonthValue() - calendar.startMonth + 1;
            if (d.getDayOfMonth() < calendar.startDay) {
                idx--;
            }
            return Math.max(1, Math.min(12, idx));
        }

        /** Quarter 1..4. */
        public int quarter(LocalDate d) {
            return (period(d) - 1) / 3 + 1;
        }

        /** The instant at wall time {@code at} on the last day of the year, in the calendar zone. */
        public Instant closingInstant(LocalTime at) {
            return LocalDateTime.of(end(), at).atZone(calendar.zone).toInstant();
        }

        /** The instant at wall time {@code at} on a day of this year (cut-offs). */
        public Instant at(LocalDate day, LocalTime at) {
            return LocalDateTime.of(day, at).atZone(calendar.zone).toInstant();
        }

        /** Period labels of the year: {@code <year>/P01} ... {@code <year>/P12} and the adjustment periods. */
        public java.util.List<String> periodLabels() {
            java.util.List<String> out = new java.util.ArrayList<>();
            for (int i = 1; i <= 12 + calendar.adjustmentPeriods; i++) {
                out.add(label() + "/P" + String.format("%02d", i));
            }
            return out;
        }

        public FiscalYear next() {
            return new FiscalYear(calendar, startYear + 1);
        }

        public FiscalYear previous() {
            return new FiscalYear(calendar, startYear - 1);
        }

        /** The label in the calendar's format ({@link FiscalYears#label}). */
        public String label() {
            return FiscalYears.label(startYear, calendar.startMonth, calendar.startDay,
                    FiscalYears.LabelFormat.of(calendar.labelFormat));
        }

        @Override
        public String toString() {
            return label();
        }

        @Override
        public int compareTo(FiscalYear o) {
            return Integer.compare(startYear, o.startYear);
        }
    }
}

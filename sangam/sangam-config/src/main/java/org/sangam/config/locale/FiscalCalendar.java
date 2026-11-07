// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.locale;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;

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

    public FiscalCalendar {
        if (startMonth < 1 || startMonth > 12 || startDay < 1 || startDay > 28) {
            throw new IllegalArgumentException("fiscal start must be month 1..12, day 1..28");
        }
        zone = zone == null ? ZoneId.of("UTC") : zone;
        labelFormat = labelFormat == null ? "START-END2" : labelFormat;
        if (adjustmentPeriods < 0 || adjustmentPeriods > 4) {
            throw new IllegalArgumentException("adjustment periods must be 0..4");
        }
    }

    public FiscalCalendar(int startMonth, int startDay, ZoneId zone, String labelFormat) {
        this(startMonth, startDay, zone, labelFormat, 0);
    }

    public static FiscalCalendar calendarYear(ZoneId zone) {
        return new FiscalCalendar(1, 1, zone, "START");
    }

    /** From a profile map {@code {startMonth: 4, startDay: 1, zone: Asia/Kolkata, label: START-END2}}. */
    public static FiscalCalendar from(Map<String, Object> m, ZoneId defaultZone) {
        if (m == null || m.isEmpty()) {
            return calendarYear(defaultZone);
        }
        Object z = m.get("zone");
        return new FiscalCalendar(Integer.parseInt(String.valueOf(m.getOrDefault("startMonth", 1))),
                Integer.parseInt(String.valueOf(m.getOrDefault("startDay", 1))),
                z == null ? defaultZone : ZoneId.of(z.toString()),
                m.get("label") == null ? null : m.get("label").toString(),
                Integer.parseInt(String.valueOf(m.getOrDefault("adjustmentPeriods", 0))));
    }

    /** The fiscal year containing a date, identified by the calendar year in which it starts. */
    public FiscalYear yearOf(LocalDate d) {
        LocalDate startThisYear = LocalDate.of(d.getYear(), startMonth, startDay);
        return new FiscalYear(this, d.isBefore(startThisYear) ? d.getYear() - 1 : d.getYear());
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
            return LocalDate.of(startYear, calendar.startMonth, calendar.startDay);
        }

        public LocalDate end() {
            return start().plusYears(1).minusDays(1);
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

        public String label() {
            int end = end().getYear();
            return switch (calendar.labelFormat) {
                case "START" -> Integer.toString(startYear);
                case "END" -> Integer.toString(end);
                case "START/END" -> startYear + "/" + end;
                default -> startYear == end ? Integer.toString(startYear) : startYear + "-" + String.format("%02d", end % 100);
            };
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

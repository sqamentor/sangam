// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.pipeline;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.BitSet;

/** Five-field cron (minute hour day-of-month month day-of-week, 0 or 7 = Sunday) with lists, ranges and steps. */
public final class CronSchedule {

    private final BitSet minute, hour, dom, month, dow;
    private final ZoneId zone;

    public CronSchedule(String expression, ZoneId zone) {
        String[] f = expression.trim().split("\\s+");
        if (f.length != 5) {
            throw new IllegalArgumentException("cron needs 5 fields: " + expression);
        }
        minute = field(f[0], 0, 59);
        hour = field(f[1], 0, 23);
        dom = field(f[2], 1, 31);
        month = field(f[3], 1, 12);
        dow = field(f[4], 0, 7);
        if (dow.get(7)) {
            dow.set(0);
        }
        this.zone = zone;
    }

    /** First fire time strictly after {@code after}. Local times skipped by DST do not fire; a repeated hour fires once per instant match. */
    public Instant next(Instant after) {
        ZonedDateTime t = after.atZone(zone).truncatedTo(ChronoUnit.MINUTES).plusMinutes(1);
        ZonedDateTime limit = t.plusYears(5);
        while (t.isBefore(limit)) {
            if (month.get(t.getMonthValue()) && dom.get(t.getDayOfMonth()) && dow.get(t.getDayOfWeek().getValue() % 7)
                    && hour.get(t.getHour()) && minute.get(t.getMinute())) {
                return t.toInstant();
            }
            t = t.plusMinutes(1);
        }
        throw new IllegalArgumentException("cron never fires within 5 years");
    }

    private static BitSet field(String spec, int min, int max) {
        BitSet b = new BitSet(max + 1);
        for (String part : spec.split(",")) {
            String[] stepSplit = part.split("/", 2);
            int step = stepSplit.length == 2 ? Integer.parseInt(stepSplit[1]) : 1;
            if (step < 1) {
                throw new IllegalArgumentException("bad cron step: " + part);
            }
            int lo, hi;
            if (stepSplit[0].equals("*")) {
                lo = min;
                hi = max;
            } else if (stepSplit[0].contains("-")) {
                String[] r = stepSplit[0].split("-", 2);
                lo = Integer.parseInt(r[0]);
                hi = Integer.parseInt(r[1]);
            } else {
                lo = Integer.parseInt(stepSplit[0]);
                hi = stepSplit.length == 2 ? max : lo;
            }
            if (lo < min || hi > max || lo > hi) {
                throw new IllegalArgumentException("cron value out of range: " + part);
            }
            for (int i = lo; i <= hi; i += step) {
                b.set(i);
            }
        }
        return b;
    }
}

// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry.kernel;

import org.sangam.registry.RegistryException;
import org.sangam.registry.support.Maps;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.util.Map;

/**
 * Validity and renewal rule of a configured type (all values are configuration):
 * <ul>
 *   <li>{@code mode}: {@code NONE} (no validity), {@code PERPETUAL} (valid from issue, no end), {@code PERIOD}
 *       ({@code period}, an ISO-8601 period such as {@code P1Y} or {@code P6M}), {@code GIVEN} (the caller supplies
 *       the end date);</li>
 *   <li>{@code alignTo}: {@code NONE}, {@code END_OF_MONTH}, {@code END_OF_YEAR} or {@code FISCAL_YEAR_END}
 *       (with {@code fiscalStartMonth}, 1..12) moves the end date to the end of that period;</li>
 *   <li>{@code renewalWindow}: how long before the end the renewal becomes due (and allowed);</li>
 *   <li>{@code grace}: how long after the end a renewal is still allowed (late renewal), otherwise a fresh issue;</li>
 *   <li>{@code renewalPeriod} (default {@code period}), {@code maxRenewals} (null = unlimited), {@code renewFrom}
 *       ({@code PREVIOUS_END} keeps the anniversary, {@code RENEWAL_DATE} starts from the renewal day);</li>
 *   <li>{@code deemedRenewalDecision}: an optional DMN key that decides deemed renewal where the rules allow it.</li>
 * </ul>
 * End instants are exclusive: an instrument valid through 31 March has {@code validTo} = 1 April 00:00 in the
 * tenant zone.
 */
public record ValidityRule(String mode, Period period, String alignTo, int fiscalStartMonth, Period renewalWindow,
                           Period grace, Period renewalPeriod, Integer maxRenewals, String renewFrom,
                           String deemedRenewalDecision) {

    public static final ValidityRule NONE = new ValidityRule("NONE", null, "NONE", 1, Period.ZERO, Period.ZERO, null,
            null, "PREVIOUS_END", null);

    public static ValidityRule from(Map<String, Object> m) {
        if (m == null || m.isEmpty()) {
            return NONE;
        }
        Period p = period(Maps.str(m, "period"));
        String mode = Maps.str(m, "mode", p == null ? "NONE" : "PERIOD").toUpperCase();
        if ("PERIOD".equals(mode) && p == null) {
            throw RegistryException.invalid("validity: mode PERIOD needs a period");
        }
        int fsm = Maps.integer(m, "fiscalStartMonth", 1);
        if (fsm < 1 || fsm > 12) {
            throw RegistryException.invalid("validity: fiscalStartMonth must be 1..12");
        }
        Period rp = period(Maps.str(m, "renewalPeriod"));
        return new ValidityRule(mode, p, Maps.str(m, "alignTo", "NONE").toUpperCase(), fsm,
                orZero(period(Maps.str(m, "renewalWindow"))), orZero(period(Maps.str(m, "grace"))), rp == null ? p : rp,
                Maps.integer(m, "maxRenewals"), Maps.str(m, "renewFrom", "PREVIOUS_END").toUpperCase(),
                Maps.str(m, "deemedRenewalDecision"));
    }

    public Map<String, Object> toMap() {
        return Maps.of("mode", mode, "period", period == null ? null : period.toString(), "alignTo", alignTo,
                "fiscalStartMonth", fiscalStartMonth, "renewalWindow", renewalWindow.toString(), "grace", grace.toString(),
                "renewalPeriod", renewalPeriod == null ? null : renewalPeriod.toString(), "maxRenewals", maxRenewals,
                "renewFrom", renewFrom, "deemedRenewalDecision", deemedRenewalDecision);
    }

    private static Period period(String s) {
        return s == null || s.isBlank() ? null : Period.parse(s.trim());
    }

    private static Period orZero(Period p) {
        return p == null ? Period.ZERO : p;
    }

    public boolean hasValidity() {
        return !"NONE".equals(mode);
    }

    /** Validity window for a first issue starting on {@code from}. */
    public Window first(LocalDate from, LocalDate givenEnd, ZoneId zone) {
        return switch (mode) {
            case "NONE" -> new Window(null, null, null);
            case "PERPETUAL" -> new Window(start(from, zone), null, null);
            case "GIVEN" -> {
                if (givenEnd == null) {
                    throw RegistryException.invalid("validity: an end date (validTo) is required for this type");
                }
                yield window(from, givenEnd, zone);
            }
            default -> window(from, align(from.plus(period).minusDays(1)), zone);
        };
    }

    /**
     * Validity window after a renewal.
     *
     * @param currentEnd    current exclusive end instant
     * @param renewalDay    the day the renewal is granted
     * @param renewalsSoFar renewals already granted
     */
    public Window renew(Instant currentEnd, LocalDate renewalDay, int renewalsSoFar, LocalDate givenEnd, ZoneId zone) {
        if (!hasValidity() || "PERPETUAL".equals(mode)) {
            throw RegistryException.state("this type has no renewable validity");
        }
        if (maxRenewals != null && renewalsSoFar >= maxRenewals) {
            throw RegistryException.state("maximum number of renewals reached (" + maxRenewals + ")");
        }
        LocalDate prevEndExclusive = currentEnd == null ? renewalDay : currentEnd.atZone(zone).toLocalDate();
        LocalDate start = "RENEWAL_DATE".equals(renewFrom) ? renewalDay : prevEndExclusive;
        if ("GIVEN".equals(mode)) {
            if (givenEnd == null) {
                throw RegistryException.invalid("validity: an end date (validTo) is required for this renewal");
            }
            return window(start, givenEnd, zone);
        }
        return window(start, align(start.plus(renewalPeriod).minusDays(1)), zone);
    }

    /** True when a renewal is allowed at {@code now} for an instrument ending at {@code end}. */
    public boolean renewalOpen(Instant end, Instant now, ZoneId zone) {
        if (end == null) {
            return false;
        }
        LocalDate endDay = end.atZone(zone).toLocalDate();
        Instant opens = endDay.minus(renewalWindow).atStartOfDay(zone).toInstant();
        Instant closes = endDay.plus(grace).atStartOfDay(zone).toInstant();
        return !now.isBefore(opens) && now.isBefore(closes);
    }

    /** When the renewal becomes due (start of the renewal window), or null. */
    public Instant renewalDueAt(Instant end, ZoneId zone) {
        if (end == null || renewalWindow.isZero()) {
            return null;
        }
        return end.atZone(zone).toLocalDate().minus(renewalWindow).atStartOfDay(zone).toInstant();
    }

    private LocalDate align(LocalDate lastDay) {
        return switch (alignTo) {
            case "END_OF_MONTH" -> lastDay.withDayOfMonth(lastDay.lengthOfMonth());
            case "END_OF_YEAR" -> LocalDate.of(lastDay.getYear(), 12, 31);
            case "FISCAL_YEAR_END" -> {
                LocalDate fyStart = LocalDate.of(lastDay.getYear(), fiscalStartMonth, 1);
                if (lastDay.isBefore(fyStart)) {
                    yield fyStart.minusDays(1);
                }
                yield fyStart.plusYears(1).minusDays(1);
            }
            default -> lastDay;
        };
    }

    private Window window(LocalDate from, LocalDate lastDayInclusive, ZoneId zone) {
        if (lastDayInclusive.isBefore(from)) {
            throw RegistryException.invalid("validity: end date before start date");
        }
        Instant end = lastDayInclusive.plusDays(1).atStartOfDay(zone).toInstant();
        return new Window(start(from, zone), end, renewalDueAt(end, zone));
    }

    private static Instant start(LocalDate from, ZoneId zone) {
        return from.atStartOfDay(zone).toInstant();
    }

    /** A validity window: start inclusive, end exclusive, and when renewal falls due. */
    public record Window(Instant validFrom, Instant validTo, Instant renewalDueAt) {
    }
}

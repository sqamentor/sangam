// SPDX-License-Identifier: Apache-2.0
package org.sangam.programme.period;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.programme.ProgrammeException;
import org.sangam.programme.config.IndicatorDefinition.Frequency;
import org.sangam.programme.support.Db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * N15 {@code ReportingPeriod}: periods per frequency inside a fiscal year whose start month is configuration
 * (WEEKLY, FORTNIGHTLY, MONTHLY, QUARTERLY, HALF_YEARLY, ANNUAL). Period ids are deterministic
 * ({@code <FREQUENCY>:<starts_on>}), so generation is idempotent. States: OPEN (reporting), CLOSED (no new returns,
 * late submission still possible), FROZEN (no change at all; re-open needs a reason).
 */
public final class PeriodService {

    public enum State { OPEN, CLOSED, FROZEN }

    public record Period(String id, Frequency frequency, LocalDate startsOn, LocalDate endsOn, String label, String fiscalYear,
                         int seqInYear, LocalDate dueOn, State state, String stateReason, String changedBy, Instant changedAt) {

        public boolean contains(LocalDate d) {
            return !d.isBefore(startsOn) && !d.isAfter(endsOn);
        }
    }

    private final Dialect dialect;

    public PeriodService(Dialect dialect) {
        this.dialect = dialect;
    }

    public static String periodId(Frequency f, LocalDate start) {
        return f.name() + ":" + start;
    }

    /** Fiscal year label {@code FY<start>} or {@code FY<start>-<end yy>} when the year does not start in January. */
    public static String fiscalYearLabel(int startYear, Month startMonth) {
        return startMonth == Month.JANUARY ? "FY" + startYear : "FY" + startYear + "-" + String.format("%02d", (startYear + 1) % 100);
    }

    /** Start of the fiscal year containing {@code d}. */
    public static LocalDate fiscalYearStart(LocalDate d, Month startMonth) {
        LocalDate s = LocalDate.of(d.getYear(), startMonth, 1);
        return d.isBefore(s) ? s.minusYears(1) : s;
    }

    /** Computes (without storing) the periods of one fiscal year. */
    public static List<Period> compute(Frequency f, int fiscalStartYear, Month startMonth, int dueOffsetDays) {
        LocalDate yStart = LocalDate.of(fiscalStartYear, startMonth, 1);
        LocalDate yEnd = yStart.plusYears(1).minusDays(1);
        String fy = fiscalYearLabel(fiscalStartYear, startMonth);
        List<Period> out = new ArrayList<>();
        LocalDate s = yStart;
        int seq = 1;
        while (!s.isAfter(yEnd)) {
            LocalDate e = switch (f) {
                case WEEKLY -> s.plusDays(6);
                case FORTNIGHTLY -> s.plusDays(13);
                default -> s.plusMonths(f.months()).minusDays(1);
            };
            if (e.isAfter(yEnd) || (f == Frequency.WEEKLY && seq == 52) || (f == Frequency.FORTNIGHTLY && seq == 26)) {
                e = yEnd; // the last week / fortnight absorbs the remaining days
            }
            String code = switch (f) {
                case WEEKLY -> "W" + String.format("%02d", seq);
                case FORTNIGHTLY -> "F" + String.format("%02d", seq);
                case MONTHLY -> "M" + String.format("%02d", seq);
                case QUARTERLY -> "Q" + seq;
                case HALF_YEARLY -> "H" + seq;
                case ANNUAL -> "Y";
            };
            out.add(new Period(periodId(f, s), f, s, e, fy + "/" + code, fy, seq, e.plusDays(dueOffsetDays), State.OPEN, null, null, null));
            s = e.plusDays(1);
            seq++;
        }
        return out;
    }

    /** Stores the periods of a fiscal year; existing periods are kept unchanged. Returns all periods of the year. */
    public List<Period> generate(Connection c, String tenant, Frequency f, int fiscalStartYear, Month startMonth, int dueOffsetDays) {
        String t = Db.tenant(tenant);
        for (Period p : compute(f, fiscalStartYear, startMonth, dueOffsetDays)) {
            Jdbc.insertIfAbsent(c, dialect, "INSERT INTO PG_PERIOD(tenant_id, period_id, frequency, starts_on, ends_on, label, fiscal_year,"
                            + " seq_in_year, due_on, state) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    t, p.id(), f.name(), Db.sqlDate(p.startsOn()), Db.sqlDate(p.endsOn()), p.label(), p.fiscalYear(), p.seqInYear(),
                    Db.sqlDate(p.dueOn()), State.OPEN.name());
        }
        return Jdbc.query(c, "SELECT * FROM PG_PERIOD WHERE tenant_id=? AND frequency=? AND fiscal_year=? ORDER BY starts_on",
                PeriodService::map, t, f.name(), fiscalYearLabel(fiscalStartYear, startMonth));
    }

    public Period get(Connection c, String tenant, String periodId) {
        return find(c, tenant, periodId).orElseThrow(() -> ProgrammeException.notFound("period", periodId));
    }

    public Optional<Period> find(Connection c, String tenant, String periodId) {
        return Jdbc.one(c, "SELECT * FROM PG_PERIOD WHERE tenant_id=? AND period_id=?", PeriodService::map, Db.tenant(tenant), periodId);
    }

    public Optional<Period> containing(Connection c, String tenant, Frequency f, LocalDate d) {
        return Jdbc.one(c, "SELECT * FROM PG_PERIOD WHERE tenant_id=? AND frequency=? AND starts_on<=? AND ends_on>=?", PeriodService::map,
                Db.tenant(tenant), f.name(), Db.sqlDate(d), Db.sqlDate(d));
    }

    /** The period immediately before {@code p} (same frequency), if stored. */
    public Optional<Period> previous(Connection c, String tenant, Period p) {
        return Jdbc.one(c, "SELECT * FROM PG_PERIOD WHERE tenant_id=? AND frequency=? AND ends_on=?", PeriodService::map,
                Db.tenant(tenant), p.frequency().name(), Db.sqlDate(p.startsOn().minusDays(1)));
    }

    /** Periods of the same fiscal year up to and including {@code p} (cumulative-to-date). */
    public List<Period> yearToDate(Connection c, String tenant, Period p) {
        return Jdbc.query(c, "SELECT * FROM PG_PERIOD WHERE tenant_id=? AND frequency=? AND fiscal_year=? AND starts_on<=? ORDER BY starts_on",
                PeriodService::map, Db.tenant(tenant), p.frequency().name(), p.fiscalYear(), Db.sqlDate(p.startsOn()));
    }

    public List<Period> list(Connection c, String tenant, Frequency f, String fiscalYear) {
        return Jdbc.query(c, "SELECT * FROM PG_PERIOD WHERE tenant_id=? AND frequency=? AND fiscal_year=? ORDER BY starts_on",
                PeriodService::map, Db.tenant(tenant), f.name(), fiscalYear);
    }

    /** OPEN to CLOSED, CLOSED to FROZEN, or back to OPEN (re-open, reason required). */
    public Period changeState(Connection c, String tenant, String periodId, State to, String reason, String actor) {
        Period p = get(c, tenant, periodId);
        if (p.state() == to) {
            return p;
        }
        boolean reopen = to.ordinal() < p.state().ordinal();
        if (reopen) {
            Db.requireText(reason, "reason for re-opening a period");
        }
        Jdbc.update(c, "UPDATE PG_PERIOD SET state=?, state_reason=?, changed_by=?, changed_at=" + dialect.now()
                + " WHERE tenant_id=? AND period_id=? AND state=?", to.name(), reason, actor, Db.tenant(tenant), periodId, p.state().name());
        return get(c, tenant, periodId);
    }

    /** Refuses a change in a frozen period (and, unless {@code allowClosed}, a closed one). */
    public void requireWritable(Connection c, String tenant, String periodId, boolean allowClosed) {
        Period p = get(c, tenant, periodId);
        if (p.state() == State.FROZEN || (!allowClosed && p.state() == State.CLOSED)) {
            throw new ProgrammeException(ProgrammeException.Code.PERIOD_LOCKED, "period " + p.label() + " is " + p.state());
        }
    }

    /** Elapsed share of the period at {@code asOf} (0..1), for pro-rata targets. */
    public static double elapsedShare(Period p, LocalDate asOf) {
        if (asOf.isBefore(p.startsOn())) {
            return 0;
        }
        if (asOf.isAfter(p.endsOn())) {
            return 1;
        }
        long total = p.endsOn().toEpochDay() - p.startsOn().toEpochDay() + 1;
        return (double) (asOf.toEpochDay() - p.startsOn().toEpochDay() + 1) / total;
    }

    static Period map(ResultSet rs) throws SQLException {
        return new Period(rs.getString("period_id"), Frequency.valueOf(rs.getString("frequency")), Db.date(rs, "starts_on"),
                Db.date(rs, "ends_on"), rs.getString("label"), rs.getString("fiscal_year"), rs.getInt("seq_in_year"), Db.date(rs, "due_on"),
                State.valueOf(rs.getString("state")), rs.getString("state_reason"), rs.getString("changed_by"), Jdbc.instant(rs, "changed_at"));
    }
}

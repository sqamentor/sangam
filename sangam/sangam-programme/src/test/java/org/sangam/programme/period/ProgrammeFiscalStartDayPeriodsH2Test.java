// SPDX-License-Identifier: Apache-2.0
package org.sangam.programme.period;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Outbox;
import org.sangam.core.layer.SqlPredicate;
import org.sangam.core.layer.Transactions;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.core.security.SecurityPorts;
import org.sangam.programme.ProgrammeModule;
import org.sangam.programme.ProgrammeSchema;
import org.sangam.programme.config.IndicatorDefinition.Frequency;
import org.sangam.programme.rest.ProgrammeRestAdapter;
import org.sangam.programme.rest.ProgrammeRestPort.ApiContext;

import java.sql.Connection;
import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * X1-03 (owner decision 2026-11-12): the programme's period generation (ProgrammeRestAdapter.generatePeriods, the same
 * PeriodService call ReturnCampaignDelegate makes) honours the published {@code fiscalStartDay}; without it the stored
 * periods are exactly those of the month-only code.
 */
class ProgrammeFiscalStartDayPeriodsH2Test {

    /** Allows everything: authorization is not under test here. */
    private static final SecurityPorts.Authorization ALLOW_ALL = new SecurityPorts.Authorization() {
        @Override
        public SqlPredicate visible(Connection c, SecurityIdentity identity, String resourceType, String permission, String alias) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public boolean allow(Connection c, SecurityIdentity identity, String resourceType, String permission, String resourceId) {
            return true;
        }

        @Override
        public void require(Connection c, SecurityIdentity identity, String resourceType, String permission, String resourceId) {
        }

        @Override
        public long policyVersion(Connection c, String tenantId) {
            return 1;
        }
    };

    private static final ApiContext CTX = new ApiContext(SecurityIdentity.system("T1", null));

    /** A fresh programme module on H2 with {@code config} published for tenant T1. */
    private static ProgrammeRestAdapter adapter(Map<String, Object> config) throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        try (Connection c = ds.getConnection()) {
            ProgrammeSchema.create(c, Dialect.POSTGRESQL);
        }
        ProgrammeModule module = new ProgrammeModule(Dialect.POSTGRESQL, Transactions.of(ds), new Outbox(Dialect.POSTGRESQL),
                ProgrammeModule.Ports.none());
        ProgrammeRestAdapter a = new ProgrammeRestAdapter(module, ALLOW_ALL);
        a.publishConfig(CTX, config);
        return a;
    }

    /** The stored fields of a period that generation decides (state columns are not compared). */
    private static List<List<Object>> shape(List<PeriodService.Period> ps) {
        return ps.stream().map(p -> List.<Object>of(p.id(), p.frequency(), p.startsOn(), p.endsOn(), p.label(), p.fiscalYear(),
                p.seqInYear(), p.dueOn())).toList();
    }

    @Test
    void aConfiguredDay15StartsEveryYearAndItsPeriodsOnThe15th() throws Exception {
        ProgrammeRestAdapter a = adapter(Map.of("fiscalYearStartMonth", 4, "fiscalStartDay", 15));
        List<PeriodService.Period> months = a.generatePeriods(CTX, Frequency.MONTHLY, 2026, 10);
        assertEquals(12, months.size());
        assertEquals(LocalDate.of(2026, 4, 15), months.get(0).startsOn());
        assertEquals(LocalDate.of(2026, 5, 14), months.get(0).endsOn());
        assertEquals("MONTHLY:2026-04-15", months.get(0).id());
        assertEquals(LocalDate.of(2027, 4, 14), months.get(11).endsOn());
        for (PeriodService.Period p : months) {
            assertEquals(15, p.startsOn().getDayOfMonth(), p.id());
            assertEquals("FY2026-27", p.fiscalYear(), p.id());
        }
        assertEquals(shape(PeriodService.compute(Frequency.MONTHLY, 2026, Month.APRIL, 15, 10)), shape(months));
        List<PeriodService.Period> quarters = a.generatePeriods(CTX, Frequency.QUARTERLY, 2026, 0);
        assertEquals(List.of(LocalDate.of(2026, 4, 15), LocalDate.of(2026, 7, 15), LocalDate.of(2026, 10, 15),
                LocalDate.of(2027, 1, 15)), quarters.stream().map(PeriodService.Period::startsOn).toList());
    }

    @Test
    void withoutADayTheStoredPeriodsAreThoseOfTheMonthOnlyCode() throws Exception {
        for (int month = 1; month <= 12; month++) {
            ProgrammeRestAdapter a = adapter(Map.of("fiscalYearStartMonth", month));
            for (Frequency f : Frequency.values()) {
                List<PeriodService.Period> stored = a.generatePeriods(CTX, f, 2026, 7);
                // the month-only overload is the pre-X1-03 path (PeriodServiceFiscalYearTest pins it to the old code)
                assertEquals(shape(PeriodService.compute(f, 2026, Month.of(month), 7)), shape(stored), f + " " + month);
                assertEquals(1, stored.get(0).startsOn().getDayOfMonth(), f + " " + month);
            }
        }
    }
}

// SPDX-License-Identifier: Apache-2.0
package org.sangam.billing;

import org.junit.jupiter.api.Test;
import org.sangam.config.catalogue.NumberSeries;
import org.sangam.core.time.BusinessClock;
import org.sangam.ledger.LedgerService;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Receipt series periods are the tenant's business date, not the JVM / UTC date. */
class BillingBusinessDateTest {

    /** 2026-04-01T19:00Z = 2026-04-02 00:30 at +05:30. */
    static final Clock AT = Clock.fixed(Instant.parse("2026-04-01T19:00:00Z"), ZoneOffset.UTC);

    @Test
    void receiptNumberSeriesUsesTheTenantsToday() {
        List<String> periods = new ArrayList<>();
        NumberSeries.InMemory inner = new NumberSeries.InMemory();
        NumberSeries series = (scope, s, unit, period) -> {
            periods.add(scope + ":" + s + ":" + period);
            return inner.next(scope, s, unit, period);
        };
        BusinessClock clock = BusinessClock.of(AT, t -> "T".equals(t) ? ZoneId.of("Asia/Kolkata") : ZoneOffset.UTC);
        LedgerService l = BillingServiceTest.ledger();
        BillingService b = new BillingService(l, new TariffEngine(null), series, null, clock);
        BillingService.Receipt r = b.collect("T", "DEMAND", "C-9", "COUNTER", "PAY-9", org.sangam.core.money.Money.parse("10", "INR"), "cashier");
        assertEquals("RCP/1", r.number());
        assertEquals(List.of("T:RECEIPT:2026-04-02"), periods);
        assertEquals(Map.of("ADVANCE", org.sangam.core.money.Money.parse("10", "INR")), r.allocation());

        BillingService utc = new BillingService(l, new TariffEngine(null), series, null, BusinessClock.utc(AT));
        utc.collect("T", "DEMAND", "C-9", "COUNTER", "PAY-10", org.sangam.core.money.Money.parse("10", "INR"), "cashier");
        assertEquals("T:RECEIPT:2026-04-01", periods.get(1));
    }
}

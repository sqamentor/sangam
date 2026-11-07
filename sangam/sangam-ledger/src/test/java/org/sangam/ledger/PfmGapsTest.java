// SPDX-License-Identifier: Apache-2.0
package org.sangam.ledger;

import org.junit.jupiter.api.Test;
import org.sangam.config.common.Finding;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** International PFM generic gaps (INTERNATIONAL-PFM section 6) on the in-memory ledger. */
class PfmGapsTest {

    @Test
    void outerYearCeilingsBreachEventsAgreementsFiscalRulesTsa() {
        List<Balance> breaches = new ArrayList<>();
        LedgerStore.InMemory store = new LedgerStore.InMemory();
        LedgerService s = new LedgerService(store, null).withBreaches((b, r) -> breaches.add(b));
        s.define("T", List.of(LedgerDefinition.from(Map.of("id", "B", "currency", "USD", "stages", List.of("CEILING"))),
                LedgerDefinition.from(Map.of("id", "SOFT", "currency", "USD", "checks", Map.of("LIMIT", "SOFT")))));
        AccountKey y1 = new AccountKey("T", "B", "FY2027", "P1", "AG", "CEILING", null);
        s.apply(LedgerRequest.of("ALLOCATE", y1, new BigDecimal("100"), "c1"));
        s.apply(LedgerRequest.of("ALLOCATE", y1.withPeriod("FY2028"), new BigDecimal("50"), "c2"));
        Map<String, BigDecimal> ok = new LinkedHashMap<>(Map.of("FY2027", new BigDecimal("60"), "FY2028", new BigDecimal("40")));
        assertTrue(s.commitMultiYear(y1, ok, "mc1", "u", Map.of()).applied());
        Map<String, BigDecimal> over = new LinkedHashMap<>(Map.of("FY2027", new BigDecimal("10"), "FY2028", new BigDecimal("20")));
        assertFalse(s.commitMultiYear(y1, over, "mc2", "u", Map.of()).applied(), "outer-year ceiling holds atomically");
        assertEquals(new BigDecimal("40"), s.available(y1));

        AccountKey soft = new AccountKey("T", "SOFT", "FY2027", "L", "H", "LIMIT", null);
        s.apply(LedgerRequest.of("COMMIT", soft, BigDecimal.TEN, "s1"));
        assertEquals(1, breaches.size(), "a SOFT overspend is reported as a breach event");

        FinancingAgreements fa = FinancingAgreements.from(List.of(Map.of("id", "IDA-1", "currency", "USD",
                "closingDate", "2029-06-30", "categories", List.of(Map.of("id", "WORKS", "percentFinanced", 80)),
                "procurementMethods", Map.of("WORKS", List.of("ICB", "NCB")))));
        FinancingAgreements.Eligibility e = fa.check("IDA-1", "WORKS", "NCB", new BigDecimal("1000"), LocalDate.of(2027, 1, 1));
        assertTrue(e.eligible());
        assertEquals(new BigDecimal("800.00"), e.financedAmount());
        assertFalse(fa.check("IDA-1", "WORKS", "DIRECT", new BigDecimal("1000"), LocalDate.of(2027, 1, 1)).eligible());
        assertFalse(fa.check("IDA-1", "WORKS", "NCB", new BigDecimal("1000"), LocalDate.of(2030, 1, 1)).eligible());

        s.apply(LedgerRequest.of("CONSUME", y1, new BigDecimal("30"), "pay1"));
        List<Finding> fr = new FiscalRuleEvaluator(store, null).evaluate("T", "FY2027", Map.of(
                "aggregates", List.of(Map.of("id", "spent", "ledger", "B", "bucket", "CONSUMED"),
                        Map.of("id", "limit", "ledger", "B", "bucket", "TOTAL")),
                "rules", List.of(Map.of("id", "SPEND_CAP", "ratio", List.of("spent", "limit"), "maxPercent", 20))));
        assertTrue(Finding.anyError(fr), "30 of 100 is above a 20 % rule");

        TsaTopology tsa = TsaTopology.from(Map.of("main", Map.of("id", "TSA", "currency", "USD"),
                "accounts", List.of(Map.of("id", "ZBA-1", "kind", "ZERO_BALANCE", "bank", "B1", "sweep", "DAILY"),
                        Map.of("id", "DA-1", "kind", "EXCEPTION", "reason", "DONOR_DESIGNATED"))));
        assertTrue(tsa.lint().isEmpty());
        assertEquals(1, tsa.sweeps(Map.of("ZBA-1", new BigDecimal("5"), "DA-1", new BigDecimal("9"))).size());
    }
}

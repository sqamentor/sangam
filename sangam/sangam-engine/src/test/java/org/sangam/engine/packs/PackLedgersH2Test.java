// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.sangam.core.engine.SangamClock;
import org.sangam.packs.port.BatchLedger;
import org.sangam.packs.port.LedgerStore;
import org.sangam.persistence.SangamSchema;

/** Fast check of the WS-PACKS port semantics on H2 (same rules as the in-memory simulators). */
class PackLedgersH2Test {

    @Test
    void ledgerAndBatchSemantics() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:packs;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamSchema.apply(ds);

        JdbcLedgerStore ledger = new JdbcLedgerStore(ds, SangamClock.SYSTEM, Duration.ofSeconds(2));
        ledger.open("t", "budget", "2026-27/H1", new BigDecimal("100.00"));
        LedgerStore.Posting p1 = ledger.post("t", "budget", "2026-27/H1", new BigDecimal("-60"), "bill-1", false);
        assertEquals(0, new BigDecimal("40").compareTo(p1.balanceAfter()));
        assertEquals("DUPLICATE_REFERENCE", ledger.post("t", "budget", "2026-27/H1", new BigDecimal("-60"), "bill-1", false).reason());
        assertFalse(ledger.post("t", "budget", "2026-27/H1", new BigDecimal("-50"), "bill-2", false).applied());
        assertEquals(0, new BigDecimal("100").compareTo(ledger.reverse("t", "budget", "2026-27/H1", "bill-1").balanceAfter()));
        assertEquals("NOTHING_TO_REVERSE", ledger.reverse("t", "budget", "2026-27/H1", "bill-1").reason());

        JdbcBatchLedger batch = new JdbcBatchLedger(ds, SangamClock.SYSTEM);
        batch.open("t", "merit", 1, List.of("a", "b", "c"));
        batch.open("t", "merit", 1, List.of("a"));
        batch.record("t", "merit", 1, "a", "SUCCESS", null, Map.of("score", 91));
        batch.record("t", "merit", 1, "a", "FAILED", "LATE", Map.of()); // first outcome wins
        batch.record("t", "merit", 1, "b", "FAILED", "INELIGIBLE", null);
        BatchLedger.BatchSummary s = batch.summary("t", "merit", 1);
        assertEquals(3, s.total());
        assertEquals(2, s.done());
        assertEquals(1, s.succeeded());
        assertEquals(Map.of("b", "INELIGIBLE"), batch.failures("t", "merit", 1));
        assertEquals(91, ((Number) batch.itemData("t", "merit", 1).get("a").get("score")).intValue());
    }
}

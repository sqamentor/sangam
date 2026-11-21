// SPDX-License-Identifier: Apache-2.0
package org.sangam.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Outbox;
import org.sangam.core.layer.PendingAudit;
import org.sangam.core.layer.PendingAuditDrainer;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;

/**
 * Owner decision 126 with the audited GL waiver (2026-11-19) and RM-05: every accounting entry is kept in
 * {@code LDG_ACCOUNTING} with its exact GL payload. Without a GL export (waiver) it stays with {@code exported_at} NULL
 * and no outbox row targets a missing connector; once a connector is set the entries are exported later, each exactly
 * once, with the payload the live export would have sent. Before this change no code wrote {@code LDG_ACCOUNTING}: an
 * entry posted without a GL export was lost.
 */
class LedgerGlWaiverH2Test {

    static final Dialect D = LedgerJournalH2Test.D;

    record Db(JdbcDataSource ds, Transactions tx, PendingAuditDrainer drainer) {
    }

    static Db db() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        try (Connection c = ds.getConnection()) {
            Schema.create(c, D);
            PendingAudit.create(c, D);
            LedgerSchema.create(c, D);
        }
        Transactions tx = Transactions.of(ds);
        return new Db(ds, tx, new PendingAuditDrainer(new PendingAudit(tx, D, new AuditChain(D)),
                new PendingAuditDrainer.Settings(1, Duration.ZERO, Duration.ZERO, 10)));
    }

    static LedgerJournal withGl(Db db) {
        return new LedgerJournal(db.drainer(), new LedgerJournal.GlExport(LedgerJournalH2Test.GL,
                LedgerJournalH2Test.GL_EVENT, ref -> "ledger:" + ref, new Outbox(D)::append));
    }

    static LedgerService service(Db db, LedgerJournal journal) {
        return LedgerJournalH2Test.service(db.ds(), journal, new ArrayList<>());
    }

    record Acc(String reference, String payload, Instant exportedAt) {
    }

    static List<Acc> accounting(Db db) {
        return db.tx().inTransaction(c -> Jdbc.query(c, "SELECT reference, payload_json, exported_at FROM LDG_ACCOUNTING"
                + " ORDER BY entry_id", rs -> new Acc(rs.getString(1), rs.getString(2), Jdbc.instant(rs, "exported_at"))));
    }

    static List<String> glPayloads(Db db) {
        return db.tx().inTransaction(c -> Jdbc.query(c, "SELECT payload FROM OUTBOX WHERE event_type=? ORDER BY id",
                rs -> rs.getString(1), LedgerJournalH2Test.GL_EVENT));
    }

    @Test
    void withoutAGlExportEveryEntryIsKeptUnexportedAndNoOutboxRowIsWritten() throws Exception {
        Db db = db();
        try (PendingAuditDrainer ignored = db.drainer()) {
            LedgerService s = service(db, new LedgerJournal(db.drainer(), null));
            assertTrue(s.apply(LedgerRequest.of("ALLOCATE", LedgerJournalH2Test.K, new BigDecimal("500.25"), "w-1"))
                    .applied(), "the posting is never blocked by the missing GL export");
            List<Acc> rows = accounting(db);
            assertEquals(1, rows.size(), "the accounting entry is kept, not dropped");
            assertEquals("w-1", rows.get(0).reference());
            assertNull(rows.get(0).exportedAt());
            assertTrue(rows.get(0).payload().contains("\"amount\":\"500.25\""), rows.get(0).payload());
            assertEquals(List.of(), glPayloads(db), "no outbox GL row targets a missing connector");
        }
    }

    @Test
    void withAGlExportTheEntryIsKeptExportedWithTheSamePayloadAsItsOutboxRow() throws Exception {
        Db db = db();
        try (PendingAuditDrainer ignored = db.drainer()) {
            LedgerService s = service(db, withGl(db));
            assertTrue(s.apply(LedgerRequest.of("ALLOCATE", LedgerJournalH2Test.K, new BigDecimal("75"), "g-1")).applied());
            List<Acc> rows = accounting(db);
            assertEquals(1, rows.size());
            assertNotNull(rows.get(0).exportedAt());
            assertEquals(List.of(rows.get(0).payload()), glPayloads(db));
        }
    }

    @Test
    void entriesKeptUnderTheWaiverAreExportedLaterExactlyOnce() throws Exception {
        Db db = db();
        try (PendingAuditDrainer ignored = db.drainer()) {
            LedgerService waived = service(db, new LedgerJournal(db.drainer(), null));
            waived.apply(LedgerRequest.of("ALLOCATE", LedgerJournalH2Test.K, new BigDecimal("10"), "b-1"));
            waived.apply(LedgerRequest.of("ALLOCATE", LedgerJournalH2Test.K, new BigDecimal("20"), "b-2"));
            List<String> kept = accounting(db).stream().map(Acc::payload).toList();
            assertEquals(2, kept.size());

            JdbcLedgerStore noGl = new JdbcLedgerStore(db.ds(), D, new LedgerJournal(db.drainer(), null));
            assertThrows(IllegalStateException.class, noGl::exportPendingAccounting, "nothing to export to");

            JdbcLedgerStore store = new JdbcLedgerStore(db.ds(), D, withGl(db));
            assertEquals(2, store.exportPendingAccounting());
            assertEquals(kept, glPayloads(db), "exported in order with the payload the live export would have sent");
            assertTrue(accounting(db).stream().allMatch(a -> a.exportedAt() != null));
            assertEquals(0, store.exportPendingAccounting(), "a second run exports nothing again");
            assertEquals(2, glPayloads(db).size());
        }
    }
}

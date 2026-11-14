// SPDX-License-Identifier: Apache-2.0
package org.sangam.ledger;

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

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RM-04 on H2 (PostgreSQL mode): the {@code LDG_ENTRY} row, the {@code LEDGER_POSTED} audit row and the GL outbox row
 * of a posting commit together. A failure injected after the ledger rows and before the audit write rolls the whole
 * posting back, and the retry leaves exactly one audit row and one GL row; a duplicate retry adds nothing; a refused
 * posting writes neither. PostgreSQL, Oracle and SQL Server run the same scenario in the test phase.
 */
class LedgerJournalH2Test {

    static final Dialect D = Dialect.POSTGRESQL;
    static final String GL = "ledger:gl";
    static final String GL_EVENT = "sangam.ledger.accounting";

    static final Map<String, Object> DEF = Map.of("id", "BUDGET", "kind", "LIMIT", "currency", "INR",
            "stages", List.of("ALLOCATION", "RELEASE"),
            "accounting", List.of(Map.of("event", "ALLOCATE", "basis", "CASH", "debit", "BANK", "credit", "{line}")));

    static final AccountKey K = new AccountKey("T", "BUDGET", "2026-27", "2054-00-001", "DDO-1", "RELEASE", null);

    record Fixture(JdbcDataSource ds, Transactions tx, PendingAuditDrainer drainer, LedgerJournal journal) {
    }

    static Fixture fixture() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        try (Connection c = ds.getConnection()) {
            Schema.create(c, D);
            PendingAudit.create(c, D);
            LedgerSchema.create(c, D);
        }
        Transactions tx = Transactions.of(ds);
        PendingAuditDrainer drainer = new PendingAuditDrainer(new PendingAudit(tx, D, new AuditChain(D)),
                new PendingAuditDrainer.Settings(1, Duration.ZERO, Duration.ZERO, 10));
        Outbox outbox = new Outbox(D);
        LedgerJournal journal = new LedgerJournal(drainer, new LedgerJournal.GlExport(GL, GL_EVENT,
                ref -> "ledger:" + ref, outbox::append));
        return new Fixture(ds, tx, drainer, journal);
    }

    static LedgerService service(JdbcDataSource ds, LedgerPorts.PostingJournal journal, List<String> sinkCalls) {
        LedgerService s = new LedgerService(new JdbcLedgerStore(ds, D, journal),
                new LedgerControlStore.JdbcStore(ds, D), null)
                .withAudit((t, r, o, a, act, sum) -> sinkCalls.add("audit:" + r))
                .withAccounting(e -> sinkCalls.add("gl:" + e.size()));
        s.define("T", List.of(LedgerDefinition.from(DEF)));
        return s;
    }

    static long count(Transactions tx, String sql, Object... p) {
        return tx.inTransaction(c -> Jdbc.one(c, sql, rs -> rs.getLong(1), p).orElse(0L));
    }

    static long entries(Fixture f, String ref) {
        return count(f.tx(), "SELECT COUNT(*) FROM LDG_ENTRY WHERE tenant_id=? AND reference=?", "T", ref);
    }

    /** LEDGER_POSTED rows of the tenant ledger chain, pending or already drained onto the chain. */
    static long auditRows(Fixture f) {
        return count(f.tx(), "SELECT COUNT(*) FROM AUDIT_PENDING WHERE tenant_id=? AND chain_id=? AND event_type=?",
                "T", "ledger:T", LedgerJournal.LEDGER_POSTED)
                + count(f.tx(), "SELECT COUNT(*) FROM AUDIT_EVENT WHERE tenant_id=? AND chain_id=? AND event_type=?",
                "T", "ledger:T", LedgerJournal.LEDGER_POSTED);
    }

    static long glRows(Fixture f, String ref) {
        return count(f.tx(), "SELECT COUNT(*) FROM OUTBOX WHERE tenant_id=? AND root_id=? AND event_type=?",
                "T", "ledger:" + ref, GL_EVENT);
    }

    @Test
    void anAppliedPostingCommitsItsAuditAndGlRowsWithIt() throws Exception {
        Fixture f = fixture();
        try (PendingAuditDrainer ignored = f.drainer()) {
            List<String> sinks = new ArrayList<>();
            LedgerService s = service(f.ds(), f.journal(), sinks);
            LedgerService.Result r = s.apply(LedgerRequest.of("ALLOCATE", K, new BigDecimal("500"), "alloc-1"));
            assertTrue(r.applied());
            assertEquals(1, r.accounting().size());
            assertEquals(1, entries(f, "alloc-1"));
            assertEquals(1, auditRows(f));
            assertEquals(1, glRows(f, "alloc-1"));
            assertEquals(1, count(f.tx(), "SELECT COUNT(*) FROM OUTBOX_DELIVERY WHERE tenant_id=? AND destination=?",
                    "T", GL), "one GL delivery");
            assertTrue(sinks.isEmpty(), "a journaling store never uses the after-commit sinks: " + sinks);

            f.drainer().sweep(Duration.ZERO);
            assertEquals(1, count(f.tx(), "SELECT COUNT(*) FROM AUDIT_EVENT WHERE tenant_id=? AND chain_id=? AND event_type=?",
                    "T", "ledger:T", LedgerJournal.LEDGER_POSTED), "the drain appended it to the chain");
            assertEquals(0, count(f.tx(), "SELECT COUNT(*) FROM AUDIT_PENDING WHERE tenant_id=?", "T"));
        }
    }

    @Test
    void aFailureBeforeTheAuditWriteRollsTheWholePostingBackAndTheRetryWritesEachRowOnce() throws Exception {
        Fixture f = fixture();
        try (PendingAuditDrainer ignored = f.drainer()) {
            AtomicBoolean failOnce = new AtomicBoolean(true);
            LedgerPorts.PostingJournal faulty = new LedgerPorts.PostingJournal() {
                @Override
                public void posted(Connection c, LedgerStore.Entry entry, List<LedgerPorts.AccountingEntry> accounting) {
                    if (failOnce.getAndSet(false)) {
                        throw new IllegalStateException("injected: crash after the ledger rows, before the audit write");
                    }
                    f.journal().posted(c, entry, accounting);
                }

                @Override
                public void accounting(Connection c, List<LedgerPorts.AccountingEntry> entries) {
                    f.journal().accounting(c, entries);
                }

                @Override
                public void committed(LedgerStore.Entry entry) {
                    f.journal().committed(entry);
                }
            };
            LedgerService s = service(f.ds(), faulty, new ArrayList<>());
            LedgerRequest req = LedgerRequest.of("ALLOCATE", K, new BigDecimal("500"), "alloc-2");
            assertThrows(IllegalStateException.class, () -> s.apply(req));
            assertEquals(0, entries(f, "alloc-2"), "the LDG_ENTRY row rolled back with the failure");
            assertEquals(0, s.available(K).signum(), "no balance moved");
            assertEquals(0, auditRows(f));
            assertEquals(0, glRows(f, "alloc-2"));

            LedgerService.Result retry = s.apply(req);
            assertTrue(retry.applied());
            assertFalse(retry.duplicate(), "the first attempt left nothing, so the retry is the posting");
            assertEquals(1, entries(f, "alloc-2"));
            assertEquals(1, auditRows(f), "exactly one audit row");
            assertEquals(1, glRows(f, "alloc-2"), "exactly one GL outbox row");

            LedgerService.Result again = s.apply(req);
            assertTrue(again.duplicate());
            assertEquals(1, auditRows(f), "a duplicate adds no audit row");
            assertEquals(1, glRows(f, "alloc-2"), "a duplicate adds no GL row");
            assertEquals(0, new BigDecimal("500").compareTo(s.available(K)), "applied once");
        }
    }

    @Test
    void aRefusedPostingWritesNeitherAuditNorGl() throws Exception {
        Fixture f = fixture();
        try (PendingAuditDrainer ignored = f.drainer()) {
            LedgerService s = service(f.ds(), f.journal(), new ArrayList<>());
            LedgerService.Result r = s.apply(LedgerRequest.of("COMMIT", K, BigDecimal.TEN, "commit-1"));
            assertFalse(r.applied(), "nothing allocated yet");
            assertEquals(0, entries(f, "commit-1"));
            assertEquals(0, auditRows(f));
        }
    }

    @Test
    void theAuditPayloadAndChainOfAPosting() {
        LedgerStore.Entry withRef = new LedgerStore.Entry("T", "r-1", "ALLOCATE", "case-9", "officer", null, List.of(),
                Map.of("amount", "5.00"));
        assertEquals("case-9", LedgerJournal.chainOf(withRef));
        assertEquals(Map.of("reference", "r-1", "operation", "ALLOCATE", "summary", Map.of("amount", "5.00"),
                "actor", "officer"), LedgerJournal.payload(withRef));
        LedgerStore.Entry noRef = new LedgerStore.Entry("T", "r-2", "ALLOCATE", " ", null, null, List.of(), null);
        assertEquals("ledger:T", LedgerJournal.chainOf(noRef), "a blank audit reference uses the tenant ledger chain");
    }

    /** RM-49: no tenant means no audit row and no GL row, never a row charged to a default or empty tenant. */
    @Test
    void aPostingOrAccountingEntryWithoutATenantIsRefusedAndWritesNothing() throws Exception {
        for (String none : new String[]{null, "", "  ", org.sangam.core.layer.TenantKey.SHARED}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> LedgerJournal.requireTenant(none, "posting", "r-x"));
            assertTrue(e.getMessage().contains("r-x") && e.getMessage().contains("no tenant"), e.getMessage());
        }
        assertEquals("T", LedgerJournal.requireTenant("T", "posting", "r-x"));

        Fixture f = fixture();
        try (PendingAuditDrainer ignored = f.drainer()) {
            LedgerStore.Entry blank = new LedgerStore.Entry(" ", "r-3", "ALLOCATE", null, "officer", null, List.of(),
                    null);
            LedgerPorts.AccountingEntry acc = new LedgerPorts.AccountingEntry(" ", "r-3", "ALLOCATE", "CASH", "BANK",
                    "LINE", new BigDecimal("5.00"), "INR", new BigDecimal("5.00"));
            assertThrows(IllegalArgumentException.class, () -> f.tx().inTransaction(c -> {
                f.journal().posted(c, blank, List.of(acc));
                return null;
            }));
            JdbcLedgerStore store = new JdbcLedgerStore(f.ds(), D, f.journal());
            assertThrows(IllegalArgumentException.class, () -> store.exportAccounting(List.of(acc)),
                    "the refusal reaches the caller");
            assertEquals(0, count(f.tx(), "SELECT COUNT(*) FROM AUDIT_PENDING"), "no audit row at all");
            assertEquals(0, count(f.tx(), "SELECT COUNT(*) FROM OUTBOX"), "no GL row at all");
        }
    }

    @Test
    void revaluationEntriesGoThroughTheJournalToo() throws Exception {
        Fixture f = fixture();
        try (PendingAuditDrainer ignored = f.drainer()) {
            JdbcLedgerStore store = new JdbcLedgerStore(f.ds(), D, f.journal());
            assertTrue(store.journals());
            store.exportAccounting(List.of(new LedgerPorts.AccountingEntry("T", "reval:2026-11-12:x", "REVALUE",
                    "ACCRUAL", "EXCHANGE_DIFFERENCE", "LINE", new BigDecimal("1.50"), "INR", new BigDecimal("1.50"))));
            assertEquals(1, glRows(f, "reval:2026-11-12:x"));
            assertEquals(0, auditRows(f), "a revaluation is no posting: no LEDGER_POSTED");
        }
    }

    @Test
    void aStoreWithoutAJournalKeepsTheAfterCommitSinks() throws Exception {
        Fixture f = fixture();
        try (PendingAuditDrainer ignored = f.drainer()) {
            List<String> sinks = new ArrayList<>();
            LedgerService s = service(f.ds(), null, sinks);
            s.apply(LedgerRequest.of("ALLOCATE", K, new BigDecimal("5"), "alloc-3"));
            assertEquals(List.of("gl:1", "audit:alloc-3"), sinks);
            assertEquals(0, auditRows(f));
            assertThrows(UnsupportedOperationException.class, () -> new JdbcLedgerStore(f.ds(), D).exportAccounting(List.of()));
        }
    }
}

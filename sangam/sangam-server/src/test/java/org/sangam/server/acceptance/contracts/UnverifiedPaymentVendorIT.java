// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.sangam.billing.AccrualPauses;
import org.sangam.billing.JdbcUnverifiedPaymentStore;
import org.sangam.billing.UnverifiedPayment;
import org.sangam.billing.UnverifiedPaymentException;
import org.sangam.billing.UnverifiedPaymentStore;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.PendingAudit;
import org.sangam.core.layer.PendingAuditDrainer;
import org.sangam.core.money.Money;
import org.sangam.ledger.AccountKey;

/**
 * RM-03 (owner decision 141) on every acceptance database (Oracle and SQL Server included, {@code -Dsangam.db}), on the
 * production schema ({@code BILL_UNVERIFIED_PAYMENT} from the Liquibase changelogs): the unique index refuses a second
 * acceptance under concurrency, a transition locks the row and the second of two concurrent resolutions sees the
 * first's state, and every column round-trips (minor units, CLOB JSON, timestamps, nullable resolution columns;
 * Oracle's empty-string-as-NULL included). Decisions 195 and 196: the accrual pauses ({@code BILL_UNVERIFIED_ACCRUAL_PAUSE})
 * and the manual-VERIFIED evidence column round-trip, a resolved payment can never be paused, a resumption is marked
 * once; the filtered list pages in SQL (OFFSET ... FETCH on Oracle and SQL Server). Needs the W4-G changelog with the
 * tables; NOT_RUN in the coding phase.
 */
class UnverifiedPaymentVendorIT extends LayerSpecSupport {

    private JdbcUnverifiedPaymentStore store() {
        PendingAuditDrainer drainer = new PendingAuditDrainer(new PendingAudit(TX, DIALECT, new AuditChain(DIALECT)),
                new PendingAuditDrainer.Settings(1, Duration.ZERO, Duration.ZERO, 10));
        return new JdbcUnverifiedPaymentStore(TX, DIALECT, drainer);
    }

    private static UnverifiedPayment pending(String tenant, String ref) {
        return new UnverifiedPayment(tenant, UUID.randomUUID().toString(), "DEMAND", "C-1", "OFF-1", "counter", ref,
                Money.parse("1234.56", "INR"), Instant.parse("2026-11-12T05:30:00Z"), null, "CHEQUE", Map.of(),
                "clearing pending", "RCPT-7/112", "counter-2026-1", List.of(), "cashier",
                Instant.parse("2026-11-12T06:00:00Z"), UnverifiedPayment.State.PAYMENT_ACCEPTED_UNVERIFIED, 1, null,
                null);
    }

    private static UnverifiedPayment resolvedAs(UnverifiedPayment l, UnverifiedPayment.State outcome) {
        return new UnverifiedPayment(l.tenant(), l.id(), l.ledgerId(), l.consumer(), l.office(), l.channel(),
                l.paymentRef(), l.amount(), l.paidAt(), l.payerName(), l.instrument(), l.details(), l.reason(),
                l.evidenceRef(), l.policyVersion(), l.downstreamPermitted(), l.acceptedBy(), l.acceptedAt(), outcome,
                l.version() + 1, new UnverifiedPayment.Resolution(outcome, "vendor test", null, "EVID-1", "supervisor",
                Instant.parse("2026-11-13T06:00:00Z")), l.receiptNo());
    }

    @Test
    void everyColumnRoundTrips() {
        JdbcUnverifiedPaymentStore s = store();
        String tenant = "t-" + UUID.randomUUID();
        UnverifiedPayment p = pending(tenant, "CHQ-1");
        assertTrue(s.insert(p, "PAYMENT_ACCEPTED_UNVERIFIED", Map.of("payment_id", p.id())));
        UnverifiedPayment back = s.find(tenant, p.id()).orElseThrow();
        assertEquals(p.amount(), back.amount());
        assertEquals(p.paidAt(), back.paidAt());
        assertEquals(Map.of(), back.details(), "an empty map is stored as {} and read back (never NULL on Oracle)");
        assertEquals(List.of(), back.downstreamPermitted());
        assertEquals(null, back.payerName());
        assertEquals(null, back.resolution());
        assertEquals(1, (long) inTx(c -> Jdbc.one(c, "SELECT COUNT(*) FROM AUDIT_PENDING WHERE tenant_id=?",
                rs -> rs.getLong(1), tenant).orElse(0L)));
    }

    @Test
    void concurrentAcceptancesOfOnePaymentLeaveOneRecord() throws Exception {
        JdbcUnverifiedPaymentStore s = store();
        String tenant = "t-" + UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Boolean>> all = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                all.add(pool.submit(() -> {
                    go.await();
                    return s.insert(pending(tenant, "CHQ-1"), "PAYMENT_ACCEPTED_UNVERIFIED", Map.of());
                }));
            }
            go.countDown();
            int inserted = 0;
            for (Future<Boolean> f : all) {
                inserted += f.get(60, TimeUnit.SECONDS) ? 1 : 0;
            }
            assertEquals(1, inserted);
            assertEquals(1, s.list(tenant, null, 10).size());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theSecondOfTwoConcurrentResolutionsIsRefused() throws Exception {
        JdbcUnverifiedPaymentStore s = store();
        String tenant = "t-" + UUID.randomUUID();
        UnverifiedPayment p = pending(tenant, "CHQ-1");
        s.insert(p, "PAYMENT_ACCEPTED_UNVERIFIED", Map.of());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<String>> all = new ArrayList<>();
            for (UnverifiedPayment.State outcome : List.of(UnverifiedPayment.State.REJECTED,
                    UnverifiedPayment.State.REVERSED)) {
                all.add(pool.submit(() -> {
                    go.await();
                    try {
                        return s.transition(tenant, p.id(), locked -> {
                            if (!locked.state().pending()) {
                                throw new UnverifiedPaymentException(UnverifiedPaymentException.Code.INVALID_TRANSITION,
                                        "already " + locked.state());
                            }
                            return new UnverifiedPaymentStore.Transition(resolvedAs(locked, outcome),
                                    "PAYMENT_UNVERIFIED_RESOLVED", Map.of("outcome", outcome.name()));
                        }).state().name();
                    } catch (UnverifiedPaymentException e) {
                        return e.code().name();
                    }
                }));
            }
            go.countDown();
            List<String> results = new ArrayList<>();
            for (Future<String> f : all) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            assertEquals(1, results.stream().filter("INVALID_TRANSITION"::equals).count(), results.toString());
            UnverifiedPayment done = s.find(tenant, p.id()).orElseThrow();
            assertFalse(done.state().pending());
            assertEquals(2, done.version());
            assertEquals("cashier", done.acceptedBy(), "the accepted columns are unchanged");
            assertEquals("EVID-1", done.resolution().evidenceRef(), "the resolution evidence round-trips");
        } finally {
            pool.shutdownNow();
        }
        assertThrows(UnverifiedPaymentException.class, () -> s.transition(tenant, "missing", l -> null));
    }

    @Test
    void accrualPausesRoundTripAndAResolvedPaymentIsNeverPaused() {
        JdbcUnverifiedPaymentStore s = store();
        String tenant = "t-" + UUID.randomUUID();
        UnverifiedPayment p = pending(tenant, "CHQ-1");
        s.insert(p, "PAYMENT_ACCEPTED_UNVERIFIED", Map.of());
        LocalDate levy = LocalDate.parse("2026-11-22");
        AccountKey penalty = new AccountKey(tenant, "DEMAND", "2026", "PENALTY", "C-1", "DEMAND", "2026-10");
        AccrualPauses.Pause pause = new AccrualPauses.Pause(p.id(), "acct-1", levy, LocalDate.parse("2026-11-12"),
                penalty, Money.parse("0.40", "INR"));
        assertEquals(List.of(new AccrualPauses.Pending(p.id(), p.amount(), p.acceptedAt())),
                s.pending(tenant, "DEMAND", "C-1"));
        assertTrue(s.recordPauses(tenant, "DEMAND", "C-1", levy, List.of(p.id()), List.of(pause)));
        AccrualPauses.Pause other = new AccrualPauses.Pause(p.id(), "acct-1", levy, LocalDate.parse("2026-11-12"),
                penalty, Money.parse("0.90", "INR"));
        assertTrue(s.recordPauses(tenant, "DEMAND", "C-1", levy, List.of(p.id()), List.of(other)));
        assertEquals(List.of(pause), s.pausesOf(tenant, p.id()), "the first pause of a levy date is kept");
        assertEquals(Map.of("acct-1", Money.parse("0.40", "INR")), s.pausedOn(tenant, "DEMAND", "C-1", levy));
        s.transition(tenant, p.id(), locked -> new UnverifiedPaymentStore.Transition(
                resolvedAs(locked, UnverifiedPayment.State.REJECTED), "PAYMENT_UNVERIFIED_RESOLVED", Map.of()));
        assertFalse(s.recordPauses(tenant, "DEMAND", "C-1", levy.plusDays(1), List.of(p.id()), List.of()),
                "a resolved payment can never be paused");
        assertTrue(s.pending(tenant, "DEMAND", "C-1").isEmpty());
        assertEquals(List.of(p.id()), s.resumptionDue(tenant, 10).stream().map(UnverifiedPayment::id).toList());
        assertTrue(s.tenantsWithWork(100_000).contains(tenant));
        assertTrue(s.markResumed(tenant, p.id(), Instant.parse("2026-11-23T06:00:00Z"),
                "PAYMENT_UNVERIFIED_ACCRUAL_RESUMED", Map.of("payment_id", p.id())));
        assertFalse(s.markResumed(tenant, p.id(), Instant.parse("2026-11-23T06:00:00Z"),
                "PAYMENT_UNVERIFIED_ACCRUAL_RESUMED", Map.of("payment_id", p.id())), "marked once");
        assertTrue(s.resumptionDue(tenant, 10).isEmpty());
        assertTrue(s.pausesToResume(tenant, p.id()).isEmpty());
        assertEquals(1, s.pausesOf(tenant, p.id()).size(), "a levied pause stays on record");
        assertEquals(1, (long) inTx(c -> Jdbc.one(c, "SELECT COUNT(*) FROM AUDIT_PENDING WHERE tenant_id=? AND"
                + " event_type=?", rs -> rs.getLong(1), tenant, "PAYMENT_UNVERIFIED_ACCRUAL_RESUMED").orElse(0L)));
    }

    @Test
    void theFilteredListPagesInSql() {
        JdbcUnverifiedPaymentStore s = store();
        String tenant = "t-" + UUID.randomUUID();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            UnverifiedPayment p = pending(tenant, "CHQ-" + i);
            s.insert(p, "PAYMENT_ACCEPTED_UNVERIFIED", Map.of());
            ids.add(p.id());
        }
        s.transition(tenant, ids.get(0), locked -> new UnverifiedPaymentStore.Transition(
                resolvedAs(locked, UnverifiedPayment.State.REJECTED), "PAYMENT_UNVERIFIED_RESOLVED", Map.of()));
        List<String> pendingIds = ids.subList(1, 5).stream().sorted().toList();    // same accepted_at: by id
        UnverifiedPaymentStore.Slice page = s.query(tenant, new UnverifiedPaymentStore.Query("DEMAND",
                UnverifiedPayment.State.PAYMENT_ACCEPTED_UNVERIFIED, "counter", "OFF-1", null, 1, 2));
        assertEquals(4, page.total());
        assertEquals(pendingIds.subList(1, 3), page.items().stream().map(UnverifiedPayment::id).toList());
        assertEquals(0, s.query(tenant, new UnverifiedPaymentStore.Query("DEMAND", null, null, null,
                Instant.parse("2026-11-12T05:59:59Z"), 0, 10)).total(), "accepted at or before");
        assertEquals(5, s.query(tenant, new UnverifiedPaymentStore.Query("DEMAND", null, null, null,
                Instant.parse("2026-11-12T06:00:00Z"), 0, 10)).total());
        assertTrue(s.query(tenant, new UnverifiedPaymentStore.Query("DEMAND", null, null, null, null, 5, 10)).items()
                .isEmpty());
    }}

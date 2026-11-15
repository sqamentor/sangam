// SPDX-License-Identifier: Apache-2.0
package org.sangam.billing;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.sangam.billing.UnverifiedPayment.State;
import org.sangam.billing.UnverifiedPaymentAuthority.Caller;
import org.sangam.billing.UnverifiedPaymentException.Code;
import org.sangam.config.catalogue.NumberSeries;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.PendingAudit;
import org.sangam.core.layer.PendingAuditDrainer;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;
import org.sangam.core.money.Money;
import org.sangam.core.time.BusinessClock;
import org.sangam.ledger.LedgerSchema;
import org.sangam.ledger.LedgerService;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RM-03 (owner decisions 141, 194, 196 and 201) on H2 (PostgreSQL mode, the portable store): unverified acceptance only
 * by an authenticated staff caller holding the scoped action (API keys, service and system identities refused), on an
 * enabled channel, with reason, evidence and payment details; pending posts nothing; one record per channel and
 * reference; resolution rechecked atomically on the locked row; maker-checker always; a manual VERIFIED needs evidence
 * and has its own audit event; reconciliation against the rail; ageing; the list filters before paging. The accrual
 * pause (decision 195) is in {@code UnverifiedAccrualPauseH2Test}. Oracle and SQL Server run the store in
 * {@code UnverifiedPaymentVendorIT} (sangam-server acceptance, NOT_RUN in the coding phase).
 */
class UnverifiedPaymentsH2Test {

    static final Dialect D = Dialect.POSTGRESQL;
    static final Caller CASHIER = Caller.staff("cashier", "T", "seat-c");
    static final Caller SUPERVISOR = Caller.staff("supervisor", "T", "seat-s");
    static final Caller CLERK = Caller.staff("clerk", "T", "seat-k");

    /** Grants as "principal|action|tenant|office|channel". */
    static final class Grants implements UnverifiedPaymentAuthority {
        final Set<String> grants = new HashSet<>();
        final List<String> asked = new ArrayList<>();
        volatile boolean failing;

        Grants grant(Caller c, String action, String office, String channel) {
            grants.add(c.principalId() + "|" + action + "|" + c.tenant() + "|" + office + "|" + channel);
            return this;
        }

        @Override
        public boolean permits(Caller caller, String action, String tenant, String office, String channel) {
            asked.add(caller.principalId() + "|" + action);
            if (failing) {
                throw new IllegalStateException("authorisation store unavailable");
            }
            return grants.contains(caller.principalId() + "|" + action + "|" + tenant + "|" + office + "|" + channel);
        }
    }

    /** A clock the test moves. */
    static final class Moving extends Clock {
        volatile Instant now = Instant.parse("2026-11-12T06:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    record Fixture(Transactions tx, PendingAuditDrainer drainer, LedgerService ledger, UnverifiedPayments service,
                   Grants grants, Moving clock, TestRails.Settling rail, BillingService billing,
                   JdbcUnverifiedPaymentStore store) {
    }

    static Map<String, String> counterPolicy() {
        Map<String, String> m = new HashMap<>();
        String p = UnverifiedPaymentPolicy.PREFIX + "counter.";
        m.put(p + "policy-version", "counter-2026-1");
        m.put(p + "currency", "INR");
        m.put(p + "max-amount", "500.00");
        m.put(p + "maker-checker", "true");
        m.put(p + "ageing-alert-after", "P3D");
        return m;
    }

    static Fixture fixture(BillingService.PaymentPort rails) throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=10000");
        try (Connection c = ds.getConnection()) {
            Schema.create(c, D);
            PendingAudit.create(c, D);
            LedgerSchema.create(c, D);
        }
        Transactions tx = Transactions.of(ds);
        PendingAuditDrainer drainer = new PendingAuditDrainer(new PendingAudit(tx, D, new AuditChain(D)),
                new PendingAuditDrainer.Settings(1, Duration.ZERO, Duration.ZERO, 10));
        LedgerService ledger = BillingServiceTest.ledger();
        TestRails.Settling settling = new TestRails.Settling();
        // "kiosk" is trusted but has no operating policy: disabled
        PaymentConfirmationPolicy trusted = PaymentConfirmationPolicy.fromConfiguration("counter,kiosk", true, true);
        JdbcUnverifiedPaymentStore store = new JdbcUnverifiedPaymentStore(tx, D, drainer);
        BillingService billing = new BillingService(ledger, new TariffEngine(null),
                DocumentNumbers.overSeries(new NumberSeries.InMemory()), settling, trusted, store,
                BusinessClock.utc(BillingRetryTest.AT));
        billing.invoice("T", "DEMAND", "2026", "C-1", "2026-10", BillingRetryTest.FLAT, Map.of(), Map.of(),
                LocalDate.parse("2026-11-01"), "sys");
        Map<String, String> props = counterPolicy();
        Grants grants = new Grants()
                .grant(CASHIER, UnverifiedPaymentAuthority.ACCEPT_UNVERIFIED, "OFF-1", "counter")
                .grant(SUPERVISOR, UnverifiedPaymentAuthority.RESOLVE_UNVERIFIED, "OFF-1", "counter")
                .grant(CASHIER, UnverifiedPaymentAuthority.RESOLVE_UNVERIFIED, "OFF-1", "counter");
        Moving clock = new Moving();
        UnverifiedPayments service = new UnverifiedPayments(billing,
                UnverifiedPaymentPolicy.from(trusted.trustedChannels(), props::get), grants, store,
                rails == null ? settling : rails, clock);
        return new Fixture(tx, drainer, ledger, service, grants, clock, settling, billing, store);
    }

    static UnverifiedPayments.AcceptRequest request(String ref, String amount) {
        return new UnverifiedPayments.AcceptRequest("DEMAND", "C-1", "OFF-1", "counter", ref, Money.parse(amount, "INR"),
                Instant.parse("2026-11-12T05:30:00Z"), "A. Payer", "CHEQUE", Map.of("bank", "SBI", "cheque", "004512"),
                "cheque deposited, clearing pending", "RCPT-BOOK-7/112");
    }

    static long rows(Fixture f) {
        return f.tx().inTransaction(c -> Jdbc.one(c, "SELECT COUNT(*) FROM BILL_UNVERIFIED_PAYMENT", rs -> rs.getLong(1))
                .orElse(0L));
    }

    /**
     * The audit events of a type, wherever they are at this instant: still pending ({@code AUDIT_PENDING}) or already
     * appended to the record's chain ({@code AUDIT_EVENT}). The store signals the drainer after every write and the
     * drain moves a row from the first table to the second on its own thread, in one transaction, so a count of
     * {@code AUDIT_PENDING} alone depends on how far that thread has got. One statement reads both tables in one
     * snapshot: an event is counted once, before or after its drain.
     */
    static long audit(Fixture f, String type) {
        return f.tx().inTransaction(c -> Jdbc.one(c, "SELECT (SELECT COUNT(*) FROM AUDIT_PENDING WHERE event_type=?)"
                + " + (SELECT COUNT(*) FROM AUDIT_EVENT WHERE event_type=?)", rs -> rs.getLong(1), type, type)
                .orElse(0L));
    }

    static BigDecimal outstanding(Fixture f) {
        return BillingRetryTest.outstanding(f.ledger(), "C-1");
    }

    /** The payloads of the audit events of a type, pending or already on the chain (see {@link #audit}). */
    static String auditPayload(Fixture f, String type) {
        return f.tx().inTransaction(c -> String.join("\n", Jdbc.query(c,
                "SELECT payload FROM AUDIT_PENDING WHERE event_type=? UNION ALL SELECT p.payload FROM AUDIT_PAYLOAD p"
                        + " JOIN AUDIT_EVENT e ON e.event_id = p.event_id WHERE e.event_type=?",
                rs -> rs.getString(1), type, type)));
    }

    static void refused(Code code, org.junit.jupiter.api.function.Executable call) {
        UnverifiedPaymentException e = assertThrows(UnverifiedPaymentException.class, call);
        assertEquals(code, e.code(), e.getMessage());
    }

    @Test
    void anAcceptanceIsPendingAuditedAndPostsNothing() throws Exception {
        Fixture f = fixture(null);
        try (PendingAuditDrainer ignored = f.drainer()) {
            UnverifiedPayment p = f.service().accept(CASHIER, "T", request("CHQ-1", "40.00"));
            assertEquals(State.PAYMENT_ACCEPTED_UNVERIFIED, p.state());
            assertEquals("cashier", p.acceptedBy(), "the actor is the authenticated caller");
            assertEquals("counter-2026-1", p.policyVersion());
            assertTrue(p.downstreamPermitted().isEmpty(), "nothing is permitted downstream while pending");
            assertEquals(0, outstanding(f).compareTo(new BigDecimal("100")), "the demand stays open");
            assertEquals(1, audit(f, UnverifiedPayments.PAYMENT_ACCEPTED_UNVERIFIED));
            UnverifiedPayment stored = f.service().find("T", p.id()).orElseThrow();
            assertEquals(p.amount(), stored.amount());
            assertEquals(Map.of("bank", "SBI", "cheque", "004512"), stored.details());
            assertNull(stored.resolution());
            assertNull(stored.receiptNo());
        }
    }

    @Test
    void aMissingForgedOrUnauthorisedCallerIsRefusedAndNothingIsWritten() throws Exception {
        Fixture f = fixture(null);
        try (PendingAuditDrainer ignored = f.drainer()) {
            refused(Code.NOT_PERMITTED, () -> f.service().accept(null, "T", request("CHQ-1", "40.00")));
            // a principal named in no grant (for example one a payload tried to claim) holds nothing
            refused(Code.NOT_PERMITTED, () -> f.service().accept(CLERK, "T", request("CHQ-1", "40.00")));
            // holding RESOLVE_UNVERIFIED does not imply ACCEPT_UNVERIFIED
            refused(Code.NOT_PERMITTED, () -> f.service().accept(SUPERVISOR, "T", request("CHQ-1", "40.00")));
            f.grants().failing = true;
            refused(Code.NOT_PERMITTED, () -> f.service().accept(CASHIER, "T", request("CHQ-1", "40.00")));
            assertThrows(IllegalArgumentException.class, () -> Caller.staff(" ", "T", null), "no anonymous caller");
            assertEquals(0, rows(f));
            assertEquals(0, audit(f, UnverifiedPayments.PAYMENT_ACCEPTED_UNVERIFIED));
        }
    }

    @Test
    void wrongChannelCrossTenantAndCrossOfficeAreRefused() throws Exception {
        Fixture f = fixture(null);
        try (PendingAuditDrainer ignored = f.drainer()) {
            UnverifiedPayments.AcceptRequest r = request("CHQ-1", "40.00");
            refused(Code.CHANNEL_NOT_ENABLED, () -> f.service().accept(CASHIER, "T", new UnverifiedPayments.AcceptRequest(
                    r.ledgerId(), r.consumer(), r.office(), "cash", r.paymentRef(), r.amount(), r.paidAt(),
                    r.payerName(), r.instrument(), r.details(), r.reason(), r.evidenceRef())));
            refused(Code.CHANNEL_NOT_ENABLED, () -> f.service().accept(CASHIER, "T", new UnverifiedPayments.AcceptRequest(
                    r.ledgerId(), r.consumer(), r.office(), "kiosk", r.paymentRef(), r.amount(), r.paidAt(),
                    r.payerName(), r.instrument(), r.details(), r.reason(), r.evidenceRef())));
            refused(Code.NOT_PERMITTED, () -> f.service().accept(Caller.staff("cashier", "U", null), "T", r));
            refused(Code.NOT_PERMITTED, () -> f.service().accept(CASHIER, "U", r));
            refused(Code.NOT_PERMITTED, () -> f.service().accept(CASHIER, "T", new UnverifiedPayments.AcceptRequest(
                    r.ledgerId(), r.consumer(), "OFF-2", r.channel(), r.paymentRef(), r.amount(), r.paidAt(),
                    r.payerName(), r.instrument(), r.details(), r.reason(), r.evidenceRef())));
            assertEquals(0, rows(f));
        }
    }

    @Test
    void requiredFieldsCurrencyAndTheLimit() throws Exception {
        Fixture f = fixture(null);
        try (PendingAuditDrainer ignored = f.drainer()) {
            UnverifiedPayments.AcceptRequest r = request("CHQ-1", "40.00");
            refused(Code.INVALID_REQUEST, () -> f.service().accept(CASHIER, "T", new UnverifiedPayments.AcceptRequest(
                    r.ledgerId(), r.consumer(), r.office(), r.channel(), r.paymentRef(), r.amount(), r.paidAt(),
                    r.payerName(), r.instrument(), r.details(), " ", r.evidenceRef())));
            refused(Code.INVALID_REQUEST, () -> f.service().accept(CASHIER, "T", new UnverifiedPayments.AcceptRequest(
                    r.ledgerId(), r.consumer(), r.office(), r.channel(), r.paymentRef(), r.amount(), r.paidAt(),
                    r.payerName(), r.instrument(), r.details(), r.reason(), null)));
            refused(Code.INVALID_REQUEST, () -> f.service().accept(CASHIER, "T", new UnverifiedPayments.AcceptRequest(
                    r.ledgerId(), r.consumer(), r.office(), r.channel(), r.paymentRef(), r.amount(), null,
                    r.payerName(), r.instrument(), r.details(), r.reason(), r.evidenceRef())));
            refused(Code.INVALID_REQUEST, () -> f.service().accept(CASHIER, "T", new UnverifiedPayments.AcceptRequest(
                    r.ledgerId(), r.consumer(), r.office(), r.channel(), r.paymentRef(), Money.parse("40.00", "USD"),
                    r.paidAt(), r.payerName(), r.instrument(), r.details(), r.reason(), r.evidenceRef())));
            refused(Code.INVALID_REQUEST, () -> f.service().accept(CASHIER, "T", request("CHQ-0", "0.00")));
            refused(Code.LIMIT_EXCEEDED, () -> f.service().accept(CASHIER, "T", request("CHQ-9", "500.01")));
            assertEquals(0, rows(f));
            assertNotNull(f.service().accept(CASHIER, "T", request("CHQ-8", "500.00")), "the limit itself is allowed");
        }
    }

    @Test
    void aReplayOrSecondAcceptanceOfTheSamePaymentIsRefused() throws Exception {
        Fixture f = fixture(null);
        try (PendingAuditDrainer ignored = f.drainer()) {
            UnverifiedPayment first = f.service().accept(CASHIER, "T", request("CHQ-1", "40.00"));
            UnverifiedPaymentException e = assertThrows(UnverifiedPaymentException.class,
                    () -> f.service().accept(CASHIER, "T", request("CHQ-1", "40.00")));
            assertEquals(Code.DUPLICATE, e.code());
            assertTrue(e.getMessage().contains(first.id()), e.getMessage());
            refused(Code.DUPLICATE, () -> f.service().accept(CASHIER, "T", request("CHQ-1", "45.00")));
            assertEquals(1, rows(f));
            assertEquals(1, audit(f, UnverifiedPayments.PAYMENT_ACCEPTED_UNVERIFIED));
        }
    }

    @Test
    void verifiedNeedsAnotherAuthorisedPrincipalThenAllocatesOnce() throws Exception {
        Fixture f = fixture(null);
        try (PendingAuditDrainer ignored = f.drainer()) {
            UnverifiedPayment p = f.service().accept(CASHIER, "T", request("CHQ-1", "40.00"));
            // maker-checker: the acceptor cannot verify their own acceptance
            // (decision 194: always, although the cashier also holds RESOLVE_UNVERIFIED)
            refused(Code.NOT_PERMITTED, () -> f.service().resolve(CASHIER, "T", p.id(), State.VERIFIED, "cleared",
                    "BANK-STMT-1", "SCAN-1"));
            refused(Code.NOT_PERMITTED, () -> f.service().resolve(CASHIER, "T", p.id(), State.REJECTED, "bounced",
                    null, null));
            // an internal call with a principal that lacks RESOLVE_UNVERIFIED
            refused(Code.NOT_PERMITTED, () -> f.service().resolve(CLERK, "T", p.id(), State.VERIFIED, "cleared",
                    "BANK-STMT-1", "SCAN-1"));
            refused(Code.INVALID_REQUEST, () -> f.service().resolve(SUPERVISOR, "T", p.id(), State.VERIFIED, "cleared",
                    " ", "SCAN-1"));
            // decision 196: a manual VERIFIED without evidence is refused
            refused(Code.INVALID_REQUEST, () -> f.service().resolve(SUPERVISOR, "T", p.id(), State.VERIFIED, "cleared",
                    "BANK-STMT-1", null));
            refused(Code.INVALID_REQUEST, () -> f.service().resolve(SUPERVISOR, "T", p.id(), State.VERIFIED, "cleared",
                    "BANK-STMT-1", " "));
            refused(Code.NOT_FOUND, () -> f.service().resolve(SUPERVISOR, "T", "no-such-id", State.REJECTED, "x", null,
                    null));
            assertEquals(State.PAYMENT_ACCEPTED_UNVERIFIED, f.service().find("T", p.id()).orElseThrow().state());
            assertEquals(0, outstanding(f).compareTo(new BigDecimal("100")));
            assertEquals(0, audit(f, UnverifiedPayments.PAYMENT_UNVERIFIED_MANUALLY_VERIFIED));

            UnverifiedPayment v = f.service().resolve(SUPERVISOR, "T", p.id(), State.VERIFIED, "cheque cleared",
                    "BANK-STMT-1", "SCAN-1");
            assertEquals(State.VERIFIED, v.state());
            assertNotNull(v.receiptNo(), "allocated through the receipt path");
            assertEquals(0, outstanding(f).compareTo(new BigDecimal("60")), "VERIFIED consumed the demand");
            UnverifiedPayment stored = f.service().find("T", p.id()).orElseThrow();
            assertEquals(v.receiptNo(), stored.receiptNo());
            assertEquals("cashier", stored.acceptedBy(), "the original record stays");
            assertEquals("cheque deposited, clearing pending", stored.reason());
            assertEquals("supervisor", stored.resolution().resolvedBy());
            assertEquals("SCAN-1", stored.resolution().evidenceRef(), "the evidence of the manual VERIFIED is kept");
            // decision 196: a manual VERIFIED has its own audit event, not the rail's
            assertEquals(1, audit(f, UnverifiedPayments.PAYMENT_UNVERIFIED_MANUALLY_VERIFIED));
            assertEquals(0, audit(f, UnverifiedPayments.PAYMENT_UNVERIFIED_RESOLVED));
            assertTrue(auditPayload(f, UnverifiedPayments.PAYMENT_UNVERIFIED_MANUALLY_VERIFIED).contains("SCAN-1"),
                    "the event carries the evidence reference");
            // already resolved: a second resolution changes nothing
            refused(Code.INVALID_TRANSITION, () -> f.service().resolve(SUPERVISOR, "T", p.id(), State.REVERSED,
                    "returned", null, null));
            assertEquals(0, outstanding(f).compareTo(new BigDecimal("60")));
        }
    }

    @Test
    void rejectedAndReversedPostNothing() throws Exception {
        Fixture f = fixture(null);
        try (PendingAuditDrainer ignored = f.drainer()) {
            UnverifiedPayment a = f.service().accept(CASHIER, "T", request("CHQ-1", "40.00"));
            UnverifiedPayment b = f.service().accept(CASHIER, "T", request("CHQ-2", "30.00"));
            assertEquals(State.REJECTED, f.service().resolve(SUPERVISOR, "T", a.id(), State.REJECTED,
                    "never arrived", null, null).state());
            assertEquals(State.REVERSED, f.service().resolve(SUPERVISOR, "T", b.id(), State.REVERSED,
                    "cheque dishonoured", "RETURN-MEMO-3", "MEMO-SCAN-3").state());
            assertEquals(0, outstanding(f).compareTo(new BigDecimal("100")));
            assertTrue(f.rail().confirmed.isEmpty());
            assertEquals(2, audit(f, UnverifiedPayments.PAYMENT_UNVERIFIED_RESOLVED));
            assertEquals(0, audit(f, UnverifiedPayments.PAYMENT_UNVERIFIED_MANUALLY_VERIFIED));
            assertEquals("MEMO-SCAN-3", f.service().find("T", b.id()).orElseThrow().resolution().evidenceRef());
        }
    }

    @Test
    void concurrentResolutionsAndAcceptancesLeaveExactlyOneOutcome() throws Exception {
        Fixture f = fixture(null);
        try (PendingAuditDrainer ignored = f.drainer()) {
            UnverifiedPayment p = f.service().accept(CASHIER, "T", request("CHQ-1", "40.00"));
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                CountDownLatch go = new CountDownLatch(1);
                Future<State> verify = pool.submit(() -> {
                    go.await();
                    return f.service().resolve(SUPERVISOR, "T", p.id(), State.VERIFIED, "cleared", "STMT-1", "SCAN-1")
                            .state();
                });
                Future<State> reject = pool.submit(() -> {
                    go.await();
                    return f.service().resolve(SUPERVISOR, "T", p.id(), State.REJECTED, "bounced", null, null).state();
                });
                go.countDown();
                int ok = 0;
                int refusedTransitions = 0;
                for (Future<State> r : List.of(verify, reject)) {
                    try {
                        r.get(30, TimeUnit.SECONDS);
                        ok++;
                    } catch (java.util.concurrent.ExecutionException e) {
                        assertEquals(Code.INVALID_TRANSITION, ((UnverifiedPaymentException) e.getCause()).code());
                        refusedTransitions++;
                    }
                }
                assertEquals(1, ok, "exactly one resolution");
                assertEquals(1, refusedTransitions);
                assertEquals(1, audit(f, UnverifiedPayments.PAYMENT_UNVERIFIED_RESOLVED)
                        + audit(f, UnverifiedPayments.PAYMENT_UNVERIFIED_MANUALLY_VERIFIED), "one resolution event");

                CountDownLatch go2 = new CountDownLatch(1);
                List<Future<String>> accepts = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    accepts.add(pool.submit(() -> {
                        go2.await();
                        try {
                            return f.service().accept(CASHIER, "T", request("CHQ-7", "20.00")).id();
                        } catch (UnverifiedPaymentException e) {
                            return e.code().name();
                        }
                    }));
                }
                go2.countDown();
                List<String> results = new ArrayList<>();
                for (Future<String> a : accepts) {
                    results.add(a.get(30, TimeUnit.SECONDS));
                }
                assertEquals(1, results.stream().filter(Code.DUPLICATE.name()::equals).count(), results.toString());
                assertEquals(2, rows(f), "CHQ-1 and one CHQ-7");
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void reconciliationVerifiesOnlyAnExactRailConfirmationAndReportsTheRest() throws Exception {
        Map<String, Money> railSays = new HashMap<>();
        BillingService.PaymentPort rail = new BillingService.PaymentPort() {
            @Override
            public Money confirm(String tenant, String channel, String paymentRef, Money amount) {
                if ("CHQ-ERR".equals(paymentRef)) {
                    throw new IllegalStateException("rail timeout");
                }
                return railSays.get(paymentRef);
            }

            @Override
            public String refund(String tenant, String channel, String paymentRef, Money amount, String operationId) {
                return null;
            }
        };
        Fixture f = fixture(rail);
        try (PendingAuditDrainer ignored = f.drainer()) {
            UnverifiedPayment ok = f.service().accept(CASHIER, "T", request("CHQ-OK", "40.00"));
            UnverifiedPayment off = f.service().accept(CASHIER, "T", request("CHQ-OFF", "30.00"));
            UnverifiedPayment none = f.service().accept(CASHIER, "T", request("CHQ-NONE", "10.00"));
            UnverifiedPayment err = f.service().accept(CASHIER, "T", request("CHQ-ERR", "5.00"));
            railSays.put("CHQ-OK", Money.parse("40.00", "INR"));
            railSays.put("CHQ-OFF", Money.parse("25.00", "INR"));
            Map<String, String> outcome = new HashMap<>();
            for (UnverifiedPayments.Reconciled r : f.service().reconcile("T", 100)) {
                outcome.put(r.id(), r.outcome());
            }
            assertEquals("VERIFIED", outcome.get(ok.id()));
            assertTrue(outcome.get(off.id()).startsWith("AMOUNT_MISMATCH"), outcome.toString());
            assertEquals("NOT_CONFIRMED", outcome.get(none.id()));
            assertEquals("FAILED", outcome.get(err.id()), "one failure does not stop the others");
            UnverifiedPayment v = f.service().find("T", ok.id()).orElseThrow();
            assertEquals(UnverifiedPayments.RECONCILIATION, v.resolution().resolvedBy());
            assertNull(v.resolution().evidenceRef(), "the rail is the evidence of a rail VERIFIED");
            assertEquals(1, audit(f, UnverifiedPayments.PAYMENT_UNVERIFIED_RESOLVED), "the rail path keeps its event");
            assertEquals(0, audit(f, UnverifiedPayments.PAYMENT_UNVERIFIED_MANUALLY_VERIFIED));
            assertNotNull(v.receiptNo());
            assertEquals(0, outstanding(f).compareTo(new BigDecimal("60")), "only the exact confirmation was allocated");
            for (UnverifiedPayment still : List.of(off, none, err)) {
                assertEquals(State.PAYMENT_ACCEPTED_UNVERIFIED, f.service().find("T", still.id()).orElseThrow().state());
            }
            assertTrue(f.service().reconcile("T", 100).stream().noneMatch(r -> r.id().equals(ok.id())),
                    "a reconciled payment is not reconciled again");
        }
    }

    @Test
    void anInterruptedAllocationIsCompletedByReconciliationOnce() throws Exception {
        Fixture f = fixture(null);
        try (PendingAuditDrainer ignored = f.drainer()) {
            UnverifiedPayment p = f.service().accept(CASHIER, "T", request("CHQ-1", "40.00"));
            // VERIFIED committed, then the process stopped before the allocation
            new JdbcUnverifiedPaymentStore(f.tx(), D, f.drainer()).transition("T", p.id(), locked ->
                    new UnverifiedPaymentStore.Transition(locked.resolved(new UnverifiedPayment.Resolution(
                            State.VERIFIED, "cleared", "STMT-9", "SCAN-9", "supervisor", f.clock().instant())),
                            UnverifiedPayments.PAYMENT_UNVERIFIED_RESOLVED, Map.of("payment_id", p.id())));
            assertEquals(0, outstanding(f).compareTo(new BigDecimal("100")));
            List<UnverifiedPayments.Reconciled> r = f.service().reconcile("T", 100);
            assertEquals("ALLOCATED", r.get(0).outcome());
            assertEquals(0, outstanding(f).compareTo(new BigDecimal("60")));
            f.service().reconcile("T", 100);
            assertEquals(0, outstanding(f).compareTo(new BigDecimal("60")), "allocated once");
        }
    }

    @Test
    void ageingReportsPendingPaymentsOlderThanTheChannelAlertAge() throws Exception {
        Fixture f = fixture(null);
        try (PendingAuditDrainer ignored = f.drainer()) {
            UnverifiedPayment p = f.service().accept(CASHIER, "T", request("CHQ-1", "40.00"));
            f.clock().now = f.clock().now.plus(Duration.ofDays(2));
            assertTrue(f.service().ageing("T", 100).isEmpty());
            f.clock().now = f.clock().now.plus(Duration.ofDays(2));
            assertEquals(List.of(p.id()), f.service().ageing("T", 100).stream().map(UnverifiedPayment::id).toList());
            assertEquals(4, f.service().find("T", p.id()).orElseThrow().ageDays(f.clock().instant()));
        }
    }

    /** Decision 201: only signed-in staff act; an API key, a service or a system identity is refused before anything. */
    @Test
    void apiKeysServiceAndSystemIdentitiesCannotAcceptOrResolve() throws Exception {
        Fixture f = fixture(null);
        try (PendingAuditDrainer ignored = f.drainer()) {
            UnverifiedPayment p = f.service().accept(CASHIER, "T", request("CHQ-1", "40.00"));
            int asked = f.grants().asked.size();
            for (UnverifiedPaymentAuthority.ActorType t : new UnverifiedPaymentAuthority.ActorType[]{
                    UnverifiedPaymentAuthority.ActorType.API_KEY, UnverifiedPaymentAuthority.ActorType.SERVICE,
                    UnverifiedPaymentAuthority.ActorType.SYSTEM}) {
                // the same principals that hold the grants, authenticated otherwise than as signed-in staff
                Caller cashier = new Caller("cashier", "T", "seat-c", t);
                Caller supervisor = new Caller("supervisor", "T", "seat-s", t);
                refused(Code.NOT_PERMITTED, () -> f.service().accept(cashier, "T", request("CHQ-" + t, "10.00")));
                refused(Code.NOT_PERMITTED, () -> f.service().resolve(supervisor, "T", p.id(), State.REJECTED,
                        "bounced", null, null));
                refused(Code.NOT_PERMITTED, () -> f.service().resolve(supervisor, "T", p.id(), State.VERIFIED,
                        "cleared", "STMT-1", "SCAN-1"));
            }
            // a caller whose kind of sign-in is not stated is a service identity
            refused(Code.NOT_PERMITTED, () -> f.service().resolve(new Caller("supervisor", "T", "seat-s"), "T", p.id(),
                    State.REJECTED, "bounced", null, null));
            assertEquals(UnverifiedPaymentAuthority.ActorType.SERVICE, new Caller("x", "T", null, null).actorType());
            assertEquals(asked, f.grants().asked.size(), "refused before the authority is asked");
            assertEquals(1, rows(f));
            assertEquals(State.PAYMENT_ACCEPTED_UNVERIFIED, f.service().find("T", p.id()).orElseThrow().state());
            assertEquals(0, audit(f, UnverifiedPayments.PAYMENT_UNVERIFIED_RESOLVED));
        }
    }

    /** Decision 195: the levy and the resolutions must see the same pauses; a mismatched wiring does not start. */
    @Test
    void theBillingServiceMustPauseAccrualInTheSameStore() throws Exception {
        Fixture f = fixture(null);
        try (PendingAuditDrainer ignored = f.drainer()) {
            PaymentConfirmationPolicy trusted = PaymentConfirmationPolicy.fromConfiguration("counter", true, true);
            BillingService unpaused = new BillingService(f.ledger(), new TariffEngine(null),
                    DocumentNumbers.overSeries(new NumberSeries.InMemory()), f.rail(), trusted,
                    BusinessClock.utc(BillingRetryTest.AT));
            UnverifiedPaymentPolicy policy = UnverifiedPaymentPolicy.from(trusted.trustedChannels(),
                    counterPolicy()::get);
            assertThrows(IllegalStateException.class, () -> new UnverifiedPayments(unpaused, policy, f.grants(),
                    f.store(), f.rail(), f.clock()));
            assertThrows(IllegalStateException.class, () -> new UnverifiedPayments(f.billing(), policy, f.grants(),
                    new JdbcUnverifiedPaymentStore(f.tx(), D, f.drainer()), f.rail(), f.clock()));
        }
    }

    /** The list bug (500-row cap before filtering, page and size ignored): every filter applies before the page. */
    @Test
    void theListFiltersBeforePagingAndCountsEveryMatch() throws Exception {
        Fixture f = fixture(null);
        try (PendingAuditDrainer ignored = f.drainer()) {
            List<String> pending = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                UnverifiedPayment p = f.service().accept(CASHIER, "T", request("CHQ-" + i, "1.00"));
                f.clock().now = f.clock().now.plus(Duration.ofHours(1));
                if (i < 3) {    // the three oldest are resolved: a page cut before filtering would start with them
                    f.service().resolve(SUPERVISOR, "T", p.id(), State.REJECTED, "bounced", null, null);
                } else {
                    pending.add(p.id());
                }
            }
            UnverifiedPaymentStore.Slice first = f.service().list("T", new UnverifiedPaymentStore.Query("DEMAND",
                    State.PAYMENT_ACCEPTED_UNVERIFIED, null, null, null, 0, 2));
            assertEquals(3, first.total(), "every match is counted, not only the page");
            assertEquals(pending.subList(0, 2), first.items().stream().map(UnverifiedPayment::id).toList());
            UnverifiedPaymentStore.Slice second = f.service().list("T", new UnverifiedPaymentStore.Query("DEMAND",
                    State.PAYMENT_ACCEPTED_UNVERIFIED, null, null, null, 2, 2));
            assertEquals(pending.subList(2, 3), second.items().stream().map(UnverifiedPayment::id).toList());
            assertEquals(3, second.total());
            assertTrue(f.service().list("T", new UnverifiedPaymentStore.Query("DEMAND", null, null, null, null, 6, 2))
                    .items().isEmpty(), "past the end");
            assertEquals(6, f.service().list("T", new UnverifiedPaymentStore.Query("DEMAND", null, null, null, null, 0,
                    500)).total());
            assertEquals(0, f.service().list("T", new UnverifiedPaymentStore.Query("OTHER", null, null, null, null, 0,
                    500)).total(), "another ledger");
            assertEquals(0, f.service().list("T", new UnverifiedPaymentStore.Query("DEMAND", null, "cash", null, null,
                    0, 500)).total(), "another channel");
            assertEquals(0, f.service().list("T", new UnverifiedPaymentStore.Query("DEMAND", null, null, "OFF-2", null,
                    0, 500)).total(), "another office");
            assertEquals(0, f.service().list("U", new UnverifiedPaymentStore.Query("DEMAND", null, null, null, null, 0,
                    500)).total(), "another tenant");
            // accepted at or before: the first pending one was accepted 3 hours after the start
            Instant cut = Instant.parse("2026-11-12T09:00:00Z");
            UnverifiedPaymentStore.Slice old = f.service().list("T", new UnverifiedPaymentStore.Query("DEMAND",
                    State.PAYMENT_ACCEPTED_UNVERIFIED, null, null, cut, 0, 50));
            assertEquals(List.of(pending.get(0)), old.items().stream().map(UnverifiedPayment::id).toList());
            assertThrows(IllegalArgumentException.class, () -> new UnverifiedPaymentStore.Query("DEMAND", null, null,
                    null, null, -1, 10));
            assertThrows(IllegalArgumentException.class, () -> new UnverifiedPaymentStore.Query("DEMAND", null, null,
                    null, null, 0, 0));
            assertThrows(IllegalArgumentException.class, () -> new UnverifiedPaymentStore.Query(" ", null, null,
                    null, null, 0, 10));
        }
    }}

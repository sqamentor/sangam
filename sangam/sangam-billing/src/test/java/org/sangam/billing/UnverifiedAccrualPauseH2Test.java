// SPDX-License-Identifier: Apache-2.0
package org.sangam.billing;

import org.junit.jupiter.api.Test;
import org.sangam.billing.UnverifiedPayment.State;
import org.sangam.billing.UnverifiedPaymentsH2Test.Fixture;
import org.sangam.config.catalogue.NumberSeries;
import org.sangam.core.layer.PendingAuditDrainer;
import org.sangam.core.money.Money;
import org.sangam.core.time.BusinessClock;
import org.sangam.ledger.AccountKey;
import org.sangam.ledger.Balance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.sangam.billing.UnverifiedPaymentsH2Test.CASHIER;
import static org.sangam.billing.UnverifiedPaymentsH2Test.SUPERVISOR;
import static org.sangam.billing.UnverifiedPaymentsH2Test.audit;
import static org.sangam.billing.UnverifiedPaymentsH2Test.auditPayload;
import static org.sangam.billing.UnverifiedPaymentsH2Test.fixture;
import static org.sangam.billing.UnverifiedPaymentsH2Test.request;

/**
 * Owner decision 195 on H2: while a payment is pending, penalty and interest on the part of the arrears it covers stop
 * from its acceptance; VERIFIED forgives what was paused (the payment counts from acceptance); REJECTED and REVERSED
 * levy it after all, once, audited. The fixture invoices consumer C-1 100.00 INR due 2026-11-01 and accepts payments at
 * 2026-11-12T06:00Z (business date 2026-11-12, UTC). The tariff charges 3% per month on the overdue amount, so
 * {@code P(R, d) = R x days / 1000}: at 2026-11-22 (21 days) 100.00 owes 2.10; with 40.00 counted as paid from
 * 2026-11-12 it owes 100 x 11 / 1000 + 60 x 10 / 1000 = 1.70, so 0.40 is paused.
 */
class UnverifiedAccrualPauseH2Test {

    static final LocalDate LEVY = LocalDate.parse("2026-11-22");

    static final Map<String, Object> INTEREST = Map.of("id", "FLAT", "currency", "INR",
            "components", List.of(Map.of("id", "FIXED", "kind", "FIXED", "amount", 100, "head", "FIXED")),
            "rounding", Map.of("mode", "HALF_UP", "scale", 0),
            "penalty", Map.of("graceDays", 0, "kind", "PERCENT_PER_MONTH", "rate", 3, "head", "PENALTY"));

    static void levy(Fixture f, LocalDate asOf) {
        f.billing().levyPenalties("T", "DEMAND", "2026", "C-1", INTEREST, asOf, "sys");
    }

    /** The penalty levied on C-1 for cycle {@code cycle} (all levy dates, the resumed accrual included). */
    static BigDecimal penalty(Fixture f, String cycle) {
        return f.ledger().balance(new AccountKey("T", "DEMAND", "2026", "PENALTY", "C-1", "DEMAND", cycle))
                .map(Balance::available).orElse(BigDecimal.ZERO);
    }

    static void eq(String expected, BigDecimal actual, String why) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), why + ": " + actual);
    }

    @Test
    void thePendingPartStopsAccruingAndVerifiedForgivesIt() throws Exception {
        Fixture f = fixture(null);
        try (PendingAuditDrainer ignored = f.drainer()) {
            UnverifiedPayment p = f.service().accept(CASHIER, "T", request("CHQ-1", "40.00"));
            levy(f, LEVY);
            eq("1.70", penalty(f, "2026-10"), "the covered 40.00 stopped accruing on 2026-11-12");
            List<AccrualPauses.Pause> paused = f.service().pauses("T", p.id());
            assertEquals(1, paused.size(), paused.toString());
            assertEquals(Money.parse("0.40", "INR"), paused.get(0).suppressed());
            assertEquals(LocalDate.parse("2026-11-12"), paused.get(0).pausedFrom());
            assertEquals(LEVY, paused.get(0).asOf());

            f.service().resolve(SUPERVISOR, "T", p.id(), State.VERIFIED, "cheque cleared", "BANK-STMT-1", "SCAN-1");
            eq("1.70", penalty(f, "2026-10"), "VERIFIED: the payment counts from acceptance, the pause is forgiven");
            eq("61.70", UnverifiedPaymentsH2Test.outstanding(f), "60.00 demand and 1.70 penalty");
            assertTrue(auditPayload(f, UnverifiedPayments.PAYMENT_UNVERIFIED_MANUALLY_VERIFIED).contains("0.40"),
                    "the forgiven accrual is on the VERIFIED event");
            assertTrue(f.service().reconcile("T", 100).stream().noneMatch(r -> r.outcome().equals("ACCRUAL_RESUMED")));
            eq("1.70", penalty(f, "2026-10"), "nothing is levied after a VERIFIED");
            assertEquals(0, audit(f, UnverifiedPayments.PAYMENT_UNVERIFIED_ACCRUAL_RESUMED));
        }
    }

    @Test
    void rejectedLeviesThePausedAccrualOnceAndAudited() throws Exception {
        Fixture f = fixture(null);
        try (PendingAuditDrainer ignored = f.drainer()) {
            UnverifiedPayment p = f.service().accept(CASHIER, "T", request("CHQ-1", "40.00"));
            levy(f, LEVY);
            eq("1.70", penalty(f, "2026-10"), "paused while pending");
            f.service().resolve(SUPERVISOR, "T", p.id(), State.REJECTED, "never arrived", null, null);
            eq("2.10", penalty(f, "2026-10"), "REJECTED: the paused 0.40 is levied after all");
            assertEquals(1, audit(f, UnverifiedPayments.PAYMENT_UNVERIFIED_ACCRUAL_RESUMED));
            assertTrue(auditPayload(f, UnverifiedPayments.PAYMENT_UNVERIFIED_ACCRUAL_RESUMED).contains("0.40"));
            // the reconciliation finds nothing left to levy, and a repeated levy of the same date adds nothing
            assertTrue(f.service().reconcile("T", 100).stream().noneMatch(r -> r.id().equals(p.id())));
            levy(f, LEVY);
            eq("2.10", penalty(f, "2026-10"), "the first levy of a date decides: no second charge");
            assertEquals(1, audit(f, UnverifiedPayments.PAYMENT_UNVERIFIED_ACCRUAL_RESUMED));
            assertEquals(1, f.service().pauses("T", p.id()).size(), "no new pause for a decided date");
        }
    }

    /**
     * The audit events of a payment leave {@code AUDIT_PENDING} when the drainer appends them to the record's chain (the
     * store signals it after every write), so the event of a resumed accrual is counted wherever it is. Here the drain
     * is forced to completion: the event is on the chain, no longer pending, and still found once with its amount.
     */
    @Test
    void theResumedAccrualEventIsFoundAfterItsDrainToTheChain() throws Exception {
        Fixture f = fixture(null);
        try (PendingAuditDrainer ignored = f.drainer()) {
            UnverifiedPayment p = f.service().accept(CASHIER, "T", request("CHQ-1", "40.00"));
            levy(f, LEVY);
            f.service().resolve(SUPERVISOR, "T", p.id(), State.REJECTED, "never arrived", null, null);
            f.drainer().sweep(java.time.Duration.ZERO);
            String chain = JdbcUnverifiedPaymentStore.chainOf(p.id());
            long stillPending = f.tx().inTransaction(c -> org.sangam.core.layer.Jdbc.one(c,
                    "SELECT COUNT(*) FROM AUDIT_PENDING WHERE chain_id=?", rs -> rs.getLong(1), chain).orElse(-1L));
            assertEquals(0L, stillPending, "every event of the payment was drained");
            List<String> onChain = f.tx().inTransaction(c -> org.sangam.core.layer.Jdbc.query(c,
                    "SELECT event_type FROM AUDIT_EVENT WHERE tenant_id=? AND chain_id=? ORDER BY seq",
                    rs -> rs.getString(1), "T", chain));
            assertEquals(List.of(UnverifiedPayments.PAYMENT_ACCEPTED_UNVERIFIED,
                    UnverifiedPayments.PAYMENT_UNVERIFIED_RESOLVED, UnverifiedPayments.PAYMENT_UNVERIFIED_ACCRUAL_RESUMED),
                    onChain, "the record's chain, in order");
            assertEquals(1, audit(f, UnverifiedPayments.PAYMENT_UNVERIFIED_ACCRUAL_RESUMED));
            assertTrue(auditPayload(f, UnverifiedPayments.PAYMENT_UNVERIFIED_ACCRUAL_RESUMED).contains("0.40"));
            assertEquals(1, audit(f, UnverifiedPayments.PAYMENT_ACCEPTED_UNVERIFIED));
        }
    }

    @Test
    void reversedLeviesThePausedAccrualToo() throws Exception {
        Fixture f = fixture(null);
        try (PendingAuditDrainer ignored = f.drainer()) {
            UnverifiedPayment p = f.service().accept(CASHIER, "T", request("CHQ-1", "40.00"));
            levy(f, LEVY);
            f.service().resolve(SUPERVISOR, "T", p.id(), State.REVERSED, "cheque dishonoured", "RETURN-MEMO-3",
                    "MEMO-SCAN-3");
            eq("2.10", penalty(f, "2026-10"), "REVERSED: the paused accrual is levied");
            assertEquals(1, audit(f, UnverifiedPayments.PAYMENT_UNVERIFIED_ACCRUAL_RESUMED));
        }
    }

    @Test
    void anInterruptedResumptionIsCompletedByReconciliationOnce() throws Exception {
        Fixture f = fixture(null);
        try (PendingAuditDrainer ignored = f.drainer()) {
            UnverifiedPayment p = f.service().accept(CASHIER, "T", request("CHQ-1", "40.00"));
            levy(f, LEVY);
            // REJECTED committed, then the process stopped before the paused accrual was levied
            f.store().transition("T", p.id(), locked -> new UnverifiedPaymentStore.Transition(locked.resolved(
                    new UnverifiedPayment.Resolution(State.REJECTED, "never arrived", null, null, "supervisor",
                            f.clock().instant())), UnverifiedPayments.PAYMENT_UNVERIFIED_RESOLVED,
                    Map.of("payment_id", p.id())));
            eq("1.70", penalty(f, "2026-10"), "not yet levied");
            assertTrue(f.service().tenantsWithWork(10).contains("T"), "an unlevied pause is reconciliation work");
            List<UnverifiedPayments.Reconciled> r = f.service().reconcile("T", 100);
            assertTrue(r.stream().anyMatch(x -> x.id().equals(p.id()) && x.outcome().equals("ACCRUAL_RESUMED")),
                    r.toString());
            eq("2.10", penalty(f, "2026-10"), "completed");
            f.service().reconcile("T", 100);
            eq("2.10", penalty(f, "2026-10"), "levied once");
            assertEquals(1, audit(f, UnverifiedPayments.PAYMENT_UNVERIFIED_ACCRUAL_RESUMED));
        }
    }

    @Test
    void nothingIsPausedBeforeAcceptanceOrAfterResolution() throws Exception {
        Fixture f = fixture(null);
        try (PendingAuditDrainer ignored = f.drainer()) {
            UnverifiedPayment p = f.service().accept(CASHIER, "T", request("CHQ-1", "40.00"));
            levy(f, LocalDate.parse("2026-11-12"));
            eq("1.10", penalty(f, "2026-10"), "a levy on the acceptance date itself pauses nothing");
            assertTrue(f.service().pauses("T", p.id()).isEmpty());
            f.service().resolve(SUPERVISOR, "T", p.id(), State.REJECTED, "never arrived", null, null);
            assertEquals(0, audit(f, UnverifiedPayments.PAYMENT_UNVERIFIED_ACCRUAL_RESUMED), "nothing to resume");
            eq("1.10", penalty(f, "2026-10"), "nothing levied by the resolution");
        }
        Fixture g = fixture(null);
        try (PendingAuditDrainer ignored = g.drainer()) {
            UnverifiedPayment p = g.service().accept(CASHIER, "T", request("CHQ-1", "40.00"));
            g.service().resolve(SUPERVISOR, "T", p.id(), State.REJECTED, "never arrived", null, null);
            levy(g, LEVY);
            eq("2.10", penalty(g, "2026-10"), "a resolved payment pauses nothing");
            assertTrue(g.service().pauses("T", p.id()).isEmpty());
        }
    }

    @Test
    void aPaymentCoversTheOldestDueFirstAcrossAccounts() throws Exception {
        Fixture f = fixture(null);
        try (PendingAuditDrainer ignored = f.drainer()) {
            f.billing().invoice("T", "DEMAND", "2026", "C-1", "2026-09", BillingRetryTest.FLAT, Map.of(), Map.of(),
                    LocalDate.parse("2026-10-01"), "sys");
            UnverifiedPayment p = f.service().accept(CASHIER, "T", request("CHQ-1", "150.00"));
            levy(f, LEVY);
            // 2026-09 (due 10-01, 52 days): 5.20 unpaused; all 100.00 covered from 11-12 pauses 100 x 10 / 1000 = 1.00
            eq("4.20", penalty(f, "2026-09"), "the oldest account is covered whole");
            // 2026-10 (due 11-01, 21 days): 2.10 unpaused; the remaining 50.00 pauses 50 x 10 / 1000 = 0.50
            eq("1.60", penalty(f, "2026-10"), "the next account is covered in part");
            assertEquals(2, f.service().pauses("T", p.id()).size());
            f.service().resolve(SUPERVISOR, "T", p.id(), State.REJECTED, "bounced", null, null);
            eq("5.20", penalty(f, "2026-09"), "resumed");
            eq("2.10", penalty(f, "2026-10"), "resumed");
        }
    }

    /** A payment resolved between the levy's reading and its pause record is never paused: the levy is recomputed. */
    @Test
    void aResolutionRacingTheLevyIsSeenUnderTheLock() throws Exception {
        Fixture f = fixture(null);
        try (PendingAuditDrainer ignored = f.drainer()) {
            UnverifiedPayment p = f.service().accept(CASHIER, "T", request("CHQ-1", "40.00"));
            AtomicBoolean raced = new AtomicBoolean();
            AccrualPauses racing = new AccrualPauses() {
                @Override
                public List<Pending> pending(String tenant, String ledgerId, String consumer) {
                    return f.store().pending(tenant, ledgerId, consumer);
                }

                @Override
                public Map<String, Money> pausedOn(String tenant, String ledgerId, String consumer, LocalDate asOf) {
                    return f.store().pausedOn(tenant, ledgerId, consumer, asOf);
                }

                @Override
                public boolean recordPauses(String tenant, String ledgerId, String consumer, LocalDate asOf,
                                            List<String> paymentIds, List<Pause> pauses) {
                    if (raced.compareAndSet(false, true)) {    // another node rejects it after the levy read it
                        f.service().resolve(SUPERVISOR, "T", p.id(), State.REJECTED, "bounced", null, null);
                    }
                    return f.store().recordPauses(tenant, ledgerId, consumer, asOf, paymentIds, pauses);
                }
            };
            BillingService levying = new BillingService(f.ledger(), new TariffEngine(null),
                    DocumentNumbers.overSeries(new NumberSeries.InMemory()), f.rail(),
                    PaymentConfirmationPolicy.fromConfiguration("counter", true, true), racing,
                    BusinessClock.utc(BillingRetryTest.AT));
            levying.levyPenalties("T", "DEMAND", "2026", "C-1", INTEREST, LEVY, "sys");
            assertTrue(raced.get());
            eq("2.10", penalty(f, "2026-10"), "the rejected payment paused nothing");
            assertTrue(f.service().pauses("T", p.id()).isEmpty());
            assertFalse(f.store().recordPauses("T", "DEMAND", "C-1", LEVY, List.of(p.id()), List.of()),
                    "a resolved payment can never be paused");
        }
    }
}

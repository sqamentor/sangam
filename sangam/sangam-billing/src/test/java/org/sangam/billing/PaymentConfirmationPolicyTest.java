// SPDX-License-Identifier: Apache-2.0
package org.sangam.billing;

import org.junit.jupiter.api.Test;
import org.sangam.config.catalogue.NumberSeries;
import org.sangam.core.money.Money;
import org.sangam.core.time.BusinessClock;
import org.sangam.ledger.LedgerService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RM-02 / RM-03 (owner decisions 136 and 141): payment confirmation fails closed, no channel is trusted by default, a
 * production trusted list needs an acknowledgement, and a trusted channel is refused by every collection call: only
 * {@link UnverifiedPayments} records its payments ({@code UnverifiedPaymentsH2Test}).
 */
class PaymentConfirmationPolicyTest {

    static Money inr(String s) {
        return Money.parse(s, "INR");
    }

    // ------------------------------------------------------------------ the rail rule (RM-02)

    @Test
    void anEmptyRailResultConfirmsNothing() {
        assertNull(PaymentConfirmationPolicy.confirmedByRail(false, null, inr("100")), "no status, no amount");
    }

    @Test
    void aSuccessStatusWithoutAnAmountConfirmsNothing() {
        assertNull(PaymentConfirmationPolicy.confirmedByRail(true, null, inr("100")),
                "a status alone is no longer 'the reported amount'");
        assertNull(PaymentConfirmationPolicy.confirmedByRail(true, "  ", inr("100")));
    }

    @Test
    void anAmountWithoutASuccessStatusConfirmsNothing() {
        assertNull(PaymentConfirmationPolicy.confirmedByRail(false, "100.00", inr("100")));
    }

    @Test
    void anUnparseableOrSubUnitAmountConfirmsNothing() {
        assertNull(PaymentConfirmationPolicy.confirmedByRail(true, "one hundred", inr("100")));
        assertNull(PaymentConfirmationPolicy.confirmedByRail(true, "100.001", inr("100")), "finer than the paisa");
    }

    @Test
    void aZeroOrNegativeAmountConfirmsNothing() {
        assertNull(PaymentConfirmationPolicy.confirmedByRail(true, "0", inr("100")));
        assertNull(PaymentConfirmationPolicy.confirmedByRail(true, "-5", inr("100")));
    }

    @Test
    void successAndAmountConfirmTheRailAmountInTheCollectionCurrency() {
        assertEquals(inr("100.00"), PaymentConfirmationPolicy.confirmedByRail(true, "100.00", inr("100")));
        assertEquals(inr("60"), PaymentConfirmationPolicy.confirmedByRail(true, "60", inr("100")),
                "the rail's figure wins over the reported one");
    }

    // ------------------------------------------------------------------ trusted channels (RM-03, decision 136)

    @Test
    void noChannelIsTrustedByDefault() {
        assertTrue(PaymentConfirmationPolicy.verifiedOnly().trustedChannels().isEmpty());
        PaymentConfirmationPolicy fromEmpty = PaymentConfirmationPolicy.fromConfiguration(null, false, true);
        assertTrue(fromEmpty.trustedChannels().isEmpty());
        assertFalse(fromEmpty.trusts("counter"));
        assertFalse(fromEmpty.trusts("cash"));
        assertTrue(PaymentConfirmationPolicy.fromConfiguration(" , ", false, true).trustedChannels().isEmpty());
    }

    @Test
    void productionRefusesATrustedListWithoutAcknowledgement() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> PaymentConfirmationPolicy.fromConfiguration("counter,cash", false, true));
        assertTrue(e.getMessage().contains(PaymentConfirmationPolicy.TRUSTED_CHANNELS_ACKNOWLEDGED), e.getMessage());
        assertDoesNotThrow(() -> PaymentConfirmationPolicy.fromConfiguration("counter,cash", true, true));
        assertDoesNotThrow(() -> PaymentConfirmationPolicy.fromConfiguration("counter,cash", false, false),
                "outside production the list needs no acknowledgement");
    }

    @Test
    void trustIsTheExactConfiguredNameOnly() {
        PaymentConfirmationPolicy p = PaymentConfirmationPolicy.fromConfiguration(" counter ", true, true);
        assertTrue(p.trusts("counter"));
        assertFalse(p.trusts("COUNTER"), "a request spelling cannot widen trust");
        assertFalse(p.trusts("cash"));
        assertFalse(p.trusts(null));
    }

    // ------------------------------------------------------------------ BillingService.collect

    private static BillingService service(LedgerService l, BillingService.PaymentPort rail, PaymentConfirmationPolicy p) {
        return new BillingService(l, new TariffEngine(null), DocumentNumbers.overSeries(new NumberSeries.InMemory()), rail,
                p, BusinessClock.utc(BillingRetryTest.AT));
    }

    private static void invoice(BillingService b) {
        b.invoice("T", "DEMAND", "2026", "C-1", "2026-10", BillingRetryTest.FLAT, Map.of(), Map.of(),
                LocalDate.parse("2026-11-01"), "sys");
    }

    @Test
    void collectRefusesWhenNoPaymentPortIsBound() {
        LedgerService l = BillingServiceTest.ledger();
        BillingService b = new BillingService(l, new TariffEngine(null), new NumberSeries.InMemory(), null);
        invoice(b);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> b.collect("T", "DEMAND", "C-1", "COUNTER", "PAY-1", inr("40"), "cashier"));
        assertTrue(e.getMessage().contains("no payment port"), e.getMessage());
        assertEquals(0, BillingRetryTest.outstanding(l, "C-1").compareTo(new BigDecimal("100")), "nothing was consumed");
    }

    @Test
    void collectRefusesWhatTheRailDidNotConfirm() {
        LedgerService l = BillingServiceTest.ledger();
        BillingService.PaymentPort silent = new BillingService.PaymentPort() {
            @Override
            public Money confirm(String tenant, String channel, String paymentRef, Money amount) {
                return PaymentConfirmationPolicy.confirmedByRail(false, null, amount); // empty connector output
            }

            @Override
            public String refund(String tenant, String channel, String paymentRef, Money amount, String operationId) {
                return null;
            }
        };
        BillingService b = service(l, silent, PaymentConfirmationPolicy.verifiedOnly());
        invoice(b);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> b.collect("T", "DEMAND", "C-1", "gateway", "PAY-1", inr("40"), "cashier"));
        assertTrue(e.getMessage().contains("payment not confirmed"), e.getMessage());
        assertEquals(0, BillingRetryTest.outstanding(l, "C-1").compareTo(new BigDecimal("100")));
    }

    @Test
    void aRequestNamingAChannelTheServerDoesNotTrustIsRailVerified() {
        LedgerService l = BillingServiceTest.ledger();
        TestRails.Settling rail = new TestRails.Settling();
        BillingService b = service(l, rail, PaymentConfirmationPolicy.fromConfiguration("", false, true));
        invoice(b);
        b.collect("T", "DEMAND", "C-1", "cash", "PAY-1", inr("40"), "cashier");
        assertEquals(List.of("PAY-1"), rail.confirmed, "the rail verified it");
    }

    /**
     * Decision 141: a trusted channel is never confirmed by a collection call, whatever actor the payload names (a pack
     * step, a connector input or a forged "admin"): it is refused, the rail is not asked, nothing is consumed.
     */
    @Test
    void aTrustedChannelIsRefusedByCollectWhateverTheActorSays() {
        for (String forged : new String[]{"cashier", "admin", "system", null}) {
            LedgerService l = BillingServiceTest.ledger();
            TestRails.Settling rail = new TestRails.Settling();
            BillingService b = service(l, rail, PaymentConfirmationPolicy.fromConfiguration("counter", true, true));
            invoice(b);
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> b.collect("T", "DEMAND", "C-1", "counter", "PAY-1", inr("40"), forged));
            assertTrue(e.getMessage().contains(UnverifiedPayments.PAYMENT_ACCEPTED_UNVERIFIED), e.getMessage());
            assertTrue(rail.confirmed.isEmpty(), "the rail is not asked");
            assertEquals(0, BillingRetryTest.outstanding(l, "C-1").compareTo(new BigDecimal("100")), "nothing consumed");
        }
    }
}

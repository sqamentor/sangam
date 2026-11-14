import sys
p = sys.argv[1]
s = open(p, encoding="utf-8").read()
s = s.replace("fromConfiguration(null, false, true, null)", "fromConfiguration(null, false, true)")
s = s.replace('fromConfiguration(" , ", false, true, null)', 'fromConfiguration(" , ", false, true)')
s = s.replace('fromConfiguration("counter,cash", false, true, null)', 'fromConfiguration("counter,cash", false, true)')
s = s.replace('fromConfiguration("counter,cash", true, true, null)', 'fromConfiguration("counter,cash", true, true)')
s = s.replace('fromConfiguration("counter,cash", false, false, null)', 'fromConfiguration("counter,cash", false, false)')
s = s.replace('fromConfiguration(" counter ", true, true, null)', 'fromConfiguration(" counter ", true, true)')
i = s.index("    @Test\n    void anUnverifiedAcceptanceWithoutAGateIsRefused()")
j = s.index("    // ------------------------------------------------------------------ BillingService.collect")
s = s[:i] + s[j:]
i = s.index("    @Test\n    void aRequestNamingAChannelTheServerDoesNotTrustIsRailVerified()")
s = s[:i] + '''    @Test
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
'''
s = s.replace("import java.util.ArrayList;\n", "")
s = s.replace(""" * RM-02 / RM-03 (owner decision 136): payment confirmation fails closed, no channel is trusted by default, a production
 * trusted list needs an acknowledgement, and each unverified acceptance passes the host gate (channel permission and
 * {@code PAYMENT_ACCEPTED_UNVERIFIED}).""", """ * RM-02 / RM-03 (owner decisions 136 and 141): payment confirmation fails closed, no channel is trusted by default, a
 * production trusted list needs an acknowledgement, and a trusted channel is refused by every collection call: only
 * {@link UnverifiedPayments} records its payments ({@code UnverifiedPaymentsH2Test}).""")
open(p, "w", encoding="utf-8", newline="\n").write(s)
print("ok", s.count("null)"), s.count("ArrayList"))

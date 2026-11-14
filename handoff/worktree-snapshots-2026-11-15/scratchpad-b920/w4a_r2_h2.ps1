$p = "D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam\sangam-billing\src\test\java\org\sangam\billing\UnverifiedPaymentsH2Test.java"
$script:s = [IO.File]::ReadAllText($p).Replace("`r`n", "`n")
function Rep([string]$o, [string]$n) {
    $o = $o.Replace("`r`n", "`n"); $n = $n.Replace("`r`n", "`n")
    if (-not $script:s.Contains($o)) { throw "missing: $o" }
    $script:s = $script:s.Replace($o, $n)
}
Rep @'
 * RM-03 (owner decision 141) on H2 (PostgreSQL mode, the portable store): unverified acceptance only by an authenticated
 * caller holding the scoped action, on an enabled channel, with reason, evidence and payment details; pending posts
 * nothing; one record per channel and reference; resolution rechecked atomically on the locked row; maker-checker;
 * reconciliation against the rail; ageing. Oracle and SQL Server run the store in {@code UnverifiedPaymentVendorIT}
 * (sangam-server acceptance, NOT_RUN in the coding phase).
'@ @'
 * RM-03 (owner decisions 141, 194, 196 and 201) on H2 (PostgreSQL mode, the portable store): unverified acceptance only
 * by an authenticated staff caller holding the scoped action (API keys, service and system identities refused), on an
 * enabled channel, with reason, evidence and payment details; pending posts nothing; one record per channel and
 * reference; resolution rechecked atomically on the locked row; maker-checker always; a manual VERIFIED needs evidence
 * and has its own audit event; reconciliation against the rail; ageing; the list filters before paging. The accrual
 * pause (decision 195) is in {@code UnverifiedAccrualPauseH2Test}. Oracle and SQL Server run the store in
 * {@code UnverifiedPaymentVendorIT} (sangam-server acceptance, NOT_RUN in the coding phase).
'@
Rep @'
    static final Caller CASHIER = new Caller("cashier", "T", "seat-c");
    static final Caller SUPERVISOR = new Caller("supervisor", "T", "seat-s");
    static final Caller CLERK = new Caller("clerk", "T", "seat-k");
'@ @'
    static final Caller CASHIER = Caller.staff("cashier", "T", "seat-c");
    static final Caller SUPERVISOR = Caller.staff("supervisor", "T", "seat-s");
    static final Caller CLERK = Caller.staff("clerk", "T", "seat-k");
'@
Rep @'
    record Fixture(Transactions tx, PendingAuditDrainer drainer, LedgerService ledger, UnverifiedPayments service,
                   Grants grants, Moving clock, TestRails.Settling rail) {
    }
'@ @'
    record Fixture(Transactions tx, PendingAuditDrainer drainer, LedgerService ledger, UnverifiedPayments service,
                   Grants grants, Moving clock, TestRails.Settling rail, BillingService billing,
                   JdbcUnverifiedPaymentStore store) {
    }
'@
Rep @'
        PaymentConfirmationPolicy trusted = PaymentConfirmationPolicy.fromConfiguration("counter,kiosk", true, true);
        BillingService billing = new BillingService(ledger, new TariffEngine(null),
                DocumentNumbers.overSeries(new NumberSeries.InMemory()), settling, trusted,
                BusinessClock.utc(BillingRetryTest.AT));
'@ @'
        PaymentConfirmationPolicy trusted = PaymentConfirmationPolicy.fromConfiguration("counter,kiosk", true, true);
        JdbcUnverifiedPaymentStore store = new JdbcUnverifiedPaymentStore(tx, D, drainer);
        BillingService billing = new BillingService(ledger, new TariffEngine(null),
                DocumentNumbers.overSeries(new NumberSeries.InMemory()), settling, trusted, store,
                BusinessClock.utc(BillingRetryTest.AT));
'@
Rep @'
                UnverifiedPaymentPolicy.from(trusted.trustedChannels(), props::get), grants,
                new JdbcUnverifiedPaymentStore(tx, D, drainer), rails == null ? settling : rails, clock);
        return new Fixture(tx, drainer, ledger, service, grants, clock, settling);
'@ @'
                UnverifiedPaymentPolicy.from(trusted.trustedChannels(), props::get), grants, store,
                rails == null ? settling : rails, clock);
        return new Fixture(tx, drainer, ledger, service, grants, clock, settling, billing, store);
'@
Rep '            assertThrows(IllegalArgumentException.class, () -> new Caller(" ", "T", null), "no anonymous caller");' '            assertThrows(IllegalArgumentException.class, () -> Caller.staff(" ", "T", null), "no anonymous caller");'
Rep '            refused(Code.NOT_PERMITTED, () -> f.service().accept(new Caller("cashier", "U", null), "T", r));' '            refused(Code.NOT_PERMITTED, () -> f.service().accept(Caller.staff("cashier", "U", null), "T", r));'
Rep @'
            refused(Code.NOT_PERMITTED, () -> f.service().resolve(CASHIER, "T", p.id(), State.VERIFIED, "cleared",
                    "BANK-STMT-1"));
            // an internal call with a principal that lacks RESOLVE_UNVERIFIED
            refused(Code.NOT_PERMITTED, () -> f.service().resolve(CLERK, "T", p.id(), State.VERIFIED, "cleared",
                    "BANK-STMT-1"));
            refused(Code.INVALID_REQUEST, () -> f.service().resolve(SUPERVISOR, "T", p.id(), State.VERIFIED, "cleared",
                    " "));
            refused(Code.NOT_FOUND, () -> f.service().resolve(SUPERVISOR, "T", "no-such-id", State.REJECTED, "x", null));
            assertEquals(State.PAYMENT_ACCEPTED_UNVERIFIED, f.service().find("T", p.id()).orElseThrow().state());
            assertEquals(0, outstanding(f).compareTo(new BigDecimal("100")));

            UnverifiedPayment v = f.service().resolve(SUPERVISOR, "T", p.id(), State.VERIFIED, "cheque cleared",
                    "BANK-STMT-1");
'@ @'
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
'@
Rep @'
            assertEquals("supervisor", stored.resolution().resolvedBy());
            assertEquals(1, audit(f, UnverifiedPayments.PAYMENT_UNVERIFIED_RESOLVED));
            // already resolved: a second resolution changes nothing
            refused(Code.INVALID_TRANSITION, () -> f.service().resolve(SUPERVISOR, "T", p.id(), State.REVERSED,
                    "returned", null));
'@ @'
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
'@
Rep @'
            assertEquals(State.REJECTED, f.service().resolve(SUPERVISOR, "T", a.id(), State.REJECTED,
                    "never arrived", null).state());
            assertEquals(State.REVERSED, f.service().resolve(SUPERVISOR, "T", b.id(), State.REVERSED,
                    "cheque dishonoured", "RETURN-MEMO-3").state());
            assertEquals(0, outstanding(f).compareTo(new BigDecimal("100")));
            assertTrue(f.rail().confirmed.isEmpty());
'@ @'
            assertEquals(State.REJECTED, f.service().resolve(SUPERVISOR, "T", a.id(), State.REJECTED,
                    "never arrived", null, null).state());
            assertEquals(State.REVERSED, f.service().resolve(SUPERVISOR, "T", b.id(), State.REVERSED,
                    "cheque dishonoured", "RETURN-MEMO-3", "MEMO-SCAN-3").state());
            assertEquals(0, outstanding(f).compareTo(new BigDecimal("100")));
            assertTrue(f.rail().confirmed.isEmpty());
            assertEquals(2, audit(f, UnverifiedPayments.PAYMENT_UNVERIFIED_RESOLVED));
            assertEquals(0, audit(f, UnverifiedPayments.PAYMENT_UNVERIFIED_MANUALLY_VERIFIED));
            assertEquals("MEMO-SCAN-3", f.service().find("T", b.id()).orElseThrow().resolution().evidenceRef());
'@
Rep '                    return f.service().resolve(SUPERVISOR, "T", p.id(), State.VERIFIED, "cleared", "STMT-1").state();' '                    return f.service().resolve(SUPERVISOR, "T", p.id(), State.VERIFIED, "cleared", "STMT-1", "SCAN-1")
                            .state();'
Rep '                    return f.service().resolve(SUPERVISOR, "T", p.id(), State.REJECTED, "bounced", null).state();' '                    return f.service().resolve(SUPERVISOR, "T", p.id(), State.REJECTED, "bounced", null, null).state();'
Rep '                assertEquals(1, audit(f, UnverifiedPayments.PAYMENT_UNVERIFIED_RESOLVED));' '                assertEquals(1, audit(f, UnverifiedPayments.PAYMENT_UNVERIFIED_RESOLVED)
                        + audit(f, UnverifiedPayments.PAYMENT_UNVERIFIED_MANUALLY_VERIFIED), "one resolution event");'
Rep @'
            assertEquals(UnverifiedPayments.RECONCILIATION, v.resolution().resolvedBy());
            assertNotNull(v.receiptNo());
'@ @'
            assertEquals(UnverifiedPayments.RECONCILIATION, v.resolution().resolvedBy());
            assertNull(v.resolution().evidenceRef(), "the rail is the evidence of a rail VERIFIED");
            assertEquals(1, audit(f, UnverifiedPayments.PAYMENT_UNVERIFIED_RESOLVED), "the rail path keeps its event");
            assertEquals(0, audit(f, UnverifiedPayments.PAYMENT_UNVERIFIED_MANUALLY_VERIFIED));
            assertNotNull(v.receiptNo());
'@
Rep '                            State.VERIFIED, "cleared", "STMT-9", "supervisor", f.clock().instant())),' '                            State.VERIFIED, "cleared", "STMT-9", "SCAN-9", "supervisor", f.clock().instant())),'
Rep @'
    static void refused(Code code, org.junit.jupiter.api.function.Executable call) {
'@ @'
    static String auditPayload(Fixture f, String type) {
        return f.tx().inTransaction(c -> String.join("\n", Jdbc.query(c,
                "SELECT payload FROM AUDIT_PENDING WHERE event_type=?", rs -> rs.getString(1), type)));
    }

    static void refused(Code code, org.junit.jupiter.api.function.Executable call) {
'@
# new tests before the final closing brace
$idx = $script:s.LastIndexOf("}`n")
$tail = @'

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
    }
'@
$script:s = $script:s.Substring(0, $idx) + $tail.Replace("`r`n", "`n") + "}`n"
[IO.File]::WriteAllText($p, $script:s)
"OK"

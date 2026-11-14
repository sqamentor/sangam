$p = "D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam\sangam-server\src\test\java\org\sangam\server\acceptance\contracts\UnverifiedPaymentVendorIT.java"
$script:s = [IO.File]::ReadAllText($p).Replace("`r`n", "`n")
function Rep([string]$o, [string]$n) {
    $o = $o.Replace("`r`n", "`n"); $n = $n.Replace("`r`n", "`n")
    if (-not $script:s.Contains($o)) { throw "missing: $o" }
    $script:s = $script:s.Replace($o, $n)
}
Rep "import java.time.Instant;`n" "import java.time.Instant;`nimport java.time.LocalDate;`n"
Rep "import org.sangam.billing.JdbcUnverifiedPaymentStore;`n" "import org.sangam.billing.AccrualPauses;`nimport org.sangam.billing.JdbcUnverifiedPaymentStore;`n"
Rep "import org.sangam.core.money.Money;`n" "import org.sangam.core.money.Money;`nimport org.sangam.ledger.AccountKey;`n"
Rep @'
 * Oracle's empty-string-as-NULL included). Needs the W4-G changelog with the table; NOT_RUN in the coding phase.
'@ @'
 * Oracle's empty-string-as-NULL included). Decisions 195 and 196: the accrual pauses ({@code BILL_UNVERIFIED_ACCRUAL_PAUSE})
 * and the manual-VERIFIED evidence column round-trip, a resolved payment can never be paused, a resumption is marked
 * once; the filtered list pages in SQL (OFFSET ... FETCH on Oracle and SQL Server). Needs the W4-G changelog with the
 * tables; NOT_RUN in the coding phase.
'@
Rep @'
                l.version() + 1, new UnverifiedPayment.Resolution(outcome, "vendor test", null, "supervisor",
'@ @'
                l.version() + 1, new UnverifiedPayment.Resolution(outcome, "vendor test", null, "EVID-1", "supervisor",
'@
Rep @'
            assertEquals("cashier", done.acceptedBy(), "the accepted columns are unchanged");
'@ @'
            assertEquals("cashier", done.acceptedBy(), "the accepted columns are unchanged");
            assertEquals("EVID-1", done.resolution().evidenceRef(), "the resolution evidence round-trips");
'@
$idx = $script:s.LastIndexOf("}`n")
$tail = @'

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
    }
'@
$script:s = $script:s.Substring(0, $idx) + $tail.Replace("`r`n", "`n") + "}`n"
[IO.File]::WriteAllText($p, $script:s)
"OK"

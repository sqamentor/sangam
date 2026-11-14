function Rep($p, $o, $n) { $s=[IO.File]::ReadAllText($p); if(-not $s.Contains($o)){ throw "missing in ${p}: $o" }; [IO.File]::WriteAllText($p, $s.Replace($o,$n)) }
$a="AccrualPauses.java"
Rep $a "import java.util.List;`nimport java.util.Set;" "import java.util.List;`nimport java.util.Map;"
Rep $a "        public Set<String> pausedAccounts(String tenant, String ledgerId, String consumer, LocalDate asOf) {`n            return Set.of();" "        public Map<String, Money> pausedOn(String tenant, String ledgerId, String consumer, LocalDate asOf) {`n            return Map.of();"
Rep $a @"
    /**
     * The base accounts of the consumer that already have a pause for levy date {@code asOf}, whatever the payment's
     * state now: that date was levied for them, and a later levy of the same date leaves them as they are (the first
     * levy of a date decides; otherwise a payment REJECTED in between would be levied twice, once by its resumption and
     * once by the repeated levy).
     */
    Set<String> pausedAccounts(String tenant, String ledgerId, String consumer, LocalDate asOf);
"@ @"
    /**
     * The base accounts of the consumer that already have pauses for levy date {@code asOf}, with the total paused on
     * each, whatever the payments' states now. The first levy of a date decides such an account: a later levy of the
     * same date records no new pause for it and levies only the unpaused penalty less that total (the ledger keeps the
     * first posting of the date, so this completes a levy interrupted between its pauses and its postings). Otherwise a
     * payment REJECTED in between would be levied twice, once by its resumption and once by the repeated levy.
     */
    Map<String, Money> pausedOn(String tenant, String ledgerId, String consumer, LocalDate asOf);
"@
$j="JdbcUnverifiedPaymentStore.java"
Rep $j @"
    public Set<String> pausedAccounts(String tenant, String ledgerId, String consumer, LocalDate asOf) {
        return tx.inTransaction(c -> new HashSet<>(Jdbc.query(c, "SELECT DISTINCT base_account_id FROM " + PAUSES
                        + " WHERE tenant_id=? AND ledger_id=? AND consumer=? AND as_of=?", rs -> rs.getString(1),
                tenant, ledgerId, consumer, asOf.toString())));
    }
"@ @"
    public Map<String, Money> pausedOn(String tenant, String ledgerId, String consumer, LocalDate asOf) {
        List<Map.Entry<String, Money>> rows = tx.inTransaction(c -> Jdbc.query(c, "SELECT base_account_id, amount,"
                        + " currency FROM " + PAUSES + " WHERE tenant_id=? AND ledger_id=? AND consumer=? AND as_of=?",
                rs -> Map.entry(rs.getString("base_account_id"), Money.ofMinor(rs.getLong("amount"),
                        rs.getString("currency"))), tenant, ledgerId, consumer, asOf.toString()));
        Map<String, Money> out = new java.util.HashMap<>();
        for (Map.Entry<String, Money> r : rows) {
            out.merge(r.getKey(), r.getValue(), Money::plus);
        }
        return out;
    }
"@
$b="BillingService.java"
Rep $b "        java.util.Set<String> decided = pauses.pausedAccounts(tenant, ledgerId, consumer, asOf);" "        Map<String, Money> decided = pauses.pausedOn(tenant, ledgerId, consumer, asOf);"
Rep $b "            if (!b.key().holder().equals(consumer) || b.key().line().equals(head) || decided.contains(b.key().id())) {" "            if (!b.key().holder().equals(consumer) || b.key().line().equals(head)) {"
Rep $b @"
            Money left = Money.round(tariffs.penaltyExact(tenant, tariff, b.available(), due, asOf), ccy, mode);
            BigDecimal accruing
"@ @"
            Money left = Money.round(tariffs.penaltyExact(tenant, tariff, b.available(), due, asOf), ccy, mode);
            Money already = decided.get(b.key().id());
            if (already != null) {    // decided by an earlier levy of this date: no new pause, complete its posting
                Money rest = left.minus(already.min(left));
                if (rest.signum() > 0) {
                    charges.add(new Charge(b.key().id(), k, rest));
                }
                continue;
            }
            BigDecimal accruing
"@
Rep $b @"
                // the marginal accrual of the covered part after its pause date; exact for rate kinds, and a flat or
                // once-only charge is paused only when the payment covers the whole remainder before it was incurred
"@ @"
                // the marginal accrual of the covered part after its pause date: exact for the per-day rate kinds; a
                // flat charge is paused only when the payment covers the whole remainder and was accepted before the
                // charge was incurred, a once-only percent in proportion when accepted before it was incurred
"@

// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.sim;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.sangam.packs.port.LedgerStore;

/**
 * In-memory {@link LedgerStore}; postings idempotent by reference, account updates serialised per account. Same unit
 * rules as the JDBC store: integer units per account, explicit unit fixed on first use.
 */
public class InMemoryLedgerStore implements LedgerStore {

    private final Map<String, Long> balances = new ConcurrentHashMap<>();
    private final Map<String, String> units = new ConcurrentHashMap<>();
    private final Map<String, Long> postings = new ConcurrentHashMap<>();

    private static String acct(String tenantId, String ledger, String account) {
        return tenantId + "|" + ledger + "|" + account;
    }

    /** Sets an opening balance (seed data: budget allotment, leave credit) in {@code unit}. */
    public synchronized void open(String tenantId, String ledger, String account, String unit, BigDecimal balance) {
        String a = acct(tenantId, ledger, account);
        units.put(a, unit);
        balances.put(a, LedgerStore.units(balance, unit));
    }

    private BigDecimal value(String a) {
        String u = units.get(a);
        return u == null ? BigDecimal.ZERO : LedgerStore.amount(balances.getOrDefault(a, 0L), u);
    }

    @Override
    public BigDecimal balance(String tenantId, String ledger, String account) {
        return value(acct(tenantId, ledger, account));
    }

    @Override
    public synchronized Posting post(String tenantId, String ledger, String account, String unit, BigDecimal delta,
                                     String reference, boolean allowNegative) {
        long d = LedgerStore.units(delta, unit);
        String a = acct(tenantId, ledger, account);
        String have = units.putIfAbsent(a, unit);
        if (have != null && !have.equals(unit)) {
            return new Posting(false, "UNIT_MISMATCH", value(a));
        }
        String p = a + "|" + reference;
        if (postings.containsKey(p)) {
            return new Posting(true, "DUPLICATE_REFERENCE", value(a));
        }
        long after = Math.addExact(balances.getOrDefault(a, 0L), d);
        if (!allowNegative && after < 0) {
            return new Posting(false, "INSUFFICIENT_BALANCE", value(a));
        }
        balances.put(a, after);
        postings.put(p, d);
        return new Posting(true, null, value(a));
    }

    @Override
    public synchronized Posting reverse(String tenantId, String ledger, String account, String reference) {
        String a = acct(tenantId, ledger, account);
        String p = a + "|" + reference;
        Long d = postings.remove(p);
        if (d == null) {
            return new Posting(true, "NOTHING_TO_REVERSE", value(a));
        }
        balances.put(a, Math.subtractExact(balances.getOrDefault(a, 0L), d));
        postings.put(p + "|reversed", -d);
        return new Posting(true, null, value(a));
    }
}

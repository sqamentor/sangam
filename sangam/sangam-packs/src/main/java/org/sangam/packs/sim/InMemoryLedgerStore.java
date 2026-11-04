// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.sim;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.sangam.packs.port.LedgerStore;

/** In-memory {@link LedgerStore}; postings idempotent by reference, account updates serialised per account. */
public class InMemoryLedgerStore implements LedgerStore {

    private final Map<String, BigDecimal> balances = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> postings = new ConcurrentHashMap<>();

    private static String acct(String tenantId, String ledger, String account) {
        return tenantId + "|" + ledger + "|" + account;
    }

    /** Sets an opening balance (seed data: budget allotment, leave credit). */
    public void open(String tenantId, String ledger, String account, BigDecimal balance) {
        balances.put(acct(tenantId, ledger, account), balance);
    }

    @Override
    public BigDecimal balance(String tenantId, String ledger, String account) {
        return balances.getOrDefault(acct(tenantId, ledger, account), BigDecimal.ZERO);
    }

    @Override
    public synchronized Posting post(String tenantId, String ledger, String account, BigDecimal delta,
                                     String reference, boolean allowNegative) {
        String a = acct(tenantId, ledger, account);
        String p = a + "|" + reference;
        if (postings.containsKey(p)) {
            return new Posting(true, "DUPLICATE_REFERENCE", balances.getOrDefault(a, BigDecimal.ZERO));
        }
        BigDecimal after = balances.getOrDefault(a, BigDecimal.ZERO).add(delta);
        if (!allowNegative && after.signum() < 0) {
            return new Posting(false, "INSUFFICIENT_BALANCE", balances.getOrDefault(a, BigDecimal.ZERO));
        }
        balances.put(a, after);
        postings.put(p, delta);
        return new Posting(true, null, after);
    }

    @Override
    public synchronized Posting reverse(String tenantId, String ledger, String account, String reference) {
        String a = acct(tenantId, ledger, account);
        String p = a + "|" + reference;
        BigDecimal delta = postings.remove(p);
        if (delta == null) {
            return new Posting(true, "NOTHING_TO_REVERSE", balances.getOrDefault(a, BigDecimal.ZERO));
        }
        BigDecimal after = balances.getOrDefault(a, BigDecimal.ZERO).subtract(delta);
        balances.put(a, after);
        postings.put(p + "|reversed", delta.negate());
        return new Posting(true, null, after);
    }
}

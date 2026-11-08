// SPDX-License-Identifier: Apache-2.0
package org.sangam.ledger;

import org.sangam.config.common.Finding;
import org.sangam.config.common.Tree;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Treasury Single Account topology as data (PFM-045; IFC-158 TSA for autonomous bodies; IFS-029 assignment accounts):
 * the main account at the central bank, sub-accounts that exist only in the ledger, real bank sub-accounts, zero-balance
 * accounts swept to the main account, and the exceptions (donor designated accounts, revenue collection accounts).
 *
 * <pre>
 * treasury:
 *   tsa:
 *     main: {id: TSA-MAIN, bank: CENTRAL_BANK, currency: INR}
 *     accounts:
 *       - {id: TSA-AB-001, kind: LEDGER_ONLY, holder: AB-001, drawingLimitLedger: TSA}   # assignment account
 *       - {id: ZBA-PAO-01, kind: ZERO_BALANCE, bank: AGENCY-1, sweep: DAILY}
 *       - {id: REV-COLL-1, kind: COLLECTION, bank: AGENCY-1, sweep: T+1}
 *       - {id: DA-IDA-1234, kind: EXCEPTION, reason: DONOR_DESIGNATED, currency: USD}
 * </pre>
 * Reconciliation and the cash plan read this model; balances of LEDGER_ONLY accounts are ledger accounts.
 */
public final class TsaTopology {

    public enum Kind { MAIN, LEDGER_ONLY, REAL, ZERO_BALANCE, COLLECTION, EXCEPTION }

    public record Account(String id, Kind kind, String bank, String currency, String sweep, String holder,
                          Map<String, Object> raw) {
    }

    /** One sweep instruction: move the balance of a real account to the main account. */
    public record Sweep(String from, String to, BigDecimal amount, String currency) {
    }

    private final Account main;
    private final List<Account> accounts;

    private TsaTopology(Account main, List<Account> accounts) {
        this.main = main;
        this.accounts = List.copyOf(accounts);
    }

    public static TsaTopology from(Map<String, Object> tsa) {
        Map<String, Object> m = Tree.map(tsa.get("main"));
        Account main = new Account(Tree.str(m.get("id"), "TSA-MAIN"), Kind.MAIN, Tree.str(m.get("bank"), "CENTRAL_BANK"),
                Tree.str(m.get("currency"), null), null, null, m);
        List<Account> out = new ArrayList<>();
        for (Map<String, Object> a : Tree.maps(tsa.get("accounts"))) {
            out.add(new Account(Tree.str(a.get("id"), null), Kind.valueOf(Tree.str(a.get("kind"), "LEDGER_ONLY").toUpperCase()),
                    Tree.str(a.get("bank"), null), Tree.str(a.get("currency"), main.currency()), Tree.str(a.get("sweep"), null),
                    Tree.str(a.get("holder"), null), a));
        }
        return new TsaTopology(main, out);
    }

    public Account main() {
        return main;
    }

    public List<Account> accounts() {
        return accounts;
    }

    /** Topology checks: unique ids, sweeps only on real accounts, exceptions carry a reason. */
    public List<Finding> lint() {
        List<Finding> f = new ArrayList<>();
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (Account a : accounts) {
            if (seen.merge(a.id(), 1, Integer::sum) > 1) {
                f.add(Finding.error("TSA_DUPLICATE", a.id(), "duplicate account id", null));
            }
            if (a.kind() == Kind.ZERO_BALANCE && a.sweep() == null) {
                f.add(Finding.error("TSA_NO_SWEEP", a.id(), "a zero-balance account needs a sweep schedule", null));
            }
            if (a.kind() == Kind.LEDGER_ONLY && a.bank() != null) {
                f.add(Finding.warn("TSA_LEDGER_BANK", a.id(), "a ledger-only sub-account has no bank", null));
            }
            if (a.kind() == Kind.EXCEPTION && a.raw().get("reason") == null) {
                f.add(Finding.error("TSA_EXCEPTION_REASON", a.id(), "an exception account needs a reason", null));
            }
        }
        return f;
    }

    /** Sweep plan for the given bank balances of zero-balance and collection accounts. */
    public List<Sweep> sweeps(Map<String, BigDecimal> bankBalances) {
        List<Sweep> out = new ArrayList<>();
        for (Account a : accounts) {
            if (a.kind() == Kind.ZERO_BALANCE || a.kind() == Kind.COLLECTION) {
                BigDecimal b = bankBalances.getOrDefault(a.id(), BigDecimal.ZERO);
                if (b.signum() > 0) {
                    out.add(new Sweep(a.id(), main.id(), b, a.currency()));
                }
            }
        }
        return out;
    }
}

// SPDX-License-Identifier: Apache-2.0
package org.sangam.ledger;

import org.sangam.config.common.Finding;
import org.sangam.config.common.Tree;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Donor / lender financing agreements as limit sources (PFM-059 to PFM-062). Agreements are configuration
 * ({@code financingAgreements:} in a profile) and their limits are ledger accounts whose {@code sub} is the agreement id
 * and whose currency is the agreement currency (limits in a foreign currency, PFM-073).
 *
 * <pre>
 * financingAgreements:
 *   - id: IDA-1234
 *     donor: IDA
 *     instrument: CREDIT                 # GRANT | LOAN | CREDIT
 *     currency: XDR
 *     amount: 50000000
 *     closingDate: 2029-06-30
 *     retroactiveFrom: 2025-01-01        # retroactive financing window start
 *     categories: [{id: GOODS, percentFinanced: 100, limit: 20000000}, {id: WORKS, percentFinanced: 80}]
 *     disbursementMethods: [ADVANCE, DIRECT_PAYMENT, REIMBURSEMENT, SPECIAL_COMMITMENT]
 *     designatedAccount: DA-IDA-1234     # replenished by withdrawal applications (PFM-062)
 *     procurementMethods: {GOODS: [ICB, NCB, SHOPPING]}
 *     onBudget: true
 *     onTreasury: false
 * </pre>
 */
public final class FinancingAgreements {

    /** Share of an expenditure the agreement finances, with the findings that explain it. */
    public record Eligibility(boolean eligible, BigDecimal financedAmount, BigDecimal counterpartAmount, String currency,
                              List<Finding> findings) {
    }

    private final List<Map<String, Object>> agreements;

    public FinancingAgreements(List<Map<String, Object>> agreements) {
        this.agreements = List.copyOf(agreements);
    }

    public static FinancingAgreements from(Object section) {
        return new FinancingAgreements(Tree.maps(section));
    }

    public Optional<Map<String, Object>> agreement(String id) {
        return agreements.stream().filter(a -> id.equals(a.get("id"))).findFirst();
    }

    /**
     * Donor-eligibility check at commitment or payment (PFM-061): agreement open on the date (or within the retroactive
     * window), category eligible, procurement method allowed; returns the financed and counterpart shares.
     */
    public Eligibility check(String agreementId, String category, String procurementMethod, BigDecimal amount, LocalDate date) {
        List<Finding> f = new ArrayList<>();
        Map<String, Object> a = agreement(agreementId).orElse(null);
        if (a == null) {
            f.add(Finding.error("AGREEMENT_UNKNOWN", agreementId, "no financing agreement " + agreementId, null));
            return new Eligibility(false, BigDecimal.ZERO, amount, null, f);
        }
        String ref = Tree.str(a.get("ruleRef"), "financing agreement " + agreementId);
        LocalDate closing = Tree.date(a.get("closingDate"), null);
        LocalDate retro = Tree.date(a.get("retroactiveFrom"), null);
        LocalDate effective = Tree.date(a.get("effectiveFrom"), null);
        if (closing != null && date.isAfter(closing)) {
            f.add(Finding.error("AGREEMENT_CLOSED", agreementId, "after the closing date " + closing, ref));
        }
        if (effective != null && date.isBefore(effective) && (retro == null || date.isBefore(retro))) {
            f.add(Finding.error("BEFORE_EFFECTIVENESS", agreementId, "before effectiveness and outside the retroactive window", ref));
        }
        Map<String, Object> cat = Tree.maps(a.get("categories")).stream().filter(c -> category.equals(c.get("id"))).findFirst()
                .orElse(null);
        if (cat == null) {
            f.add(Finding.error("CATEGORY_INELIGIBLE", category, "category not financed by " + agreementId, ref));
        }
        List<String> methods = Tree.strings(Tree.map(a.get("procurementMethods")).get(category));
        if (procurementMethod != null && !methods.isEmpty() && !methods.contains(procurementMethod)) {
            f.add(Finding.error("PROCUREMENT_METHOD", procurementMethod, "method not allowed for " + category, ref));
        }
        BigDecimal pct = cat == null ? BigDecimal.ZERO : Tree.decimal(cat.get("percentFinanced"), BigDecimal.valueOf(100));
        BigDecimal financed = amount.multiply(pct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        return new Eligibility(!Finding.anyError(f), Finding.anyError(f) ? BigDecimal.ZERO : financed,
                Finding.anyError(f) ? amount : amount.subtract(financed), Tree.str(a.get("currency"), null), f);
    }

    /** The ledger account holding an agreement category's limit (sub = agreement id, line = category). */
    public static AccountKey limitAccount(String tenant, String ledger, String period, String agreementId, String category,
                                          String stage) {
        return new AccountKey(tenant, ledger, period, category, "AGREEMENT", stage, agreementId);
    }
}

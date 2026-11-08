// SPDX-License-Identifier: Apache-2.0
package org.sangam.ledger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * One ledger operation.
 *
 * <p>Operations: {@code ALLOCATE} (top-stage limit from a source: appropriation, warrant, donor agreement, receipt of
 * stock, demand raised), {@code DISTRIBUTE} (parent stage → child stage / holder: release, authorisation,
 * sub-allotment, stock transfer), {@code WITHDRAW} (child → parent: surrender, return), {@code COMMIT} /
 * {@code UNCOMMIT} (sanction, order, reservation), {@code ENCUMBER} / {@code DISENCUMBER} (bill in process; from a
 * commitment when {@code fromCommitment}), {@code CONSUME} / {@code REVERSE} (payment, issue, collection; from an
 * encumbrance when {@code fromEncumbrance}), {@code TRANSFER} (between lines of one holder and stage:
 * re-appropriation, virement), {@code ADJUST} (signed change of total: supplementary, cut, revaluation),
 * {@code WRITE_OFF} (receivable waiver / write-off; stock loss), {@code LAPSE} (period close).
 *
 * @param target    the second account for DISTRIBUTE, WITHDRAW and TRANSFER
 * @param currency  transaction currency (null = account currency); converted with {@code rateType}
 * @param attrs     request attributes for policies and decisions (case type, flags, quarter, authority ...)
 */
public record LedgerRequest(String operation, AccountKey account, AccountKey target, BigDecimal amount, String currency,
                            String rateType, String reference, String auditRef, String actor, LocalDate asOf,
                            boolean fromCommitment, boolean fromEncumbrance, boolean holdIfShort,
                            Map<String, Object> attrs) {

    public LedgerRequest {
        attrs = attrs == null ? Map.of() : Map.copyOf(attrs);
        operation = operation.toUpperCase();
    }

    /**
     * A money operation (SD-398): the amount is exact {@link org.sangam.core.money.Money} and its currency is the
     * transaction currency. Use this for LIMIT / RECEIVABLE ledgers; {@link #of} remains for QUANTITY ledgers.
     */
    public static LedgerRequest money(String op, AccountKey account, org.sangam.core.money.Money amount, String reference) {
        return new LedgerRequest(op, account, null, amount.toMajor(), amount.currencyCode(), null, reference, null, null, null,
                false, false, false, null);
    }

    /** Replaces the amount (and currency) with an exact money amount. */
    public LedgerRequest withMoney(org.sangam.core.money.Money m) {
        return new LedgerRequest(operation, account, target, m.toMajor(), m.currencyCode(), rateType, reference, auditRef, actor,
                asOf, fromCommitment, fromEncumbrance, holdIfShort, attrs);
    }

    public static LedgerRequest of(String op, AccountKey account, BigDecimal amount, String reference) {
        return new LedgerRequest(op, account, null, amount, null, null, reference, null, null, null, false, false, false, null);
    }

    public LedgerRequest withTarget(AccountKey t) {
        return new LedgerRequest(operation, account, t, amount, currency, rateType, reference, auditRef, actor, asOf,
                fromCommitment, fromEncumbrance, holdIfShort, attrs);
    }

    public LedgerRequest withAmount(BigDecimal a) {
        return new LedgerRequest(operation, account, target, a, currency, rateType, reference, auditRef, actor, asOf,
                fromCommitment, fromEncumbrance, holdIfShort, attrs);
    }

    public LedgerRequest withReference(String r) {
        return new LedgerRequest(operation, account, target, amount, currency, rateType, r, auditRef, actor, asOf,
                fromCommitment, fromEncumbrance, holdIfShort, attrs);
    }
}

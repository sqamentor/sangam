// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.port;

import java.math.BigDecimal;
import java.util.Currency;

import org.sangam.core.money.Money;

/**
 * State carried across instances and years (GP-48): budget balance and encumbrance (GP-11), leave balance, reservation
 * roster points, cumulative expenditure against a sanction (GP-42). Postings are idempotent by {@code reference}, so a
 * retried delegate never posts twice.
 *
 * <p><b>Units (SD-398, SG-20).</b> Every account has an explicit {@code unit}, fixed by its first posting or opening:
 * an ISO 4217 currency code for money (stored as integer minor units of that currency: paise for INR), or a quantity
 * unit code such as {@code DAYS}, {@code POINTS} or {@code COUNT} (stored as integers at
 * {@value #QUANTITY_EXPONENT} decimal places). An amount finer than the unit is rejected, never rounded, and a posting
 * in another unit than the account's is refused ({@code UNIT_MISMATCH}).
 */
public interface LedgerStore {

    /** Decimal places of a quantity (non-money) unit, e.g. half days of leave. */
    int QUANTITY_EXPONENT = 4;

    BigDecimal balance(String tenantId, String ledger, String account);

    /**
     * Posts {@code delta} (in {@code unit}) to the account.
     *
     * @param allowNegative false rejects a posting that would take the balance below zero
     * @return the result; {@code applied=false} with {@code reason=INSUFFICIENT_BALANCE} or {@code UNIT_MISMATCH}
     */
    Posting post(String tenantId, String ledger, String account, String unit, BigDecimal delta, String reference,
                 boolean allowNegative);

    /** Money posting: the unit is the amount's currency. */
    default Posting post(String tenantId, String ledger, String account, Money delta, String reference, boolean allowNegative) {
        return post(tenantId, ledger, account, delta.currencyCode(), delta.toMajor(), reference, allowNegative);
    }

    /** Reverses an earlier posting by reference (release of an encumbrance, refund). Idempotent. */
    Posting reverse(String tenantId, String ledger, String account, String reference);

    record Posting(boolean applied, String reason, BigDecimal balanceAfter) {
    }

    /** True when {@code unit} is an ISO 4217 currency with a minor unit (a money account). */
    static boolean isMoney(String unit) {
        try {
            return unit != null && Currency.getInstance(unit).getDefaultFractionDigits() >= 0;
        } catch (IllegalArgumentException notIso) {
            return false;
        }
    }

    /** Decimal exponent of the stored integers of an account in {@code unit}. */
    static int exponent(String unit) {
        if (unit == null || unit.isBlank()) {
            throw new IllegalArgumentException("a ledger posting needs an explicit unit (ISO 4217 currency or a quantity unit)");
        }
        return isMoney(unit) ? Currency.getInstance(unit).getDefaultFractionDigits() : QUANTITY_EXPONENT;
    }

    /** Exact integer units of {@code v} in {@code unit}; throws if {@code v} is finer than the unit. */
    static long units(BigDecimal v, String unit) {
        return Money.toMinorExact(v, exponent(unit));
    }

    static BigDecimal amount(long units, String unit) {
        return BigDecimal.valueOf(units, exponent(unit));
    }
}

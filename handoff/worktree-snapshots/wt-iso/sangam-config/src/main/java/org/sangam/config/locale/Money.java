// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.locale;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/** An exact amount in an ISO 4217 currency (R33: decimals, never floating point). */
public record Money(BigDecimal amount, String currency) implements Comparable<Money> {

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        Currency.getInstance(currency); // validates the ISO code
    }

    public static Money of(String amount, String currency) {
        return new Money(new BigDecimal(amount), currency);
    }

    public static Money zero(String currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public Money plus(Money o) {
        same(o);
        return new Money(amount.add(o.amount), currency);
    }

    public Money minus(Money o) {
        same(o);
        return new Money(amount.subtract(o.amount), currency);
    }

    public Money times(BigDecimal factor) {
        return new Money(amount.multiply(factor), currency);
    }

    public Money scaled() {
        return new Money(amount.setScale(Currency.getInstance(currency).getDefaultFractionDigits(), RoundingMode.HALF_UP),
                currency);
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    private void same(Money o) {
        if (!currency.equals(o.currency)) {
            throw new IllegalArgumentException("currency mismatch: " + currency + " vs " + o.currency);
        }
    }

    /** {@code part / whole * 100} with 4 decimals; zero when the whole is zero. */
    public static BigDecimal percent(BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return (part == null ? BigDecimal.ZERO : part).multiply(BigDecimal.valueOf(100)).divide(whole, 4, RoundingMode.HALF_UP);
    }

    @Override
    public int compareTo(Money o) {
        same(o);
        return amount.compareTo(o.amount);
    }
}

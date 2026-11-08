// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.locale;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Money formatting and parsing per locale configuration: ISO 4217 currency, digit grouping and amount units.
 *
 * <p>Units are configuration, e.g. {@code {K: 1000, M: 1000000, BN: 1000000000}} or
 * {@code {L: 100000, CR: 10000000}}, so a profile threshold may be written {@code 10L}, {@code 2.5M} or
 * {@code 15CR}. Parsing tolerates the currency symbol and grouping separators.
 */
public final class MoneyFormat {

    private final Currency currency;
    private final NumberGrouping grouping;
    private final Map<String, BigDecimal> units;
    private final String symbol;

    public MoneyFormat(Currency currency, NumberGrouping grouping, Map<String, BigDecimal> units, String symbol) {
        this.currency = currency;
        this.grouping = grouping == null ? NumberGrouping.of(3, 3) : grouping;
        Map<String, BigDecimal> u = new LinkedHashMap<>();
        if (units != null) {
            units.entrySet().stream()
                    .sorted(Comparator.comparingInt((Map.Entry<String, BigDecimal> e) -> e.getKey().length()).reversed())
                    .forEach(e -> u.put(e.getKey().toUpperCase(Locale.ROOT), e.getValue()));
        }
        this.units = Map.copyOf(u);
        this.symbol = symbol == null ? (currency == null ? "" : currency.getSymbol(Locale.ROOT)) : symbol;
    }

    /** A format with no units and western grouping (used when a profile has no locale). */
    public static MoneyFormat plain(String isoCode) {
        return new MoneyFormat(isoCode == null ? null : Currency.getInstance(isoCode), NumberGrouping.of(3, 3), Map.of(), null);
    }

    public Currency currency() {
        return currency;
    }

    public Map<String, BigDecimal> units() {
        return units;
    }

    /** Parses {@code 1500}, {@code "12.50"}, {@code "10L"}, {@code "2.5M"}, {@code "₹5,00,000"}. Null for null or blank. */
    public BigDecimal parse(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal b) {
            return b;
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        String s = v.toString().trim();
        if (!symbol.isEmpty()) {
            s = s.replace(symbol, "");
        }
        if (currency != null) {
            s = s.replace(currency.getCurrencyCode(), "");
        }
        s = s.replace(grouping.separator(), "").replace("_", "").replace(" ", "").toUpperCase(Locale.ROOT);
        if (!".".equals(grouping.decimal())) {
            s = s.replace(grouping.decimal(), ".");
        }
        if (s.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, BigDecimal> u : units.entrySet()) {
            if (s.endsWith(u.getKey()) && s.length() > u.getKey().length()) {
                return new BigDecimal(s.substring(0, s.length() - u.getKey().length())).multiply(u.getValue());
            }
        }
        return new BigDecimal(s);
    }

    /** Formats with the currency's minor digits, grouping and symbol, e.g. {@code ₹12,34,567.00}. */
    public String format(BigDecimal amount) {
        int digits = currency == null ? 2 : Math.max(currency.getDefaultFractionDigits(), 0);
        BigDecimal a = amount.setScale(digits, RoundingMode.HALF_UP);
        String sign = a.signum() < 0 ? "-" : "";
        String plain = a.abs().toPlainString();
        int dot = plain.indexOf('.');
        String intPart = dot < 0 ? plain : plain.substring(0, dot);
        String frac = dot < 0 ? "" : grouping.decimal() + plain.substring(dot + 1);
        return sign + symbol + grouping.group(intPart) + frac;
    }

    /** Rounds to whole currency units with the configured rounding mode (e.g. "fractions of a rupee" rules). */
    public static BigDecimal roundWhole(BigDecimal amount, RoundingMode mode) {
        return amount.setScale(0, mode == null ? RoundingMode.HALF_UP : mode);
    }
}

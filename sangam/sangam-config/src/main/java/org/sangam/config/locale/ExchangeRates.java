// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.locale;

import org.sangam.config.common.EffectiveDated;
import org.sangam.config.common.Tree;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Effective-dated official exchange rates (e.g. the rate a government notifies for its missions abroad, or a
 * treasury rate for a corporate). Rows: {@code {from: USD, to: INR, rate: 83.25, effectiveFrom: 2026-10-01}}.
 * Conversion also gives the gain or loss against a booked rate, which the ledger posts to the account the profile
 * names for exchange differences.
 */
public final class ExchangeRates {

    private final List<Map<String, Object>> rows;

    public ExchangeRates(List<Map<String, Object>> rows) {
        this.rows = rows == null ? List.of() : List.copyOf(rows);
    }

    public static ExchangeRates from(Object v) {
        return new ExchangeRates(Tree.maps(v));
    }

    public Optional<BigDecimal> rate(String from, String to, LocalDate asOf) {
        if (from.equals(to)) {
            return Optional.of(BigDecimal.ONE);
        }
        List<Map<String, Object>> direct = new ArrayList<>();
        List<Map<String, Object>> inverse = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            if (from.equals(r.get("from")) && to.equals(r.get("to"))) {
                direct.add(r);
            } else if (to.equals(r.get("from")) && from.equals(r.get("to"))) {
                inverse.add(r);
            }
        }
        Optional<Map<String, Object>> d = EffectiveDated.select(direct, asOf);
        if (d.isPresent()) {
            return Optional.of(new BigDecimal(d.get().get("rate").toString()));
        }
        return EffectiveDated.select(inverse, asOf)
                .map(r -> BigDecimal.ONE.divide(new BigDecimal(r.get("rate").toString()), 10, RoundingMode.HALF_UP));
    }

    public Money convert(Money m, String to, LocalDate asOf) {
        BigDecimal r = rate(m.currency(), to, asOf)
                .orElseThrow(() -> new IllegalArgumentException("no rate " + m.currency() + "->" + to + " on " + asOf));
        return new Money(m.amount().multiply(r), to).scaled();
    }

    /** Difference (in {@code to}) between converting at today's rate and at the booked rate; positive is a gain. */
    public Money exchangeDifference(Money m, String to, BigDecimal bookedRate, LocalDate asOf) {
        Money now = convert(m, to, asOf);
        Money booked = new Money(m.amount().multiply(bookedRate), to).scaled();
        return now.minus(booked);
    }
}

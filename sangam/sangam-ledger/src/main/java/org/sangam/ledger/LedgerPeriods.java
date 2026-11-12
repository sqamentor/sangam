// SPDX-License-Identifier: Apache-2.0
package org.sangam.ledger;

import org.sangam.config.common.Tree;
import org.sangam.config.locale.FiscalYearNotConfiguredException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * The one "explicit period, else the tenant's current fiscal period" rule of the ledger and billing (X1-08: it was
 * written three times, and billing did not accept the {@code fiscalYear} alias the ledger accepts). Callers: the
 * ledger connector, the billing connector and the billing refund endpoint.
 *
 * <p>The current period always comes from the caller's period resolver (the tenant profile's fiscal calendar, which
 * computes the year with the core {@code FiscalYears}); this class computes no fiscal year itself. Without a fiscal
 * year the resolver throws {@link FiscalYearNotConfiguredException}; nothing here invents a calendar year (SL-125,
 * SL-127).
 */
public final class LedgerPeriods {

    /** Step input keys naming the period, first non-blank wins: {@code period}, then the alias {@code fiscalYear}. */
    public static final List<String> KEYS = List.of("period", "fiscalYear");

    /** The value that asks for the tenant's current fiscal period, like a missing period. */
    public static final String CURRENT = "current";

    private LedgerPeriods() {
    }

    /** The first non-blank of {@link #KEYS} in {@code input}, as text; null when none is given. */
    public static String explicit(Map<String, ?> input) {
        if (input == null) {
            return null;
        }
        for (String k : KEYS) {
            Object v = input.get(k);
            if (v != null && !v.toString().isBlank()) {
                return Tree.str(v, null);
            }
        }
        return null;
    }

    /**
     * {@code explicit} when it names a period; else (null, blank or {@value #CURRENT}, any case) the period
     * {@code current} supplies, which is asked only then.
     */
    public static String periodOr(String explicit, Supplier<String> current) {
        if (explicit != null && !explicit.isBlank() && !CURRENT.equalsIgnoreCase(explicit.trim())) {
            return explicit;
        }
        return current.get();
    }

    /**
     * The period of a step: {@link #explicit} of its input, else {@code resolver(tenant, today)}. A null resolver is a
     * deployment without a fiscal calendar: a step that needs the current period is refused with
     * {@link FiscalYearNotConfiguredException}.
     */
    public static String periodOr(Map<String, ?> input, String tenant, LocalDate today,
                                  BiFunction<String, LocalDate, String> resolver) {
        return periodOr(explicit(input), () -> {
            if (resolver == null) {
                throw new FiscalYearNotConfiguredException("no period resolver: a step without a period for tenant "
                        + tenant + " is refused");
            }
            return resolver.apply(tenant, today);
        });
    }
}

// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry.asset;

import org.sangam.registry.RegistryException;
import org.sangam.registry.support.Maps;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

/**
 * Depreciation parameters of an asset class (configuration). {@code method}: NONE, STRAIGHT_LINE,
 * DECLINING_BALANCE ({@code rate} = annual percent; default double-declining 200 / life in years),
 * UNITS_OF_PRODUCTION ({@code unitsTotal} over the life, units from the meter {@code unitsMeterType}) or
 * SUM_OF_YEARS. {@code convention}: FULL_MONTH, HALF_MONTH or DAILY (first-period proration). Residual value is
 * {@code residualPercent} of cost unless the asset carries its own residual value.
 */
public record DepreciationParams(String method, Integer usefulLifeMonths, BigDecimal residualPercent, BigDecimal rate,
                                 BigDecimal unitsTotal, String convention, String unitsMeterType) {

    static final Set<String> METHODS = Set.of("NONE", "STRAIGHT_LINE", "DECLINING_BALANCE", "UNITS_OF_PRODUCTION", "SUM_OF_YEARS");
    static final Set<String> CONVENTIONS = Set.of("FULL_MONTH", "HALF_MONTH", "DAILY");

    public static final DepreciationParams NONE = new DepreciationParams(null, null, null, null, null, null, null);

    public static DepreciationParams from(Map<String, Object> m) {
        if (m == null || m.isEmpty()) {
            return NONE;
        }
        String method = Maps.str(m, "method") == null ? null : Maps.str(m, "method").toUpperCase();
        if (method != null && !METHODS.contains(method)) {
            throw RegistryException.invalid("depreciation method must be one of " + METHODS);
        }
        String conv = Maps.str(m, "convention") == null ? null : Maps.str(m, "convention").toUpperCase();
        if (conv != null && !CONVENTIONS.contains(conv)) {
            throw RegistryException.invalid("depreciation convention must be one of " + CONVENTIONS);
        }
        Integer life = Maps.integer(m, "usefulLifeMonths");
        if (life != null && life <= 0) {
            throw RegistryException.invalid("usefulLifeMonths must be positive");
        }
        return new DepreciationParams(method, life, Maps.decimal(m, "residualPercent"), Maps.decimal(m, "rate"),
                Maps.decimal(m, "unitsTotal"), conv, Maps.str(m, "unitsMeterType"));
    }

    /** This class's values, falling back to the parent class's. */
    public DepreciationParams inherit(DepreciationParams parent) {
        if (parent == null) {
            return this;
        }
        return new DepreciationParams(method != null ? method : parent.method,
                usefulLifeMonths != null ? usefulLifeMonths : parent.usefulLifeMonths,
                residualPercent != null ? residualPercent : parent.residualPercent, rate != null ? rate : parent.rate,
                unitsTotal != null ? unitsTotal : parent.unitsTotal, convention != null ? convention : parent.convention,
                unitsMeterType != null ? unitsMeterType : parent.unitsMeterType);
    }

    public String effectiveMethod() {
        return method == null ? "NONE" : method;
    }

    public String effectiveConvention() {
        return convention == null ? "FULL_MONTH" : convention;
    }

    public Map<String, Object> toMap() {
        return Maps.of("method", method, "usefulLifeMonths", usefulLifeMonths, "residualPercent", residualPercent,
                "rate", rate, "unitsTotal", unitsTotal, "convention", convention, "unitsMeterType", unitsMeterType);
    }
}

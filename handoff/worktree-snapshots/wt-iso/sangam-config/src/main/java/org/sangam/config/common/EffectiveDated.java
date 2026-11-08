// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.common;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Effective dating for profile rows (everything is versioned and effective-dated). A row is a map that may carry
 * {@code effectiveFrom} / {@code effectiveTo} (ISO dates, {@code effectiveTo} exclusive). Rows without dates are
 * always effective.
 */
public final class EffectiveDated {

    private EffectiveDated() {
    }

    public static boolean isEffective(Map<String, Object> row, LocalDate asOf) {
        LocalDate from = date(row.get("effectiveFrom"));
        LocalDate to = date(row.get("effectiveTo"));
        return (from == null || !asOf.isBefore(from)) && (to == null || asOf.isBefore(to));
    }

    public static List<Map<String, Object>> filter(List<Map<String, Object>> rows, LocalDate asOf) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (rows != null) {
            for (Map<String, Object> r : rows) {
                if (isEffective(r, asOf)) {
                    out.add(r);
                }
            }
        }
        return out;
    }

    /** The latest-starting effective row, or empty. */
    public static Optional<Map<String, Object>> select(List<Map<String, Object>> rows, LocalDate asOf) {
        return filter(rows, asOf).stream()
                .max(Comparator.comparing(r -> {
                    LocalDate f = date(r.get("effectiveFrom"));
                    return f == null ? LocalDate.MIN : f;
                }));
    }

    public static LocalDate date(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof LocalDate d) {
            return d;
        }
        if (v instanceof java.util.Date d) {
            return new java.sql.Date(d.getTime()).toLocalDate();
        }
        String s = v.toString().trim();
        return s.isEmpty() || s.equalsIgnoreCase("null") ? null : LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
    }
}

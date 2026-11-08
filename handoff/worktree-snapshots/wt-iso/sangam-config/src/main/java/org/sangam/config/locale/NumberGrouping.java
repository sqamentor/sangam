// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.locale;

import java.util.Map;

/**
 * Digit grouping of the integer part, from configuration: the first (rightmost) group size and the size of every
 * further group. {@code 3/3} is 1,234,567 (thousand / million); {@code 3/2} is 12,34,567 (lakh / crore).
 *
 * @param first     size of the rightmost group
 * @param rest      size of every group to its left
 * @param separator grouping separator
 * @param decimal   decimal separator
 */
public record NumberGrouping(int first, int rest, String separator, String decimal) {

    public NumberGrouping {
        if (first < 1 || rest < 1) {
            throw new IllegalArgumentException("group sizes must be positive");
        }
        separator = separator == null ? "," : separator;
        decimal = decimal == null ? "." : decimal;
    }

    public static NumberGrouping of(int first, int rest) {
        return new NumberGrouping(first, rest, ",", ".");
    }

    /** From a profile map {@code {first: 3, rest: 2, separator: ",", decimal: "."}}; default 3/3. */
    public static NumberGrouping from(Map<String, Object> m) {
        if (m == null || m.isEmpty()) {
            return of(3, 3);
        }
        return new NumberGrouping(num(m.get("first"), 3), num(m.get("rest"), 3),
                m.get("separator") == null ? "," : m.get("separator").toString(),
                m.get("decimal") == null ? "." : m.get("decimal").toString());
    }

    private static int num(Object v, int d) {
        return v == null ? d : Integer.parseInt(v.toString().trim());
    }

    /** Groups an unsigned digit string. */
    public String group(String digits) {
        int n = digits.length();
        if (n <= first) {
            return digits;
        }
        StringBuilder out = new StringBuilder(digits.substring(n - first));
        int end = n - first;
        while (end > 0) {
            int start = Math.max(0, end - rest);
            out.insert(0, digits.substring(start, end) + separator);
            end = start;
        }
        return out.toString();
    }
}

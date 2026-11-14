// SPDX-License-Identifier: Apache-2.0
package org.sangam.host.conformance;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The host-independent business result of one scenario (M-150): named values in canonical text, free of ids,
 * timestamps and host artefacts, so the same operation, inputs and rule versions give the same result on every host.
 *
 * <p>Canonical text: {@code null}; a number as its exact decimal without trailing zeros (an integral {@code Long},
 * {@code Integer} or {@code BigDecimal} all read {@code 1500}; binary floating point is refused);
 * booleans and strings as they are; a list in its own order; a map by sorted keys; nested values the same way.
 */
public final class BusinessResult {

    private final SortedMap<String, String> values;

    private BusinessResult(SortedMap<String, String> values) {
        this.values = Collections.unmodifiableSortedMap(values);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** A result read back from canonical text (the golden file). */
    public static BusinessResult ofCanonical(Map<String, String> canonical) {
        return new BusinessResult(new TreeMap<>(canonical));
    }

    public SortedMap<String, String> values() {
        return values;
    }

    /** Canonical text of {@code value}. */
    public static String canonical(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Double || value instanceof Float) {
            throw new IllegalArgumentException("a floating-point value is not a business result (money is minor units"
                    + " or BigDecimal): " + value);
        }
        if (value instanceof BigDecimal d) {
            return d.signum() == 0 ? "0" : d.stripTrailingZeros().toPlainString();
        }
        if (value instanceof BigInteger || value instanceof Long || value instanceof Integer || value instanceof Short
                || value instanceof Byte) {
            return new BigDecimal(value.toString()).toPlainString();
        }
        if (value instanceof Map<?, ?> m) {
            return m.entrySet().stream()
                    .map(e -> String.valueOf(e.getKey()) + "=" + canonical(e.getValue()))
                    .sorted()
                    .collect(Collectors.joining(",", "{", "}"));
        }
        if (value instanceof Collection<?> c) {
            return c.stream().map(BusinessResult::canonical).collect(Collectors.joining(",", "[", "]"));
        }
        if (value instanceof byte[]) {
            throw new IllegalArgumentException("compare bytes in the scenario and record the outcome, not the bytes");
        }
        return value.toString();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BusinessResult r && values.equals(r.values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public String toString() {
        return values.toString();
    }

    /** Collects one scenario's values; a name may be set only once. */
    public static final class Builder {

        private final SortedMap<String, String> values = new TreeMap<>();

        private Builder() {
        }

        public Builder put(String name, Object value) {
            Objects.requireNonNull(name, "name");
            if (!name.matches("[A-Za-z0-9][A-Za-z0-9.-]*")) {
                throw new IllegalArgumentException("result names are letters, digits, '.' and '-': " + name);
            }
            if (values.putIfAbsent(name, canonical(value)) != null) {
                throw new IllegalArgumentException("result " + name + " was already recorded");
            }
            return this;
        }

        public BusinessResult build() {
            return new BusinessResult(new TreeMap<>(values));
        }
    }
}

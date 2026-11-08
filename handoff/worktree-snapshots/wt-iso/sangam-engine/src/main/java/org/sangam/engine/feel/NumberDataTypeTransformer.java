// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.feel;

import java.math.BigDecimal;

import org.operaton.bpm.dmn.engine.impl.spi.type.DmnDataTypeTransformer;
import org.operaton.bpm.engine.variable.Variables;
import org.operaton.bpm.engine.variable.value.TypedValue;

/**
 * SG-33 (ADR-D-26): DMN {@code typeRef="number"} (the FEEL number type, DMN 1.3 §10.3.2.3.1), which Operaton does not
 * register (it would pass any value through unchecked). Accepts any {@link Number} or a decimal string and yields
 * the exact value: {@code Long} when whole and in range, otherwise {@code BigDecimal}; a {@code Double}/{@code Float}
 * is read by its decimal representation. Anything else (or NaN/infinity) is rejected like the other DMN types.
 * Money tables use {@code number} or {@code long} (minor units), never {@code double}.
 */
public final class NumberDataTypeTransformer implements DmnDataTypeTransformer {

    public static final String TYPE = "number";

    @Override
    public TypedValue transform(Object value) {
        return Variables.untypedValue(SangamValueMapper.toJava(exact(value)));
    }

    static BigDecimal exact(Object value) {
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return BigDecimal.valueOf(((Number) value).longValue());
        }
        if (value instanceof java.math.BigInteger bi) {
            return new BigDecimal(bi);
        }
        if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new IllegalArgumentException("not a FEEL number: " + value);
            }
            return new BigDecimal(value.toString());
        }
        if (value instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        if (value instanceof String s) {
            try {
                return new BigDecimal(s.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("not a FEEL number: '" + s + "'", e);
            }
        }
        throw new IllegalArgumentException("not a FEEL number: " + value);
    }
}

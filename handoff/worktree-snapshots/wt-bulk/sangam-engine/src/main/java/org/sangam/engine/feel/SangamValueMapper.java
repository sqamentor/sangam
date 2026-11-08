// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.feel;

import java.math.BigDecimal;

import org.camunda.feel.syntaxtree.Val;
import org.camunda.feel.syntaxtree.ValNumber;
import org.camunda.feel.valuemapper.CustomValueMapper;
import org.sangam.core.money.Money;

import scala.Function1;
import scala.Option;

/**
 * SG-33 (ADR-D-26): Sangam's FEEL value mapper. FEEL numbers are exact decimals (DMN 1.3 §10.3.2.3.1); the stock
 * {@code JavaValueMapper} turns every non-whole number into a {@code java.lang.Double} on the way out, which loses
 * precision and feeds the double back into decision-table input entries. This mapper runs ahead of it
 * ({@link #priority()}) and keeps numbers exact in both directions:
 * <ul>
 *   <li>FEEL to Java: a whole number that fits a {@code long} is a {@link Long} (unchanged behaviour), every other
 *       number is a {@link BigDecimal} (never a {@code Double});</li>
 *   <li>Java to FEEL: {@link BigDecimal} is taken as is; {@link Money} is its exact major-unit amount (minor units
 *       moved by the currency exponent, no rounding). Other values fall through to the stock mappers.</li>
 * </ul>
 * Registered for every Sangam DMN engine by {@link SangamFeel#install}.
 */
public final class SangamValueMapper implements CustomValueMapper {

    /** Ahead of the stock Java (priority 1) and Spin mappers. */
    static final int PRIORITY = 100;

    @Override
    public void org$camunda$feel$valuemapper$CustomValueMapper$_setter_$priority_$eq(int ignored) {
        // priority is fixed by priority()
    }

    @Override
    public int priority() {
        return PRIORITY;
    }

    @Override
    public Option<Val> toVal(Object x, Function1<Object, Val> inner) {
        if (x instanceof BigDecimal bd) {
            return Option.apply(new ValNumber(decimal(bd)));
        }
        if (x instanceof Money m) {
            return Option.apply(new ValNumber(decimal(m.toMajor())));
        }
        return Option.empty();
    }

    @Override
    public Option<Object> unpackVal(Val value, Function1<Val, Object> inner) {
        if (value instanceof ValNumber n) {
            return Option.apply(toJava(n.value().bigDecimal()));
        }
        if (value instanceof org.camunda.feel.syntaxtree.ValError e) {
            // SG-34: an error value (for example an invalid date literal) never leaves the engine as null
            throw new org.operaton.bpm.dmn.feel.impl.FeelException("SANGAM-FEEL-STRICT: evaluation error: " + e.error());
        }
        return Option.empty();
    }

    /** FEEL decimal (DECIMAL128 context for later arithmetic, DMN 1.3 §10.3.2.3.1); the value itself is not rounded. */
    static scala.math.BigDecimal decimal(BigDecimal bd) {
        return new scala.math.BigDecimal(bd, java.math.MathContext.DECIMAL128);
    }

    /** Exact Java form of a FEEL number: Long when whole and in range, else BigDecimal. */
    static Object toJava(BigDecimal d) {
        BigDecimal s = d.stripTrailingZeros();
        if (s.scale() <= 0 && s.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) <= 0
                && s.compareTo(BigDecimal.valueOf(Long.MIN_VALUE)) >= 0) {
            return s.longValueExact();
        }
        return d;
    }
}

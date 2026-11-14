// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.engine;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.regex.Pattern;

/**
 * Decision 138: the lossless decimal-text format of the engine decimal type, version 1 ({@value #FORMAT_VERSION}).
 *
 * <p><b>Format v1.</b> The stored text is {@link BigDecimal#toString()} of the value: the canonical form the JDK
 * documents as converting back through {@link BigDecimal#BigDecimal(String)} to the same unscaled value <b>and</b>
 * scale (so {@code 1.50} stays {@code 1.50}, {@code 1E+3} keeps scale -3). No rounding, no trimming of zeros, no
 * locale, no grouping. A stored text that is not in canonical form is refused on read (it was not written by this
 * format).
 *
 * <p><b>Inputs</b> ({@link #parse}): decimal text only: an optional minus sign, digits, an optional fraction, an
 * optional exponent ({@code e}/{@code E}, at most 9 digits). No plus sign, no blanks, no {@code NaN}/{@code Infinity},
 * no hexadecimal, at most {@value #MAX_CHARS} characters. Exact representations ({@code BigDecimal}, {@code BigInteger},
 * {@code Long}, {@code Integer}, {@code Short}, {@code Byte}) convert exactly ({@link #exact}). {@code Double} and
 * {@code Float} are <b>refused</b>: a binary float does not hold the decimal the caller meant, and decision 138 forbids
 * any float or Double, including intermediates.
 *
 * <p><b>Limits.</b> The canonical text may have at most {@value #MAX_CHARS} characters (the engine stores it in
 * {@code TEXT_}, 2,000 characters on Oracle, 4,000 elsewhere); a longer value is refused, never truncated or rounded.
 * This type does no arithmetic: division, scale and rounding are decided by the business rule that computes the value
 * (decision 139), never here.
 */
public final class DecimalText {

    /** The format version, part of the persisted type id {@code sangam-decimal-v1}. */
    public static final int FORMAT_VERSION = 1;
    /** The longest accepted text, input or canonical. */
    public static final int MAX_CHARS = 1000;

    static final Pattern INPUT = Pattern.compile("-?[0-9]+(\\.[0-9]+)?([eE][+-]?[0-9]{1,9})?");

    private DecimalText() {
    }

    /** The canonical text of {@code value}; refuses {@code null} and values whose text exceeds {@link #MAX_CHARS}. */
    public static String format(BigDecimal value) {
        if (value == null) {
            throw new DecimalFormatException("DECIMAL_NULL", "a decimal value is required");
        }
        String s = value.toString();
        if (s.length() > MAX_CHARS) {
            throw new DecimalFormatException("DECIMAL_TOO_LARGE", "decimal text of " + s.length()
                    + " characters exceeds " + MAX_CHARS);
        }
        return s;
    }

    /** Parses decimal input text strictly (see the class comment); never goes through a binary float. */
    public static BigDecimal parse(String text) {
        if (text == null) {
            throw new DecimalFormatException("DECIMAL_NULL", "decimal text is required");
        }
        if (text.length() > MAX_CHARS) {
            throw new DecimalFormatException("DECIMAL_TOO_LARGE", "decimal text exceeds " + MAX_CHARS + " characters");
        }
        if (!INPUT.matcher(text).matches()) {
            throw new DecimalFormatException("DECIMAL_INVALID", "not a decimal: '" + abbreviate(text) + "'");
        }
        try {
            BigDecimal v = new BigDecimal(text);
            format(v); // the canonical form must fit too
            return v;
        } catch (NumberFormatException | ArithmeticException e) {
            // defensive: the pattern caps the exponent at 9 digits, so the scale cannot leave int range
            throw new DecimalFormatException("DECIMAL_OUT_OF_RANGE", "decimal out of range: '" + abbreviate(text) + "'");
        }
    }

    /** Reads stored text: it must be the canonical form written by {@link #format}. */
    public static BigDecimal parseCanonical(String stored) {
        BigDecimal v = parse(stored);
        if (!v.toString().equals(stored)) {
            throw new DecimalFormatException("DECIMAL_NOT_CANONICAL", "stored decimal text is not in format v"
                    + FORMAT_VERSION + ": '" + abbreviate(stored) + "'");
        }
        return v;
    }

    /**
     * The exact decimal of a value: {@code BigDecimal} as is, integral types exactly, decimal text through
     * {@link #parse}; {@code Double}, {@code Float} and anything else are refused.
     */
    public static BigDecimal exact(Object value) {
        if (value instanceof BigDecimal d) {
            format(d);
            return d;
        }
        if (value instanceof BigInteger i) {
            return checked(new BigDecimal(i));
        }
        if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return BigDecimal.valueOf(((Number) value).longValue());
        }
        if (value instanceof String s) {
            return parse(s);
        }
        if (value instanceof Double || value instanceof Float) {
            throw new DecimalFormatException("DECIMAL_FROM_FLOAT",
                    "a binary floating-point value cannot become an exact decimal; pass decimal text or BigDecimal");
        }
        throw new DecimalFormatException("DECIMAL_UNSUPPORTED", "cannot convert "
                + (value == null ? "null" : value.getClass().getName()) + " to an exact decimal");
    }

    private static BigDecimal checked(BigDecimal v) {
        format(v);
        return v;
    }

    private static String abbreviate(String s) {
        return s.length() <= 40 ? s : s.substring(0, 40) + "...";
    }

    /** A refused decimal, with a stable code. */
    public static final class DecimalFormatException extends IllegalArgumentException {
        private final String code;

        public DecimalFormatException(String code, String message) {
            super(code + ": " + message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}

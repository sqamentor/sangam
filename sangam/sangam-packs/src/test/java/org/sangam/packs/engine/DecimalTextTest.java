// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.variable.Variables;
import org.operaton.bpm.engine.variable.value.TypedValue;

/** Decision 138: format v1 of the engine decimal type (no float anywhere, value and scale round-trip). */
class DecimalTextTest {

    private static String code(Runnable r) {
        return assertThrows(DecimalText.DecimalFormatException.class, r::run).code();
    }

    @Test
    void valueAndScaleRoundTrip() {
        for (String s : List.of("0", "0.00", "1.50", "-0.10", "12345678901234567890.123456789", "1E+3", "1.0E-10",
                "-7.25", "0.1", "100", "1E-999")) {
            BigDecimal v = new BigDecimal(s);
            String stored = DecimalText.format(v);
            BigDecimal back = DecimalText.parseCanonical(stored);
            assertEquals(v.unscaledValue(), back.unscaledValue(), s);
            assertEquals(v.scale(), back.scale(), s);
            assertEquals(v, back, s);
        }
    }

    @Test
    void repeatedAdditionStaysExact() {
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < 10; i++) {
            sum = DecimalText.parseCanonical(DecimalText.format(sum.add(new BigDecimal("0.1"))));
        }
        assertEquals(new BigDecimal("1.0"), sum, "ten times 0.1 through the stored form is exactly 1.0");
        double d = 0;
        for (int i = 0; i < 10; i++) {
            d += 0.1;
        }
        assertFalse(d == 1.0, "the same sum in double is not 1.0: the reason for decision 138");
    }

    @Test
    void inputIsStrictDecimalText() {
        assertEquals(new BigDecimal("-12.340"), DecimalText.parse("-12.340"));
        assertEquals(new BigDecimal("1.5E+3"), DecimalText.parse("1.5e3"));
        for (String bad : List.of("", " 1", "1 ", "+1", "1.", ".5", "NaN", "Infinity", "-Infinity", "0x10", "1,5",
                "1_000", "١٢", "1e", "1e1234567890", "--1", "1.2.3")) {
            assertEquals("DECIMAL_INVALID", code(() -> DecimalText.parse(bad)), bad);
        }
    }

    @Test
    void extremeExponentsStayExact() {
        // the 9-digit exponent cap keeps every accepted scale inside int range: no wrap, no rounding
        for (String s : List.of("1e999999999", "1e-999999999", "-9.99e-999999999")) {
            BigDecimal v = DecimalText.parse(s);
            assertEquals(new BigDecimal(s), v, s);
            assertEquals(v.scale(), DecimalText.parseCanonical(DecimalText.format(v)).scale(), s);
        }
    }

    @Test
    void storedTextMustBeCanonical() {
        assertEquals("DECIMAL_NOT_CANONICAL", code(() -> DecimalText.parseCanonical("1.5e3")));
        assertEquals("DECIMAL_NOT_CANONICAL", code(() -> DecimalText.parseCanonical("1000E-3")));
        assertEquals("DECIMAL_INVALID", code(() -> DecimalText.parseCanonical("abc")));
        assertEquals(new BigDecimal("1.000"), DecimalText.parseCanonical("1.000"));
    }

    @Test
    void lengthLimitRefusesNeverTruncates() {
        String max = "9".repeat(DecimalText.MAX_CHARS);
        assertEquals(new BigDecimal(max), DecimalText.parseCanonical(max));
        String over = "9".repeat(DecimalText.MAX_CHARS + 1);
        assertEquals("DECIMAL_TOO_LARGE", code(() -> DecimalText.parse(over)));
        assertEquals("DECIMAL_TOO_LARGE", code(() -> DecimalText.format(new BigDecimal(over))));
        // a value built in code whose canonical text is too long is refused when stored
        BigDecimal wide = new BigDecimal(new BigInteger("1" + "0".repeat(DecimalText.MAX_CHARS)), 0);
        assertEquals("DECIMAL_TOO_LARGE", code(() -> DecimalText.format(wide)));
    }

    @Test
    void exactConversionNeverAcceptsBinaryFloats() {
        assertEquals(new BigDecimal("42"), DecimalText.exact(42));
        assertEquals(new BigDecimal("42"), DecimalText.exact(42L));
        assertEquals(new BigDecimal("42"), DecimalText.exact((short) 42));
        assertEquals(new BigDecimal("42"), DecimalText.exact((byte) 42));
        assertEquals(new BigDecimal("123456789012345678901234567890"),
                DecimalText.exact(new BigInteger("123456789012345678901234567890")));
        assertEquals(new BigDecimal("0.10"), DecimalText.exact("0.10"));
        assertEquals("DECIMAL_FROM_FLOAT", code(() -> DecimalText.exact(0.1d)));
        assertEquals("DECIMAL_FROM_FLOAT", code(() -> DecimalText.exact(0.1f)));
        assertEquals("DECIMAL_UNSUPPORTED", code(() -> DecimalText.exact(new Object())));
        assertEquals("DECIMAL_UNSUPPORTED", code(() -> DecimalText.exact(null)));
        assertEquals("DECIMAL_NULL", code(() -> DecimalText.format(null)));
    }

    @Test
    void theTypedValueKeepsTypeNameAndConvertsOnlyExactly() {
        TypedValue v = DecimalValueType.DECIMAL.createValue("2.50", Map.of());
        assertEquals(DecimalValueType.NAME, v.getType().getName());
        assertEquals(new BigDecimal("2.50"), v.getValue());
        assertTrue(DecimalValueType.decimal(new BigDecimal("1"), true).isTransient());
        assertTrue(DecimalValueType.DECIMAL.canConvertFromTypedValue(Variables.longValue(7L)));
        assertEquals(new BigDecimal("7"), DecimalValueType.DECIMAL.convertFromTypedValue(Variables.integerValue(7))
                .getValue());
        assertFalse(DecimalValueType.DECIMAL.canConvertFromTypedValue(Variables.doubleValue(0.1)));
        assertThrows(IllegalArgumentException.class,
                () -> DecimalValueType.DECIMAL.convertFromTypedValue(Variables.doubleValue(0.1)));
        assertEquals("DECIMAL_FROM_FLOAT", code(() -> DecimalValueType.DECIMAL.createValue(0.1d, Map.of())));
        assertEquals("sangam-decimal-v1", DecimalValueSerializer.NAME, "the persisted type id is fixed");
    }
}

// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Decision 363: the SDK writes a Decimal the way the server does ({@code TypedVariables.decimalText}). A value of
 * negative scale ({@code 1000} with scale -3) has no plain text of the same scale; {@code toPlainString} gave
 * {@code "1000"} (scale 0), so the scale was silently changed on the wire.
 */
class TypedValueDecimalTest {

    private static final Path CONTRACT = Paths.get("..", "..", "sangam-rest", "api", "sangam-api.yaml");

    /** The published DecimalString pattern, read from the contract so the test follows it. */
    static Pattern decimalStringPattern() throws IOException {
        List<String> lines = Files.readAllLines(CONTRACT, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).equals("    DecimalString:")) {
                for (int j = i + 1; j < i + 5; j++) {
                    String l = lines.get(j).trim();
                    if (l.startsWith("pattern: ")) return Pattern.compile(l.substring("pattern: ".length()));
                }
            }
        }
        throw new AssertionError("DecimalString pattern not found in " + CONTRACT.toAbsolutePath());
    }

    static List<BigDecimal> values() {
        return List.of(
                new BigDecimal("1234.50"),
                new BigDecimal("0.00"),
                new BigDecimal("-7"),
                new BigDecimal(BigInteger.ONE, -3),            // 1E+3
                new BigDecimal(BigInteger.ZERO, -3),           // 0E+3
                new BigDecimal(BigInteger.valueOf(-150), -1),  // -1.50E+3
                new BigDecimal(BigInteger.ONE, 1001));         // plain text longer than 1000 characters
    }

    @ParameterizedTest
    @MethodSource("values")
    void decimalKeepsValueAndScaleAndMatchesTheContract(BigDecimal v) throws IOException {
        String text = (String) TypedValue.decimal(v).value();
        BigDecimal back = new BigDecimal(text);
        assertEquals(v, back, "value and scale survive the wire text " + text);
        assertEquals(v.scale(), back.scale(), "scale of " + text);
        assertTrue(decimalStringPattern().matcher(text).matches(), text + " matches the published DecimalString");
    }

    @Test
    void negativeScaleIsCanonicalTextAndPlainScaleStaysPlain() {
        assertEquals("1E+3", TypedValue.decimal(new BigDecimal(BigInteger.ONE, -3)).value());
        assertEquals("1234.50", TypedValue.decimal(new BigDecimal("1234.50")).value());
        assertEquals("0.00", TypedValue.decimal("0.00").value());
    }
}

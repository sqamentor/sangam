// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Decision 363: every Decimal text {@link TypedVariables} writes on the way out matches the published
 * {@code DecimalString} pattern, so a client that validates an answer against the contract never rejects one. The
 * pattern is read from {@code sangam-rest/api/sangam-api.yaml} (the generated contract), so code and contract cannot
 * drift: a negative-scale decimal ({@code 1E+3}, the only text that keeps scale -3) and a decimal whose plain text is
 * over the limit ({@code 1E-2000}) are in canonical exponent form, everything else is plain text.
 */
class TypedVariablesDecimalContractTest {

    static Pattern decimalStringPattern() throws IOException {
        Path contract = Path.of("..", "sangam-rest", "api", "sangam-api.yaml");
        List<String> lines = Files.readAllLines(contract, StandardCharsets.UTF_8);
        int at = lines.indexOf("    DecimalString:");
        assertTrue(at >= 0, "the contract has components.schemas.DecimalString: " + contract.toAbsolutePath());
        for (int i = at + 1; i < lines.size() && lines.get(i).startsWith("      "); i++) {
            String line = lines.get(i);
            if (line.startsWith("      pattern: ")) {
                String p = line.substring("      pattern: ".length());
                if (p.startsWith("'") && p.endsWith("'")) {
                    p = p.substring(1, p.length() - 1).replace("''", "'");
                }
                return Pattern.compile(p);
            }
        }
        throw new AssertionError("DecimalString has no pattern in " + contract);
    }

    static String written(BigDecimal d) {
        Map<?, ?> out = (Map<?, ?>) TypedVariables.toJsonValue(d);
        assertEquals("Decimal", out.get("type"));
        return (String) out.get("value");
    }

    @Test
    void everyDecimalTheServerWritesMatchesThePublishedPattern() throws IOException {
        Pattern pattern = decimalStringPattern();
        List<BigDecimal> values = new ArrayList<>();
        for (String t : List.of("1E+3", "-2.5E+10", "0E+3", "1.00E+4", "-1E+1", "9.99E+999999", "1E-2000", "0E-2000",
                "-1.5E-1001", "1234.50", "0", "-0.01", "0E-7", "12345678901234567890.123456789")) {
            values.add(new BigDecimal(t));
        }
        Random random = new Random(363);
        for (int i = 0; i < 5000; i++) {
            BigInteger unscaled = new BigInteger(1 + random.nextInt(80), random);
            values.add(new BigDecimal(random.nextBoolean() ? unscaled : unscaled.negate(), random.nextInt(61) - 30));
        }
        for (BigDecimal d : values) {
            String text = written(d);
            assertTrue(pattern.matcher(text).matches(), d.unscaledValue() + " scale " + d.scale() + " written as '" + text
                    + "' does not match the published DecimalString pattern " + pattern);
        }
        assertEquals("1E+3", written(new BigDecimal("1E+3")), "decision 363: canonical exponent form");
        assertEquals("-2.5E+10", written(new BigDecimal("-2.5E+10")));
    }

    @Test
    void thePublishedPatternAcceptsOnlyTheFormsTheServerWrites() throws IOException {
        Pattern pattern = decimalStringPattern();
        for (String ok : List.of("0", "12", "-1250.5", "100000.00", "0.075", "1E+3", "-2.5E+10", "0E+3", "1.00E+4",
                "1E-2000", "0E-7")) {
            assertTrue(pattern.matcher(ok).matches(), ok);
        }
        // not BigDecimal#toString output: lower-case e, no sign, a small negative exponent (plain text is written),
        // a coefficient that is not normalised, a leading zero exponent, a negative zero
        for (String bad : List.of("1e5", "1E5", "1e+5", "1.5E-3", "1E-6", "12E+3", "0.5E+3", "1E+03", "1E+0", "-0E+3",
                ".5", "5.", "+1", "1,000.00", "NaN", "Infinity", " 1", "0x10", "1E+")) {
            assertFalse(pattern.matcher(bad).matches(), bad);
        }
    }
}

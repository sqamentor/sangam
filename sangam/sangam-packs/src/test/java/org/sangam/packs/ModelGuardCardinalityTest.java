// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.sangam.packs.spi.ModelGuard;

/**
 * SL-148: {@code ModelGuard.check} reports every malformed {@code sangam.maxCardinality} as a GP-09 violation instead
 * of throwing at deploy (before: a NullPointerException for a property without a value, a NumberFormatException for a
 * non-number), and refuses a bound below 1.
 */
class ModelGuardCardinalityTest {

    static byte[] model(String property) {
        return ("""
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:operaton="http://operaton.org/schema/1.0/bpmn" id="d" targetNamespace="sl148">
                  <process id="p" isExecutable="true" operaton:historyTimeToLive="30">
                    <startEvent id="s"/>
                    <userTask id="u"><extensionElements><operaton:properties>%s</operaton:properties></extensionElements>
                      <multiInstanceLoopCharacteristics isSequential="false"><loopCardinality>3</loopCardinality></multiInstanceLoopCharacteristics>
                    </userTask>
                    <endEvent id="e"/>
                    <sequenceFlow id="f1" sourceRef="s" targetRef="u"/>
                    <sequenceFlow id="f2" sourceRef="u" targetRef="e"/>
                  </process>
                </definitions>
                """).formatted(property).getBytes(StandardCharsets.UTF_8);
    }

    static List<String> check(String value) {
        String property = value == null ? "<operaton:property name=\"sangam.maxCardinality\"/>"
                : "<operaton:property name=\"sangam.maxCardinality\" value=\"" + value + "\"/>";
        return ModelGuard.check("m.bpmn", model(property));
    }

    static void oneViolation(List<String> v, String... fragments) {
        assertEquals(1, v.size(), v.toString());
        for (String f : fragments) {
            assertTrue(v.get(0).contains(f), v.get(0) + " should contain " + f);
        }
        assertTrue(v.get(0).contains("GP-09") && v.get(0).contains("activity u"), v.get(0));
    }

    @Test
    void aPropertyWithoutAValueIsAViolationNotANullPointer() {
        oneViolation(check(null), "without a value");
        oneViolation(check(""), "without a value");
        oneViolation(check("   "), "without a value");
    }

    @Test
    void aNonNumericValueIsAViolationNotANumberFormatException() {
        oneViolation(check("ten"), "'ten'", "not a whole number");
        oneViolation(check("2.5"), "'2.5'", "not a whole number");
        oneViolation(check("99999999999"), "not a whole number");
    }

    @Test
    void aBoundBelowOneIsRefused() {
        oneViolation(check("0"), "sangam.maxCardinality 0 < 1");
        oneViolation(check("-3"), "sangam.maxCardinality -3 < 1");
    }

    @Test
    void theAcceptedRangeIsOneToTheLimitAndAboveItIsStillRefused() {
        assertEquals(List.of(), check("1"));
        assertEquals(List.of(), check(" 50 "));
        oneViolation(check("51"), "51 > " + ModelGuard.MAX_MULTI_INSTANCE);
        oneViolation(ModelGuard.check("m.bpmn", model("")), "has no sangam.maxCardinality");
    }

    /** The regression seed B37 parked for the fuzz harness (M-095) passes through the guard as a violation. */
    @Test
    void theParkedFuzzSeedIsReportedAsAViolation() throws IOException {
        for (String seed : List.of("maxcardinality-without-value.bpmn", "maxcardinality-not-a-number.bpmn")) {
            byte[] xml;
            try (InputStream in = getClass().getResourceAsStream("PackParserFuzzTestInputs/bpmnModel/" + seed)) {
                assertTrue(in != null, seed);
                xml = in.readAllBytes();
            }
            List<String> v = ModelGuard.check(seed, xml);
            assertEquals(1, v.size(), seed + ": " + v);
            assertTrue(v.get(0).contains("sangam.maxCardinality") && v.get(0).contains("GP-09"), v.get(0));
        }
    }
}

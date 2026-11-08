// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.delegation;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** SG-01: delegation-of-powers amount bands are exact at 99,999 / 1,00,000 / 1,00,001 (major) and at one paisa. */
class PowerBandBoundaryTest {

    /** Two bands on the amount: up to 1,00,000 the seat's power is 1,00,000 (BAND-1); above it BAND-2 (10,00,000). */
    static final String DMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d" name="d" namespace="sangam">
              <decision id="powers" name="powers">
                <decisionTable id="t" hitPolicy="UNIQUE">
                  <input id="i1"><inputExpression id="e1" typeRef="number"><text>amount</text></inputExpression></input>
                  <output id="o1" name="maxAmount" typeRef="string"/>
                  <output id="o2" name="ruleRef" typeRef="string"/>
                  <rule id="r1"><inputEntry id="a1"><text>&lt;= 100000</text></inputEntry>
                    <outputEntry id="b1"><text>"100000"</text></outputEntry><outputEntry id="c1"><text>"BAND-1"</text></outputEntry></rule>
                  <rule id="r2"><inputEntry id="a2"><text>&gt; 100000</text></inputEntry>
                    <outputEntry id="b2"><text>"1000000"</text></outputEntry><outputEntry id="c2"><text>"BAND-2"</text></outputEntry></rule>
                </decisionTable>
              </decision>
            </definitions>""";

    static final DmnPowerEvaluator EVAL = new DmnPowerEvaluator();

    @ParameterizedTest
    @CsvSource({"99999,BAND-1", "99999.99,BAND-1", "100000,BAND-1", "100000.00,BAND-1", "100000.01,BAND-2", "100001,BAND-2"})
    void dmnBandSelectionAtTheBoundary(String amount, String band) {
        DmnPowerEvaluator.Evaluation e = EVAL.evaluate("h1", "powers", DMN, Map.of("amount", new BigDecimal(amount)));
        assertEquals(band, e.ruleRef(), amount);
    }

    @ParameterizedTest
    @CsvSource({"99999,true", "99999.99,true", "100000,true", "100000.00,true", "100000.01,false", "100001,false"})
    void limitIsInclusiveAndExact(String amount, boolean within) {
        assertEquals(within, DelegationOfPowersService.withinPower(new BigDecimal(amount), new BigDecimal("100000"), false));
    }

    @ParameterizedTest
    @CsvSource({"99999,true", "100000,true", "100001,false"})
    void limitBandInMinorUnits(long paise, boolean within) {
        // 1,00,000 paise = 1,000.00
        assertEquals(within, DelegationOfPowersService.withinPower(BigDecimal.valueOf(paise, 2), new BigDecimal("1000.00"), false));
    }
}

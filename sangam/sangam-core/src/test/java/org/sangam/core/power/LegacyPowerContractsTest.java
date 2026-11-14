// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.power;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * One test per entry of the behaviour-difference inventory in {@link LegacyPowerContracts} (owner decision 142):
 * each shows the old outcome in a comment and asserts the new one. None broadens authority.
 */
class LegacyPowerContractsTest {

    static final String INR = "INR";
    static final OperationMetadata MONETARY = (t, c, v, a) -> Optional.of(new OperationMetadata.Classification(true, INR, "sangam-core-powers", "m1"));

    static PowerOutcome eval(List<PowerRuleSource.DelegatedLimit> limits, List<PowerRuleSource.MandatoryCap> caps,
                             String amount, String currency) {
        return new PowerLimitEvaluator(MONETARY, (r, m) -> new PowerRuleSource.ApplicableRules(limits, caps))
                .evaluate(new PowerRequest("p", "SANCTION", "t", null, "GRANT", 1,
                        amount == null ? null : new BigDecimal(amount), currency, 1));
    }

    static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    static PowerRuleSource.DelegatedLimit l(Map<String, Object> row) {
        return LegacyPowerContracts.listenerRow("sangam-core-powers", "4", row, INR);
    }

    // 1: old permitted (allowed absent, no ceiling, no amount); new DENY
    @Test
    void inventory1_listenerRowStatingNoPowerDenies() {
        assertInstanceOf(PowerDecision.Deny.class, l(row("seatRole", "SO")).decision());
        assertInstanceOf(PowerDecision.Deny.class, l(row()).decision());
        assertInstanceOf(PowerDecision.Deny.class, l(null).decision());
    }

    // 2: old a null amount was within power in both contracts; new a monetary operation without amount is denied
    @Test
    void inventory2_missingAmountIsDeniedForBothContracts() {
        assertFalse(eval(List.of(l(row("powerLimit", new BigDecimal("1000")))), List.of(), null, INR).permitted());
        assertFalse(eval(LegacyPowerContracts.serviceRows("t", 1, List.of(row("maxAmount", 1000)), INR), List.of(),
                null, INR).permitted());
    }

    // 3: old any negative amount was within any ceiling; new compared by magnitude
    @Test
    void inventory3_negativeAmountsAreComparedByMagnitude() {
        assertFalse(eval(List.of(l(row("powerLimit", new BigDecimal("1000")))), List.of(), "-5000", INR).permitted());
        assertFalse(eval(LegacyPowerContracts.serviceRows("t", 1, List.of(row("maxAmount", "1000")), INR), List.of(),
                "-5000", INR).permitted());
    }

    // 4: old noMonetaryLimit won over a ceiling in the same row; new conflicting row denies
    @Test
    void inventory4_ceilingAndNoMonetaryLimitTogetherConflict() {
        PowerDecision d = l(row("powerLimit", new BigDecimal("1000"), "noMonetaryLimit", true)).decision();
        assertInstanceOf(PowerDecision.Deny.class, d);
        assertTrue(((PowerDecision.Deny) d).reason().contains("conflicting"));
        assertInstanceOf(PowerDecision.Unlimited.class, l(row("noMonetaryLimit", true)).decision(), "explicit only");
        assertInstanceOf(PowerDecision.Deny.class, l(row("noMonetaryLimit", "true")).decision(), "a string is malformed");
    }

    // 5: old monetary-ness came from the amount variable and the case type from a variable with default GENERAL;
    //    new from trusted metadata only: a case type the metadata does not know is denied, whatever the variables say
    @Test
    void inventory5_monetaryNessComesFromTrustedMetadataOnly() {
        OperationMetadata none = (t, c, v, a) -> Optional.empty();
        PowerOutcome o = new PowerLimitEvaluator(none, (r, m) -> new PowerRuleSource.ApplicableRules(
                List.of(l(row("noMonetaryLimit", true))), List.of()))
                .evaluate(new PowerRequest("p", "SANCTION", "t", null, "GENERAL", 1, null, null, 1));
        assertFalse(o.permitted());
    }

    // 6: old the highest ceiling and any UNLIMITED row won; new all rows must pass
    @Test
    void inventory6_severalServiceRowsAllMustPass() {
        List<PowerRuleSource.DelegatedLimit> rows = LegacyPowerContracts.serviceRows("t", 2,
                List.of(row("maxAmount", 500, "ruleRef", "R1"), row("maxAmount", "10000", "ruleRef", "R2")), INR);
        assertEquals(List.of("t#R1", "t#R2"), rows.stream().map(PowerRuleSource.DelegatedLimit::ruleId).toList());
        assertFalse(eval(rows, List.of(), "501", INR).permitted(), "old: max(500, 10000) = 10000 permitted");
        assertTrue(eval(rows, List.of(), "500", INR).permitted());
        List<PowerRuleSource.DelegatedLimit> withUnlimited = LegacyPowerContracts.serviceRows("t", 2,
                List.of(row("maxAmount", 500), row("maxAmount", "unlimited")), INR);
        assertFalse(eval(withUnlimited, List.of(), "501", INR).permitted(), "old: any UNLIMITED row won");
        List<PowerRuleSource.DelegatedLimit> withEmptyRow = LegacyPowerContracts.serviceRows("t", 2,
                List.of(row("maxAmount", 500), row("allowed", true)), INR);
        assertFalse(eval(withEmptyRow, List.of(), "1", INR).permitted(), "old: the row without maxAmount was ignored");
    }

    // 7: old NumberFormatException, unaudited; new DENY with reason
    @Test
    void inventory7_malformedMaxAmountDeniesWithAReason() {
        PowerDecision d = LegacyPowerContracts.serviceDecision(row("maxAmount", "ten lakh"), INR);
        assertInstanceOf(PowerDecision.Deny.class, d);
        assertTrue(((PowerDecision.Deny) d).reason().contains("malformed"));
        assertInstanceOf(PowerDecision.Deny.class, LegacyPowerContracts.serviceDecision(row("maxAmount", "-1"), INR));
    }

    // 8: old a non-boolean allowed was ignored; new malformed, DENY
    @Test
    void inventory8_nonBooleanAllowedIsMalformed() {
        assertInstanceOf(PowerDecision.Deny.class,
                LegacyPowerContracts.serviceDecision(row("allowed", "yes", "maxAmount", 100), INR));
        assertInstanceOf(PowerDecision.Deny.class,
                LegacyPowerContracts.serviceDecision(row("allowed", false, "maxAmount", "UNLIMITED"), INR));
    }

    // 9: old no currency anywhere; new ceilings carry the table currency and another currency is denied
    @Test
    void inventory9_currencyIsPartOfTheCeiling() {
        assertEquals(new PowerDecision.Limited(new BigDecimal("100"), INR),
                LegacyPowerContracts.serviceDecision(row("maxAmount", 100), INR));
        assertInstanceOf(PowerDecision.Deny.class, LegacyPowerContracts.serviceDecision(row("maxAmount", 100), null),
                "an unknown table currency never becomes a ceiling in any currency");
        assertFalse(eval(List.of(l(row("powerLimit", new BigDecimal("1000")))), List.of(), "10", "USD").permitted());
    }

    // 10: old an evaluation exception (not recorded as a decision); new DENY with an audit payload
    @Test
    void inventory10_evaluationFailureIsAnAuditedDeny() {
        PowerOutcome o = new PowerLimitEvaluator(MONETARY, (r, m) -> {
            throw new IllegalStateException("DMN evaluation failed");
        }).evaluate(new PowerRequest("p", "SANCTION", "t", null, "GRANT", 1, BigDecimal.ONE, INR, 1));
        assertFalse(o.permitted());
        assertEquals("DENY", o.auditPayload().get("decision"));
        assertTrue(o.reason().contains("DMN evaluation failed"));
    }

    // 11: old posting cap min'd with the ceiling (also under UNLIMITED); new a mandatory cap UNLIMITED never removes
    @Test
    void inventory11_postingCapIsAMandatoryCap() {
        List<PowerRuleSource.MandatoryCap> cap = LegacyPowerContracts.postingCap("seat-1", "holder@3",
                new BigDecimal("2500"), INR).stream().toList();
        assertEquals(1, cap.size());
        List<PowerRuleSource.DelegatedLimit> unlimited = LegacyPowerContracts.serviceRows("t", 1,
                List.of(row("maxAmount", "UNLIMITED")), INR);
        assertTrue(eval(unlimited, cap, "2500", INR).permitted());
        assertFalse(eval(unlimited, cap, "2500.01", INR).permitted());
        assertTrue(LegacyPowerContracts.postingCap("seat-1", "1", null, INR).isEmpty(), "no cap is invented");
    }

    @Test
    void noMatchedServiceRowDenies() {
        assertInstanceOf(PowerDecision.Deny.class,
                LegacyPowerContracts.serviceRows("t", 1, List.of(), INR).get(0).decision());
        assertInstanceOf(PowerDecision.Deny.class,
                LegacyPowerContracts.serviceRows("t", 1, null, INR).get(0).decision());
    }
}

// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.power;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/**
 * Owner decision 142 (RM-08): the one power-limit evaluator. Pure Java; the database-level callers (security service,
 * packs listener) carry the Oracle and SQL Server cases.
 */
class PowerLimitEvaluatorTest {

    static final String INR = "INR";

    /** Trusted metadata: SANCTION on GRANT v3 is monetary, NOTE on GRANT v3 is not, everything else unknown. */
    static final OperationMetadata META = (tenant, caseType, version, action) -> {
        if (!"GRANT".equals(caseType) || version != 3) {
            return Optional.empty();
        }
        return switch (action) {
            case "SANCTION" -> Optional.of(new OperationMetadata.Classification(true, INR, "dop-grant", "GRANT@3"));
            case "NOTE" -> Optional.of(new OperationMetadata.Classification(false, null, "dop-grant", "GRANT@3"));
            default -> Optional.empty();
        };
    };

    static PowerRequest req(String action, String amount, String currency) {
        return new PowerRequest("person-1", action, "tenant-a", "office-7", "GRANT", 3,
                amount == null ? null : new BigDecimal(amount), currency, 11L);
    }

    static PowerRuleSource.DelegatedLimit limited(String id, String max) {
        return new PowerRuleSource.DelegatedLimit(id, "1", new PowerDecision.Limited(new BigDecimal(max), INR));
    }

    static PowerRuleSource.DelegatedLimit unlimited(String id) {
        return new PowerRuleSource.DelegatedLimit(id, "1", new PowerDecision.Unlimited());
    }

    static PowerRuleSource.DelegatedLimit deny(String id) {
        return new PowerRuleSource.DelegatedLimit(id, "1", new PowerDecision.Deny("explicitly no power"));
    }

    static PowerLimitEvaluator evaluator(List<PowerRuleSource.DelegatedLimit> limits,
                                         List<PowerRuleSource.MandatoryCap> caps) {
        return new PowerLimitEvaluator(META, (r, m) -> new PowerRuleSource.ApplicableRules(limits, caps));
    }

    @Test
    void missingAmountOfAMonetaryOperationIsDenied() {
        PowerOutcome o = evaluator(List.of(limited("r", "1000")), List.of()).evaluate(req("SANCTION", null, INR));
        assertFalse(o.permitted());
        assertTrue(o.reason().contains("without an amount"), o.reason());
        assertNull(o.approval(), "a denial carries no approval");
    }

    @Test
    void missingOrInvalidCurrencyIsDenied() {
        PowerLimitEvaluator e = evaluator(List.of(limited("r", "1000")), List.of());
        assertFalse(e.evaluate(req("SANCTION", "10", null)).permitted());
        assertFalse(e.evaluate(req("SANCTION", "10", "inr")).permitted());
    }

    @Test
    void zeroIsAValidAmount() {
        PowerOutcome o = evaluator(List.of(limited("r", "1000")), List.of()).evaluate(req("SANCTION", "0", INR));
        assertTrue(o.permitted(), o.reason());
    }

    @Test
    void aNegativeAmountIsComparedByItsMagnitude() {
        PowerLimitEvaluator e = evaluator(List.of(limited("r", "1000")), List.of());
        assertFalse(e.evaluate(req("SANCTION", "-1000.01", INR)).permitted(), "a reversal never bypasses the limit");
        assertTrue(e.evaluate(req("SANCTION", "-1000", INR)).permitted());
    }

    @Test
    void theCeilingIsInclusiveAndExact() {
        PowerLimitEvaluator e = evaluator(List.of(limited("r", "100000")), List.of());
        assertTrue(e.evaluate(req("SANCTION", "100000.00", INR)).permitted(), "scale-insensitive boundary");
        PowerOutcome over = e.evaluate(req("SANCTION", "100000.01", INR));
        assertFalse(over.permitted());
        assertTrue(over.reason().contains("exceeds the delegated power"), over.reason());
    }

    @Test
    void aCurrencyMismatchIsDenied() {
        PowerOutcome o = evaluator(List.of(limited("r", "1000")), List.of()).evaluate(req("SANCTION", "10", "USD"));
        assertFalse(o.permitted());
        assertTrue(o.reason().contains("USD"), o.reason());
    }

    @Test
    void explicitUnlimitedPermitsAnyAmountWithoutACap() {
        PowerOutcome o = evaluator(List.of(unlimited("full")), List.of()).evaluate(req("SANCTION", "99999999999.99", INR));
        assertTrue(o.permitted(), o.reason());
        assertInstanceOf(PowerDecision.Unlimited.class, o.effective());
    }

    @Test
    void postingCapsStillApplyUnderUnlimited() {
        PowerRuleSource.MandatoryCap cap = new PowerRuleSource.MandatoryCap("posting-cap:seat-1", "4",
                new BigDecimal("5000"), INR);
        PowerLimitEvaluator e = evaluator(List.of(unlimited("full")), List.of(cap));
        assertTrue(e.evaluate(req("SANCTION", "5000", INR)).permitted());
        PowerOutcome over = e.evaluate(req("SANCTION", "5000.01", INR));
        assertFalse(over.permitted());
        assertTrue(over.reason().contains("mandatory cap"), over.reason());
        PowerOutcome otherCurrency = evaluator(List.of(unlimited("full")), List.of(
                new PowerRuleSource.MandatoryCap("cap", "1", new BigDecimal("5000"), "USD")))
                .evaluate(req("SANCTION", "1", INR));
        assertFalse(otherCurrency.permitted(), "a cap in another currency is not silently skipped");
    }

    @Test
    void conflictingRulesNeverPickThePermissiveOne() {
        assertFalse(evaluator(List.of(unlimited("a"), deny("b")), List.of())
                .evaluate(req("SANCTION", "1", INR)).permitted(), "a Deny next to Unlimited denies");
        PowerLimitEvaluator lowAndHigh = evaluator(List.of(limited("high", "10000"), limited("low", "500")), List.of());
        assertFalse(lowAndHigh.evaluate(req("SANCTION", "501", INR)).permitted(), "the lowest ceiling binds");
        PowerOutcome ok = lowAndHigh.evaluate(req("SANCTION", "500", INR));
        assertTrue(ok.permitted());
        assertEquals(new PowerDecision.Limited(new BigDecimal("500"), INR), ok.effective());
        assertFalse(evaluator(List.of(unlimited("a"), limited("b", "500")), List.of())
                .evaluate(req("SANCTION", "501", INR)).permitted(), "Unlimited never lifts another ceiling");
    }

    @Test
    void noRuleOrAnUnknownCaseTypeNeverImpliesPermission() {
        assertFalse(evaluator(List.of(), List.of()).evaluate(req("SANCTION", "1", INR)).permitted());
        assertFalse(new PowerLimitEvaluator(META, (r, m) -> null).evaluate(req("SANCTION", "1", INR)).permitted());
        assertFalse(new PowerLimitEvaluator(META, (r, m) -> new PowerRuleSource.ApplicableRules(null, null))
                .evaluate(req("SANCTION", "1", INR)).permitted());
        PowerOutcome unknown = evaluator(List.of(unlimited("full")), List.of())
                .evaluate(new PowerRequest("p", "SANCTION", "tenant-a", null, "OTHER", 1, BigDecimal.ONE, INR, 1));
        assertFalse(unknown.permitted());
        assertTrue(unknown.reason().contains("no trusted metadata"), unknown.reason());
    }

    @Test
    void forgedMetadataCannotMakeAMonetaryOperationNonMonetary() {
        // the request has no monetary flag at all; a caller that claims a different case-type version or a made-up
        // case type gets no classification and is denied, and the real one is monetary
        PowerLimitEvaluator e = evaluator(List.of(limited("r", "10")), List.of());
        assertFalse(e.evaluate(new PowerRequest("p", "SANCTION", "tenant-a", null, "GRANT", 99, null, null, 1))
                .permitted(), "an unknown version is not a non-monetary operation");
        assertFalse(e.evaluate(req("SANCTION", null, null)).permitted(), "the trusted metadata says monetary");
        assertTrue(e.evaluate(req("NOTE", null, null)).permitted(), "a trusted non-monetary action needs no amount");
    }

    @Test
    void anEvaluationFailureDeniesWithItsReason() {
        PowerOutcome rulesFail = new PowerLimitEvaluator(META, (r, m) -> {
            throw new IllegalStateException("decision engine down");
        }).evaluate(req("SANCTION", "1", INR));
        assertFalse(rulesFail.permitted());
        assertTrue(rulesFail.reason().contains("decision engine down"), rulesFail.reason());
        PowerOutcome metaFails = new PowerLimitEvaluator((t, c, v, a) -> {
            throw new IllegalStateException("registry unreachable");
        }, (r, m) -> new PowerRuleSource.ApplicableRules(List.of(unlimited("x")), List.of())).evaluate(req("SANCTION", "1", INR));
        assertFalse(metaFails.permitted());
        assertTrue(metaFails.reason().contains("registry unreachable"), metaFails.reason());
    }

    @Test
    void aDenyThrowsOnRequirePermittedAndKeepsTheOutcome() {
        PowerOutcome o = evaluator(List.of(deny("r")), List.of()).evaluate(req("SANCTION", "1", INR));
        PowerOutcome.PowerLimitExceededException e = assertThrows(PowerOutcome.PowerLimitExceededException.class,
                o::requirePermitted);
        assertEquals(o, e.outcome());
        assertTrue(e.getMessage().startsWith("POWER_LIMIT_EXCEEDED"), e.getMessage());
    }

    @Test
    void anAmountOrStateChangeAfterEvaluationIsReEvaluatedNeverReused() {
        AtomicReference<List<PowerRuleSource.DelegatedLimit>> rules = new AtomicReference<>(List.of(limited("r", "1000")));
        List<PowerRequest> asked = new ArrayList<>();
        PowerLimitEvaluator e = new PowerLimitEvaluator(META, (r, m) -> {
            asked.add(r);
            return new PowerRuleSource.ApplicableRules(rules.get(), List.of());
        });
        PowerOutcome first = e.evaluate(req("SANCTION", "900", INR));
        assertTrue(first.permitted());
        assertNotNull(first.approval());

        // the amount was raised concurrently: the authoritative re-read at apply time is judged again and denied
        PowerOutcome raised = e.recheck(first.approval(), req("SANCTION", "900", INR).withState(new BigDecimal("1200"), INR, 12));
        assertFalse(raised.permitted());
        assertEquals(List.of("amount", "stateVersion"), raised.changedFields());
        assertEquals(List.of("amount", "stateVersion"), raised.auditPayload().get("changed_since_approval"));

        // unchanged amount and state are still evaluated again (the rules may have changed meanwhile)
        rules.set(List.of(limited("r", "800")));
        PowerOutcome same = e.recheck(first.approval(), req("SANCTION", "900.00", INR));
        assertFalse(same.permitted(), "a lowered ceiling applies at apply time");
        assertTrue(same.changedFields().isEmpty());
        assertEquals(3, asked.size(), "every check reached the rule source");

        assertFalse(e.recheck(null, req("SANCTION", "1", INR)).permitted(), "no approval, no apply");
    }

    @Test
    void theAuditPayloadNamesEveryRequiredField() {
        PowerRuleSource.MandatoryCap cap = new PowerRuleSource.MandatoryCap("posting-cap:seat-1", "4",
                new BigDecimal("5000"), INR);
        PowerOutcome o = evaluator(List.of(limited("table#r1", "1000.50")), List.of(cap)).evaluate(req("SANCTION", "10.25", INR));
        Map<String, Object> p = o.auditPayload();
        assertEquals(PowerDecision.CONTRACT_VERSION, p.get("contract_version"));
        assertEquals("person-1", p.get("actor"));
        assertEquals("SANCTION", p.get("action"));
        assertEquals(Map.of("tenant", "tenant-a", "office", "office-7"), p.get("scope"));
        assertEquals("10.25", p.get("amount"), "decimals as plain strings");
        assertEquals(INR, p.get("currency"));
        assertEquals("GRANT@3", p.get("metadata_version"));
        assertEquals(List.of(Map.of("rule", "table#r1@1", "kind", "LIMITED", "max_amount", "1000.50", "currency", INR)),
                p.get("applicable_limits"));
        assertEquals(List.of(Map.of("cap", "posting-cap:seat-1@4", "max_amount", "5000", "currency", INR)),
                p.get("mandatory_caps"));
        assertEquals("PERMIT", p.get("decision"));
        assertNotNull(p.get("reason"));
        assertEquals(List.of("table#r1@1"), o.approval().ruleVersions());
        assertEquals(List.of("posting-cap:seat-1@4"), o.approval().capVersions());
        assertEquals("DENY", evaluator(List.of(deny("r")), List.of()).evaluate(req("SANCTION", "1", INR))
                .auditPayload().get("decision"));
    }

    @Test
    void theContractRejectsMalformedDecisions() {
        assertThrows(IllegalArgumentException.class, () -> new PowerDecision.Limited(new BigDecimal("-1"), INR));
        assertThrows(IllegalArgumentException.class, () -> new PowerDecision.Limited(BigDecimal.TEN, "rupees"));
        assertThrows(NullPointerException.class, () -> new PowerDecision.Limited(null, INR));
        assertThrows(IllegalArgumentException.class, () -> new PowerRuleSource.DelegatedLimit(" ", "1",
                new PowerDecision.Unlimited()));
        assertThrows(IllegalArgumentException.class, () -> new OperationMetadata.Classification(true, INR, "t", ""));
        assertThrows(IllegalArgumentException.class, () -> new OperationMetadata.Classification(true, null, "t", "v"),
                "a monetary operation declares its currency");
        assertThrows(IllegalArgumentException.class, () -> new OperationMetadata.Classification(true, "inr", "t", "v"));
        assertThrows(IllegalArgumentException.class, () -> new OperationMetadata.Classification(false, INR, "t", "v"),
                "a non-monetary operation declares no currency");
        assertThrows(IllegalArgumentException.class, () -> new OperationMetadata.Classification(true, INR, " ", "v"),
                "the limit table is declared, never left to the request");
    }

    @Test
    void theDeclaredCurrencyBindsAndTheRulesComeFromTheDeclaredTable() {
        List<String> tables = new ArrayList<>();
        PowerLimitEvaluator e = new PowerLimitEvaluator(META, (r, m) -> {
            tables.add(m.limitTableKey());
            return new PowerRuleSource.ApplicableRules(List.of(new PowerRuleSource.DelegatedLimit("usd", "1",
                    new PowerDecision.Limited(new BigDecimal("1000"), "USD"))), List.of());
        });
        PowerOutcome o = e.evaluate(req("SANCTION", "10", "USD"));
        assertFalse(o.permitted(), "a request in another currency than the manifest declares is denied");
        assertTrue(o.reason().contains("declares INR"), o.reason());
        assertTrue(tables.isEmpty(), "denied before any rule is read");
        assertTrue(evaluator(List.of(limited("r", "1000")), List.of()).evaluate(req("SANCTION", "10", INR)).permitted());
        e.evaluate(req("NOTE", null, null));
        assertEquals(List.of("dop-grant"), tables, "the rule source is given the declared table");
        PowerOutcome audited = evaluator(List.of(limited("r", "1000")), List.of()).evaluate(req("SANCTION", "10", INR));
        assertEquals("dop-grant", audited.auditPayload().get("limit_table"));
        assertEquals(INR, audited.auditPayload().get("declared_currency"));
    }

    @Test
    void aRequestWithoutActorOrTenantIsDenied() {
        PowerLimitEvaluator e = evaluator(List.of(unlimited("full")), List.of());
        assertFalse(e.evaluate(new PowerRequest(null, "SANCTION", "tenant-a", null, "GRANT", 3, BigDecimal.ONE, INR, 1))
                .permitted());
        assertFalse(e.evaluate(new PowerRequest("p", "SANCTION", " ", null, "GRANT", 3, BigDecimal.ONE, INR, 1))
                .permitted());
        assertFalse(e.evaluate(null).permitted());
    }
}

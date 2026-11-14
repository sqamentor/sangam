// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.power;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Owner decision 193: the monetary metadata of an operation comes only from the deployed pack manifest; the request
 * cannot change it, and a malformed manifest never classifies permissively.
 */
class ManifestOperationMetadataTest {

    static final Map<String, Object> POWERS = Map.of(
            "SANCTION", Map.of("monetary", true, "currency", "INR", "limitTable", "dop-sanction"),
            "DISPOSE", Map.of("monetary", false, "limitTable", "dop-disposal"));

    static ManifestOperationMetadata metadata() {
        ManifestOperationMetadata.Deployed grant = new ManifestOperationMetadata.Deployed("p3-grant", "4.2.0",
                ManifestOperationMetadata.parse(POWERS));
        return new ManifestOperationMetadata((tenant, caseType, version) ->
                "T".equals(tenant) && "GRANT".equals(caseType) && version == 4 ? Optional.of(grant) : Optional.empty());
    }

    @Test
    void theDeployedManifestClassifiesEachDeclaredAction() {
        OperationMetadata.Classification s = metadata().classify("T", "GRANT", 4, "SANCTION").orElseThrow();
        assertTrue(s.monetary());
        assertEquals("INR", s.currency());
        assertEquals("dop-sanction", s.limitTableKey());
        assertEquals("pack:p3-grant@4.2.0", s.metadataVersion());
        OperationMetadata.Classification d = metadata().classify("T", "GRANT", 4, "DISPOSE").orElseThrow();
        assertFalse(d.monetary());
        assertEquals(null, d.currency());
        assertEquals("dop-disposal", d.limitTableKey());
    }

    @Test
    void anUndeclaredActionAnotherVersionOrTenantHasNoClassification() {
        ManifestOperationMetadata m = metadata();
        assertTrue(m.classify("T", "GRANT", 4, "NOTE").isEmpty());
        assertTrue(m.classify("T", "GRANT", 5, "SANCTION").isEmpty(), "a version that is not deployed");
        assertTrue(m.classify("U", "GRANT", 4, "SANCTION").isEmpty());
        assertTrue(m.classify(null, "GRANT", 4, "SANCTION").isEmpty());
        assertTrue(new ManifestOperationMetadata((t, c, v) -> null).classify("T", "GRANT", 4, "SANCTION").isEmpty());
    }

    @Test
    void theEvaluatorDeniesARequestThatContradictsTheManifest() {
        PowerLimitEvaluator e = new PowerLimitEvaluator(metadata(), (r, m) -> new PowerRuleSource.ApplicableRules(
                List.of(new PowerRuleSource.DelegatedLimit(m.limitTableKey() + "#R1", "1",
                        new PowerDecision.Limited(new BigDecimal("1000"), "INR"))), List.of()));
        assertTrue(e.evaluate(request("SANCTION", "999", "INR")).permitted());
        assertFalse(e.evaluate(request("SANCTION", "999", "USD")).permitted(), "the manifest currency binds");
        assertFalse(e.evaluate(request("SANCTION", null, null)).permitted(), "monetary per manifest needs an amount");
        PowerOutcome o = e.evaluate(request("SANCTION", "10", "INR"));
        assertEquals(List.of("dop-sanction#R1@1"), o.approval().ruleVersions(), "rules of the declared table");
        assertEquals("pack:p3-grant@4.2.0", o.auditPayload().get("metadata_version"));
    }

    @Test
    void aMalformedPowersBlockIsRefused() {
        for (Object bad : List.<Object>of("x",
                Map.of("A", "monetary"),
                Map.of("A", Map.of("monetary", "true", "currency", "INR", "limitTable", "t")),
                Map.of("A", Map.of("monetary", true, "limitTable", "t")),
                Map.of("A", Map.of("monetary", true, "currency", "inr", "limitTable", "t")),
                Map.of("A", Map.of("monetary", false, "currency", "INR", "limitTable", "t")),
                Map.of("A", Map.of("monetary", true, "currency", "INR")),
                Map.of("A", Map.of("monetary", true, "currency", "INR", "limitTable", "t", "unlimited", true)),
                Map.of("A", Map.of("monetary", true, "currency", 356, "limitTable", "t")))) {
            assertThrows(IllegalArgumentException.class, () -> ManifestOperationMetadata.parse(bad), String.valueOf(bad));
        }
        Map<String, Object> blankAction = new HashMap<>();
        blankAction.put(" ", Map.of("monetary", false, "limitTable", "t"));
        assertThrows(IllegalArgumentException.class, () -> ManifestOperationMetadata.parse(blankAction));
        assertEquals(Map.of(), ManifestOperationMetadata.parse(null), "no powers block declares nothing");
        assertThrows(IllegalArgumentException.class,
                () -> new ManifestOperationMetadata.Deployed("p", " ", Map.of()), "the pack version is required");
    }

    // ------------------------------------------------------------------ owner decision 222: ruleCaseType

    static final Map<String, Object> POWERS_222 = Map.of(
            "SANCTION", Map.of("monetary", true, "currency", "INR", "limitTable", "dop-sanction",
                    "ruleCaseType", "GRANT_PROJECT"),
            "DISPOSE", Map.of("monetary", false, "limitTable", "dop-disposal"));

    static ManifestOperationMetadata metadata222() {
        ManifestOperationMetadata.Deployed grant = new ManifestOperationMetadata.Deployed("p3-grant", "4.3.0",
                ManifestOperationMetadata.parse(POWERS_222));
        return new ManifestOperationMetadata((tenant, caseType, version) ->
                "T".equals(tenant) && "GRANT".equals(caseType) && version == 4 ? Optional.of(grant) : Optional.empty());
    }

    /** A delegation table rule source that sees only the inputs built from the classification. */
    static PowerLimitEvaluator tableEvaluator(ManifestOperationMetadata m, Map<String, Object> startVariables,
                                              List<Map<String, Object>> seen) {
        return new PowerLimitEvaluator(m, (r, cls) -> {
            Map<String, Object> in = cls.ruleInputs(startVariables);
            seen.add(in);
            // the table: GRANT_PROJECT rows allow 1000, any other case type (or none) allows 10
            BigDecimal limit = "GRANT_PROJECT".equals(in.get("caseType")) ? new BigDecimal("1000") : BigDecimal.TEN;
            return new PowerRuleSource.ApplicableRules(List.of(new PowerRuleSource.DelegatedLimit(
                    cls.limitTableKey() + "#" + in.get("caseType"), "1", new PowerDecision.Limited(limit, "INR"))),
                    List.of());
        });
    }

    @Test
    void aDeclaredRuleCaseTypeIsTheTablesCaseType() {
        OperationMetadata.Classification s = metadata222().classify("T", "GRANT", 4, "SANCTION").orElseThrow();
        assertEquals("GRANT_PROJECT", s.ruleCaseType());
        List<Map<String, Object>> seen = new java.util.ArrayList<>();
        PowerOutcome o = tableEvaluator(metadata222(), Map.of("seatRole", "DDO"), seen)
                .evaluate(request("SANCTION", "999", "INR"));
        assertTrue(o.permitted(), "the GRANT_PROJECT row applies");
        assertEquals(Map.of("seatRole", "DDO", "caseType", "GRANT_PROJECT"), seen.get(0));
        assertEquals("GRANT_PROJECT", o.auditPayload().get("rule_case_type"));
    }

    @Test
    void anAbsentRuleCaseTypeLeavesTheTableWithoutOne() {
        OperationMetadata.Classification d = metadata222().classify("T", "GRANT", 4, "DISPOSE").orElseThrow();
        assertEquals(null, d.ruleCaseType());
        assertEquals(null, metadata().classify("T", "GRANT", 4, "SANCTION").orElseThrow().ruleCaseType(),
                "a manifest written before decision 222 declares none");
        assertEquals(Map.of("seatRole", "DDO"), d.ruleInputs(Map.of("seatRole", "DDO")), "no caseType input at all");
        assertEquals(Map.of(), d.ruleInputs(null));
        List<Map<String, Object>> seen = new java.util.ArrayList<>();
        PowerOutcome o = tableEvaluator(metadata(), Map.of(), seen).evaluate(request("SANCTION", "999", "INR"));
        assertFalse(o.permitted(), "no declared case type: the table's case-type rows never apply");
        assertFalse(seen.get(0).containsKey("caseType"));
        assertEquals(null, o.auditPayload().get("rule_case_type"));
    }

    @Test
    void aStartVariableCannotChooseTheCaseType() {
        // a start variable (or a process default) claims the generous row
        Map<String, Object> start = new HashMap<>();
        start.put("seatRole", "DDO");
        start.put("caseType", "GRANT_PROJECT");
        List<Map<String, Object>> seen = new java.util.ArrayList<>();
        PowerOutcome withoutDeclaration = tableEvaluator(metadata(), start, seen)
                .evaluate(request("SANCTION", "999", "INR"));
        assertFalse(withoutDeclaration.permitted(), "the variable is ignored when the manifest declares none");
        assertEquals(Map.of("seatRole", "DDO"), seen.get(0));
        // and against a declaration: the declared value wins, the variable never reaches the table
        start.put("caseType", "WORKS_TS");
        seen.clear();
        PowerOutcome declared = tableEvaluator(metadata222(), start, seen).evaluate(request("SANCTION", "999", "INR"));
        assertTrue(declared.permitted());
        assertEquals("GRANT_PROJECT", seen.get(0).get("caseType"));
        assertEquals(List.of("dop-sanction#GRANT_PROJECT@1"), declared.approval().ruleVersions());
        assertEquals("WORKS_TS", start.get("caseType"), "the caller's map is not changed");
        assertThrows(UnsupportedOperationException.class, () -> seen.get(0).put("caseType", "X"),
                "the built inputs cannot be changed afterwards");
    }

    @Test
    void aMalformedRuleCaseTypeIsRefused() {
        for (Object bad : List.<Object>of(" ", 7, Boolean.TRUE)) {
            Map<String, Object> entry = new HashMap<>(Map.of("monetary", false, "limitTable", "t"));
            entry.put("ruleCaseType", bad);
            assertThrows(IllegalArgumentException.class, () -> ManifestOperationMetadata.parse(Map.of("A", entry)),
                    String.valueOf(bad));
        }
        Map<String, Object> nullValue = new HashMap<>(Map.of("monetary", false, "limitTable", "t"));
        nullValue.put("ruleCaseType", null);
        assertThrows(IllegalArgumentException.class, () -> ManifestOperationMetadata.parse(Map.of("A", nullValue)),
                "a present key needs a value");
        assertThrows(IllegalArgumentException.class,
                () -> new OperationMetadata.Classification(false, null, "t", "v", " "));
    }

    static PowerRequest request(String action, String amount, String currency) {
        return new PowerRequest("person-1", action, "T", null, "GRANT", 4,
                amount == null ? null : new BigDecimal(amount), currency, 1);
    }
}

// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.arazzo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Owner decision 159: the supported Arazzo runtime-expression and success-criteria subset. */
class ArazzoExpressionsTest {

    private static final ArazzoExpressions.Scope SCOPE = new ArazzoExpressions.Scope(200,
            Map.of("id", "p-1", "items", List.of(Map.of("n", 3)), "ok", true),
            Map.of("petId", "p-1", "steps.find.outputs.owner", Map.of("name", "Asha")));

    @Test
    void expressionsReadTheResponseAndTheProcessVariables() {
        assertEquals(200, ArazzoExpressions.evaluate("$statusCode", SCOPE));
        assertEquals("p-1", ArazzoExpressions.evaluate("$response.body#/id", SCOPE));
        assertEquals(3, ArazzoExpressions.evaluate("$response.body#/items/0/n", SCOPE));
        assertNull(ArazzoExpressions.evaluate("$response.body#/missing", SCOPE));
        assertEquals("p-1", ArazzoExpressions.evaluate("$inputs.petId", SCOPE));
        assertEquals("Asha", ArazzoExpressions.evaluate("$steps.find.outputs.owner#/name", SCOPE));
    }

    @Test
    void expressionsThisRuntimeCannotEvaluateAreRefused() {
        for (String e : List.of("$response.header.Location", "$request.body", "$url", "$method",
                "$sourceDescriptions.a.url", "$workflows.w.outputs.x", "$components.parameters.p", "$inputs.a b")) {
            assertThrows(ArazzoExpressions.Unsupported.class, () -> ArazzoExpressions.evaluate(e, SCOPE), e);
        }
    }

    @Test
    void resolveReplacesExactAndEmbeddedExpressions() {
        Object v = ArazzoExpressions.resolve(Map.of("id", "$inputs.petId", "label", "pet {$inputs.petId} ok",
                "list", List.of("$statusCode", 7)), SCOPE);
        assertEquals(Map.of("id", "p-1", "label", "pet p-1 ok", "list", List.of(200, 7)), v);
    }

    @Test
    void anEmptyCriteriaListMeansTwoHundredRange() {
        assertTrue(ArazzoExpressions.criteriaHold(List.of(), SCOPE));
        assertFalse(ArazzoExpressions.criteriaHold(List.of(), new ArazzoExpressions.Scope(404, null, null)));
        assertFalse(ArazzoExpressions.criteriaHold(null, new ArazzoExpressions.Scope(null, null, null)));
    }

    @Test
    void simpleConditionsCompareAndCombine() {
        assertTrue(hold("$statusCode == 200"));
        assertFalse(hold("$statusCode != 200"));
        assertTrue(hold("$statusCode >= 200 && $statusCode < 300"));
        assertTrue(hold("$statusCode == 404 || $response.body#/id == 'p-1'"));
        assertTrue(hold("!($statusCode == 500)"));
        assertTrue(hold("$response.body#/ok == true"));
        assertTrue(hold("$response.body#/missing == null"));
        assertTrue(hold("$response.body#/items/0/n > 2.5"));
    }

    @Test
    void unreadableConditionsAreRefusedNotGuessed() {
        for (String c : List.of("$statusCode ==", "$statusCode = 200", "($statusCode == 200", "'open",
                "$statusCode == maybe", "$response.body#/id < 'x'")) {
            assertThrows(ArazzoExpressions.Unsupported.class, () -> hold(c), c);
        }
    }

    @Test
    void regexCriteriaNeedAContextExpression() {
        assertTrue(ArazzoExpressions.criteriaHold(List.of(Map.of("type", "regex", "context", "$response.body#/id",
                "condition", "^p-\\d+$")), SCOPE));
        assertFalse(ArazzoExpressions.criteriaHold(List.of(Map.of("type", "regex", "context", "$statusCode",
                "condition", "^4")), SCOPE));
        assertThrows(ArazzoExpressions.Unsupported.class, () -> ArazzoExpressions.criteriaHold(
                List.of(Map.of("type", "regex", "condition", "x")), SCOPE));
    }

    @Test
    void jsonPathAndXpathCriteriaAreRefused() {
        for (Object type : List.of("jsonpath", "xpath", Map.of("type", "jsonpath", "version", "draft-goessner-dispatch-jsonpath-00"))) {
            assertThrows(ArazzoExpressions.Unsupported.class, () -> ArazzoExpressions.criteriaHold(
                    List.of(Map.of("type", type, "context", "$response.body", "condition", "$.id")), SCOPE));
        }
    }

    private static boolean hold(String condition) {
        return ArazzoExpressions.criteriaHold(List.of(Map.of("condition", condition)), SCOPE);
    }
}

// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.handlers;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** M-059: bounded, root-restricted, strict FEEL for handlers; arguments checked; results made plain. */
class InstanceHandlerEvaluatorTest {

    private final InstanceHandlerEvaluator ev = new InstanceHandlerEvaluator();

    private String refused(String expression) {
        return assertThrows(InstanceHandlerException.class, () -> ev.check("q", expression)).code();
    }

    @Test
    void unboundedConstructsAreRefusedAtDeploy() {
        assertEquals("L6-FEEL-UNBOUNDED", refused("for x in vars.items return x"));
        assertEquals("L6-FEEL-UNBOUNDED", refused("some x in vars.items satisfies x > 1"));
        assertEquals("L6-FEEL-UNBOUNDED", refused("every x in vars.items satisfies x > 1"));
        assertEquals("L6-FEEL-UNBOUNDED", refused("[1..10]"));
        assertEquals("L6-FEEL-UNBOUNDED", refused("function(a) a"));
        assertEquals("L6-FEEL-UNBOUNDED", refused("matches(args.name, \"(a+)+\")"));
        assertEquals("L6-FEEL-UNBOUNDED", refused("replace(vars.a, \"x\", \"y\")"));
        assertEquals("L6-FEEL-UNBOUNDED", refused("split(vars.a, \",\")"));
    }

    @Test
    void keywordsInsideStringsOrPathsAreNotConstructs() {
        assertDoesNotThrow(() -> ev.check("q", "\"for every split\" + vars.formName"));
        assertDoesNotThrow(() -> ev.check("q", "vars.forecast + 1"));
    }

    @Test
    void onlyTheThreeRootsAreReachable() {
        assertDoesNotThrow(() -> ev.check("q", "vars.fee + args.extra"));
        assertDoesNotThrow(() -> ev.check("q", "identity.tenantId"));
        assertEquals("L6-FEEL-ROOT", refused("execution.id"));
        assertEquals("L6-FEEL-ROOT", refused("fee + 1"));
        assertEquals("L6-FEEL-SYNTAX", refused("vars.fee +* 1"));
    }

    @Test
    void argumentsAreCheckedAgainstTheDeclaration() {
        List<InstanceHandlers.Argument> decl = List.of(
                new InstanceHandlers.Argument("n", InstanceHandlers.ArgType.NUMBER, true),
                new InstanceHandlers.Argument("d", InstanceHandlers.ArgType.DATE, false));
        Map<String, Object> ok = InstanceHandlerEvaluator.arguments(decl, Map.of("n", 5L));
        assertEquals(5L, ok.get("n"));
        assertTrue(ok.containsKey("d"), "a declared, absent argument is present as null");

        InstanceHandlerException e = assertThrows(InstanceHandlerException.class,
                () -> InstanceHandlerEvaluator.arguments(decl, Map.of("n", "five", "d", "2026-13-40", "zzz", 1)));
        assertEquals("VALIDATION_FAILED", e.code());
        assertTrue(e.detail().contains("unknown argument 'zzz'"), e.detail());
        assertTrue(e.detail().contains("'n' must be of type number"), e.detail());
        assertTrue(e.detail().contains("'d' must be of type date"), e.detail());
        assertEquals("VALIDATION_FAILED", assertThrows(InstanceHandlerException.class,
                () -> InstanceHandlerEvaluator.arguments(decl, Map.of())).code());
    }

    @Test
    void queryIsExactAndUpdateReadsTheStateBeforeTheCall() {
        InstanceHandlers.Query q = new InstanceHandlers.Query("due", "vars.fee - vars.paid", List.of());
        Object due = ev.query(q, Map.of("fee", new BigDecimal("100.10"), "paid", new BigDecimal("0.20")), Map.of(), Map.of());
        assertEquals(0, new BigDecimal("99.90").compareTo((BigDecimal) due), "exact decimal, never a binary float");

        InstanceHandlers.Update u = new InstanceHandlers.Update("swap", List.of(), List.of(
                new InstanceHandlers.Assignment("a", "vars.b"),
                new InstanceHandlers.Assignment("b", "vars.a")), Set.of());
        Map<String, Object> out = ev.update(u, Map.of("a", "x", "b", "y"), Map.of(), Map.of());
        assertEquals(Map.of("a", "y", "b", "x"), out, "every assignment sees the state before the call");

        assertEquals("t1", ev.query(new InstanceHandlers.Query("who", "identity.tenantId", List.of()),
                Map.of(), Map.of(), Map.of("tenantId", "t1")));
    }

    @Test
    void evaluationFailureIsRaisedNotNull() {
        InstanceHandlers.Query q = new InstanceHandlers.Query("bad", "vars.fee - \"x\"", List.of());
        assertEquals("HANDLER_EVALUATION_FAILED", assertThrows(InstanceHandlerException.class,
                () -> ev.query(q, Map.of("fee", 1L), Map.of(), Map.of())).code());
    }

    @Test
    void resultsArePlainAndBounded() {
        assertEquals(7L, InstanceHandlerEvaluator.result("r", 7));
        assertEquals(new BigDecimal("1.5"), InstanceHandlerEvaluator.result("r", 1.5d));
        assertEquals("2026-11-12", InstanceHandlerEvaluator.result("r", LocalDate.of(2026, 11, 12)));
        assertEquals(Map.of("1", List.of(2L)), InstanceHandlerEvaluator.result("r", Map.of(1, List.of(2))));

        List<Object> big = new ArrayList<>();
        for (int i = 0; i <= InstanceHandlerEvaluator.MAX_RESULT_NODES; i++) {
            big.add(i);
        }
        assertEquals("RESULT_TOO_LARGE", assertThrows(InstanceHandlerException.class,
                () -> InstanceHandlerEvaluator.result("r", big)).code());
        Object deep = "x";
        for (int i = 0; i <= InstanceHandlerEvaluator.MAX_RESULT_DEPTH + 1; i++) {
            deep = List.of(deep);
        }
        Object tooDeep = deep;
        assertEquals("RESULT_TOO_LARGE", assertThrows(InstanceHandlerException.class,
                () -> InstanceHandlerEvaluator.result("r", tooDeep)).code());
    }
}

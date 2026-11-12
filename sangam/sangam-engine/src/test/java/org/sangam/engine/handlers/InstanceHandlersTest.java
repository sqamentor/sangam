// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** M-059: the declaration rules of {@code sangam:query} / {@code sangam:update}, checked at deploy. */
class InstanceHandlersTest {

    private static final InstanceHandlers.ExpressionCheck ANY_FEEL = (where, expr) -> { };
    private static final java.util.function.Predicate<String> ACTIVITIES = Set.of("scrutiny", "verify")::contains;

    record N(String uri, String localName, Map<String, String> attrs, List<InstanceHandlers.Node> children)
            implements InstanceHandlers.Node {
        @Override
        public String attr(String name) {
            return attrs.get(name);
        }
    }

    static N s(String local, Object... kv) {
        Map<String, String> a = new LinkedHashMap<>();
        List<InstanceHandlers.Node> c = new ArrayList<>();
        for (int i = 0; i < kv.length; i++) {
            if (kv[i] instanceof N child) {
                c.add(child);
            } else {
                a.put((String) kv[i], (String) kv[++i]);
            }
        }
        return new N(InstanceHandlers.NS, local, a, c);
    }

    static N ext(InstanceHandlers.Node... children) {
        return new N("http://www.omg.org/spec/BPMN/20100524/MODEL", "extensionElements", Map.of(), List.of(children));
    }

    static String code(Runnable r) {
        return assertThrows(InstanceHandlerException.class, r::run).code();
    }

    @Test
    void parsesQueriesAndUpdatesWithArgumentsSetsAndActivities() {
        InstanceHandlers.Definitions d = InstanceHandlers.parse("p", ext(
                s("query", "name", "feeDue", "expression", "vars.fee - vars.paid",
                        s("argument", "name", "asOf", "type", "date")),
                s("update", "name", "correctAddress", "activities", "scrutiny, verify",
                        s("argument", "name", "address", "type", "string", "required", "true"),
                        s("set", "variable", "address", "expression", "args.address")),
                new N("urn:other", "query", Map.of(), List.of())), ACTIVITIES, ANY_FEEL);

        InstanceHandlers.Query q = d.queries().get("feeDue");
        assertEquals("vars.fee - vars.paid", q.expression());
        assertEquals(List.of(new InstanceHandlers.Argument("asOf", InstanceHandlers.ArgType.DATE, false)), q.arguments());
        InstanceHandlers.Update u = d.updates().get("correctAddress");
        assertEquals(Set.of("scrutiny", "verify"), u.activities());
        assertEquals(List.of(new InstanceHandlers.Assignment("address", "args.address")), u.assignments());
        assertTrue(u.arguments().get(0).required());
        assertEquals(1, d.queries().size(), "elements of another namespace are not handlers");
    }

    @Test
    void noDeclarationMeansNone() {
        assertSame(InstanceHandlers.Definitions.NONE, InstanceHandlers.parse("p", null, ACTIVITIES, ANY_FEEL));
        assertSame(InstanceHandlers.Definitions.NONE, InstanceHandlers.parse("p", ext(), ACTIVITIES, ANY_FEEL));
    }

    @Test
    void everyBrokenRuleHasItsCode() {
        assertEquals("L6-NAME", code(() -> InstanceHandlers.parse("p", ext(s("query", "expression", "1")), ACTIVITIES, ANY_FEEL)));
        assertEquals("L6-NAME", code(() -> InstanceHandlers.parse("p", ext(s("query", "name", "9x", "expression", "1")), ACTIVITIES, ANY_FEEL)));
        assertEquals("L6-DUPLICATE", code(() -> InstanceHandlers.parse("p", ext(
                s("query", "name", "a", "expression", "1"), s("query", "name", "a", "expression", "2")), ACTIVITIES, ANY_FEEL)));
        assertEquals("L6-EXPRESSION", code(() -> InstanceHandlers.parse("p", ext(s("query", "name", "a")), ACTIVITIES, ANY_FEEL)));
        assertEquals("L6-STRUCTURE", code(() -> InstanceHandlers.parse("p", ext(s("set", "variable", "x", "expression", "1")), ACTIVITIES, ANY_FEEL)));
        assertEquals("L6-STRUCTURE", code(() -> InstanceHandlers.parse("p", ext(s("update", "name", "u", "expression", "1",
                s("set", "variable", "x", "expression", "1"))), ACTIVITIES, ANY_FEEL)));
        assertEquals("L6-SET", code(() -> InstanceHandlers.parse("p", ext(s("update", "name", "u")), ACTIVITIES, ANY_FEEL)));
        assertEquals("L6-SET", code(() -> InstanceHandlers.parse("p", ext(s("update", "name", "u",
                s("set", "variable", "x", "expression", "1"), s("set", "variable", "x", "expression", "2"))), ACTIVITIES, ANY_FEEL)));
        assertEquals("L6-SET-RESERVED", code(() -> InstanceHandlers.parse("p", ext(s("update", "name", "u",
                s("set", "variable", "sangamCaseVersion", "expression", "1"))), ACTIVITIES, ANY_FEEL)));
        assertEquals("L6-ACTIVITY", code(() -> InstanceHandlers.parse("p", ext(s("update", "name", "u", "activities", "nowhere",
                s("set", "variable", "x", "expression", "1"))), ACTIVITIES, ANY_FEEL)));
        assertEquals("L6-ARGUMENT", code(() -> InstanceHandlers.parse("p", ext(s("query", "name", "q", "expression", "1",
                s("argument", "name", "a", "type", "float"))), ACTIVITIES, ANY_FEEL)));
        assertEquals("L6-ARGUMENT", code(() -> InstanceHandlers.parse("p", ext(s("query", "name", "q", "expression", "1",
                s("argument", "name", "a"), s("argument", "name", "a"))), ACTIVITIES, ANY_FEEL)));
        assertEquals("L6-ARGUMENT", code(() -> InstanceHandlers.parse("p", ext(s("query", "name", "q", "expression", "1",
                s("argument", "name", "a", "required", "yes"))), ACTIVITIES, ANY_FEEL)));
        assertEquals("L6-LIMIT", code(() -> InstanceHandlers.parse("p", ext(s("query", "name", "q",
                "expression", "1".repeat(InstanceHandlers.MAX_EXPRESSION_CHARS + 1))), ACTIVITIES, ANY_FEEL)));
    }

    @Test
    void handlerCountIsBounded() {
        InstanceHandlers.Node[] many = new InstanceHandlers.Node[InstanceHandlers.MAX_HANDLERS + 1];
        for (int i = 0; i < many.length; i++) {
            many[i] = s("query", "name", "q" + i, "expression", "1");
        }
        assertEquals("L6-LIMIT", code(() -> InstanceHandlers.parse("p", ext(many), ACTIVITIES, ANY_FEEL)));
    }

    @Test
    void theExpressionCheckFailureNamesTheProcess() {
        InstanceHandlerException e = assertThrows(InstanceHandlerException.class, () -> InstanceHandlers.parse("licence",
                ext(s("query", "name", "q", "expression", "x")), ACTIVITIES,
                (where, expr) -> {
                    throw new InstanceHandlerException("L6-FEEL-ROOT", where + ": no");
                }));
        assertEquals("L6-FEEL-ROOT", e.code());
        assertTrue(e.detail().startsWith("process 'licence': query 'q'"), e.detail());
    }
}

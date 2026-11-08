// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.feel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/** ADR-D-26 deploy-time DMN rules (SG-33 money doubles, SG-35 zoned date-time equality), and the shipped corpus. */
class DmnModelLintTest {

    private static List<DmnModelLint.Finding> lint(String body) {
        String xml = "<definitions xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\" id=\"d\" name=\"d\" "
                + "namespace=\"t\">" + body + "</definitions>";
        return DmnModelLint.check("t.dmn", xml.getBytes(StandardCharsets.UTF_8));
    }

    private static String table(String inputTypeRef, String inputExpr, String outName, String outType, String entry) {
        return "<decision id=\"x\" name=\"x\"><decisionTable id=\"xt\"><input id=\"i\" label=\"in\"><inputExpression "
                + "id=\"ie\" typeRef=\"" + inputTypeRef + "\"><text>" + inputExpr + "</text></inputExpression></input>"
                + "<output id=\"o\" name=\"" + outName + "\" typeRef=\"" + outType + "\"/><rule id=\"r\"><inputEntry "
                + "id=\"re\"><text>" + entry + "</text></inputEntry><outputEntry id=\"ro\"><text>1</text></outputEntry>"
                + "</rule></decisionTable></decision>";
    }

    @Test
    void moneyDoubleIsAnError() {
        for (String expr : new String[] {"amount", "claimAmount", "maxAmount", "powerLimit", "fee", "budget_total",
                "unitCost", "ldAmount"}) {
            List<DmnModelLint.Finding> f = lint(table("double", expr, "ok", "string", "&lt; 5"));
            assertEquals(1, f.size(), expr);
            assertTrue(f.get(0).error() && f.get(0).code().equals("SANGAM-DMN-MONEY-DOUBLE"), expr + " " + f);
        }
        List<DmnModelLint.Finding> out = lint(table("long", "x", "feeAmount", "float", "&lt; 5"));
        assertTrue(out.get(0).error(), "money output typeRef=float: " + out);
    }

    @Test
    void otherDoublesWarnAndExactTypesPass() {
        List<DmnModelLint.Finding> f = lint(table("double", "temperature", "ok", "string", "&lt; 5"));
        assertEquals(1, f.size());
        assertFalse(f.get(0).error());
        assertTrue(lint(table("number", "amount", "fee", "number", "&lt; 5")).isEmpty());
        assertTrue(lint(table("long", "amountPaise", "fee", "long", "&lt; 5")).isEmpty());
    }

    @Test
    void zonedDateTimeEqualityWarns() {
        List<DmnModelLint.Finding> f = lint(table("string", "t", "ok", "string",
                "date and time(\"2026-11-08T10:00:00@Asia/Kolkata\")"));
        assertEquals(1, f.size(), f.toString());
        assertEquals("SANGAM-DMN-DATETIME-EQ", f.get(0).code());
        assertFalse(f.get(0).error());
        assertEquals(1, lint("<decision id=\"l\" name=\"l\"><literalExpression><text>a = date and time("
                + "\"2026-11-08T04:30:00Z\")</text></literalExpression></decision>").size());
        assertTrue(lint(table("string", "t", "ok", "string", "&lt; date and time(\"2026-11-08T04:30:00Z\")"))
                .isEmpty(), "ordering compares instants and is fine");
    }

    /** Every shipped pack/corpus DMN passes the deploy rule (176 files migrated from double to number). */
    @Test
    void shippedModelsHaveNoMoneyDoubles() throws IOException {
        Path packs = Path.of("..", "sangam-packs", "src").toAbsolutePath().normalize();
        List<String> errors = new ArrayList<>();
        int n = 0;
        try (Stream<Path> s = Files.walk(packs)) {
            for (Path p : (Iterable<Path>) s.filter(x -> x.toString().endsWith(".dmn"))::iterator) {
                n++;
                String xml = Files.readString(p);
                assertFalse(xml.matches("(?s).*typeRef=\"(double|float)\".*"), p + " still uses double/float");
                for (DmnModelLint.Finding f : DmnModelLint.check(p.toString(), xml.getBytes(StandardCharsets.UTF_8))) {
                    if (f.error()) {
                        errors.add(f.toString());
                    }
                }
            }
        }
        assertTrue(n > 100, "scanned " + n + " DMN files");
        assertTrue(errors.isEmpty(), errors.toString());
    }
}

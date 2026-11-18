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

    /** Owner ruling G1 (DAT-1, 2026-11-18): date-time = and != are refused at deploy, never only warned. */
    @Test
    void zonedDateTimeEqualityIsRefused() {
        List<DmnModelLint.Finding> f = lint(table("string", "t", "ok", "string",
                "date and time(\"2026-11-08T10:00:00@Asia/Kolkata\")"));
        assertEquals(1, f.size(), f.toString());
        assertEquals("SANGAM-DMN-DATETIME-EQ", f.get(0).code());
        assertTrue(f.get(0).error(), "G1: a refusal, not a warning");
        assertTrue(f.get(0).message().contains("refused"), f.get(0).message());
        List<DmnModelLint.Finding> ne = lint("<decision id=\"n\" name=\"n\"><literalExpression><text>a != @\"2026-11-08T04:30:00Z\""
                + "</text></literalExpression></decision>");
        assertTrue(ne.size() == 1 && ne.get(0).error(), ne.toString());
        List<DmnModelLint.Finding> lit = lint("<decision id=\"l\" name=\"l\"><literalExpression><text>a = date and time("
                + "\"2026-11-08T04:30:00Z\")</text></literalExpression></decision>");
        assertTrue(lit.size() == 1 && lit.get(0).error(), lit.toString());
        assertTrue(lint(table("string", "t", "ok", "string", "&lt; date and time(\"2026-11-08T04:30:00Z\")"))
                .isEmpty(), "ordering compares instants and is fine");
    }

    /** Decision 206 (DMN 1.6): moved unchanged from Dmn16SupportTest when the FEEL policies moved to sangam-feel. */
    @Test
    void timeLiteralWithIanaZoneIsWarned() {
        String xml = """
                <definitions xmlns="%s" id="w" name="w" namespace="https://example.org/w">
                  <decision id="a" name="a"><literalExpression id="la"><text>time("10:30:00@Europe/Paris")</text></literalExpression></decision>
                  <decision id="b" name="b"><literalExpression id="lb"><text>@"10:30:00@Asia/Kolkata"</text></literalExpression></decision>
                  <decision id="c" name="c"><literalExpression id="lc"><text>date and time("2026-11-08T10:00:00@Asia/Kolkata")</text></literalExpression></decision>
                  <decision id="d" name="d"><literalExpression id="ld"><text>time("10:30:00+05:30")</text></literalExpression></decision>
                </definitions>
                """.formatted(org.sangam.feel.Dmn16Support.DMN16_NS);
        List<DmnModelLint.Finding> f = DmnModelLint.check("w.dmn", xml.getBytes(StandardCharsets.UTF_8)).stream()
                .filter(x -> "SANGAM-DMN-TIME-IANA".equals(x.code())).toList();
        assertEquals(List.of("la", "lb"), f.stream().map(DmnModelLint.Finding::element).toList(),
                "time literals with an IANA id only; date-time literals and offsets are not deprecated");
        assertTrue(f.stream().noneMatch(DmnModelLint.Finding::error), "a warning, never a refusal");
    }

    /** Owner DEC-5 (2026-11-18): DMN never rounds money; rounding belongs to the financial core (A2). */
    @Test
    void decimalOnAMoneyOutputIsAnError() {
        String rule = "<decision id=\"x\" name=\"x\"><decisionTable id=\"xt\"><input id=\"i\"><inputExpression id=\"ie\" "
                + "typeRef=\"number\"><text>basic</text></inputExpression></input><output id=\"o\" name=\"%s\" "
                + "typeRef=\"number\"/><rule id=\"r\"><inputEntry id=\"re\"><text>-</text></inputEntry><outputEntry "
                + "id=\"ro\"><text>%s</text></outputEntry></rule></decisionTable></decision>";
        List<DmnModelLint.Finding> f = lint(rule.formatted("feeAmount", "decimal(basic * 0.1, 2)"));
        assertEquals(1, f.size(), f.toString());
        assertTrue(f.get(0).error() && "SANGAM-DMN-MONEY-ROUNDING".equals(f.get(0).code()), f.toString());
        assertTrue(lint(rule.formatted("feeAmount", "basic * 0.1")).isEmpty(), "unrounded money is fine");
        assertTrue(lint(rule.formatted("ratio", "decimal(basic / 3, 4)")).isEmpty(), "non-money rounding is fine");
        List<DmnModelLint.Finding> lit = lint("<decision id=\"l\" name=\"l\"><variable name=\"pensionAmount\" "
                + "typeRef=\"number\"/><literalExpression id=\"le\"><text>decimal(basic / 2, 0)</text>"
                + "</literalExpression></decision>");
        assertTrue(lit.stream().anyMatch(x -> x.error() && "SANGAM-DMN-MONEY-ROUNDING".equals(x.code())), lit.toString());
    }

    /** Owner D2 (2026-11-18): BKM, boxed context, relation, invocation, function and decision services are not supported. */
    @Test
    void unsupportedDmnElementsAreRefused() {
        String[] bodies = {
            "<businessKnowledgeModel id=\"b\" name=\"b\"/>",
            "<decisionService id=\"s\" name=\"s\"/>",
            "<decision id=\"c\" name=\"c\"><context id=\"cx\"/></decision>",
            "<decision id=\"r\" name=\"r\"><relation id=\"rl\"/></decision>",
            "<decision id=\"v\" name=\"v\"><invocation id=\"iv\"/></decision>",
            "<decision id=\"f\" name=\"f\"><functionDefinition id=\"fd\"/></decision>",
        };
        for (String b : bodies) {
            List<DmnModelLint.Finding> f = lint(b);
            assertTrue(f.stream().anyMatch(x -> x.error() && "SANGAM-DMN-UNSUPPORTED".equals(x.code())), b + " " + f);
        }
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

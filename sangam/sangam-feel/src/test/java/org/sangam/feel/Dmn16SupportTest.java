// SPDX-License-Identifier: Apache-2.0
package org.sangam.feel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.operaton.bpm.dmn.engine.DmnDecision;
import org.operaton.bpm.dmn.engine.DmnEngine;
import org.operaton.bpm.dmn.engine.impl.DefaultDmnEngineConfiguration;
import org.operaton.bpm.dmn.engine.impl.spi.transform.DmnTransformer;
import org.operaton.bpm.model.dmn.DmnModelInstance;

/**
 * Decision 158 (M-126): DMN 1.6 documents (namespace 20240513) are accepted by every Sangam DMN engine, validated
 * against the DMN 1.5 schema form (evidence: the DMN 1.6 XSD differs from DMN 1.5 only in namespace, FEEL default and
 * the ONNX function kind), evaluated as FEEL, and ONNX or B-FEEL content is refused with a code. Written, NOT_RUN.
 */
class Dmn16SupportTest {

    private static final String NS16 = Dmn16Support.DMN16_NS;

    private static String table(String rootAttrs, String prefix) {
        String p = prefix.isEmpty() ? "" : prefix + ":";
        String xmlns = prefix.isEmpty() ? "xmlns=\"" + NS16 + "\"" : "xmlns:" + prefix + "=\"" + NS16 + "\"";
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <%1$sdefinitions %2$s id="d16" name="d16" namespace="https://example.org/d16" %3$s>
                  <%1$sdecision id="band" name="band">
                    <%1$sdecisionTable id="t" hitPolicy="FIRST">
                      <%1$sinput id="i"><%1$sinputExpression id="ie" typeRef="number"><%1$stext>amount</%1$stext></%1$sinputExpression></%1$sinput>
                      <%1$soutput id="o" name="fee" typeRef="number"/>
                      <%1$srule id="r1"><%1$sinputEntry id="r1i"><%1$stext>&lt; 1000</%1$stext></%1$sinputEntry><%1$soutputEntry id="r1o"><%1$stext>amount * 0.015</%1$stext></%1$soutputEntry></%1$srule>
                      <%1$srule id="r2"><%1$sinputEntry id="r2i"><%1$stext>&gt;= 1000</%1$stext></%1$sinputEntry><%1$soutputEntry id="r2o"><%1$stext>15.5</%1$stext></%1$soutputEntry></%1$srule>
                    </%1$sdecisionTable>
                  </%1$sdecision>
                  <%1$sdecision id="spec3arg" name="spec3arg">
                    <%1$svariable name="r" typeRef="boolean"/>
                    <%1$sliteralExpression id="le"><%1$stext>date and time(date("2024-12-24"), time("23:59:00"), "Z") = date and time("2024-12-24T23:59:00Z")</%1$stext></%1$sliteralExpression>
                  </%1$sdecision>
                </%1$sdefinitions>
                """.formatted(p, xmlns, rootAttrs);
    }

    private static byte[] bytes(String s) {
        return s.strip().getBytes(StandardCharsets.UTF_8);
    }

    private static DmnEngine engine() {
        return SangamFeel.dmnEngineConfiguration().buildEngine();
    }

    private static Object evaluate(byte[] xml, String key, Map<String, Object> vars) {
        DmnEngine e = engine();
        List<DmnDecision> ds = e.parseDecisions(new ByteArrayInputStream(xml));
        DmnDecision d = ds.stream().filter(x -> key.equals(x.getKey())).findFirst().orElseThrow();
        return e.evaluateDecision(d, vars).getSingleResult().getSingleEntry();
    }

    @Test
    void earlierVersionsPassThroughByteForByte() {
        byte[] dmn15 = bytes(table("", "").replace(NS16, Dmn16Support.DMN15_NS));
        assertSame(dmn15, Dmn16Support.toParserInput(dmn15), "a DMN 1.5 document is not touched");
        assertFalse(Dmn16Support.isDmn16(dmn15));
    }

    @Test
    void dmn16DecisionTableEvaluatesExactly() {
        byte[] xml = bytes(table("", ""));
        assertTrue(Dmn16Support.isDmn16(xml));
        assertEquals(0, new BigDecimal("15.5").compareTo(new BigDecimal(
                evaluate(xml, "band", Map.of("amount", new BigDecimal("1000.00"))).toString())),
                "rule 2 gives 15.5 (exact)");
        assertEquals(0, new BigDecimal("1.5").compareTo(new BigDecimal(
                evaluate(xml, "band", Map.of("amount", 100L)).toString())), "100 * 0.015 = 1.5 exactly");
    }

    @Test
    void explicitFeel16ExpressionLanguageIsFeel() {
        byte[] xml = bytes(table("expressionLanguage=\"" + Dmn16Support.FEEL16_NS + "\" typeLanguage=\""
                + Dmn16Support.FEEL16_NS + "\"", ""));
        assertEquals(0, new BigDecimal("1.5").compareTo(new BigDecimal(
                evaluate(xml, "band", Map.of("amount", 100L)).toString())));
        String converted = new String(Dmn16Support.toParserInput(xml), StandardCharsets.UTF_8);
        assertTrue(converted.contains(Dmn16Support.FEEL15_NS), converted);
        assertFalse(converted.contains("20240513"), "no DMN 1.6 namespace left in the parser copy: " + converted);
    }

    @Test
    void prefixedNamespaceIsConverted() {
        byte[] xml = bytes(table("", "dmn"));
        assertEquals(0, new BigDecimal("1.5").compareTo(new BigDecimal(
                evaluate(xml, "band", Map.of("amount", 100L)).toString())));
    }

    @Test
    void threeArgumentDateAndTimeFromTheSpecificationExample() {
        assertEquals(Boolean.TRUE, evaluate(bytes(table("", "")), "spec3arg", Map.of()),
                "DMN 1.6 clause 10.3.4.1 example: date and time(date, time, \"Z\")");
    }

    @Test
    void onnxFunctionIsRefusedWithACode() {
        String xml = """
                <definitions xmlns="%s" id="o" name="o" namespace="https://example.org/o">
                  <decision id="d" name="d">
                    <functionDefinition id="f" kind="ONNX"><context id="c"/></functionDefinition>
                  </decision>
                </definitions>
                """.formatted(NS16);
        Dmn16Support.Dmn16Exception e = assertThrows(Dmn16Support.Dmn16Exception.class,
                () -> Dmn16Support.toParserInput(bytes(xml)));
        assertEquals(Dmn16Support.CODE_ONNX, e.code());
    }

    @Test
    void bFeelIsRefusedWithACode() {
        Dmn16Support.Dmn16Exception e = assertThrows(Dmn16Support.Dmn16Exception.class, () -> Dmn16Support
                .toParserInput(bytes(table("expressionLanguage=\"" + Dmn16Support.BFEEL16_NS + "\"", ""))));
        assertEquals(Dmn16Support.CODE_BFEEL, e.code());
        assertThrows(RuntimeException.class, () -> engine().parseDecisions(new ByteArrayInputStream(
                bytes(table("expressionLanguage=\"" + Dmn16Support.BFEEL16_NS + "\"", "")))));
    }

    @Test
    void schemaValidationStillRunsForDmn16() {
        String invalid = """
                <definitions xmlns="%s" id="x" name="x" namespace="https://example.org/x">
                  <decision id="d" name="d"><notADmnElement/></decision>
                </definitions>
                """.formatted(NS16);
        assertThrows(RuntimeException.class, () -> Dmn16Support.readModel(bytes(invalid)),
                "an element the DMN 1.6 schema does not define is refused (validation is not skipped)");
        String missingNamespace = """
                <definitions xmlns="%s" id="x" name="x"><decision id="d" name="d"/></definitions>
                """.formatted(NS16);
        assertThrows(RuntimeException.class, () -> Dmn16Support.readModel(bytes(missingNamespace)),
                "definitions/@namespace is required by the DMN 1.6 schema");
    }

    @Test
    void readModelGivesAnOperatonModelInstance() {
        DmnModelInstance m = Dmn16Support.readModel(bytes(table("", "")));
        assertEquals("d16", m.getDefinitions().getName());
    }

    @Test
    void installIsIdempotentAndKeepsRegistries() {
        DefaultDmnEngineConfiguration cfg = (DefaultDmnEngineConfiguration) DefaultDmnEngineConfiguration
                .createDefaultDmnEngineConfiguration();
        DmnTransformer before = cfg.getTransformer();
        Object handlers = before.getElementTransformHandlerRegistry();
        Object hitPolicies = before.getHitPolicyHandlerRegistry();
        Object types = before.getDataTypeTransformerRegistry();
        Object listeners = before.getTransformListeners();
        Dmn16Support.install(cfg);
        DmnTransformer after = cfg.getTransformer();
        assertTrue(after instanceof Dmn16Support.Transformer16);
        assertSame(handlers, after.getElementTransformHandlerRegistry());
        assertSame(hitPolicies, after.getHitPolicyHandlerRegistry());
        assertSame(types, after.getDataTypeTransformerRegistry());
        assertSame(listeners, after.getTransformListeners());
        Dmn16Support.install(cfg);
        assertSame(after, cfg.getTransformer(), "second install changes nothing");
    }

    // timeLiteralWithIanaZoneIsWarned moved unchanged to sangam-engine DmnModelLintTest: DmnModelLint stays in
    // org.sangam.engine.feel (deploy-time lint, not a FEEL policy), and sangam-feel must not depend on sangam-engine.
}

// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.pipeline;

import org.operaton.bpm.dmn.engine.DmnDecision;
import org.operaton.bpm.dmn.engine.DmnEngine;
import org.operaton.bpm.dmn.engine.DmnEngineConfiguration;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Standalone FEEL (no GraalJS, D-8): each expression is compiled once as a DMN literal-expression decision on Operaton's
 * DMN engine and cached.
 */
public final class FeelEvaluator {

    private final DmnEngine engine = DmnEngineConfiguration.createDefaultDmnEngineConfiguration().buildEngine();
    private final Map<String, DmnDecision> cache = new ConcurrentHashMap<>();

    public Object evaluate(String expression, Map<String, Object> variables) {
        DmnDecision d = cache.computeIfAbsent(expression, this::compile);
        return engine.evaluateDecision(d, variables).getSingleEntry();
    }

    private DmnDecision compile(String expression) {
        String xml = "<definitions xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\" id=\"d\" name=\"d\" namespace=\"sangam\">"
                + "<decision id=\"e\" name=\"e\"><variable name=\"r\"/>"
                + "<literalExpression expressionLanguage=\"feel\"><text>" + expression.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                + "</text></literalExpression></decision></definitions>";
        return engine.parseDecisions(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))).get(0);
    }
}

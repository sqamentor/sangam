// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.delegation;

import org.operaton.bpm.dmn.engine.DmnDecision;
import org.operaton.bpm.dmn.engine.DmnDecisionResult;
import org.operaton.bpm.dmn.engine.DmnEngine;
import org.sangam.core.security.SangamSecurityException;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Evaluates a delegation-of-powers DMN table with Operaton's standalone DMN engine (DMN 1.3+ / FEEL, ADR-D-16).
 *
 * <h2>Table contract</h2>
 * Inputs available to the table: {@code seatId}, {@code designation}, {@code unitId}, {@code levelType},
 * {@code rankNo}, {@code caseType}, {@code amount} plus caller extras. Outputs:
 * <ul>
 *   <li>{@code maxAmount}: the power limit of the seat for the case type — a number, a decimal string, or
 *       {@code "UNLIMITED"}; absent means no power;</li>
 *   <li>{@code allowed} (optional boolean): an explicit "no power" row ({@code false}) wins;</li>
 *   <li>{@code ruleRef} (optional): the order/rule reference shown in audit.</li>
 * </ul>
 * The amount comparison is done in Java with {@link BigDecimal} so decimal boundaries are exact (T18:
 * ₹4,99,999.99 / ₹5,00,000.00 / ₹10,00,000.01). Hit policy UNIQUE is recommended: overlapping rows then fail
 * evaluation, and the draft test cases catch them before review (AJ-03 step 1).
 *
 * <p>Parsed decisions are cached by the table's content hash; a published version is immutable.
 */
public final class DmnPowerEvaluator {

    public static final String UNLIMITED = "UNLIMITED";

    /** Result of one evaluation. {@code limit} null with {@code unlimited} false means no power. */
    public record Evaluation(boolean allowedFlag, BigDecimal limit, boolean unlimited, String ruleRef, int matchedRows) {
    }

    private final DmnEngine engine;
    private final Map<String, DmnDecision> cache = new ConcurrentHashMap<>();

    public DmnPowerEvaluator() {
        this(org.sangam.engine.feel.SangamFeel.dmnEngineConfiguration().buildEngine());
    }

    public DmnPowerEvaluator(DmnEngine engine) {
        this.engine = engine;
    }

    /** Parses and caches a decision; throws {@code PolicyRejected} for an invalid table. */
    public DmnDecision parse(String dmnHash, String decisionId, String dmnXml) {
        return cache.computeIfAbsent(dmnHash + "|" + decisionId, k -> {
            try {
                return engine.parseDecision(decisionId, new ByteArrayInputStream(dmnXml.getBytes(StandardCharsets.UTF_8)));
            } catch (RuntimeException e) {
                throw new SangamSecurityException.PolicyRejected("invalid delegation-of-powers table: " + e.getMessage());
            }
        });
    }

    public Evaluation evaluate(String dmnHash, String decisionId, String dmnXml, Map<String, Object> inputs) {
        DmnDecision d = parse(dmnHash, decisionId, dmnXml);
        DmnDecisionResult result;
        try {
            result = engine.evaluateDecision(d, inputs);
        } catch (RuntimeException e) {
            throw new SangamSecurityException.PolicyRejected("delegation-of-powers evaluation failed: " + e.getMessage());
        }
        List<Map<String, Object>> rows = result.getResultList();
        boolean allowed = true;
        BigDecimal limit = null;
        boolean unlimited = false;
        String ruleRef = null;
        for (Map<String, Object> row : rows) {
            Object a = row.get("allowed");
            if (a instanceof Boolean b && !b) {
                allowed = false;
            }
            Object m = row.get("maxAmount");
            if (m != null) {
                String s = m.toString().trim();
                if (UNLIMITED.equals(s.toUpperCase(Locale.ROOT))) {
                    unlimited = true;
                } else {
                    BigDecimal v = new BigDecimal(s);
                    limit = limit == null ? v : limit.max(v);
                }
            }
            if (row.get("ruleRef") != null && ruleRef == null) {
                ruleRef = row.get("ruleRef").toString();
            }
        }
        if (rows.isEmpty()) {
            allowed = false;
        }
        return new Evaluation(allowed, limit, unlimited, ruleRef, rows.size());
    }
}

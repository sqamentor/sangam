// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.delegation;

import org.operaton.bpm.dmn.engine.DmnDecision;
import org.operaton.bpm.dmn.engine.DmnDecisionResult;
import org.operaton.bpm.dmn.engine.DmnEngine;
import org.sangam.core.security.SangamSecurityException;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads the matched rows of a delegation-of-powers DMN table with Operaton's standalone DMN engine (DMN 1.3+ / FEEL,
 * ADR-D-16). It decides nothing: whether the rows confer a power, and up to which amount, is decided by the one
 * power-limit evaluator of sangam-core (owner decision 142, RM-08), which {@link DelegationOfPowersService} calls with
 * these rows mapped through {@code LegacyPowerContracts.serviceRows}.
 *
 * <h2>Table contract</h2>
 * Inputs available to the table: {@code seatId}, {@code designation}, {@code unitId}, {@code levelType},
 * {@code rankNo}, {@code amount}, the {@code caseType} the deployed pack manifest declares for the action (owner
 * decision 222; absent when it declares none) plus caller extras. Outputs of each row:
 * <ul>
 *   <li>{@code maxAmount}: the power limit of the seat for the case type — a number, a decimal string, or
 *       {@code "UNLIMITED"}; a row without it states no power;</li>
 *   <li>{@code allowed} (optional boolean): an explicit "no power" row ({@code false}) denies;</li>
 *   <li>{@code ruleRef} (optional): the order/rule reference shown in audit.</li>
 * </ul>
 * Every matched row is a delegated limit and all of them must pass, so the lowest ceiling binds. Hit policy UNIQUE is
 * recommended: overlapping rows then fail evaluation (a denial), and the draft test cases catch them before review
 * (AJ-03 step 1).
 *
 * <p>Parsed decisions are cached by the table's content hash; a published version is immutable.
 */
public final class DmnPowerRows {

    private final DmnEngine engine;
    private final Map<String, DmnDecision> cache = new ConcurrentHashMap<>();

    public DmnPowerRows() {
        this(org.sangam.engine.feel.SangamFeel.dmnEngineConfiguration().buildEngine());
    }

    public DmnPowerRows(DmnEngine engine) {
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

    /**
     * The output rows the table matches for {@code inputs}, in the table's order; empty when no rule matches.
     *
     * @throws SangamSecurityException.PolicyRejected when the table is invalid or its evaluation fails (the evaluator
     *                                                turns a failing rule source into a denial)
     */
    public List<Map<String, Object>> rows(String dmnHash, String decisionId, String dmnXml, Map<String, Object> inputs) {
        DmnDecision d = parse(dmnHash, decisionId, dmnXml);
        DmnDecisionResult result;
        try {
            result = engine.evaluateDecision(d, inputs);
        } catch (RuntimeException e) {
            throw new SangamSecurityException.PolicyRejected("delegation-of-powers evaluation failed: " + e.getMessage());
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : result.getResultList()) {
            rows.add(new LinkedHashMap<>(row));
        }
        return rows;
    }
}

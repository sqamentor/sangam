// SPDX-License-Identifier: Apache-2.0
package org.sangam.ledger;

import org.sangam.config.common.Finding;
import org.sangam.config.common.Tree;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fiscal-rule checks over aggregated ledger data (PFM-078; also IFS-style ceilings): debt or personnel-cost ratios,
 * end-of-mandate restrictions, deficit limits. Aggregates and rules are configuration:
 *
 * <pre>
 * fiscalRules:
 *   aggregates:
 *     - {id: personnel, ledger: BUDGET, linePrefix: "2-01", bucket: CONSUMED}
 *     - {id: revenue, ledger: REVENUE, bucket: CONSUMED}
 *   rules:
 *     - {id: PERSONNEL_LIMIT, ratio: [personnel, revenue], maxPercent: 50, severity: ERROR, ruleRef: "LRF art. 19 [U]"}
 *     - {id: ALERT_90, ratio: [personnel, revenue], maxPercent: 45, severity: WARNING}
 *   decisionKey: fiscal-rules           # optional DMN over the aggregates (end-of-mandate rules etc.)
 * </pre>
 */
public final class FiscalRuleEvaluator {

    private final LedgerStore store;
    private final LedgerPorts.DecisionPort decisions;

    public FiscalRuleEvaluator(LedgerStore store, LedgerPorts.DecisionPort decisions) {
        this.store = store;
        this.decisions = decisions;
    }

    /** Computes the configured aggregates for a tenant and period. */
    public Map<String, BigDecimal> aggregates(String tenant, String period, Map<String, Object> cfg) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (Map<String, Object> a : Tree.maps(cfg.get("aggregates"))) {
            Balance.Bucket bucket = Balance.Bucket.valueOf(Tree.str(a.get("bucket"), "CONSUMED").toUpperCase());
            BigDecimal sum = BigDecimal.ZERO;
            for (Balance b : store.find(tenant, Tree.str(a.get("ledger"), null), period, Tree.str(a.get("linePrefix"), null),
                    Tree.str(a.get("holderPrefix"), null), Tree.str(a.get("stage"), null))) {
                sum = sum.add(b.bucket(bucket));
            }
            out.put(Tree.str(a.get("id"), "?"), sum);
        }
        return out;
    }

    /** Evaluates the rules; an ERROR finding means the rule blocks (the caller refuses new commitments). */
    public List<Finding> evaluate(String tenant, String period, Map<String, Object> cfg) {
        Map<String, BigDecimal> agg = aggregates(tenant, period, cfg);
        List<Finding> out = new ArrayList<>();
        for (Map<String, Object> r : Tree.maps(cfg.get("rules"))) {
            List<String> ratio = Tree.strings(r.get("ratio"));
            if (ratio.size() != 2) {
                continue;
            }
            BigDecimal num = agg.getOrDefault(ratio.get(0), BigDecimal.ZERO);
            BigDecimal den = agg.getOrDefault(ratio.get(1), BigDecimal.ZERO);
            BigDecimal pct = den.signum() == 0 ? BigDecimal.ZERO : num.multiply(BigDecimal.valueOf(100)).divide(den, 4, RoundingMode.HALF_UP);
            BigDecimal max = Tree.decimal(r.get("maxPercent"), null);
            if (max != null && pct.compareTo(max) > 0) {
                Finding.Severity sev = Finding.Severity.valueOf(Tree.str(r.get("severity"), "ERROR").toUpperCase());
                out.add(new Finding(sev, Tree.str(r.get("id"), "FISCAL_RULE"), ratio.get(0) + "/" + ratio.get(1),
                        pct + "% above " + max + "%", Tree.str(r.get("ruleRef"), null)));
            }
        }
        String key = Tree.str(cfg.get("decisionKey"), null);
        if (key != null && decisions != null) {
            Map<String, Object> vars = new LinkedHashMap<>(agg);
            vars.put("period", period);
            for (Map<String, Object> row : decisions.evaluate(tenant, key, vars)) {
                if (!Tree.bool(row.get("allowed"), true)) {
                    out.add(Finding.error(Tree.str(row.get("code"), "FISCAL_RULE"), key, Tree.str(row.get("message"), "refused"),
                            Tree.str(row.get("ruleRef"), null)));
                }
            }
        }
        return out;
    }
}

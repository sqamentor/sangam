// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.master;

import org.sangam.config.catalogue.DocumentTypeCatalogue;
import org.sangam.config.common.EffectiveDated;
import org.sangam.config.common.Finding;
import org.sangam.config.common.Tree;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Classification crosswalks and combination rules, all data:
 * <pre>
 * crosswalks:
 *   - id: HOA_TO_COFOG          # national code to an international classification, or version to version
 *     from: HOA
 *     to: COFOG
 *     rows:
 *       - {match: "2202-*", target: "09", share: 100, effectiveFrom: 2026-04-01}
 *       - {match: "2210-*", target: "07.3", share: 60}
 *       - {match: "2210-*", target: "07.4", share: 40}     # percentage split
 * combinations:                 # allowed / forbidden segment combinations
 *   - {scheme: HOA, if: {major: "2*"}, require: {object: "0*|1*|2*|3*|4*|5*"}, ruleRef: "..."}
 *   - {scheme: HOA, if: {major: "4*"}, forbid: {object: "01|02"}}
 * tags:                          # percentage-split tags on a line (gender / climate / sub-plan)
 *   - {id: GENDER, values: [A, B], splitAllowed: true}
 * </pre>
 */
public final class Crosswalk {

    /** One mapped target with its share of the amount. */
    public record Mapped(String target, BigDecimal share, BigDecimal amount) {
    }

    private Crosswalk() {
    }

    /** Maps a code (and amount) through a crosswalk; shares of matching rows split the amount. */
    public static List<Mapped> map(Map<String, Object> crosswalk, String code, BigDecimal amount, LocalDate asOf) {
        List<Mapped> out = new ArrayList<>();
        String bestPattern = null;
        List<Map<String, Object>> rows = EffectiveDated.filter(Tree.maps(crosswalk.get("rows")), asOf);
        for (Map<String, Object> r : rows) {
            String p = Tree.str(r.get("match"), "");
            if (DocumentTypeCatalogue.glob(p, code) && (bestPattern == null || p.length() > bestPattern.length())) {
                bestPattern = p;
            }
        }
        if (bestPattern == null) {
            return out;
        }
        for (Map<String, Object> r : rows) {
            if (bestPattern.equals(Tree.str(r.get("match"), ""))) {
                BigDecimal share = Tree.decimal(r.get("share"), BigDecimal.valueOf(100));
                out.add(new Mapped(Tree.str(r.get("target"), null), share,
                        amount == null ? null : amount.multiply(share).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)));
            }
        }
        return out;
    }

    /** Checks every crosswalk's shares sum to 100 per pattern. */
    public static List<Finding> lint(Map<String, Object> crosswalk) {
        Map<String, BigDecimal> sums = new LinkedHashMap<>();
        for (Map<String, Object> r : Tree.maps(crosswalk.get("rows"))) {
            sums.merge(Tree.str(r.get("match"), "") + "@" + Tree.str(r.get("effectiveFrom"), ""),
                    Tree.decimal(r.get("share"), BigDecimal.valueOf(100)), BigDecimal::add);
        }
        List<Finding> out = new ArrayList<>();
        sums.forEach((k, v) -> {
            if (v.compareTo(BigDecimal.valueOf(100)) != 0) {
                out.add(Finding.error("CROSSWALK_SHARES", k, "shares sum to " + v + ", not 100", null));
            }
        });
        return out;
    }

    /** Applies combination rules to a parsed code. */
    public static List<Finding> checkCombinations(List<Map<String, Object>> rules, ClassificationRegistry.Code code) {
        List<Finding> out = new ArrayList<>();
        for (Map<String, Object> r : rules) {
            if (r.get("scheme") != null && !Tree.str(r.get("scheme"), "").equals(code.scheme())) {
                continue;
            }
            if (!matchesAll(Tree.map(r.get("if")), code)) {
                continue;
            }
            Map<String, Object> req = Tree.map(r.get("require"));
            if (!req.isEmpty() && !matchesAll(req, code)) {
                out.add(Finding.error("COMBINATION_REQUIRED", req.toString(), "segment combination not allowed",
                        Tree.str(r.get("ruleRef"), null)));
            }
            Map<String, Object> forbid = Tree.map(r.get("forbid"));
            if (!forbid.isEmpty() && matchesAny(forbid, code)) {
                out.add(Finding.error("COMBINATION_FORBIDDEN", forbid.toString(), "segment combination forbidden",
                        Tree.str(r.get("ruleRef"), null)));
            }
        }
        return out;
    }

    /** Validates percentage-split tags on a line: {@code {GENDER: {A: 60, B: 40}}} must sum to 100. */
    public static List<Finding> checkSplitTags(Map<String, Object> tags) {
        List<Finding> out = new ArrayList<>();
        tags.forEach((tag, v) -> {
            if (v instanceof Map<?, ?> m) {
                BigDecimal sum = BigDecimal.ZERO;
                for (Object x : m.values()) {
                    sum = sum.add(Tree.decimal(x, BigDecimal.ZERO));
                }
                if (sum.compareTo(BigDecimal.valueOf(100)) != 0) {
                    out.add(Finding.error("TAG_SPLIT", tag, "split of " + tag + " sums to " + sum, null));
                }
            }
        });
        return out;
    }

    private static boolean matchesAll(Map<String, Object> cond, ClassificationRegistry.Code code) {
        for (Map.Entry<String, Object> e : cond.entrySet()) {
            if (!matches(e.getValue().toString(), code.segment(e.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesAny(Map<String, Object> cond, ClassificationRegistry.Code code) {
        for (Map.Entry<String, Object> e : cond.entrySet()) {
            if (matches(e.getValue().toString(), code.segment(e.getKey()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(String alternatives, String v) {
        if (v == null) {
            return false;
        }
        for (String p : alternatives.split("\\|")) {
            if (DocumentTypeCatalogue.glob(p.trim(), v)) {
                return true;
            }
        }
        return false;
    }
}

// SPDX-License-Identifier: Apache-2.0
package org.sangam.ledger;

import org.sangam.config.common.Tree;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A ledger definition, read from a profile section {@code ledgers: [...]} (ADR-D-20: no domain constants in code).
 *
 * <pre>
 * ledgers:
 *   - id: BUDGET
 *     kind: LIMIT                    # LIMIT (money limits) | QUANTITY (stock) | RECEIVABLE (demand / collection)
 *     mode: AUTHORITATIVE            # or SHADOW: mirror of an external system of record, reconciled via a port
 *     currency: INR                  # functional currency; accounts may hold a transaction currency
 *     stages: [APPROPRIATION, ALLOCATION, RELEASE, AUTHORISATION, SUB_ALLOTMENT]   # N-stage chain, top first
 *     consumeAt: SUB_ALLOTMENT       # stage whose accounts carry commitments / spending (default: last)
 *     checks: {APPROPRIATION: HARD, RELEASE: SOFT}  # per-stage availability check (HARD blocks, SOFT warns, NONE)
 *     allowNegative: false           # net / negative limits allowed
 *     availability: {type: ANNUAL, years: 1, carryForwardCommitments: false, cancelAfterYears: 5}
 *                                    # ANNUAL | MULTI_YEAR (years) | NO_YEAR; expired accounts can only be adjusted
 *     stripes: 1                     # hot-account striping (concurrency), see JdbcLedgerStore
 *     holdQueue: true                # requests failing for funds may be held and released on allocation
 *     transfer: {sameAttributes: [section, chargedVoted], ...}   # generic transfer constraints (see LedgerService)
 *     accounting: [{event: CONSUME, basis: CASH, debit: "{line}", credit: "CASH"}]   # event-to-entry mapping
 *     issueOrder: FEFO               # QUANTITY: FIFO | FEFO | LIFO
 * </pre>
 */
public record LedgerDefinition(String id, Kind kind, Mode mode, String currency, List<String> stages, String consumeAt,
                               Map<String, Object> checks, boolean allowNegative, Map<String, Object> availability,
                               int stripes, boolean holdQueue, Map<String, Object> raw) {

    public enum Kind { LIMIT, QUANTITY, RECEIVABLE }

    public enum Mode { AUTHORITATIVE, SHADOW }

    public enum Check { HARD, SOFT, NONE }

    public static LedgerDefinition from(Map<String, Object> m) {
        List<String> stages = new ArrayList<>(Tree.strings(m.get("stages")));
        if (stages.isEmpty()) {
            stages.add("LIMIT");
        }
        return new LedgerDefinition(Tree.str(m.get("id"), null),
                Kind.valueOf(Tree.str(m.get("kind"), "LIMIT").toUpperCase()),
                Mode.valueOf(Tree.str(m.get("mode"), "AUTHORITATIVE").toUpperCase()),
                Tree.str(m.get("currency"), null), List.copyOf(stages),
                Tree.str(m.get("consumeAt"), stages.get(stages.size() - 1)), Tree.map(m.get("checks")),
                Tree.bool(m.get("allowNegative"), false), Tree.map(m.get("availability")),
                Math.max(1, Tree.integer(m.get("stripes"), 1)), Tree.bool(m.get("holdQueue"), true), m);
    }

    public static List<LedgerDefinition> all(Object section) {
        List<LedgerDefinition> out = new ArrayList<>();
        for (Map<String, Object> m : Tree.maps(section)) {
            out.add(from(m));
        }
        return out;
    }

    public int stageIndex(String stage) {
        return stages.indexOf(stage);
    }

    public String parentStage(String stage) {
        int i = stageIndex(stage);
        return i <= 0 ? null : stages.get(i - 1);
    }

    public Check check(String stage) {
        Object v = checks.get(stage);
        if (v == null) {
            return kind == Kind.RECEIVABLE ? Check.SOFT : Check.HARD;
        }
        return Check.valueOf(v.toString().toUpperCase());
    }

    public String availabilityType() {
        return Tree.str(availability.get("type"), "ANNUAL").toUpperCase();
    }

    public int availabilityYears() {
        return Tree.integer(availability.get("years"), 1);
    }

    public boolean carryForwardCommitments() {
        return Tree.bool(availability.get("carryForwardCommitments"), false);
    }

    public Object get(String path) {
        return Tree.get(raw, path);
    }
}

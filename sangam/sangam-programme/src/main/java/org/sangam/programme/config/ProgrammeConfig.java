// SPDX-License-Identifier: Apache-2.0
package org.sangam.programme.config;

import org.sangam.programme.ProgrammeException;
import org.sangam.programme.indicator.FormulaEvaluator;
import org.sangam.programme.support.Db;

import java.math.BigDecimal;
import java.time.Month;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The tenant's programme configuration (data, never code; X.0e). JSON shape:
 * <pre>
 * {
 *   "fiscalYearStartMonth": 4,
 *   "units": ["count", "percent", "km", "currency"],
 *   "programmeKinds": [{"kind": "PROGRAMME"}, {"kind": "SCHEME", "parents": ["PROGRAMME"]}, {"kind": "PROJECT", "parents": ["SCHEME"]}],
 *   "variance": {"warnPct": 10, "criticalPct": 25},
 *   "physicalProgress": "MILESTONES" | "INDICATORS",
 *   "indicators": [{"key": "...", "name": "...", "unit": "count", "frequency": "MONTHLY", "kind": "PHYSICAL",
 *                   "aggregation": "SUM", "cumulative": true, "validation": {"min": 0, "integer": true}}],
 *   "returns": [{"key": "...", "name": "...", "frequency": "MONTHLY", "indicators": [{"key": "...", "required": true}],
 *                "rules": [{"code": "R1", "expr": "a <= b", "severity": "ERROR", "message": "..."}],
 *                "dueOffsetDays": 10, "reminderDays": [-3, 0, 3], "approvalLevels": 2, "reportingLevel": "..."}]
 * }
 * </pre>
 * {@link #parse} validates units, kinds, formula references, formula cycles, weight indicators, parent indicators
 * and return items.
 */
public record ProgrammeConfig(Month fiscalYearStartMonth, Set<String> units, Map<String, Set<String>> programmeKinds,
                              BigDecimal varianceWarnPct, BigDecimal varianceCriticalPct, String physicalProgressBasis,
                              Map<String, IndicatorDefinition> indicators, Map<String, ReturnDefinition> returns) {

    public ProgrammeConfig {
        units = Set.copyOf(units);
        programmeKinds = Map.copyOf(programmeKinds);
        indicators = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(indicators));
        returns = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(returns));
    }

    public static ProgrammeConfig parse(String json) {
        return parse(Db.parseObject(json));
    }

    public static ProgrammeConfig parse(Map<String, Object> m) {
        Month fy = Month.of(Db.intVal(m, "fiscalYearStartMonth", 1));
        Set<String> units = new LinkedHashSet<>();
        Db.arr(m.get("units")).forEach(u -> units.add(String.valueOf(u)));
        Map<String, Set<String>> kinds = new LinkedHashMap<>();
        for (Object o : Db.arr(m.get("programmeKinds"))) {
            Map<String, Object> km = o instanceof Map<?, ?> ? Db.obj(o) : Db.kv("kind", String.valueOf(o));
            Set<String> parents = new LinkedHashSet<>();
            Db.arr(km.get("parents")).forEach(p -> parents.add(String.valueOf(p)));
            kinds.put(Db.requireText(Db.str(km.get("kind")), "programme kind"), parents);
        }
        Map<String, Object> variance = Db.obj(m.get("variance"));
        Map<String, IndicatorDefinition> inds = new LinkedHashMap<>();
        for (Object o : Db.arr(m.get("indicators"))) {
            IndicatorDefinition d = IndicatorDefinition.fromMap(Db.obj(o));
            if (inds.put(d.key(), d) != null) {
                throw cfg("duplicate indicator " + d.key());
            }
        }
        Map<String, ReturnDefinition> rets = new LinkedHashMap<>();
        for (Object o : Db.arr(m.get("returns"))) {
            ReturnDefinition r = ReturnDefinition.fromMap(Db.obj(o));
            if (rets.put(r.key(), r) != null) {
                throw cfg("duplicate return " + r.key());
            }
        }
        BigDecimal warn = Db.dec(variance.getOrDefault("warnPct", "10"));
        BigDecimal crit = Db.dec(variance.getOrDefault("criticalPct", "25"));
        String basis = Db.str(m, "physicalProgress", "MILESTONES");
        if (!"MILESTONES".equals(basis) && !"INDICATORS".equals(basis)) {
            throw cfg("physicalProgress must be MILESTONES or INDICATORS");
        }
        ProgrammeConfig c = new ProgrammeConfig(fy, units, kinds, warn, crit, basis, inds, rets);
        c.validate();
        return c;
    }

    /** Structural checks; throws {@code INVALID_CONFIG} with the first problem. */
    public void validate() {
        for (Map.Entry<String, Set<String>> k : programmeKinds.entrySet()) {
            for (String p : k.getValue()) {
                if (!programmeKinds.containsKey(p)) {
                    throw cfg("programme kind " + k.getKey() + " has unknown parent kind " + p);
                }
            }
        }
        Map<String, Set<String>> deps = new HashMap<>();
        for (IndicatorDefinition d : indicators.values()) {
            if (!units.isEmpty() && !units.contains(d.unit())) {
                throw cfg("indicator " + d.key() + " uses unknown unit " + d.unit());
            }
            if (d.parentIndicatorKey() != null && !indicators.containsKey(d.parentIndicatorKey())) {
                throw cfg("indicator " + d.key() + " has unknown parent " + d.parentIndicatorKey());
            }
            if (d.weightIndicator() != null && !indicators.containsKey(d.weightIndicator())) {
                throw cfg("indicator " + d.key() + " has unknown weight indicator " + d.weightIndicator());
            }
            if (d.aggregation() == IndicatorDefinition.Aggregation.FORMULA) {
                Set<String> refs = FormulaEvaluator.references(d.formula());
                for (String r : refs) {
                    if (!indicators.containsKey(r)) {
                        throw cfg("formula of " + d.key() + " references unknown indicator " + r);
                    }
                }
                deps.put(d.key(), refs);
            }
        }
        // cycle check over FORMULA dependencies and the parent chain
        for (String k : deps.keySet()) {
            detectCycle(k, deps, new HashSet<>(), new ArrayList<>());
        }
        for (IndicatorDefinition d : indicators.values()) {
            Set<String> seen = new HashSet<>();
            for (String p = d.key(); p != null; p = indicators.get(p).parentIndicatorKey()) {
                if (!seen.add(p)) {
                    throw cfg("indicator parent cycle at " + p);
                }
            }
        }
        for (ReturnDefinition r : returns.values()) {
            for (ReturnDefinition.Item i : r.items()) {
                IndicatorDefinition d = indicators.get(i.indicatorKey());
                if (d == null) {
                    throw cfg("return " + r.key() + " collects unknown indicator " + i.indicatorKey());
                }
                if (d.aggregation() == IndicatorDefinition.Aggregation.FORMULA) {
                    throw cfg("return " + r.key() + " collects derived (FORMULA) indicator " + i.indicatorKey());
                }
            }
            for (Map<String, Object> rule : r.rules()) {
                String expr = Db.requireText(Db.str(rule.get("expr")), "rule expr of " + r.key());
                for (String ref : FormulaEvaluator.references(expr)) {
                    String base = ref.startsWith("prev.") ? ref.substring(5) : ref.startsWith("target.") ? ref.substring(7) : ref;
                    if (!indicators.containsKey(base)) {
                        throw cfg("rule of " + r.key() + " references unknown indicator " + ref);
                    }
                }
            }
        }
    }

    private void detectCycle(String k, Map<String, Set<String>> deps, Set<String> done, List<String> path) {
        if (path.contains(k)) {
            throw cfg("formula cycle: " + String.join(" -> ", path) + " -> " + k);
        }
        if (!done.add(k)) {
            return;
        }
        path.add(k);
        for (String d : deps.getOrDefault(k, Set.of())) {
            detectCycle(d, deps, done, path);
        }
        path.remove(path.size() - 1);
        done.remove(k);
        done.add(k);
    }

    /** Whether a programme of {@code kind} may sit under a parent of {@code parentKind} (null = top level). */
    public boolean kindAllowed(String kind, String parentKind) {
        if (programmeKinds.isEmpty()) {
            return true;
        }
        Set<String> parents = programmeKinds.get(kind);
        if (parents == null) {
            return false;
        }
        return parentKind == null ? parents.isEmpty() || parents.contains("") : parents.contains(parentKind);
    }

    /** FORMULA indicators in dependency order (inputs first). */
    public List<IndicatorDefinition> formulaOrder() {
        List<IndicatorDefinition> out = new ArrayList<>();
        Set<String> placed = new HashSet<>();
        List<IndicatorDefinition> pending = new ArrayList<>();
        indicators.values().stream().filter(d -> d.aggregation() == IndicatorDefinition.Aggregation.FORMULA).forEach(pending::add);
        while (!pending.isEmpty()) {
            boolean progressed = false;
            for (var it = pending.iterator(); it.hasNext(); ) {
                IndicatorDefinition d = it.next();
                boolean ready = FormulaEvaluator.references(d.formula()).stream()
                        .allMatch(r -> placed.contains(r) || indicators.get(r).aggregation() != IndicatorDefinition.Aggregation.FORMULA);
                if (ready) {
                    out.add(d);
                    placed.add(d.key());
                    it.remove();
                    progressed = true;
                }
            }
            if (!progressed) {
                throw cfg("formula cycle among " + pending.stream().map(IndicatorDefinition::key).toList());
            }
        }
        return out;
    }

    private static ProgrammeException cfg(String message) {
        return new ProgrammeException(ProgrammeException.Code.INVALID_CONFIG, message);
    }
}

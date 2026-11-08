// SPDX-License-Identifier: Apache-2.0
package org.sangam.field.schedule;

import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.field.FieldAudit;
import org.sangam.field.FieldException;
import org.sangam.field.FieldJson;
import org.sangam.field.ports.FieldPorts.DecisionPort;
import org.sangam.field.support.FieldSql;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Risk-based random selection of units to inspect with an audited seed (N05; randomised, risk-based selection).
 * The draw is weighted sampling without replacement (Efraimidis–Spirakis: key = u^(1/w), top-k), driven by a
 * {@link SplittableRandom} seeded from SHA-256 of the seed text, over the population sorted by ref. Anyone holding the
 * seed, the population and the weights can re-run the draw and get the same sample; FLD_SELECTION stores all three
 * (population as a hash plus the weights) and the audit chain records the draw.
 *
 * <p>Weights come from the candidate, or from a DMN risk decision evaluated per candidate (output {@code weight}).
 */
public final class RandomSelector {

    public record Candidate(String ref, double weight, Map<String, Object> attributes) {
        public Candidate {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }

    public record Selection(String selectionId, String seed, String method, String populationHash, int populationSize,
                            List<String> selected, Map<String, Double> weights, Instant drawnAt) {
    }

    private final Transactions tx;
    private final DecisionPort decisions;
    private final FieldAudit audit;
    private final Clock clock;

    public RandomSelector(Transactions tx, DecisionPort decisions, FieldAudit audit, Clock clock) {
        this.tx = tx;
        this.decisions = decisions;
        this.audit = audit;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** Pure, deterministic draw. */
    public static List<String> draw(String seed, List<Candidate> population, int k) {
        if (seed == null || seed.isBlank()) {
            throw new FieldException("INVALID_REQUEST", "a seed is required for an auditable draw");
        }
        List<Candidate> sorted = new ArrayList<>(population);
        sorted.sort(Comparator.comparing(Candidate::ref));
        SplittableRandom rnd = new SplittableRandom(ByteBuffer.wrap(Hashes.sha256(seed)).getLong());
        record Keyed(String ref, double key) {
        }
        List<Keyed> keyed = new ArrayList<>(sorted.size());
        for (Candidate c : sorted) {
            double u = rnd.nextDouble();
            if (c.weight() <= 0) {
                continue;
            }
            keyed.add(new Keyed(c.ref(), Math.log(Math.max(u, Double.MIN_VALUE)) / c.weight()));
        }
        keyed.sort(Comparator.comparingDouble(Keyed::key).reversed().thenComparing(Keyed::ref));
        return keyed.stream().limit(Math.max(0, k)).map(Keyed::ref).toList();
    }

    public static String populationHash(List<Candidate> population) {
        List<String> refs = population.stream().map(Candidate::ref).sorted().toList();
        return Hashes.hex(Hashes.sha256(String.join("\n", refs)));
    }

    /**
     * Draws and records a selection.
     *
     * @param riskDecisionKey optional DMN: evaluated per candidate with its attributes + {@code ref}; output {@code weight}
     */
    public Selection select(String tenantId, String seed, List<Candidate> population, int k, String riskDecisionKey, String actor) {
        return tx.inTransaction(c -> select(c, tenantId, seed, population, k, riskDecisionKey, actor));
    }

    public Selection select(Connection c, String tenantId, String seed, List<Candidate> population, int k, String riskDecisionKey,
                            String actor) {
        List<Candidate> weighted = new ArrayList<>(population.size());
        Map<String, Double> weights = new LinkedHashMap<>();
        for (Candidate cand : population) {
            double w = cand.weight();
            if (riskDecisionKey != null && !riskDecisionKey.isBlank()) {
                Map<String, Object> vars = new LinkedHashMap<>(cand.attributes());
                vars.put("ref", cand.ref());
                vars.put("baseWeight", cand.weight());
                List<Map<String, Object>> r = decisions.evaluate(tenantId, riskDecisionKey, vars);
                w = r.isEmpty() || !(r.get(0).get("weight") instanceof Number n) ? 0 : n.doubleValue();
            }
            weights.put(cand.ref(), w);
            weighted.add(new Candidate(cand.ref(), w, cand.attributes()));
        }
        List<String> selected = draw(seed, weighted, k);
        String id = FieldSql.newId();
        Instant now = clock.instant();
        String method = riskDecisionKey == null || riskDecisionKey.isBlank() ? "WEIGHTED_ES" : "WEIGHTED_ES_DMN:" + riskDecisionKey;
        String popHash = populationHash(population);
        Jdbc.update(c, "INSERT INTO FLD_SELECTION (tenant_id, selection_id, seed, method, population_hash, population_size, sample_size, "
                        + "weights_json, selected_json, drawn_by, drawn_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tenantId, id, seed, method, popHash, population.size(), k, FieldJson.write(weights), FieldJson.write(selected), actor, now);
        audit.record(c, tenantId, "selection:" + id, FieldAudit.SELECTION_DRAWN, actor, Map.of("seed", seed, "method", method,
                "populationHash", popHash, "populationSize", population.size(), "selected", selected));
        return new Selection(id, seed, method, popHash, population.size(), selected, weights, now);
    }

    /** Re-runs a stored draw from its recorded seed and weights; true when the stored sample is reproduced. */
    public boolean verify(String tenantId, String selectionId) {
        return tx.inTransaction(c -> Jdbc.one(c, "SELECT seed, sample_size, weights_json, selected_json FROM FLD_SELECTION "
                + "WHERE tenant_id = ? AND selection_id = ?", rs -> {
                    Map<String, Object> w = FieldJson.map(rs.getString("weights_json"));
                    List<Candidate> pop = new ArrayList<>();
                    w.forEach((ref, v) -> pop.add(new Candidate(ref, ((Number) v).doubleValue(), null)));
                    return draw(rs.getString("seed"), pop, rs.getInt("sample_size")).equals(FieldJson.strings(rs.getString("selected_json")));
                }, tenantId, selectionId).orElseThrow(() -> FieldException.notFound("selection", selectionId)));
    }
}

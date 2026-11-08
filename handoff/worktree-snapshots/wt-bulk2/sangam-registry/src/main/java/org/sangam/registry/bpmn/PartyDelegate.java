// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry.bpmn;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.sangam.registry.party.PartyRecords;
import org.sangam.registry.party.PartyService;

import java.util.List;
import java.util.Map;

/**
 * Party registry steps for BPMN service tasks ({@code operaton:class} or a delegate expression). Field {@code op}:
 * <ul>
 *   <li>{@code REGISTER}: creates a party from the map variable named by {@code input} (default {@code party});
 *       sets {@code partyId} (or {@code resultVar}), {@code partyMatches} and {@code partyAutoMergedInto};</li>
 *   <li>{@code MATCH}: scores the probe map in {@code input} (or the existing {@code partyId}); sets
 *       {@code partyMatches} (list of maps) and {@code partyMatchDecision} (best decision or NON_MATCH);</li>
 *   <li>{@code MERGE}: merges {@code mergedId} into {@code survivorId} (fields naming variables or literals); sets
 *       {@code mergeEventId};</li>
 *   <li>{@code SNAPSHOT}: eligibility snapshot of {@code partyId} for {@code reference} (default: business key); sets
 *       {@code partySnapshotId} and {@code partySnapshotDigest};</li>
 *   <li>{@code RESOLVE}: follows merges from {@code partyId}; sets {@code resolvedPartyId} (or {@code resultVar}).</li>
 * </ul>
 * All writes use the engine command's connection (same transaction). Registry errors become BPMN errors
 * {@code REGISTRY_<CODE>}.
 */
public class PartyDelegate implements JavaDelegate {

    private Expression op;
    private Expression input;
    private Expression partyId;
    private Expression survivorId;
    private Expression mergedId;
    private Expression reference;
    private Expression reason;
    private Expression resultVar;

    @Override
    public void execute(DelegateExecution ex) {
        PartyService parties = DelegateSupport.module().parties();
        String operation = DelegateSupport.field(op, ex, "").toUpperCase();
        switch (operation) {
            case "REGISTER" -> DelegateSupport.run(ex, (c, ctx) -> {
                PartyService.CreateResult r = parties.create(c, ctx,
                        DelegateSupport.map(ex, DelegateSupport.field(input, ex, "party")));
                ex.setVariable(DelegateSupport.out(resultVar, ex, "partyId"), r.party().id());
                ex.setVariable("partyMatches", r.matches().stream().map(PartyRecords.MatchResult::toMap).toList());
                ex.setVariable("partyAutoMergedInto", r.autoMergedInto());
                return null;
            });
            case "MATCH" -> DelegateSupport.run(ex, (c, ctx) -> {
                String id = str(DelegateSupport.valueOf(partyId, ex));
                List<PartyRecords.MatchResult> res = id != null ? parties.matchParty(c, ctx, id, true)
                        : parties.findMatches(c, ctx, DelegateSupport.map(ex, DelegateSupport.field(input, ex, "party")));
                List<Map<String, Object>> out = res.stream().map(PartyRecords.MatchResult::toMap).toList();
                ex.setVariable("partyMatches", out);
                ex.setVariable("partyMatchDecision", res.isEmpty() ? "NON_MATCH" : res.get(0).decision());
                return null;
            });
            case "MERGE" -> DelegateSupport.run(ex, (c, ctx) -> {
                PartyRecords.MergeEvent ev = parties.merge(c, ctx, required(survivorId, ex, "survivorId"),
                        required(mergedId, ex, "mergedId"), DelegateSupport.field(reason, ex, "process merge"), null);
                ex.setVariable(DelegateSupport.out(resultVar, ex, "mergeEventId"), ev.id());
                return null;
            });
            case "SNAPSHOT" -> DelegateSupport.run(ex, (c, ctx) -> {
                String ref = str(DelegateSupport.valueOf(reference, ex));
                if (ref == null) {
                    ref = ex.getProcessBusinessKey() != null ? ex.getProcessBusinessKey() : ex.getProcessInstanceId();
                }
                PartyRecords.Snapshot s = parties.snapshot(c, ctx, required(partyId, ex, "partyId"), ref,
                        Map.of("processInstanceId", ex.getProcessInstanceId()));
                ex.setVariable("partySnapshotId", s.id());
                ex.setVariable("partySnapshotDigest", s.digestHex());
                return null;
            });
            case "RESOLVE" -> DelegateSupport.run(ex, (c, ctx) -> {
                ex.setVariable(DelegateSupport.out(resultVar, ex, "resolvedPartyId"),
                        parties.resolve(c, ctx, required(partyId, ex, "partyId")));
                return null;
            });
            default -> throw new ProcessEngineException("PartyDelegate: unknown op '" + operation + "'");
        }
    }

    private static String required(Expression e, DelegateExecution ex, String defaultVar) {
        Object v = e == null ? ex.getVariable(defaultVar) : DelegateSupport.valueOf(e, ex);
        String s = str(v);
        if (s == null) {
            throw new ProcessEngineException("PartyDelegate: " + defaultVar + " is required");
        }
        return s;
    }

    private static String str(Object v) {
        return v == null || v.toString().isBlank() ? null : v.toString();
    }
}

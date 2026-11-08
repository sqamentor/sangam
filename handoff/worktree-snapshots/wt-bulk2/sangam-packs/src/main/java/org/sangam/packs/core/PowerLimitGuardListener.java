// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.delegate.DelegateTask;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.TaskListener;

import org.sangam.packs.port.PackRuntime;

/**
 * Delegated-power check at the moment of sanction (K6, IX.5 rule 3, J-P3-E05). The seat's power is read from the
 * delegation-of-powers DMN table that is current on the action date (the latest deployed version for the tenant) with
 * inputs {@code seatRole}, {@code caseType} and {@code amount}; the table returns {@code powerLimit} (and optionally
 * {@code allowed}, and {@code noMonetaryLimit} for a power the rule confers without a money ceiling). When the case value exceeds the power the completion is refused with
 * {@code 422 POWER_LIMIT_EXCEEDED}, and the officer forwards instead.
 *
 * <p>Fields: {@code decisionKey} (default {@code sangam-core-powers}), {@code decisionVar}, {@code approveValues}
 * (comma list, default {@code approve}), {@code amountVar} (default {@code amount}), {@code caseTypeVar}
 * (default {@code caseType}).
 */
public class PowerLimitGuardListener implements TaskListener {

    private Expression decisionKey;
    private Expression decisionVar;
    private Expression approveValues;
    private Expression amountVar;
    private Expression caseTypeVar;

    @Override
    public void notify(DelegateTask t) {
        String dv = Vars.field(decisionVar, t, "decision");
        Object decision = t.getVariable(dv);
        if (decision == null || !Vars.csv(Vars.field(approveValues, t, "approve")).contains(decision.toString())) {
            return;
        }
        TaskActor a = TaskActor.of(t);
        String role = a.seat() == null ? null
                : PackRuntime.seats().roleOf(Vars.tenant(t), a.seat()).orElse(a.seat());
        BigDecimal amount = Vars.decimal(t, Vars.field(amountVar, t, "amount"));
        Map<String, Object> in = new HashMap<>(t.getVariables()); // tables may key on any case field (subject head)
        in.put("seatRole", role);
        in.put("caseType", Vars.str(t, Vars.field(caseTypeVar, t, "caseType"), "GENERAL"));
        in.put("amount", amount == null ? BigDecimal.ZERO : amount); // exact: band boundaries (SG-01)
        String key = Vars.field(decisionKey, t, "sangam-core-powers");
        Decisions.Evaluation ev = Decisions.evaluate(t.getProcessEngineServices(), Vars.tenant(t), key, in);
        Map<String, Object> row = ev.single();
        BigDecimal limit = Vars.decimal(row.get("powerLimit"));
        Object allowed = row.get("allowed");
        // A rule may confer a power without a money ceiling (for example "full powers" subject to the scheme rules):
        // the table says so explicitly with noMonetaryLimit = true. Absent or false, the ceiling applies (fail closed).
        boolean unlimited = Boolean.TRUE.equals(row.get("noMonetaryLimit"));
        boolean ok = (allowed == null || Boolean.TRUE.equals(allowed))
                && (amount == null || unlimited || (limit != null && amount.compareTo(limit) <= 0));
        Vars.auditTask(t, "RULE_EVALUATED", a.person(), a.seat(), null, a.authorityBasis(),
                Vars.map("decision", key, "version", ev.version(), "seatRole", role, "powerLimit",
                        unlimited ? "NO_MONETARY_LIMIT" : limit == null ? null : limit.toPlainString(), "amount",
                        amount == null ? null : amount.toPlainString(), "result", ok));
        t.getExecution().setVariable("approvalRuleVersion", key + ":" + ev.version());
        if (!ok) {
            Vars.auditTask(t, "POWER_LIMIT_EXCEEDED", a.person(), a.seat(), null, a.authorityBasis(),
                    Vars.map("powerLimit", limit == null ? null : limit.toPlainString(),
                            "amount", amount == null ? null : amount.toPlainString()));
            throw new ProcessEngineException("422 POWER_LIMIT_EXCEEDED: power of " + role + " is "
                    + (limit == null ? "nil" : limit.toPlainString()) + " for this case type; case value "
                    + (amount == null ? "-" : amount.toPlainString()) + ". Forward to the competent authority.");
        }
    }
}

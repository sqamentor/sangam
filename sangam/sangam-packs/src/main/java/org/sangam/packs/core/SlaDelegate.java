// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.JavaDelegate;

import org.sangam.packs.port.PackRuntime;

/**
 * SLA timers, escalation and deemed outcomes (R31, R79, GP-06, GP-35, GP-44, GP-45, J-P4-E01, J-P4-E07).
 *
 * <p>Every SLA-guarded human task in the packs is preceded by {@code op=PLAN} and carries two boundary timers: a
 * non-interrupting warning timer on {@code <clock>WarnAt} ({@code op=WARN}) and an interrupting breach timer on
 * {@code <clock>DueAt} ({@code op=BREACH}). The breach action ({@code onBreach}) is one of:
 * <ul>
 *   <li>{@code ESCALATE}: the task returns to the queue of the escalation seat (the reporting seat, or
 *       {@code escalationSeat}); up to {@code maxEscalations} tiers, then it stays and is re-notified;</li>
 *   <li>{@code DEEMED_APPROVAL}: the decision is set to the approve value by the system principal on behalf of the
 *       competent seat (time-bound service laws, J-P4-E07) - same state machine, no bypass (IX.5 rule 6);</li>
 *   <li>{@code DEEMED_REFUSAL}: the decision is set to the refuse value (RTI s7(2), GP-44).</li>
 * </ul>
 * The stage due date never exceeds the case-level statutory deadline {@code caseDueAt}. {@code op=PAUSE} and
 * {@code op=RESUME} stop and restart the statutory clock, moving {@code caseDueAt} by the excluded time (RTI s7(3)
 * fee period, clarification awaited, applicant-requested reschedule: GP-45). {@code op=DEADLINE} sets a plain
 * deadline {@code <clock>DueAt} (clarification window, appeal window, objection window); {@code op=BEFORE} a deadline
 * a lead time before a date variable (guarantee review before expiry, GP-41); {@code op=SHIFT} moves a date variable
 * by a number of days (extension of time, GP-43).
 */
public class SlaDelegate implements JavaDelegate {

    private Expression op;
    private Expression clock;
    private Expression spec;
    private Expression defaultSpec;
    private Expression onBreach;
    private Expression seatVar;
    private Expression decisionVar;
    private Expression approveValue;
    private Expression refuseValue;
    private Expression maxEscalations;
    private Expression boundByCase;
    private Expression reason;
    private Expression dateVar;
    private Expression daysVar;

    @Override
    public void execute(DelegateExecution ex) {
        String o = Vars.field(op, ex, "PLAN");
        String c = Vars.field(clock, ex, "stage");
        switch (o) {
            case "PLAN" -> plan(ex, c);
            case "WARN" -> {
                Vars.audit(ex, "SLA_WARNING", Vars.map("clock", c, "dueAt", Vars.str(ex, c + "DueAt")));
                String seat = Vars.str(ex, Vars.field(seatVar, ex, "currentSeat"));
                NotifyDelegate.send(ex, "inapp", "sla-warning", seat, PackRuntime.gateway());
            }
            case "BREACH" -> breach(ex, c);
            case "PAUSE" -> pause(ex);
            case "RESUME" -> resume(ex);
            case "DEADLINE" -> {
                String s = Vars.field(spec, ex, Vars.field(defaultSpec, ex, "P30D"));
                Instant due = PackRuntime.calendar().due(Vars.tenant(ex), Vars.now(), s);
                ex.setVariable(c + "DueAt", Date.from(due));
                Vars.audit(ex, "SLA_STARTED", Vars.map("clock", c, "spec", s, "dueAt", due.toString()));
            }
            case "BEFORE" -> {
                // <clock>DueAt = <dateVar> minus an ISO duration (review a guarantee 30 days before expiry, GP-41)
                Instant base = Vars.instant(ex, Vars.field(dateVar, ex));
                Duration lead = Duration.parse(Vars.field(spec, ex, "P30D"));
                Instant due = base == null ? Vars.now() : base.minus(lead);
                if (due.isBefore(Vars.now())) {
                    due = Vars.now().plusSeconds(60);
                }
                ex.setVariable(c + "DueAt", Date.from(due));
            }
            case "SHIFT" -> {
                // extension of time: <dateVar> plus <daysVar> calendar days (GP-43)
                String dv = Vars.field(dateVar, ex);
                Instant base = Vars.instant(ex, dv);
                int days = Vars.intVal(ex, Vars.field(daysVar, ex, "extensionDays"), 0);
                if (base != null) {
                    ex.setVariable(dv, Date.from(base.plus(Duration.ofDays(days))));
                    Vars.audit(ex, "INSTANCE_MODIFIED", Vars.map("variable", dv, "extendedDays", days));
                }
            }
            default -> throw new IllegalArgumentException("unknown SLA op " + o);
        }
    }

    private void plan(DelegateExecution ex, String c) {
        Instant now = Vars.now();
        String s = Vars.field(spec, ex, Vars.field(defaultSpec, ex, "5WD"));
        Instant due = PackRuntime.calendar().due(Vars.tenant(ex), now, s);
        boolean bound = Vars.fieldBool(boundByCase, ex, true);
        Instant caseDue = Vars.instant(ex, Vars.CASE_DUE_AT);
        boolean byCase = false;
        if (bound && caseDue != null && ex.getVariable(Vars.CASE_PAUSED_AT) == null && caseDue.isBefore(due)) {
            due = caseDue;
            byCase = true;
        }
        if (!due.isAfter(now)) {
            due = now.plusSeconds(60); // already overdue: breach handling runs at once
        }
        Instant warn = now.plus(Duration.between(now, due).multipliedBy(3).dividedBy(4));
        ex.setVariable(c + "DueAt", Date.from(due));
        ex.setVariable(c + "WarnAt", Date.from(warn));
        ex.setVariable(c + "Spec", s);
        ex.setVariable("slaAction", null);
        Vars.audit(ex, "SLA_STARTED", Vars.map("clock", c, "spec", s, "dueAt", due.toString(),
                "boundByCaseDeadline", byCase));
    }

    private void breach(DelegateExecution ex, String c) {
        String action = Vars.field(onBreach, ex, Vars.str(ex, "slaOnBreach", "ESCALATE"));
        String sv = Vars.field(seatVar, ex, "currentSeat");
        String seat = Vars.str(ex, sv);
        Vars.audit(ex, "SLA_BREACHED", Vars.map("clock", c, "seat", seat, "action", action));
        switch (action) {
            case "DEEMED_APPROVAL", "DEEMED_REFUSAL" -> {
                String dv = Vars.field(decisionVar, ex, "decision");
                String value = action.equals("DEEMED_APPROVAL") ? Vars.field(approveValue, ex, "approve")
                        : Vars.field(refuseValue, ex, "reject");
                ex.setVariable(dv, value);
                ex.setVariable("deemedDecision", true);
                ex.setVariable("deemedOutcome", action);
                ex.setVariable("lastActorPerson", "system");
                ex.setVariable("lastActorSeat", seat);
                ex.setVariable("lastAuthorityBasis", "onBehalfOf:" + seat);
                ex.setVariable("slaAction", "DEEMED");
                Vars.audit(ex, "DEEMED_DECISION", Vars.map("clock", c, "outcome", action, "onBehalfOf", seat,
                        "rule", Vars.str(ex, "deemedRule")));
            }
            default -> {
                int done = Vars.intVal(ex, c + "Escalations", 0);
                int max = Vars.fieldInt(maxEscalations, ex, 3);
                String next = null;
                if (done < max) {
                    next = Vars.str(ex, "escalationSeat");
                    if (next == null || done > 0) {
                        next = seat == null ? null
                                : PackRuntime.seats().escalationSeatOf(Vars.tenant(ex), seat).orElse(next);
                    }
                }
                if (next != null && !next.equals(seat)) {
                    ex.setVariable(sv, next);
                    ex.setVariable(c + "Escalations", done + 1);
                    ex.setVariable("escalated", true);
                    Vars.audit(ex, "ESCALATED", Vars.map("clock", c, "fromSeat", seat, "toSeat", next,
                            "tier", done + 1));
                    NotifyDelegate.send(ex, "inapp,email", "sla-escalated", next, PackRuntime.gateway());
                    NotifyDelegate.send(ex, "sms", "status-delayed-escalated",
                            Vars.str(ex, "applicantRef", Vars.str(ex, Vars.INITIATOR_PERSON)), PackRuntime.gateway());
                } else {
                    NotifyDelegate.send(ex, "inapp,email", "sla-breached", seat, PackRuntime.gateway());
                }
                ex.setVariable("slaAction", "ESCALATE");
            }
        }
    }

    private void pause(DelegateExecution ex) {
        if (ex.getVariable(Vars.CASE_PAUSED_AT) != null) {
            return;
        }
        ex.setVariable(Vars.CASE_PAUSED_AT, Date.from(Vars.now()));
        Vars.audit(ex, "SLA_PAUSED", Vars.map("reason", Vars.field(reason, ex, "awaiting applicant"),
                "caseDueAt", Vars.str(ex, Vars.CASE_DUE_AT)));
    }

    private void resume(DelegateExecution ex) {
        Instant paused = Vars.instant(ex, Vars.CASE_PAUSED_AT);
        if (paused == null) {
            return;
        }
        Duration excluded = Duration.between(paused, Vars.now());
        Instant due = Vars.instant(ex, Vars.CASE_DUE_AT);
        if (due != null) {
            ex.setVariable(Vars.CASE_DUE_AT, Date.from(due.plus(excluded)));
        }
        ex.setVariable(Vars.CASE_PAUSED_AT, null);
        long total = Vars.intVal(ex, "slaExcludedMinutes", 0) + excluded.toMinutes();
        ex.setVariable("slaExcludedMinutes", (int) Math.min(Integer.MAX_VALUE, total));
        Vars.audit(ex, "SLA_RESUMED", Vars.map("excludedMinutes", excluded.toMinutes(),
                "caseDueAt", due == null ? null : due.plus(excluded).toString()));
    }
}

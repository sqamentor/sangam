// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.JavaDelegate;

import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.port.SeatDirectory;

/**
 * Seat resolution by hierarchy, never by person (IX.5 rules 1 and 2). Field {@code op}:
 * <ul>
 *   <li>{@code ROLE}: {@code targetVar} = the seat with role {@code roleVar} (or {@code role}) serving the case office
 *       {@code officeId} (nearest ancestor); if none, the {@code fallbackSeat} (J-P1-E07 no-candidate fallback);</li>
 *   <li>{@code ROLES}: maps a comma list of roles (variable {@code rolesVar}) to a comma list of seats in
 *       {@code targetVar} (the n-level recommendation chain, P2 "put up to" path);</li>
 *   <li>{@code CANDIDATES}: the eligible seats of role {@code role} today, excluding the initiator's seat and any seat
 *       the initiator holds (K5), as {@code <targetVar>Candidates}; the selected seat (variable
 *       {@code selectedSeat}) is re-validated against that list (S07, J-P1-H step 7); with no candidate the
 *       fallback seat is used and {@code assignmentBasis=fallback};</li>
 *   <li>{@code ESCALATE}: {@code targetVar} = the reporting seat of the seat in {@code targetVar} (power forward,
 *       J-P3-E05);</li>
 *   <li>{@code EXCLUDE_ACTORS}: {@code targetVar} = the seats of the comma list in variable {@code seatsVar}, in that
 *       order, without every seat that an earlier actor of the case acted on or that an earlier actor holds today
 *       (substantive, additional charge or delegation), and {@code <targetVar>Count} = their number. The earlier
 *       actors are exactly those {@link SodGuardListener} compares a completion of step {@code step} with (field
 *       {@code steps}: the same filter as the task's SoD listener; empty = every earlier actor, including the
 *       initiator), so the task is never offered to a seat whose completion segregation of duties must refuse. A
 *       joint approval uses it: the seat that sanctioned the case already approved that payload and must not count as
 *       a second approval (SL-32, J-P1-E09). Then only the first seat of each current holder stays (one person
 *       holding two joint seats is one approval). Fewer than {@code min} (default 1) seats left is refused with
 *       {@code 422 SOD_VIOLATION} (fail closed: the two-approver control is never reduced to one).</li>
 * </ul>
 */
public class SeatResolutionDelegate implements JavaDelegate {

    private Expression op;
    private Expression role;
    private Expression roleVar;
    private Expression rolesVar;
    private Expression targetVar;
    private Expression fallbackSeat;
    private Expression seatsVar;
    private Expression step;
    private Expression steps;
    private Expression min;

    @Override
    public void execute(DelegateExecution ex) {
        SeatDirectory dir = PackRuntime.seats();
        String tenant = Vars.tenant(ex);
        String office = Vars.str(ex, "officeId");
        String target = Vars.field(targetVar, ex, "resolvedSeat");
        String fallback = Vars.field(fallbackSeat, ex, Vars.str(ex, "fallbackSeat"));
        switch (Vars.field(op, ex, "ROLE")) {
            case "ROLE" -> {
                String r = Vars.field(role, ex);
                if (r == null) {
                    r = Vars.str(ex, Vars.field(roleVar, ex, "competentRole"));
                }
                String seat = r == null ? null : dir.seatForRole(tenant, r, office).orElse(null);
                if (seat == null) {
                    seat = fallback != null ? fallback : r; // the role code doubles as a pooled seat group
                    ex.setVariable("assignmentBasis", "fallback");
                } else {
                    ex.setVariable("assignmentBasis", "hierarchy");
                }
                ex.setVariable(target, seat);
                Vars.audit(ex, "TASK_ASSIGNED", Vars.map("role", r, "seat", seat,
                        "basis", Vars.str(ex, "assignmentBasis")));
            }
            case "ROLES" -> {
                List<String> seats = new ArrayList<>();
                for (String r : Vars.csv(Vars.str(ex, Vars.field(rolesVar, ex, "recommendationRoles")))) {
                    seats.add(dir.seatForRole(tenant, r, office).orElse(r));
                }
                ex.setVariable(target, Vars.joinCsv(seats));
            }
            case "CANDIDATES" -> candidates(ex, dir, tenant, office, target, fallback);
            case "ESCALATE" -> {
                String cur = Vars.str(ex, target);
                String up = cur == null ? null : dir.escalationSeatOf(tenant, cur).orElse(null);
                if (up == null) {
                    up = fallback;
                }
                if (up == null) {
                    throw new ProcessEngineException("no higher seat above " + cur + " and no fallback seat");
                }
                ex.setVariable(target, up);
                Vars.audit(ex, "TASK_ASSIGNED", Vars.map("fromSeat", cur, "seat", up, "basis", "power-forward"));
            }
            case "EXCLUDE_ACTORS" -> excludeActors(ex, dir, tenant, target);
            default -> throw new ProcessEngineException("unknown seat op");
        }
    }

    /**
     * The seats of {@code pool}, in order, that none of {@code actors} acted on and none of them holds
     * ({@code holds.test(person, seat)}). Pure, so that the rule is tested without an engine.
     */
    static List<String> excludingActors(List<String> pool, List<SodGuardListener.EarlierActor> actors,
                                        BiPredicate<String, String> holds) {
        List<String> out = new ArrayList<>();
        for (String s : pool) {
            boolean acted = false;
            for (SodGuardListener.EarlierActor a : actors) {
                if (s.equals(a.seat()) || (a.person() != null && holds.test(a.person(), s))) {
                    acted = true;
                    break;
                }
            }
            if (!acted && !out.contains(s)) {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * The seats in order, keeping only the first seat of each current holder ({@code holder.apply(seat)}; a seat
     * without a holder today stays): one person holding two joint seats (additional charge, delegation) is one
     * approval, so gets one instance (IFMS R-102). Pure, so that the rule is tested without an engine.
     */
    static List<String> firstSeatPerHolder(List<String> seats, Function<String, Optional<String>> holder) {
        List<String> out = new ArrayList<>();
        Set<String> people = new HashSet<>();
        for (String s : seats) {
            String p = holder.apply(s).orElse(null);
            if (p == null || people.add(p)) {
                out.add(s);
            }
        }
        return out;
    }

    private void excludeActors(DelegateExecution ex, SeatDirectory dir, String tenant, String target) {
        String poolVar = Vars.field(seatsVar, ex);
        if (poolVar == null) {
            throw new ProcessEngineException("seat op EXCLUDE_ACTORS needs the field seatsVar");
        }
        List<String> pool = Vars.csv(Vars.str(ex, poolVar));
        String forStep = Vars.field(step, ex, "");
        List<SodGuardListener.EarlierActor> actors = SodGuardListener.earlierActors(
                Set.copyOf(Vars.csv(Vars.field(steps, ex))), forStep, Vars.str(ex, Vars.INITIATOR_PERSON),
                Vars.str(ex, Vars.INITIATOR_SEAT), Vars.jsonList(ex, Vars.ACTOR_LOG));
        LocalDate today = Vars.today(tenant);
        List<String> out = firstSeatPerHolder(
                excludingActors(pool, actors, (p, s) -> dir.holding(tenant, p, s, today).isPresent()),
                s -> dir.holderOf(tenant, s, today));
        List<String> excluded = new ArrayList<>(pool);
        excluded.removeAll(out);
        int need = Integer.parseInt(Vars.field(min, ex, "1").trim());
        if (out.size() < need) {
            throw new ProcessEngineException("422 SOD_VIOLATION: " + poolVar + " " + pool + " leaves " + out.size()
                    + " seat(s) for " + forStep + " after the earlier actors of the case (" + excluded + "); "
                    + need + " needed (IX.5 rule 5)");
        }
        ex.setVariable(target, Vars.joinCsv(out));
        ex.setVariable(target + "Count", out.size());
        Vars.audit(ex, "TASK_ASSIGNED", Vars.map("seats", out, "excluded", excluded, "step", forStep,
                "basis", "excluding earlier actors"));
    }

    private void candidates(DelegateExecution ex, SeatDirectory dir, String tenant, String office, String target,
                            String fallback) {
        String r = Vars.field(role, ex, Vars.str(ex, Vars.field(roleVar, ex, "checkerRole")));
        List<String> all = dir.eligibleSeats(tenant, r, office, Vars.today(tenant));
        String initiator = Vars.str(ex, Vars.INITIATOR_PERSON);
        String initiatorSeat = Vars.str(ex, Vars.INITIATOR_SEAT);
        List<String> out = new ArrayList<>();
        for (String s : all) {
            if (s.equals(initiatorSeat)) {
                continue;
            }
            String holder = dir.holderOf(tenant, s, Vars.today(tenant)).orElse(null);
            if (holder != null && holder.equals(initiator)) {
                continue;
            }
            out.add(s);
        }
        ex.setVariable(target + "Candidates", Vars.joinCsv(out));
        String selected = Vars.str(ex, "selectedSeat");
        String chosen;
        String basis;
        if (selected != null && out.contains(selected)) {
            chosen = selected;
            basis = "explicit selection";
        } else if (selected != null) {
            throw new ProcessEngineException("422 SOD_VIOLATION: selected seat " + selected
                    + " is not an eligible checker today (eligible: " + out + ")");
        } else if (!out.isEmpty()) {
            chosen = out.get(0);
            basis = "first eligible";
        } else {
            chosen = fallback == null ? r : fallback;
            basis = "fallback";
        }
        ex.setVariable(target, chosen);
        ex.setVariable("assignmentBasis", basis);
        Vars.audit(ex, "TASK_ASSIGNED", Map.of("seat", chosen, "basis", basis, "candidates", out.size()));
    }
}

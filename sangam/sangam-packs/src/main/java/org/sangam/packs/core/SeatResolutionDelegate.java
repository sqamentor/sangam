// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
 *       J-P3-E05).</li>
 * </ul>
 */
public class SeatResolutionDelegate implements JavaDelegate {

    private Expression op;
    private Expression role;
    private Expression roleVar;
    private Expression rolesVar;
    private Expression targetVar;
    private Expression fallbackSeat;

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
            default -> throw new ProcessEngineException("unknown seat op");
        }
    }

    private void candidates(DelegateExecution ex, SeatDirectory dir, String tenant, String office, String target,
                            String fallback) {
        String r = Vars.field(role, ex, Vars.str(ex, Vars.field(roleVar, ex, "checkerRole")));
        List<String> all = dir.eligibleSeats(tenant, r, office, Vars.today());
        String initiator = Vars.str(ex, Vars.INITIATOR_PERSON);
        String initiatorSeat = Vars.str(ex, Vars.INITIATOR_SEAT);
        List<String> out = new ArrayList<>();
        for (String s : all) {
            if (s.equals(initiatorSeat)) {
                continue;
            }
            String holder = dir.holderOf(tenant, s, Vars.today()).orElse(null);
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

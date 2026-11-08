// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.assignment;

import org.sangam.core.security.SecurityIdentity;
import org.sangam.core.security.SecurityPorts;
import org.sangam.security.audit.SecurityAuditService;
import org.sangam.security.audit.SecurityEventType;
import org.sangam.security.org.PostingService;
import org.sangam.security.org.SeatService;
import org.sangam.security.policy.AuthEffectiveMaintainer;
import org.sangam.security.policy.Permission;
import org.sangam.security.policy.ResourceRegistry;
import org.sangam.security.sod.SegregationOfDutiesService;

import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Engine-neutral seat-based assignment (IX.5 rules 1, 2, 4, 5; AJ-04 step 6; CX-8; R82 base).
 *
 * <p>A task is assigned to a <b>seat</b>; whoever can act for the seat <i>at the time of action</i> sees and acts on it
 * (regular holder, else the leave substitute / additional-charge holder, who acts "for" the seat). Resolution:
 * <ol>
 *   <li>the seat comes from the spec: a fixed seat id, or a designation at a hierarchy level above the case's unit;</li>
 *   <li>a seat that already acted in a conflicting duty on this case is routed past (SoD by seat);</li>
 *   <li>persons who acted in a conflicting duty are excluded (SoD by person) — they get an explicit deny on this task,
 *       so they are not offered it (deny wins, A-3);</li>
 *   <li>a vacant seat (no one can act, or everyone excluded) falls back to the escalation seat, up to
 *       {@value #MAX_ESCALATION} levels.</li>
 * </ol>
 * The task's authorization is materialised as grant source {@code task:<id>}: the seat principal gets
 * {@code READ_TASK|READ_VARIABLE|CLAIM|COMPLETE|APPROVE} on the task, excluded persons get a deny. Grants follow the
 * seat, so a transfer moves the task to the new holder without touching the task (CX-1).
 */
public final class SeatAssignmentResolver {

    public static final int MAX_ESCALATION = 5;

    public static final long SEAT_TASK_PERMS = Permission.mask(Permission.READ_TASK, Permission.READ_VARIABLE,
            Permission.READ, Permission.CLAIM, Permission.COMPLETE, Permission.APPROVE);
    public static final long EXCLUDED_PERMS = Permission.mask(Permission.READ_TASK, Permission.CLAIM, Permission.COMPLETE,
            Permission.APPROVE);

    /** What to resolve: a fixed seat, or a designation at a hierarchy level relative to a unit. */
    public record SeatSpec(String seatId, String fromUnitId, String levelType, String designation) {
        public static SeatSpec seat(String seatId) {
            return new SeatSpec(seatId, null, null, null);
        }

        public static SeatSpec atLevel(String fromUnitId, String levelType, String designation) {
            return new SeatSpec(null, fromUnitId, levelType, designation);
        }
    }

    /**
     * @param seatId           the seat that got the task (after escalation)
     * @param requestedSeatId  the seat the model asked for
     * @param candidates       persons who can act now (after SoD exclusion)
     * @param actingFor        candidates who act for the seat (substitute / additional charge)
     * @param excludedPersons  persons denied by SoD
     * @param escalated        true when the requested seat was vacant or excluded
     * @param basis            human-readable selection basis for {@code TASK_ASSIGNED}
     */
    public record AssignmentDecision(String seatId, String requestedSeatId, List<String> candidates, Set<String> actingFor,
                                     Set<String> excludedPersons, boolean escalated, String basis) {
    }

    private final SeatService seats;
    private final PostingService postings;
    private final SegregationOfDutiesService sod;
    private final AuthEffectiveMaintainer maintainer;
    private final SecurityAuditService audit;

    public SeatAssignmentResolver(SeatService seats, PostingService postings, SegregationOfDutiesService sod,
                                  AuthEffectiveMaintainer maintainer, SecurityAuditService audit) {
        this.seats = seats;
        this.postings = postings;
        this.sod = sod;
        this.maintainer = maintainer;
        this.audit = audit;
    }

    public AssignmentDecision resolve(Connection c, String tenant, SeatSpec spec, String caseId, String duty, Instant at) {
        String requested = spec.seatId() != null ? spec.seatId()
                : seats.seatAtLevel(c, tenant, spec.fromUnitId(), spec.levelType(), spec.designation(), at)
                .orElseThrow(() -> new IllegalStateException("no seat '" + spec.designation() + "' at level " + spec.levelType()
                        + " above unit " + spec.fromUnitId()));
        Set<String> excludedPersons = caseId == null || duty == null ? Set.of() : sod.excludedPersons(c, tenant, caseId, duty);
        Set<String> excludedSeats = caseId == null || duty == null ? Set.of() : sod.excludedSeats(c, tenant, caseId, duty);
        String seat = requested;
        List<String> basis = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        for (int level = 0; level <= MAX_ESCALATION && seat != null && visited.add(seat); level++) {
            if (excludedSeats.contains(seat)) {
                basis.add(seat + ": acted in a conflicting duty (SoD by seat)");
            } else {
                List<SecurityPorts.SeatHolder> holders = postings.holdersAt(c, tenant, seat, at);
                List<String> candidates = new ArrayList<>();
                Set<String> acting = new LinkedHashSet<>();
                for (SecurityPorts.SeatHolder h : holders) {
                    if (!excludedPersons.contains(h.personId())) {
                        candidates.add(h.personId());
                        if (h.actingFor()) {
                            acting.add(h.personId());
                        }
                    }
                }
                if (!candidates.isEmpty()) {
                    basis.add(seat + ": " + (acting.isEmpty() ? "holder" : "acting holder") + " at " + at);
                    return new AssignmentDecision(seat, requested, List.copyOf(candidates), Set.copyOf(acting),
                            Set.copyOf(excludedPersons), !seat.equals(requested), String.join("; ", basis));
                }
                basis.add(seat + (holders.isEmpty() ? ": vacant" : ": all holders excluded by SoD"));
            }
            Optional<String> up = postings.escalationSeat(c, tenant, seat, at);
            seat = up.orElse(null);
        }
        // nobody can act: keep the requested seat so the next holder sees it; ops sees it on the vacancy tile
        basis.add("no candidate; task stays with " + requested);
        return new AssignmentDecision(requested, requested, List.of(), Set.of(), Set.copyOf(excludedPersons), false,
                String.join("; ", basis));
    }

    /** Materialises the task's grants (seat principal + SoD denies) in the engine transaction and audits the assignment. */
    public void applyTaskGrants(Connection c, String tenant, String taskId, String rootId, AssignmentDecision d,
                                SecurityIdentity actor) {
        List<AuthEffectiveMaintainer.GrantRow> rows = new ArrayList<>();
        rows.add(new AuthEffectiveMaintainer.GrantRow(SecurityIdentity.SEAT + d.seatId(), ResourceRegistry.TASK, taskId,
                false, SEAT_TASK_PERMS, null));
        for (String p : d.excludedPersons()) {
            rows.add(new AuthEffectiveMaintainer.GrantRow(SecurityIdentity.USER + p, ResourceRegistry.TASK, taskId,
                    true, EXCLUDED_PERMS, null));
        }
        maintainer.replaceSource(c, tenant, source(taskId), rows);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("task_id", taskId);
        p.put("seat", d.seatId());
        p.put("requested_seat", d.requestedSeatId());
        p.put("candidates", d.candidates());
        p.put("excluded_persons", List.copyOf(d.excludedPersons()));
        p.put("escalated", d.escalated());
        p.put("selection_basis", d.basis());
        audit.record(c, actor, d.escalated() ? SecurityEventType.TASK_REASSIGNED : SecurityEventType.TASK_ASSIGNED, rootId, p);
    }

    /** Removes the task's grants (task completed or deleted). */
    public void releaseTaskGrants(Connection c, String tenant, String taskId) {
        maintainer.removeSource(c, tenant, source(taskId));
    }

    public static String source(String taskId) {
        return "task:" + taskId;
    }
}

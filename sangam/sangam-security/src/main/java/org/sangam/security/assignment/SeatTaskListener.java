// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.assignment;

import org.operaton.bpm.engine.delegate.DelegateTask;
import org.operaton.bpm.engine.delegate.TaskListener;
import org.operaton.bpm.engine.task.IdentityLink;
import org.sangam.core.security.SangamSecurityException;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.core.security.SecurityPorts;
import org.sangam.security.SangamSecurity;
import org.sangam.security.audit.SecurityEventType;
import org.sangam.security.support.Sql;
import org.sangam.security.tenant.TenantContext;

import java.sql.Connection;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Operaton {@link TaskListener} that assigns user tasks to seats and enforces seat authority at the time of action
 * (IX.5 rules 1, 4, 5; K5; K7; CX-1; CX-8). Attached to every user task by {@link SeatAssignmentParseListener} as a
 * built-in listener for {@code create}, {@code assignment}, {@code complete} and {@code delete}.
 *
 * <h2>Model contract (Operaton extension properties on the user task)</h2>
 * <ul>
 *   <li>{@code sangam.seat}: fixed seat id; or a candidate group {@code seat:<id>}; or</li>
 *   <li>{@code sangam.seatVariable}: process variable holding the seat id; or</li>
 *   <li>{@code sangam.level} + {@code sangam.designation} (+ {@code sangam.unitVariable}, default {@code orgUnitId}):
 *       the designation at that hierarchy level above the case's unit (IX.5 rule 2);</li>
 *   <li>{@code sangam.duty}: the SoD duty of the step (INITIATOR, CHECKER, APPROVER, ...);</li>
 *   <li>{@code sangam.caseVariable}: variable holding the case id (default: the business key, else the process
 *       instance id).</li>
 * </ul>
 * A user task without any seat configuration is left to Operaton's own assignment.
 *
 * <h2>Events</h2>
 * <ul>
 *   <li><b>create</b>: resolves the seat (with SoD exclusion and vacancy escalation), adds the candidate group
 *       {@code seat:<id>}, stores {@code sangamSeatId}/{@code sangamDuty}/{@code sangamCaseId} as task-local variables,
 *       and materialises the task grants in the engine transaction.</li>
 *   <li><b>assignment</b> (claim/delegate): the new assignee must be able to act for the seat now (K7) and pass SoD (K5).</li>
 *   <li><b>complete</b>: re-checks K7 and K5 for the acting person at the database time, records the actor for SoD,
 *       writes {@code TASK_ACTED_FOR} when acting for the seat, and releases the task grants.</li>
 *   <li><b>delete</b>: releases the task grants.</li>
 * </ul>
 * A failed check throws, and Operaton rolls the whole command back.
 */
public final class SeatTaskListener implements TaskListener {

    public static final String VAR_SEAT = "sangamSeatId";
    public static final String VAR_DUTY = "sangamDuty";
    public static final String VAR_CASE = "sangamCaseId";

    /** Seat configuration read from the model at parse time. */
    public record Config(String seatId, String seatVariable, String levelType, String designation, String unitVariable,
                         String duty, String caseVariable) {
        public boolean configured() {
            return seatId != null || seatVariable != null || (levelType != null && designation != null);
        }
    }

    private final SangamSecurity security;
    private final Config config;

    public SeatTaskListener(SangamSecurity security, Config config) {
        this.security = security;
        this.config = config;
    }

    @Override
    public void notify(DelegateTask task) {
        switch (task.getEventName()) {
            case EVENTNAME_CREATE -> onCreate(task);
            case EVENTNAME_ASSIGNMENT -> onAssignment(task);
            case EVENTNAME_COMPLETE -> onComplete(task);
            case EVENTNAME_DELETE -> release(task);
            default -> { }
        }
    }

    private void onCreate(DelegateTask task) {
        Optional<String> modelSeat = seatFromCandidates(task);
        if (!config.configured() && modelSeat.isEmpty()) {
            return;
        }
        Connection c = EngineConnections.current();
        String tenant = tenant(task);
        Instant now = Sql.dbNow(c, EngineConnections.dialect(c));
        String caseId = caseId(task);
        SeatAssignmentResolver.SeatSpec spec;
        if (config.seatId() != null) {
            spec = SeatAssignmentResolver.SeatSpec.seat(config.seatId());
        } else if (config.seatVariable() != null && task.getVariable(config.seatVariable()) != null) {
            spec = SeatAssignmentResolver.SeatSpec.seat(String.valueOf(task.getVariable(config.seatVariable())));
        } else if (modelSeat.isPresent()) {
            spec = SeatAssignmentResolver.SeatSpec.seat(modelSeat.get());
        } else {
            String unitVar = config.unitVariable() == null ? "orgUnitId" : config.unitVariable();
            Object unit = task.getVariable(unitVar);
            if (unit == null) {
                throw new IllegalStateException("user task " + task.getTaskDefinitionKey() + " routes by level but variable "
                        + unitVar + " is not set");
            }
            spec = SeatAssignmentResolver.SeatSpec.atLevel(String.valueOf(unit), config.levelType(), config.designation());
        }
        SeatAssignmentResolver.AssignmentDecision d = security.assignment().resolve(c, tenant, spec, caseId, config.duty(), now);
        modelSeat.ifPresent(s -> {
            if (!s.equals(d.seatId())) {
                task.deleteCandidateGroup(SecurityIdentity.SEAT + s);
            }
        });
        task.addCandidateGroup(SecurityIdentity.SEAT + d.seatId());
        task.setVariableLocal(VAR_SEAT, d.seatId());
        if (config.duty() != null) {
            task.setVariableLocal(VAR_DUTY, config.duty());
        }
        task.setVariableLocal(VAR_CASE, caseId);
        SecurityIdentity actor = TenantContext.current().filter(i -> i.tenantId().equals(tenant))
                .orElse(SecurityIdentity.system(tenant, null));
        security.assignment().applyTaskGrants(c, tenant, task.getId(), caseId, d, actor);
    }

    private void onAssignment(DelegateTask task) {
        String seat = (String) task.getVariableLocal(VAR_SEAT);
        String assignee = task.getAssignee();
        if (seat == null || assignee == null) {
            return;
        }
        Connection c = EngineConnections.current();
        String tenant = tenant(task);
        Instant now = Sql.dbNow(c, EngineConnections.dialect(c));
        SecurityPorts.SeatHolder h = requireHolder(c, tenant, assignee, seat, now);
        String duty = (String) task.getVariableLocal(VAR_DUTY);
        if (duty != null) {
            // claim-time check (K5): a granted exception is honoured but consumed only at completion
            String caseId = (String) task.getVariableLocal(VAR_CASE);
            security.sod().check(c, tenant, caseId, duty, assignee, seat, h.actingFor() ? seat : null, false);
        }
    }

    private void onComplete(DelegateTask task) {
        String seat = (String) task.getVariableLocal(VAR_SEAT);
        if (seat == null) {
            return;
        }
        Connection c = EngineConnections.current();
        String tenant = tenant(task);
        Instant now = Sql.dbNow(c, EngineConnections.dialect(c));
        Optional<SecurityIdentity> current = TenantContext.current().filter(i -> i.tenantId().equals(tenant));
        String person = current.filter(i -> !i.system()).map(SecurityIdentity::personId).orElse(task.getAssignee());
        if (person == null) {
            throw new SangamSecurityException.AccessDenied("a seat task needs an acting person", 0);
        }
        SecurityPorts.SeatHolder h = requireHolder(c, tenant, person, seat, now);
        String duty = (String) task.getVariableLocal(VAR_DUTY);
        String caseId = (String) task.getVariableLocal(VAR_CASE);
        String actedFor = h.actingFor() ? seat : null;
        if (duty != null) {
            String exception = security.sod().requireAllowed(c, tenant, caseId, duty, person, seat, actedFor).orElse(null);
            security.sod().recordActor(c, tenant, caseId, duty, person, seat, actedFor, task.getId(), exception);
        }
        SecurityIdentity actor = current.orElse(SecurityIdentity.system(tenant, person));
        if (actedFor != null) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("task_id", task.getId());
            p.put("seat_id", seat);
            p.put("acted_for_seat_id", seat);
            p.put("substitute_person_id", person);
            p.put("posting_id", h.postingId());
            p.put("authority_basis", h.kind() + ":" + h.postingId());
            security.audit().record(c, actor, SecurityEventType.TASK_ACTED_FOR, caseId, p);
        }
        security.assignment().releaseTaskGrants(c, tenant, task.getId());
    }

    private void release(DelegateTask task) {
        if (task.getVariableLocal(VAR_SEAT) == null) {
            return;
        }
        Connection c = EngineConnections.current();
        security.assignment().releaseTaskGrants(c, tenant(task), task.getId());
    }

    private SecurityPorts.SeatHolder requireHolder(Connection c, String tenant, String person, String seat, Instant now) {
        try {
            return security.postings().requireHolder(c, tenant, person, seat, now);
        } catch (SangamSecurityException.AccessDenied e) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("seat_id", seat);
            p.put("person_id", person);
            p.put("outcome", "DENIED");
            p.put("reason", "SEAT_NOT_HELD");
            security.audit().denial(c, SecurityIdentity.system(tenant, person), SecurityEventType.AUTH_DENIED, null, p);
            throw new SangamSecurityException.AccessDenied("You no longer hold authority for seat " + seat
                    + " for this action (since " + now + ").", security.authorization().policyVersion(c, tenant));
        }
    }

    private static Optional<String> seatFromCandidates(DelegateTask task) {
        for (IdentityLink l : task.getCandidates()) {
            if (l.getGroupId() != null && l.getGroupId().startsWith(SecurityIdentity.SEAT)) {
                return Optional.of(l.getGroupId().substring(SecurityIdentity.SEAT.length()));
            }
        }
        return Optional.empty();
    }

    private String caseId(DelegateTask task) {
        if (config.caseVariable() != null && task.getVariable(config.caseVariable()) != null) {
            return String.valueOf(task.getVariable(config.caseVariable()));
        }
        if (task.getExecution() != null && task.getExecution().getProcessBusinessKey() != null) {
            return task.getExecution().getProcessBusinessKey();
        }
        return task.getProcessInstanceId() != null ? task.getProcessInstanceId() : task.getCaseInstanceId();
    }

    private static String tenant(DelegateTask task) {
        if (task.getTenantId() != null) {
            return task.getTenantId();
        }
        return TenantContext.require().tenantId();
    }
}

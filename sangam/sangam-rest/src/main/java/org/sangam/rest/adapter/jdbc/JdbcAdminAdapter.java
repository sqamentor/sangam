// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.jdbc;

import static org.sangam.rest.adapter.jdbc.JdbcSupport.checkRevision;
import static org.sangam.rest.adapter.jdbc.JdbcSupport.invalid;
import static org.sangam.rest.adapter.jdbc.JdbcSupport.newId;
import static org.sangam.rest.adapter.jdbc.JdbcSupport.oneOf;
import static org.sangam.rest.adapter.jdbc.JdbcSupport.payload;
import static org.sangam.rest.adapter.jdbc.JdbcSupport.required;
import static org.sangam.rest.adapter.jdbc.JdbcSupport.securityChain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.sangam.core.layer.Jdbc;
import org.sangam.rest.port.AdminPort;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.DocumentStore;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;

/**
 * JDBC {@link AdminPort}: org units, seats, effective-dated assignments, delegations, policies and tenants
 * (IX.5, VI.4), persisted in {@code REST_DOCUMENT} (kinds {@code admin:org-unit}, {@code admin:seat},
 * {@code admin:assignment}, {@code admin:delegation}, {@code admin:policy} per tenant, and {@code admin:tenant} under the
 * global tenant {@code *}); the policy version lives in {@code AUTH_POLICY_VERSION}.
 *
 * <p><b>Module-disabled default.</b> In production sangam-security is bound and owns the {@code SEC_*} tables and
 * {@code AUTH_EFFECTIVE}, and enforces the VI.4 object policy; this adapter serves only when that module is switched
 * off ({@code sangam.modules.security.enabled=false}) or for the parts its bridge delegates here.
 *
 * <p>Behaviour kept from the contract: everything is tenant-scoped by the authenticated tenant (VI.4 rule 6);
 * {@code revision} checks on update (409 REVISION_CONFLICT); every policy change increments the tenant
 * {@code policy_version} (VI.4 rule 5) in the same transaction; every change is a class-B event on the tenant security
 * chain {@code sec:<tenant>}, committed together with the change.
 */
public final class JdbcAdminAdapter implements AdminPort {

    private static final Set<String> LEVELS = Set.of("MINISTRY", "DEPARTMENT", "DIRECTORATE", "REGION", "DISTRICT",
            "OFFICE", "SECTION", "OTHER");
    private static final Set<String> ASSIGNMENT_KINDS = Set.of("SUBSTANTIVE", "ADDITIONAL_CHARGE", "LEAVE_SUBSTITUTE",
            "ACTING");
    private static final Set<String> DELEGATION_KINDS = Set.of("POWER", "TASK");
    private static final Set<String> EFFECTS = Set.of("GRANT", "DENY");
    private static final Set<String> PRINCIPAL_TYPES = Set.of("USER", "GROUP", "SEAT");
    private static final Set<String> TENANT_STATUS = Set.of("DRAFT", "ACTIVE", "SUSPENDED");
    private static final Set<String> PROFILES = Set.of("GOVERNMENT", "STANDARD");
    private static final Set<String> ISOLATION = Set.of("SHARED", "SCHEMA", "DATABASE");

    private final TxSupport tx;
    private final AuditPort audit;
    private final RestProperties props;

    private final DocMap<OrgUnit> orgUnits;
    private final DocMap<Seat> seats;
    private final DocMap<Assignment> assignments;
    private final DocMap<Delegation> delegations;
    private final DocMap<Policy> policies;
    private final DocMap<Tenant> tenants;

    private final org.sangam.rest.support.BusinessDates dates;

    public JdbcAdminAdapter(DocumentStore store, TxSupport tx, AuditPort audit, RestProperties props) {
        this(store, tx, audit, props, org.sangam.rest.support.BusinessDates.utc());
    }

    /** @param dates default validity dates (\"today\") in the tenant's business zone */
    public JdbcAdminAdapter(DocumentStore store, TxSupport tx, AuditPort audit, RestProperties props,
                            org.sangam.rest.support.BusinessDates dates) {
        this.dates = java.util.Objects.requireNonNull(dates, "dates");
        this.tx = tx;
        this.audit = audit;
        this.props = props;
        this.orgUnits = new DocMap<>(store, "admin:org-unit", OrgUnit.class,
                o -> DocMap.ix(o.code(), null, o.parentId(), o.active() ? "ACTIVE" : "INACTIVE"));
        this.seats = new DocMap<>(store, "admin:seat", Seat.class,
                s -> DocMap.ix(s.code(), null, s.orgUnitId(), s.active() ? "ACTIVE" : "INACTIVE"));
        this.assignments = new DocMap<>(store, "admin:assignment", Assignment.class,
                a -> DocMap.ix(a.kind(), a.personId(), a.seatId(), a.ended() ? "ENDED" : "ACTIVE"));
        this.delegations = new DocMap<>(store, "admin:delegation", Delegation.class,
                d -> DocMap.ix(d.kind(), d.toPersonId(), d.seatId(), d.revoked() ? "REVOKED" : "ACTIVE"));
        this.policies = new DocMap<>(store, "admin:policy", Policy.class,
                p -> DocMap.ix(p.resourceType(), p.principalId(), p.resourceId(), p.effect()));
        this.tenants = new DocMap<>(store, "admin:tenant", Tenant.class,
                x -> DocMap.ix(x.id(), null, null, x.status()));
    }

    // ------------------------------------------------------------------ org units

    @Override
    public Page<OrgUnit> orgUnits(RequestContext ctx, AdminFilter f, Page.PageRequest page) {
        AdminFilter filter = filter(f);
        Stream<OrgUnit> s = orgUnits.in(tid(ctx)).values().stream()
                .filter(o -> filter.parentId() == null || filter.parentId().equals(o.parentId()))
                .filter(o -> Boolean.TRUE.equals(filter.includeInactive()) || o.active())
                .filter(o -> matches(filter.q(), o.code(), o.name()));
        return slice(s.sorted(Comparator.comparing(o -> nz(o.code()))), page);
    }

    @Override
    public OrgUnit orgUnit(RequestContext ctx, String id) {
        return find(orgUnits.in(tid(ctx)), id, "OrgUnit");
    }

    @Override
    public synchronized OrgUnit createOrgUnit(RequestContext ctx, OrgUnit in) {
        return tx.inTx(() -> {
            String t = tid(ctx);
            validateOrgUnit(t, in, null);
            OrgUnit o = new OrgUnit(newId(), t, in.parentId(), in.code(), in.name(), in.names(),
                    in.level() == null ? "OTHER" : in.level(), true, in.attributes(), 1);
            orgUnits.in(t).put(o.id(), o);
            audit.record(ctx, securityChain(ctx), "ORG_UNIT_CHANGED",
                    payload("change", "CREATED", "orgUnitId", o.id(), "code", o.code(), "parentId", o.parentId(), "actor", ctx.actor()));
            return o;
        });
    }

    @Override
    public synchronized OrgUnit updateOrgUnit(RequestContext ctx, String id, OrgUnit in) {
        return tx.inTx(() -> {
            String t = tid(ctx);
            OrgUnit cur = orgUnit(ctx, id);
            checkRevision(in.revision(), cur.revision(), "OrgUnit", id);
            validateOrgUnit(t, in, id);
            OrgUnit o = new OrgUnit(id, t, in.parentId(), in.code(), in.name(), in.names(),
                    in.level() == null ? cur.level() : in.level(), in.active(), in.attributes(), cur.revision() + 1);
            orgUnits.in(t).put(id, o);
            audit.record(ctx, securityChain(ctx), "ORG_UNIT_CHANGED",
                    payload("change", "UPDATED", "orgUnitId", id, "code", o.code(), "parentId", o.parentId(),
                            "active", o.active(), "actor", ctx.actor()));
            return o;
        });
    }

    @Override
    public synchronized void deleteOrgUnit(RequestContext ctx, String id, String reason) {
        tx.runInTx(() -> {
            String t = tid(ctx);
            orgUnit(ctx, id);
            boolean children = orgUnits.in(t).values().stream().anyMatch(o -> id.equals(o.parentId()));
            boolean hasSeats = seats.in(t).values().stream().anyMatch(s -> id.equals(s.orgUnitId()));
            if (children || hasSeats) {
                throw ApiProblem.conflict("IN_USE", "OrgUnit " + id + " still has child units or seats");
            }
            orgUnits.in(t).remove(id);
            audit.record(ctx, securityChain(ctx), "ORG_UNIT_CHANGED",
                    payload("change", "DELETED", "orgUnitId", id, "reason", reason, "actor", ctx.actor()));
        });
    }

    private void validateOrgUnit(String t, OrgUnit in, String selfId) {
        required(in.code(), "code");
        required(in.name(), "name");
        if (in.level() != null) {
            oneOf(in.level(), "level", LEVELS);
        }
        if (in.parentId() != null) {
            if (in.parentId().equals(selfId)) {
                throw invalid("parentId", "an org unit cannot be its own parent");
            }
            if (!orgUnits.in(t).containsKey(in.parentId())) {
                throw invalid("parentId", "parent org unit " + in.parentId() + " does not exist");
            }
            // no cycles: walk up from the parent
            String p = in.parentId();
            int guard = 0;
            while (p != null && guard++ < 1000) {
                if (p.equals(selfId)) {
                    throw invalid("parentId", "the parent would create a cycle");
                }
                OrgUnit po = orgUnits.in(t).get(p);
                p = po == null ? null : po.parentId();
            }
        }
        boolean dupCode = orgUnits.in(t).values().stream()
                .anyMatch(o -> o.code().equalsIgnoreCase(in.code()) && !o.id().equals(selfId));
        if (dupCode) {
            throw ApiProblem.conflict("ALREADY_EXISTS", "An org unit with code " + in.code() + " exists");
        }
    }

    // ------------------------------------------------------------------ seats

    @Override
    public Page<Seat> seats(RequestContext ctx, AdminFilter f, Page.PageRequest page) {
        AdminFilter filter = filter(f);
        String t = tid(ctx);
        Set<String> seatsOfPerson = filter.personId() == null ? null
                : activeAssignments(t, dates.orToday(filter.activeOn(), t))
                .filter(a -> filter.personId().equals(a.personId())).map(Assignment::seatId)
                .collect(java.util.stream.Collectors.toSet());
        Stream<Seat> s = seats.in(t).values().stream()
                .filter(x -> filter.orgUnitId() == null || filter.orgUnitId().equals(x.orgUnitId()))
                .filter(x -> filter.parentId() == null || filter.parentId().equals(x.reportsToSeatId()))
                .filter(x -> seatsOfPerson == null || seatsOfPerson.contains(x.id()))
                .filter(x -> Boolean.TRUE.equals(filter.includeInactive()) || x.active())
                .filter(x -> matches(filter.q(), x.code(), x.designation(), x.name()));
        return slice(s.sorted(Comparator.comparing(x -> nz(x.code()))), page);
    }

    @Override
    public Seat seat(RequestContext ctx, String id) {
        return find(seats.in(tid(ctx)), id, "Seat");
    }

    @Override
    public synchronized Seat createSeat(RequestContext ctx, Seat in) {
        return tx.inTx(() -> {
            String t = tid(ctx);
            validateSeat(t, in, null);
            Seat s = new Seat(newId(), t, in.orgUnitId(), in.code(), in.designation(), in.name(), in.hierarchyLevel(),
                    in.reportsToSeatId(), in.roles() == null ? List.of() : List.copyOf(in.roles()), true, in.attributes(), 1);
            seats.in(t).put(s.id(), s);
            audit.record(ctx, securityChain(ctx), "SEAT_CREATED",
                    payload("seatId", s.id(), "code", s.code(), "designation", s.designation(), "orgUnitId", s.orgUnitId(),
                            "actor", ctx.actor()));
            return s;
        });
    }

    @Override
    public synchronized Seat updateSeat(RequestContext ctx, String id, Seat in) {
        return tx.inTx(() -> {
            String t = tid(ctx);
            Seat cur = seat(ctx, id);
            checkRevision(in.revision(), cur.revision(), "Seat", id);
            validateSeat(t, in, id);
            Seat s = new Seat(id, t, in.orgUnitId(), in.code(), in.designation(), in.name(), in.hierarchyLevel(),
                    in.reportsToSeatId(), in.roles() == null ? List.of() : List.copyOf(in.roles()), in.active(),
                    in.attributes(), cur.revision() + 1);
            seats.in(t).put(id, s);
            boolean moved = !Objects.equals(cur.orgUnitId(), s.orgUnitId());
            audit.record(ctx, securityChain(ctx), moved ? "SEAT_MOVED" : "SEAT_UPDATED",
                    payload("seatId", id, "fromOrgUnitId", moved ? cur.orgUnitId() : null, "orgUnitId", s.orgUnitId(),
                            "active", s.active(), "actor", ctx.actor()));
            return s;
        });
    }

    @Override
    public synchronized void deleteSeat(RequestContext ctx, String id, String reason) {
        tx.runInTx(() -> {
            String t = tid(ctx);
            seat(ctx, id);
            boolean used = activeAssignments(t, dates.today(t)).anyMatch(a -> id.equals(a.seatId()) || id.equals(a.actedForSeatId()))
                    || seats.in(t).values().stream().anyMatch(s -> id.equals(s.reportsToSeatId()));
            if (used) {
                throw ApiProblem.conflict("IN_USE", "Seat " + id + " has active assignments or subordinate seats");
            }
            seats.in(t).remove(id);
            audit.record(ctx, securityChain(ctx), "SEAT_DELETED", payload("seatId", id, "reason", reason, "actor", ctx.actor()));
        });
    }

    private void validateSeat(String t, Seat in, String selfId) {
        required(in.code(), "code");
        required(in.designation(), "designation");
        required(in.orgUnitId(), "orgUnitId");
        if (!orgUnits.in(t).containsKey(in.orgUnitId())) {
            throw invalid("orgUnitId", "org unit " + in.orgUnitId() + " does not exist");
        }
        if (in.reportsToSeatId() != null) {
            if (in.reportsToSeatId().equals(selfId) || !seats.in(t).containsKey(in.reportsToSeatId())) {
                throw invalid("reportsToSeatId", "reports-to seat " + in.reportsToSeatId() + " is not valid");
            }
        }
        boolean dupCode = seats.in(t).values().stream()
                .anyMatch(s -> s.code().equalsIgnoreCase(in.code()) && !s.id().equals(selfId));
        if (dupCode) {
            throw ApiProblem.conflict("ALREADY_EXISTS", "A seat with code " + in.code() + " exists");
        }
    }

    // ------------------------------------------------------------------ assignments

    @Override
    public Page<Assignment> assignments(RequestContext ctx, AdminFilter f, Page.PageRequest page) {
        AdminFilter filter = filter(f);
        Stream<Assignment> s = assignments.in(tid(ctx)).values().stream()
                .filter(a -> filter.seatId() == null || filter.seatId().equals(a.seatId())
                        || filter.seatId().equals(a.actedForSeatId()))
                .filter(a -> filter.personId() == null || filter.personId().equals(a.personId()))
                .filter(a -> filter.kind() == null || filter.kind().equals(a.kind()))
                .filter(a -> filter.activeOn() == null || activeOn(a.validFrom(), a.validTo(), filter.activeOn()))
                .filter(a -> Boolean.TRUE.equals(filter.includeInactive()) || filter.activeOn() != null || !a.ended());
        return slice(s.sorted(Comparator.comparing(Assignment::validFrom).thenComparing(Assignment::id)), page);
    }

    @Override
    public Assignment assignment(RequestContext ctx, String id) {
        return find(assignments.in(tid(ctx)), id, "Assignment");
    }

    @Override
    public synchronized Assignment createAssignment(RequestContext ctx, Assignment in) {
        return tx.inTx(() -> {
            String t = tid(ctx);
            required(in.seatId(), "seatId");
            required(in.personId(), "personId");
            String kind = in.kind() == null ? "SUBSTANTIVE" : oneOf(in.kind(), "kind", ASSIGNMENT_KINDS);
            if (!seats.in(t).containsKey(in.seatId())) {
                throw invalid("seatId", "seat " + in.seatId() + " does not exist");
            }
            LocalDate from = dates.orToday(in.validFrom(), t);
            if (in.validTo() != null && in.validTo().isBefore(from)) {
                throw invalid("validTo", "validTo must not be before validFrom");
            }
            if ((kind.equals("ADDITIONAL_CHARGE") || kind.equals("LEAVE_SUBSTITUTE")) && in.actedForSeatId() == null) {
                throw invalid("actedForSeatId", kind + " needs actedForSeatId (IX.5 rule 4)");
            }
            if (in.actedForSeatId() != null && !seats.in(t).containsKey(in.actedForSeatId())) {
                throw invalid("actedForSeatId", "seat " + in.actedForSeatId() + " does not exist");
            }
            if (kind.equals("SUBSTANTIVE")) {
                boolean overlap = assignments.in(t).values().stream()
                        .filter(a -> !a.ended() && a.kind().equals("SUBSTANTIVE") && a.seatId().equals(in.seatId()))
                        .anyMatch(a -> overlaps(a.validFrom(), a.validTo(), from, in.validTo()));
                if (overlap) {
                    throw ApiProblem.conflict("ASSIGNMENT_OVERLAP",
                            "Seat " + in.seatId() + " already has a substantive holder in that period").with("field", "validFrom");
                }
            }
            Assignment a = new Assignment(newId(), t, in.seatId(), in.personId(), kind, from, in.validTo(),
                    in.actedForSeatId(), in.orderRef(), ctx.principalId(), Instant.now(), false);
            assignments.in(t).put(a.id(), a);
            String type = kind.equals("SUBSTANTIVE") ? "POSTING_EFFECTIVE"
                    : kind.equals("ADDITIONAL_CHARGE") ? "ADDITIONAL_CHARGE_GRANTED" : "SEAT_HOLDER_CHANGED";
            audit.record(ctx, securityChain(ctx), type,
                    payload("assignmentId", a.id(), "seatId", a.seatId(), "personId", a.personId(), "kind", kind,
                            "validFrom", String.valueOf(from), "validTo", a.validTo() == null ? null : a.validTo().toString(),
                            "actedForSeatId", a.actedForSeatId(), "orderRef", a.orderRef(), "actor", ctx.actor()));
            return a;
        });
    }

    @Override
    public synchronized Assignment endAssignment(RequestContext ctx, String id, LocalDate validTo, String reason) {
        return tx.inTx(() -> {
            String t = tid(ctx);
            Assignment cur = assignment(ctx, id);
            LocalDate end = dates.orToday(validTo, t);
            if (end.isBefore(cur.validFrom())) {
                throw invalid("validTo", "validTo must not be before validFrom");
            }
            Assignment a = new Assignment(id, t, cur.seatId(), cur.personId(), cur.kind(), cur.validFrom(), end,
                    cur.actedForSeatId(), cur.orderRef(), cur.createdBy(), cur.createdAt(), true);
            assignments.in(t).put(id, a);
            audit.record(ctx, securityChain(ctx), "SEAT_HOLDER_CHANGED",
                    payload("assignmentId", id, "seatId", a.seatId(), "personId", a.personId(), "change", "ENDED",
                            "validTo", end.toString(), "reason", reason, "actor", ctx.actor()));
            return a;
        });
    }

    private Stream<Assignment> activeAssignments(String t, LocalDate on) {
        return assignments.in(t).values().stream().filter(a -> activeOn(a.validFrom(), a.validTo(), on));
    }

    // ------------------------------------------------------------------ delegations

    @Override
    public Page<Delegation> delegations(RequestContext ctx, AdminFilter f, Page.PageRequest page) {
        AdminFilter filter = filter(f);
        Stream<Delegation> s = delegations.in(tid(ctx)).values().stream()
                .filter(d -> filter.seatId() == null || filter.seatId().equals(d.seatId()) || filter.seatId().equals(d.toSeatId()))
                .filter(d -> filter.personId() == null || filter.personId().equals(d.fromPersonId())
                        || filter.personId().equals(d.toPersonId()))
                .filter(d -> filter.kind() == null || filter.kind().equals(d.kind()))
                .filter(d -> filter.activeOn() == null || (!d.revoked() && activeOn(d.validFrom(), d.validTo(), filter.activeOn())))
                .filter(d -> Boolean.TRUE.equals(filter.includeInactive()) || !d.revoked());
        return slice(s.sorted(Comparator.comparing(Delegation::validFrom).thenComparing(Delegation::id)), page);
    }

    @Override
    public Delegation delegation(RequestContext ctx, String id) {
        return find(delegations.in(tid(ctx)), id, "Delegation");
    }

    @Override
    public synchronized Delegation createDelegation(RequestContext ctx, Delegation in) {
        return tx.inTx(() -> {
            String t = tid(ctx);
            String kind = oneOf(in.kind(), "kind", DELEGATION_KINDS);
            LocalDate from = dates.orToday(in.validFrom(), t);
            if (in.validTo() != null && in.validTo().isBefore(from)) {
                throw invalid("validTo", "validTo must not be before validFrom");
            }
            JdbcSupport.decimal(in.amountLimit(), "amountLimit");
            if (kind.equals("POWER")) {
                required(in.seatId(), "seatId");
                if ((in.decisionKey() == null || in.decisionKey().isBlank()) && in.amountLimit() == null) {
                    throw invalid("decisionKey", "a POWER delegation needs decisionKey or amountLimit (IX.5 rule 3)");
                }
            } else if (in.toPersonId() == null && in.toSeatId() == null) {
                throw invalid("toPersonId", "a TASK delegation needs toPersonId or toSeatId");
            }
            for (String seatId : new String[]{in.seatId(), in.toSeatId()}) {
                if (seatId != null && !seats.in(t).containsKey(seatId)) {
                    throw invalid("seatId", "seat " + seatId + " does not exist");
                }
            }
            Delegation d = new Delegation(newId(), t, kind, in.seatId(), in.toSeatId(), in.fromPersonId(), in.toPersonId(),
                    in.decisionKey(), in.decisionVersion(), in.caseType(), in.amountLimit(), in.currency(), from,
                    in.validTo(), in.orderRef(), false, ctx.principalId(), Instant.now());
            delegations.in(t).put(d.id(), d);
            audit.record(ctx, securityChain(ctx), "DELEGATION_GRANTED",
                    payload("delegationId", d.id(), "kind", kind, "seatId", d.seatId(), "toSeatId", d.toSeatId(),
                            "toPersonId", d.toPersonId(), "decisionKey", d.decisionKey(), "amountLimit", d.amountLimit(),
                            "validFrom", from.toString(), "validTo", d.validTo() == null ? null : d.validTo().toString(),
                            "orderRef", d.orderRef(), "actor", ctx.actor()));
            return d;
        });
    }

    @Override
    public synchronized Delegation revokeDelegation(RequestContext ctx, String id, String reason) {
        return tx.inTx(() -> {
            String t = tid(ctx);
            Delegation c = delegation(ctx, id);
            if (c.revoked()) {
                return c;
            }
            Delegation d = new Delegation(id, t, c.kind(), c.seatId(), c.toSeatId(), c.fromPersonId(), c.toPersonId(),
                    c.decisionKey(), c.decisionVersion(), c.caseType(), c.amountLimit(), c.currency(), c.validFrom(),
                    c.validTo(), c.orderRef(), true, c.createdBy(), c.createdAt());
            delegations.in(t).put(id, d);
            audit.record(ctx, securityChain(ctx), "DELEGATION_REVOKED",
                    payload("delegationId", id, "reason", reason, "actor", ctx.actor()));
            return d;
        });
    }

    // ------------------------------------------------------------------ policies

    @Override
    public Page<Policy> policies(RequestContext ctx, AdminFilter f, Page.PageRequest page) {
        AdminFilter filter = filter(f);
        Stream<Policy> s = policies.in(tid(ctx)).values().stream()
                .filter(p -> filter.kind() == null || filter.kind().equals(p.effect()) || filter.kind().equals(p.resourceType()))
                .filter(p -> filter.personId() == null || filter.personId().equals(p.principalId()))
                .filter(p -> filter.seatId() == null || ("SEAT".equals(p.principalType()) && filter.seatId().equals(p.principalId())))
                .filter(p -> matches(filter.q(), p.principalId(), p.resourceType(), p.resourceId()));
        return slice(s.sorted(Comparator.comparing(Policy::createdAt).thenComparing(Policy::id)), page);
    }

    @Override
    public synchronized Policy createPolicy(RequestContext ctx, Policy in) {
        return tx.inTx(() -> {
            String t = tid(ctx);
            String effect = oneOf(in.effect(), "effect", EFFECTS);
            String principalType = in.principalType() == null ? "USER" : oneOf(in.principalType(), "principalType", PRINCIPAL_TYPES);
            required(in.principalId(), "principalId");
            required(in.resourceType(), "resourceType");
            if (in.actions() == null || in.actions().isEmpty() || in.actions().stream().anyMatch(a -> a == null || a.isBlank())) {
                throw invalid("actions", "actions must be a non-empty list");
            }
            long version = nextPolicyVersion(t);
            Policy p = new Policy(newId(), t, effect, principalType, in.principalId(), in.resourceType(),
                    in.resourceId() == null || in.resourceId().isBlank() ? "*" : in.resourceId(),
                    List.copyOf(in.actions()), in.conditionId(), version, ctx.principalId(), Instant.now());
            policies.in(t).put(p.id(), p);
            audit.record(ctx, securityChain(ctx), effect.equals("DENY") ? "DENY_CHANGED" : "GRANT_CHANGED",
                    payload("change", "ADDED", "policyId", p.id(), "principalType", principalType, "principalId", p.principalId(),
                            "resourceType", p.resourceType(), "resourceId", p.resourceId(), "actions", p.actions(),
                            "policy_version", version, "actor", ctx.actor()));
            return p;
        });
    }

    @Override
    public synchronized void deletePolicy(RequestContext ctx, String id, String reason) {
        tx.runInTx(() -> {
            String t = tid(ctx);
            Policy p = find(policies.in(t), id, "Policy");
            policies.in(t).remove(id);
            long version = nextPolicyVersion(t);
            audit.record(ctx, securityChain(ctx), p.effect().equals("DENY") ? "DENY_CHANGED" : "GRANT_CHANGED",
                    payload("change", "REMOVED", "policyId", id, "principalId", p.principalId(), "resourceType", p.resourceType(),
                            "resourceId", p.resourceId(), "reason", reason, "policy_version", version, "actor", ctx.actor()));
        });
    }

    @Override
    public PolicyVersion policyVersion(RequestContext ctx) {
        String t = tid(ctx);
        long v = tx.withConnection(c -> Jdbc.one(c, "SELECT policy_version FROM AUTH_POLICY_VERSION WHERE tenant_id=?",
                rs -> rs.getLong(1), t).orElse(0L));
        return new PolicyVersion(t, v);
    }

    /** VI.4 rule 5: increments {@code AUTH_POLICY_VERSION} in the caller's transaction (row-locked). */
    private long nextPolicyVersion(String t) {
        return tx.withConnection(c -> {
            var cur = Jdbc.one(c, tx.dialect().lockingSelect("policy_version", "AUTH_POLICY_VERSION", "tenant_id=?"),
                    rs -> rs.getLong(1), t);
            if (cur.isPresent()) {
                long next = cur.get() + 1;
                Jdbc.update(c, "UPDATE AUTH_POLICY_VERSION SET policy_version=? WHERE tenant_id=?", next, t);
                return next;
            }
            if (!Jdbc.insertIfAbsent(c, tx.dialect(),
                    "INSERT INTO AUTH_POLICY_VERSION(tenant_id, policy_version) VALUES (?,?)", t, 1L)) {
                // A concurrent first insert won: increment that row instead.
                Jdbc.update(c, "UPDATE AUTH_POLICY_VERSION SET policy_version=policy_version+1 WHERE tenant_id=?", t);
                return Jdbc.one(c, "SELECT policy_version FROM AUTH_POLICY_VERSION WHERE tenant_id=?",
                        rs -> rs.getLong(1), t).orElse(1L);
            }
            return 1L;
        });
    }

    // ------------------------------------------------------------------ tenants

    @Override
    public Page<Tenant> tenants(RequestContext ctx, AdminFilter f, Page.PageRequest page) {
        AdminFilter filter = filter(f);
        boolean admin = JdbcSupport.isAdmin(ctx);
        Stream<Tenant> s = tenants.in(DocumentStore.GLOBAL).values().stream()
                .filter(x -> admin || x.id().equals(ctx.tenantId()))
                .filter(x -> matches(filter.q(), x.id(), x.name()))
                .filter(x -> filter.kind() == null || filter.kind().equals(x.status()));
        return slice(s.sorted(Comparator.comparing(Tenant::id)), page);
    }

    @Override
    public Tenant tenant(RequestContext ctx, String id) {
        Tenant x = tenants.in(DocumentStore.GLOBAL).get(id);
        if (x == null || (!JdbcSupport.isAdmin(ctx) && !id.equals(ctx.tenantId()))) {
            throw ApiProblem.notFound("Tenant", id);
        }
        return x;
    }

    @Override
    public synchronized Tenant createTenant(RequestContext ctx, Tenant in) {
        return tx.inTx(() -> {
            String id = in.id() == null || in.id().isBlank() ? newId() : in.id();
            if (!id.matches("[A-Za-z0-9._-]{1,64}")) {
                throw invalid("id", "tenant id must be 1..64 characters of [A-Za-z0-9._-]");
            }
            required(in.name(), "name");
            if (tenants.in(DocumentStore.GLOBAL).containsKey(id)) {
                throw ApiProblem.conflict("ALREADY_EXISTS", "Tenant " + id + " exists");
            }
            Tenant x = new Tenant(id, in.name(),
                    in.status() == null ? "DRAFT" : oneOf(in.status(), "status", TENANT_STATUS),
                    in.profile() == null ? "STANDARD" : oneOf(in.profile(), "profile", PROFILES),
                    in.isolation() == null ? "SHARED" : oneOf(in.isolation(), "isolation", ISOLATION),
                    in.defaultLanguage() == null ? "en" : in.defaultLanguage(),
                    in.languages() == null ? List.of("en", "hi") : List.copyOf(in.languages()),
                    in.attributes(), Instant.now(), 1);
            tenants.in(DocumentStore.GLOBAL).put(id, x);
            RequestContext target = withTenant(ctx, id);
            audit.record(target, "sec:" + id, "TENANT_CREATED",
                    payload("tenantId", id, "name", x.name(), "profile", x.profile(), "isolation", x.isolation(),
                            "actor", ctx.actor()));
            return x;
        });
    }

    @Override
    public synchronized Tenant updateTenant(RequestContext ctx, String id, Tenant in) {
        return tx.inTx(() -> {
            Tenant cur = tenant(ctx, id);
            checkRevision(in.revision(), cur.revision(), "Tenant", id);
            Tenant x = new Tenant(id, in.name() == null ? cur.name() : in.name(),
                    in.status() == null ? cur.status() : oneOf(in.status(), "status", TENANT_STATUS),
                    in.profile() == null ? cur.profile() : oneOf(in.profile(), "profile", PROFILES),
                    in.isolation() == null ? cur.isolation() : oneOf(in.isolation(), "isolation", ISOLATION),
                    in.defaultLanguage() == null ? cur.defaultLanguage() : in.defaultLanguage(),
                    in.languages() == null ? cur.languages() : List.copyOf(in.languages()),
                    in.attributes() == null ? cur.attributes() : in.attributes(), cur.createdAt(), cur.revision() + 1);
            tenants.in(DocumentStore.GLOBAL).put(id, x);
            String type = !cur.status().equals(x.status()) && x.status().equals("ACTIVE") ? "TENANT_ACTIVATED" : "TENANT_CONFIGURED";
            audit.record(withTenant(ctx, id), "sec:" + id, type,
                    payload("tenantId", id, "status", x.status(), "profile", x.profile(), "actor", ctx.actor()));
            return x;
        });
    }

    // ------------------------------------------------------------------ helpers

    private static String tid(RequestContext ctx) {
        return JdbcSupport.tenant(ctx);
    }

    private static RequestContext withTenant(RequestContext ctx, String tenantId) {
        return new RequestContext(tenantId, ctx.principalId(), ctx.groups(), ctx.seatId(), ctx.actingForSeatId(),
                ctx.idempotencyKey(), ctx.traceId(), ctx.clientIp(), ctx.authMethod());
    }

    private <T> Page<T> slice(Stream<T> s, Page.PageRequest page) {
        List<T> all = new ArrayList<>(s.toList());
        return Page.slice(all, page, props.getCountCap());
    }

    private static <T> T find(DocMap<T>.View m, String id, String what) {
        T v = id == null ? null : m.get(id);
        if (v == null) {
            throw ApiProblem.notFound(what, id);
        }
        return v;
    }

    private static AdminFilter filter(AdminFilter f) {
        return f == null ? new AdminFilter(null, null, null, null, null, null, null, null) : f;
    }

    private static boolean matches(String q, String... values) {
        if (q == null || q.isBlank()) {
            return true;
        }
        String needle = q.toLowerCase(Locale.ROOT);
        Predicate<String> p = v -> v != null && v.toLowerCase(Locale.ROOT).contains(needle);
        for (String v : values) {
            if (p.test(v)) {
                return true;
            }
        }
        return false;
    }

    private static boolean activeOn(LocalDate from, LocalDate to, LocalDate on) {
        return (from == null || !on.isBefore(from)) && (to == null || !on.isAfter(to));
    }

    private static boolean overlaps(LocalDate aFrom, LocalDate aTo, LocalDate bFrom, LocalDate bTo) {
        LocalDate maxDate = LocalDate.MAX;
        LocalDate ae = aTo == null ? maxDate : aTo;
        LocalDate be = bTo == null ? maxDate : bTo;
        LocalDate as = aFrom == null ? LocalDate.MIN : aFrom;
        LocalDate bs = bFrom == null ? LocalDate.MIN : bFrom;
        return !as.isAfter(be) && !bs.isAfter(ae);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}

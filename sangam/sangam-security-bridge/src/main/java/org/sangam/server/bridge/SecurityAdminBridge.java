// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.security.SangamSecurityException;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.rest.port.AdminPort;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;
import org.sangam.security.SangamSecurity;
import org.sangam.security.admin.PolicyAdminService;
import org.sangam.security.admin.SecurityDirectoryService;
import org.sangam.security.admin.TenantAdminService;
import org.sangam.security.org.OrgModel;
import org.sangam.security.policy.AuthorizedQueryBuilder;
import org.sangam.security.policy.Permission;

/**
 * sangam-rest {@link AdminPort} on sangam-security (IX.5, VI.4, EC-17). Writes go through the security services, which
 * check the caller's admin permission on the object, maintain {@code AUTH_EFFECTIVE} and {@code policy_version} in
 * the same transaction and write the class-B events to {@code sec:<tenant>}. Lists come from
 * {@code SecurityDirectoryService} (paged and tenant-bound in SQL, with the READ object check and a capped total, A-16);
 * this bridge runs no SQL of its own. Unit renames are effective-dated through {@code OrgUnitService.renameUnit}
 * ({@code SEC_ORG_UNIT_NAME}); {@code attributes.effectiveFrom} (ISO instant) and {@code attributes.orderRef} are honoured.
 *
 * <p>Mapping notes: {@code OrgUnit.level} ↔ {@code level_type}; {@code names.hi} ↔ {@code name_hi};
 * {@code Seat.hierarchyLevel} ↔ {@code rank_no}; {@code reportsToSeatId} ↔ escalation seat; {@code attributes.pool}
 * ↔ pool flag. Assignments: SUBSTANTIVE ↔ posting REGULAR, ADDITIONAL_CHARGE ↔ additional charge,
 * LEAVE_SUBSTITUTE ↔ a leave record of the seat's substantive holder with this person as substitute.
 * Person-to-person and power delegations are not part of the security posting model; they stay with the default
 * adapter ({@code fallback}). Unit deletion has no security service operation and is refused with
 * {@code 422 OPERATION_NOT_SUPPORTED}; seats are abolished, not deleted.
 */
public final class SecurityAdminBridge implements AdminPort {

    /** The principal types {@link #subject} maps; anything else is refused, not stored as a user grant. */
    private static final List<String> PRINCIPAL_TYPES = List.of("USER", "GROUP", "SEAT", "ROLE");

    private final SangamSecurity security;
    private final TxSupport tx;
    private final AdminPort fallback;
    private final RestProperties props;

    public SecurityAdminBridge(SangamSecurity security, TxSupport tx, AdminPort fallback, RestProperties props) {
        this.security = security;
        this.tx = tx;
        this.fallback = fallback;
        this.props = props;
    }

    /** Drops the cached quota now and again after commit, so a reader never keeps a pre-commit value (was QuotaWiring.invalidateAfterCommit). */
    private void invalidateQuota(String tenantId) {
        org.sangam.core.quota.TenantQuotas.current().invalidate(tenantId);
        tx.afterCommit(() -> org.sangam.core.quota.TenantQuotas.current().invalidate(tenantId));
    }

    // ------------------------------------------------------------------ org units

    @Override
    public Page<OrgUnit> orgUnits(RequestContext ctx, AdminFilter f, Page.PageRequest page) {
        var filter = new SecurityDirectoryService.OrgUnitFilter(f == null ? null : f.parentId(),
                f == null ? null : f.kind(), f == null ? null : f.q(),
                f != null && Boolean.TRUE.equals(f.includeInactive()), f == null ? null : at(f.activeOn()));
        var p = run(c -> security.directory().orgUnits(c, actor(ctx), filter, page.firstResult(), page.size(), cap()));
        return rest(p, u -> unit(ctx.tenantId(), u.unitId(), u.levelType(), u.nameEn(), u.nameHi(), u.status(),
                u.parentId()));
    }

    @Override
    public OrgUnit orgUnit(RequestContext ctx, String id) {
        return run(c -> security.orgUnits().unitAt(c, ctx.tenantId(), id, dbNow(c))
                .map(u -> unit(ctx.tenantId(), u.unitId(), u.levelType(), u.nameEn(), u.nameHi(), u.status(), u.parentId()))
                .orElseThrow(() -> ApiProblem.notFound("org unit", id)));
    }

    @Override
    public OrgUnit createOrgUnit(RequestContext ctx, OrgUnit o) {
        require(o.name(), "name");
        require(o.level(), "level");
        String id = o.id() != null && !o.id().isBlank() ? o.id() : o.code() != null ? o.code() : Hashes.uuidV7().toString();
        String nameHi = o.names() == null ? null : o.names().get("hi");
        run(c -> security.orgUnits().createUnit(c, actor(ctx), id, o.level(), o.parentId(), o.name(), nameHi, dbNow(c)));
        return orgUnit(ctx, id);
    }

    @Override
    public OrgUnit updateOrgUnit(RequestContext ctx, String id, OrgUnit o) {
        OrgUnit current = orgUnit(ctx, id);
        String nameHi = o.names() == null ? null : o.names().get("hi");
        boolean renamed = (o.name() != null && !o.name().equals(current.name()))
                || (nameHi != null && !nameHi.equals(current.names() == null ? null : current.names().get("hi")));
        if (renamed) {
            // Effective-dated rename (SEC_ORG_UNIT_NAME, AJ-02); the original names stay on SEC_ORG_UNIT.
            String nameEn = o.name() != null ? o.name() : current.name();
            String orderRef = o.attributes() == null || o.attributes().get("orderRef") == null ? null
                    : String.valueOf(o.attributes().get("orderRef"));
            Instant given = o.attributes() != null && o.attributes().get("effectiveFrom") != null
                    ? Instant.parse(String.valueOf(o.attributes().get("effectiveFrom"))) : null;
            run(c -> {
                security.orgUnits().renameUnit(c, actor(ctx), id, nameEn, nameHi, given != null ? given : dbNow(c), orderRef);
                return null;
            });
        }
        if (o.parentId() != null && !o.parentId().equals(current.parentId())) {
            run(c -> {
                security.orgUnits().moveUnit(c, actor(ctx), id, o.parentId(), dbNow(c));
                return null;
            });
        }
        return orgUnit(ctx, id);
    }

    @Override
    public void deleteOrgUnit(RequestContext ctx, String id, String reason) {
        throw ApiProblem.unprocessable("OPERATION_NOT_SUPPORTED",
                "Org units are effective-dated and never deleted; move or abolish their seats instead");
    }

    // ------------------------------------------------------------------ org levels (EV-M-0923)

    /** A level type: letters, digits, '_' or '-', at most 64 (SEC_ORG_LEVEL.level_type, e.g. OFFICE, DISTRICT). */
    private static final java.util.regex.Pattern LEVEL_TYPE = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{1,64}");

    @Override
    public OrgLevels orgLevels(RequestContext ctx) {
        return new OrgLevels(ctx.tenantId(), run(c -> security.orgUnits().levels(c, ctx.tenantId())));
    }

    @Override
    public OrgLevels defineOrgLevels(RequestContext ctx, OrgLevels body) {
        if (body != null && body.tenantId() != null && !body.tenantId().equals(ctx.tenantId())) {
            throw ApiProblem.forbidden("TENANT_MISMATCH", "org levels are defined only for the caller's own tenant");
        }
        List<String> levels = body == null ? null : body.levels();
        if (levels == null || levels.isEmpty()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "levels is required").with("field", "levels");
        }
        for (String l : levels) {
            if (l == null || !LEVEL_TYPE.matcher(l).matches()) {
                throw ApiProblem.badRequest("VALIDATION_FAILED", "not a valid level type: " + l).with("field", "levels");
            }
        }
        if (Set.copyOf(levels).size() != levels.size()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "a level type appears twice").with("field", "levels");
        }
        run(c -> {
            security.orgUnits().defineLevels(c, actor(ctx), List.copyOf(levels));
            return null;
        });
        return orgLevels(ctx);
    }

    // ------------------------------------------------------------------ seats

    @Override
    public Page<Seat> seats(RequestContext ctx, AdminFilter f, Page.PageRequest page) {
        var filter = new SecurityDirectoryService.SeatFilter(f == null ? null : f.orgUnitId(), null,
                f == null ? null : f.q(), f != null && Boolean.TRUE.equals(f.includeInactive()),
                f == null ? null : at(f.activeOn()));
        var p = run(c -> security.directory().seats(c, actor(ctx), filter, page.firstResult(), page.size(), cap()));
        return rest(p, s -> seat(ctx.tenantId(), s.seatId(), s.designation(), s.rankNo(), s.pool(),
                s.escalationSeatId(), s.status(), s.unitId()));
    }

    @Override
    public Seat seat(RequestContext ctx, String id) {
        return run(c -> security.seats().seatAt(c, ctx.tenantId(), id, dbNow(c))
                .map(s -> seat(ctx.tenantId(), s.seatId(), s.designation(), s.rankNo(), s.pool(), s.escalationSeatId(),
                        s.status(), s.unitId()))
                .orElseThrow(() -> ApiProblem.notFound("seat", id)));
    }

    @Override
    public Seat createSeat(RequestContext ctx, Seat s) {
        require(s.designation(), "designation");
        require(s.orgUnitId(), "orgUnitId");
        String id = s.id() != null && !s.id().isBlank() ? s.id() : s.code() != null ? s.code() : Hashes.uuidV7().toString();
        boolean pool = s.attributes() != null && Boolean.TRUE.equals(s.attributes().get("pool"));
        int rank = s.hierarchyLevel() == null ? 0 : s.hierarchyLevel();
        run(c -> security.seats().createSeat(c, actor(ctx), id, s.designation(), s.orgUnitId(), rank, pool,
                s.reportsToSeatId(), dbNow(c)));
        return seat(ctx, id);
    }

    @Override
    public Seat updateSeat(RequestContext ctx, String id, Seat s) {
        Seat current = seat(ctx, id);
        if (s.orgUnitId() != null && !s.orgUnitId().equals(current.orgUnitId())) {
            run(c -> {
                security.seats().moveSeat(c, actor(ctx), id, s.orgUnitId(), dbNow(c));
                return null;
            });
        }
        if (s.designation() != null && !s.designation().equals(current.designation())) {
            throw ApiProblem.unprocessable("OPERATION_NOT_SUPPORTED",
                    "A seat's designation is fixed; abolish the seat and create a new one (IX.5 rule 1)");
        }
        return seat(ctx, id);
    }

    @Override
    public void deleteSeat(RequestContext ctx, String id, String reason) {
        run(c -> {
            security.seats().abolishSeat(c, actor(ctx), id, dbNow(c));
            return null;
        });
    }

    // ------------------------------------------------------------------ assignments (postings, additional charge, leave)

    @Override
    public Page<Assignment> assignments(RequestContext ctx, AdminFilter f, Page.PageRequest page) {
        String kind = f == null || f.kind() == null ? null : postingKind(f.kind());
        var filter = new SecurityDirectoryService.PostingFilter(f == null ? null : f.seatId(),
                f == null ? null : f.personId(), kind, false, f == null ? null : at(f.activeOn()),
                f != null && Boolean.TRUE.equals(f.includeInactive()));
        Instant[] now = new Instant[1];
        var p = run(c -> {
            now[0] = dbNow(c); // the same database's now for every row of the page (SL-108)
            return security.directory().postings(c, actor(ctx), filter, page.firstResult(), page.size(), cap());
        });
        return rest(p, r -> {
            Assignment a = assignment(r.posting(), now[0]);
            return new Assignment(a.id(), a.tenantId(), a.seatId(), a.personId(), a.kind(), a.validFrom(), a.validTo(),
                    a.actedForSeatId(), a.orderRef(), r.createdBy(), r.createdAt(), a.ended() || r.revoked());
        });
    }

    private static String postingKind(String restKind) {
        return switch (restKind.toUpperCase(Locale.ROOT)) {
            case "SUBSTANTIVE" -> "REGULAR";
            case "ACTING" -> "ADDITIONAL_CHARGE";
            default -> restKind.toUpperCase(Locale.ROOT);
        };
    }

    @Override
    public Assignment assignment(RequestContext ctx, String id) {
        return run(c -> security.postings().posting(c, ctx.tenantId(), id).map(p -> assignment(p, dbNow(c)))
                .orElseThrow(() -> ApiProblem.notFound("assignment", id)));
    }

    @Override
    public Assignment createAssignment(RequestContext ctx, Assignment a) {
        require(a.seatId(), "seatId");
        require(a.personId(), "personId");
        require(a.validFrom(), "validFrom");
        String kind = a.kind() == null ? "SUBSTANTIVE" : a.kind().toUpperCase(Locale.ROOT);
        Instant from = start(a.validFrom());
        Instant to = a.validTo() == null ? null : start(a.validTo().plusDays(1));
        SecurityIdentity actor = actor(ctx);
        return switch (kind) {
            case "SUBSTANTIVE" -> run(c -> assignment(security.postings().post(c, actor, a.seatId(), a.personId(),
                    OrgModel.PostingKind.REGULAR, from, to, a.orderRef(), null), dbNow(c)));
            case "ADDITIONAL_CHARGE", "ACTING" -> run(c -> assignment(security.postings().grantAdditionalCharge(c, actor,
                    a.seatId(), a.personId(), from, to, a.orderRef(), null), dbNow(c)));
            case "LEAVE_SUBSTITUTE" -> {
                String seat = a.actedForSeatId() != null ? a.actedForSeatId() : a.seatId();
                if (to == null) {
                    throw ApiProblem.badRequest("VALIDATION_FAILED", "a leave substitution needs validTo")
                            .with("field", "validTo");
                }
                OrgModel.Leave leave = run(c -> {
                    String holder = security.postings().holdersAt(c, ctx.tenantId(), seat, from).stream()
                            .filter(h -> !h.actingFor()).map(h -> h.personId()).findFirst()
                            .orElseThrow(() -> ApiProblem.unprocessable("SEAT_VACANT",
                                    "Seat " + seat + " has no substantive holder on " + a.validFrom()));
                    return security.postings().recordLeave(c, actor, holder, from, to, a.personId(), ctx.principalId());
                });
                yield new Assignment(leave.leaveId(), ctx.tenantId(), seat, a.personId(), "LEAVE_SUBSTITUTE",
                        date(leave.validFrom()), date(leave.validTo()), seat, a.orderRef(), ctx.principalId(),
                        Instant.now(), false);
            }
            default -> throw ApiProblem.badRequest("VALIDATION_FAILED",
                    "kind must be SUBSTANTIVE, ADDITIONAL_CHARGE, LEAVE_SUBSTITUTE or ACTING").with("field", "kind");
        };
    }

    @Override
    public Assignment endAssignment(RequestContext ctx, String id, LocalDate validTo, String reason) {
        run(c -> {
            // SL-83: "now" is the product database's time (A-1), as every effective-dated read uses; the JVM clock can
            // be behind the database, so a posting ended at JVM time could still be in force for the next read
            Instant at = validTo == null ? dbNow(c) : start(validTo.plusDays(1));
            security.postings().endPosting(c, actor(ctx), id, at);
            return null;
        });
        return assignment(ctx, id);
    }

    // ------------------------------------------------------------------ delegations (default adapter)

    @Override
    public Page<Delegation> delegations(RequestContext ctx, AdminFilter filter, Page.PageRequest page) {
        return fallback.delegations(ctx, filter, page);
    }

    @Override
    public Delegation delegation(RequestContext ctx, String id) {
        return fallback.delegation(ctx, id);
    }

    @Override
    public Delegation createDelegation(RequestContext ctx, Delegation delegation) {
        return fallback.createDelegation(ctx, delegation);
    }

    @Override
    public Delegation revokeDelegation(RequestContext ctx, String id, String reason) {
        return fallback.revokeDelegation(ctx, id, reason);
    }

    // ------------------------------------------------------------------ policies

    /**
     * Policies come paged from {@code SecurityDirectoryService.policies}. A REST policy row is one (definition,
     * subject) pair, so a definition with several subjects yields several rows on its page; the capped total counts
     * definitions.
     */
    @Override
    public Page<Policy> policies(RequestContext ctx, AdminFilter f, Page.PageRequest page) {
        long version = policyVersion(ctx).policyVersion();
        var filter = new SecurityDirectoryService.PolicyFilter(null, null, f == null ? null : f.q());
        var p = run(c -> security.directory().policies(c, actor(ctx), filter, page.firstResult(), page.size(), cap()));
        List<Policy> out = new ArrayList<>();
        for (SecurityDirectoryService.PolicyRow row : p.items()) {
            PolicyAdminService.PolicyDefinition d = row.definition();
            for (String subject : d.subjects()) {
                out.add(policy(ctx.tenantId(), d, subject, version));
            }
        }
        return Page.of(out, p.total().value(), p.total().capped());
    }

    @Override
    public Policy createPolicy(RequestContext ctx, Policy p) {
        require(p.principalId(), "principalId");
        require(p.resourceType(), "resourceType");
        if (p.actions() == null || p.actions().isEmpty()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "actions must not be empty").with("field", "actions");
        }
        // UI-DEMO sweep S13, fail closed: every field the request carries is applied or refused, never dropped. An
        // effect outside the contract's GRANT/DENY used to become a grant, an unknown principal type a user grant,
        // and a conditionId was dropped, which turned a conditional grant into an unconditional one. This API
        // carries no condition definition, so a policy with a condition cannot be created through it.
        PolicyAdminService.Effect effect = switch (p.effect() == null ? "" : p.effect().trim().toUpperCase(Locale.ROOT)) {
            case "GRANT" -> PolicyAdminService.Effect.ALLOW;
            case "DENY" -> PolicyAdminService.Effect.DENY;
            default -> throw ApiProblem.badRequest("VALIDATION_FAILED", "effect must be GRANT or DENY")
                    .with("field", "effect");
        };
        if (p.principalType() != null && !PRINCIPAL_TYPES.contains(p.principalType().trim().toUpperCase(Locale.ROOT))) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "principalType must be one of " + PRINCIPAL_TYPES)
                    .with("field", "principalType");
        }
        if (p.conditionId() != null && !p.conditionId().isBlank()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED",
                    "a policy condition cannot be set through this API; the policy was not created").with("field", "conditionId");
        }
        Set<Permission> perms = EnumSet.noneOf(Permission.class);
        for (String a : p.actions()) {
            try {
                perms.add(Permission.parse(a));
            } catch (IllegalArgumentException | NullPointerException e) {
                throw ApiProblem.badRequest("VALIDATION_FAILED", "unknown action: " + a).with("field", "actions");
            }
        }
        String id = p.id() != null && !p.id().isBlank() ? p.id() : Hashes.uuidV7().toString();
        String subject = subject(p.principalType(), p.principalId());
        PolicyAdminService.PolicyDefinition def = new PolicyAdminService.PolicyDefinition(id, id, effect,
                List.of(subject), p.resourceType(), p.resourceId() == null ? "*" : p.resourceId(), perms, null);
        PolicyAdminService.PolicyChange change = run(c -> security.policies().create(c, actor(ctx), def,
                "admin API"));
        long version = policyVersion(ctx).policyVersion();
        return new Policy(change.policyId(), ctx.tenantId(), effect == PolicyAdminService.Effect.DENY ? "DENY" : "GRANT",
                principalType(subject), principalId(subject), def.resourceType(), def.resourceId(),
                perms.stream().map(Enum::name).toList(), null, version, ctx.principalId(), Instant.now());
    }

    @Override
    public void deletePolicy(RequestContext ctx, String id, String reason) {
        run(c -> security.policies().delete(c, actor(ctx), id, reason));
    }

    @Override
    public PolicyVersion policyVersion(RequestContext ctx) {
        return new PolicyVersion(ctx.tenantId(), run(c -> security.authorization().policyVersion(c, ctx.tenantId())));
    }

    // ------------------------------------------------------------------ tenants

    @Override
    public Page<Tenant> tenants(RequestContext ctx, AdminFilter filter, Page.PageRequest page) {
        SecurityIdentity actor = actor(ctx);
        List<Tenant> all;
        if (actor.hasRole(TenantAdminService.PLATFORM_ADMIN_ROLE)) {
            all = run(c -> security.tenants().list(c, actor).stream().map(SecurityAdminBridge::tenant).toList());
        } else if (actor.system()) {
            // SL-117: background schedules read the directory as the system principal, which list() (platform admins
            // only) refuses; the dedicated read-only listing gives ids and statuses, no admin data.
            all = run(c -> security.tenants().listForSystem(c, actor).stream().map(SecurityAdminBridge::systemTenant).toList());
        } else {
            all = run(c -> security.tenants().find(c, ctx.tenantId()).map(SecurityAdminBridge::tenant).stream().toList());
        }
        return Page.slice(all, page, props.getCountCap());
    }

    @Override
    public Tenant tenant(RequestContext ctx, String id) {
        SecurityIdentity actor = actor(ctx);
        if (actor.system()) {
            // decision 97: the shared-instance isolation check reads as the system principal, before any user
            // permission is known; it gets the read-only system view (id, status, isolation), no admin data.
            return run(c -> security.tenants().findForSystem(c, actor, id).map(SecurityAdminBridge::systemTenant)
                    .orElseThrow(() -> ApiProblem.notFound("tenant", id)));
        }
        if (!id.equals(ctx.tenantId()) && !actor(ctx).hasRole(TenantAdminService.PLATFORM_ADMIN_ROLE)) {
            throw ApiProblem.notFound("tenant", id);
        }
        return run(c -> security.tenants().find(c, id).map(SecurityAdminBridge::tenant)
                .orElseThrow(() -> ApiProblem.notFound("tenant", id)));
    }

    @Override
    public Tenant createTenant(RequestContext ctx, Tenant t) {
        require(t.id(), "id");
        require(t.name(), "name");
        String nameHi = t.attributes() == null || t.attributes().get("nameHi") == null ? null
                : String.valueOf(t.attributes().get("nameHi"));
        // decision 97: the requested isolation is stored (null = the instance default); a dedicated instance refuses others
        return run(c -> tenant(security.tenants().createTenant(c, actor(ctx), t.id(), t.name(), nameHi, null,
                t.profile(), Map.of(), t.isolation())));
    }

    @Override
    public Tenant updateTenant(RequestContext ctx, String id, Tenant t) {
        SecurityIdentity actor = actor(ctx);
        Tenant current = tenant(ctx, id);
        // decision 97: the isolation is fixed at creation; moving a tenant is a data move, never a field update
        if (t.isolation() != null && !t.isolation().isBlank()
                && !t.isolation().trim().equalsIgnoreCase(current.isolation())) {
            throw ApiProblem.conflict("ISOLATION_IMMUTABLE", "tenant " + id + " has isolation " + current.isolation()
                    + "; it is not changed by an update").with("field", "isolation");
        }
        run(c -> {
            if (id.equals(ctx.tenantId()) && t.attributes() != null) {
                security.tenants().configure(c, actor, new LinkedHashMap<>(t.attributes()), null, null);
            }
            if (t.status() != null && !t.status().equalsIgnoreCase(current.status())) {
                switch (t.status().toUpperCase(Locale.ROOT)) {
                    case "ACTIVE" -> activate(c, ctx, id, current, null);
                    case "SUSPENDED" -> security.tenants().suspend(c, actor, id, "admin API");
                    default -> throw ApiProblem.badRequest("VALIDATION_FAILED", "status must be ACTIVE or SUSPENDED")
                            .with("field", "status");
                }
            }
            return null;
        });
        // SL-43: the tenant row (quotas included) changed; the cached quota overrides of this node go now
        invalidateQuota(id);
        return tenant(ctx, id);
    }

    @Override
    public TenantReadiness validateTenant(RequestContext ctx, String id) {
        Tenant current = tenant(ctx, id);
        return run(c -> {
            List<String> required = security.tenants().requiredChecklist(c, id);
            List<String> missing = security.tenants().missingEvidence(c, id);
            return new TenantReadiness(id, current.status(), missing.isEmpty(), required, missing);
        });
    }

    @Override
    public Tenant activateTenant(RequestContext ctx, String id, String reason) {
        Tenant current = tenant(ctx, id);
        run(c -> {
            activate(c, ctx, id, current, reason);
            return null;
        });
        invalidateQuota(id);
        return tenant(ctx, id);
    }

    @Override
    public Tenant suspendTenant(RequestContext ctx, String id, String reason) {
        tenant(ctx, id);
        run(c -> {
            security.tenants().suspend(c, actor(ctx), id, reason);
            return null;
        });
        invalidateQuota(id);
        return tenant(ctx, id);
    }

    /**
     * A suspended tenant is resumed (platform administrator, TenantAdminService#resume). Any other tenant is activated
     * through TenantAdminService#activate, which acts on the caller's own tenant only (AJ-01 step 10: its administrator,
     * once the checklist has evidence); naming another tenant is refused instead of activating the caller's. The
     * optional reason is stored in the TENANT_ACTIVATED event either way.
     */
    private void activate(Connection c, RequestContext ctx, String id, Tenant current, String reason) {
        if ("SUSPENDED".equalsIgnoreCase(current.status())) {
            security.tenants().resume(c, actor(ctx), id, reason);
        } else if (id.equals(ctx.tenantId())) {
            security.tenants().activate(c, actor(ctx), reason);
        } else {
            throw ApiProblem.conflict("TENANT_ACTIVATION_BY_OWN_ADMIN", "tenant " + id + " is activated by its own "
                    + "administrator once its go-live checklist has evidence (AJ-01 step 10); a suspended tenant is "
                    + "resumed by a platform administrator").with("field", "id");
        }
    }

    // ------------------------------------------------------------------ helpers

    private <T> T run(Function<Connection, T> work) {
        try {
            return tx.withReadConnection(work);
        } catch (SangamSecurityException e) {
            throw new ApiProblem(e.httpStatus(), e.code(), e.getMessage());
        }
    }

    private static SecurityIdentity actor(RequestContext ctx) {
        return SecurityIdentities.of(ctx);
    }

    /** Count cap for the directory (A-16), bounded by the directory's own page/count limit. */
    private int cap() {
        return (int) Math.min(props.getCountCap(), Integer.MAX_VALUE);
    }

    private static Instant at(LocalDate d) {
        return d == null ? null : start(d);
    }

    /** Security page → REST page, keeping the capped total ("more than N"). */
    static <S, T> Page<T> rest(AuthorizedQueryBuilder.Page<S> p, Function<S, T> mapper) {
        return Page.of(p.items().stream().map(mapper).toList(), p.total().value(), p.total().capped());
    }

    private static OrgUnit unit(String tenant, String id, String level, String nameEn, String nameHi, String status,
                                String parentId) {
        Map<String, String> names = new LinkedHashMap<>();
        if (nameEn != null) {
            names.put("en", nameEn);
        }
        if (nameHi != null) {
            names.put("hi", nameHi);
        }
        return new OrgUnit(id, tenant, parentId, id, nameEn, names, level, status == null || "ACTIVE".equalsIgnoreCase(status),
                Map.of(), 0);
    }

    private static Seat seat(String tenant, String id, String designation, int rank, boolean pool, String escalation,
                             String status, String unitId) {
        return new Seat(id, tenant, unitId, id, designation, designation, rank, escalation, List.of(),
                status == null || "ACTIVE".equalsIgnoreCase(status), Map.of("pool", pool), 0);
    }

    /** {@code ended} compares the stored (database-time) end with the database's now (SL-108). */
    private static Assignment assignment(OrgModel.Posting p, Instant dbNow) {
        return new Assignment(p.postingId(), p.tenantId(), p.seatId(), p.personId(), restKind(p.kind().name()),
                date(p.validFrom()), date(p.validTo()), p.kind().acting() ? p.seatId() : null, p.orderRef(), null, null,
                p.validTo() != null && !p.validTo().isAfter(dbNow));
    }

    /**
     * SL-83 / SL-108: "now" of every effective-dated read or write here is the product database's time (A-1,
     * {@link org.sangam.security.support.Sql#dbNow}, milliseconds), the time sangam-security's own reads use. The JVM
     * clock has finer precision and can differ, so an org unit or seat created "now" at JVM time was not yet in force for
     * the read that follows it. JVM time stays only for response metadata ({@code createdAt} of a returned record).
     */
    private Instant dbNow(java.sql.Connection c) {
        return org.sangam.security.support.Sql.dbNow(c, security.dialect());
    }

    private static String restKind(String postingKind) {
        if (postingKind == null) {
            return null;
        }
        return switch (postingKind) {
            case "REGULAR" -> "SUBSTANTIVE";
            case "ADDITIONAL_CHARGE" -> "ADDITIONAL_CHARGE";
            default -> postingKind;
        };
    }

    private static boolean activeOn(Assignment a, LocalDate on) {
        return (a.validFrom() == null || !a.validFrom().isAfter(on)) && (a.validTo() == null || !a.validTo().isBefore(on))
                && !a.ended();
    }

    /** SL-117, decision 97: a tenant as the system view gives it: id, status and isolation only. */
    static Tenant systemTenant(TenantAdminService.SystemTenant t) {
        return new Tenant(t.tenantId(), null, t.status(), null, t.isolation(), null, List.of(), Map.of(), null, 0);
    }

    private static Tenant tenant(TenantAdminService.Tenant t) {
        Map<String, Object> attributes = new LinkedHashMap<>(t.config() == null ? Map.of() : t.config());
        if (t.nameHi() != null) {
            attributes.put("nameHi", t.nameHi());
        }
        attributes.put("fourEyes", t.fourEyes());
        return new Tenant(t.tenantId(), t.nameEn(), t.status(), t.hostingProfile(), t.isolation(), "en",
                t.nameHi() == null ? List.of("en") : List.of("en", "hi"), attributes, null, 0);
    }

    private static Policy policy(String tenant, PolicyAdminService.PolicyDefinition d, String subject, long version) {
        return new Policy(d.policyId(), tenant, d.effect() == PolicyAdminService.Effect.DENY ? "DENY" : "GRANT",
                principalType(subject), principalId(subject), d.resourceType(), d.resourceId(),
                d.permissions().stream().map(Enum::name).toList(), d.condition() == null ? null : "condition", version,
                null, null);
    }

    private static String subject(String principalType, String principalId) {
        String type = principalType == null ? "USER" : principalType.toUpperCase(Locale.ROOT);
        return switch (type) {
            case "GROUP" -> SecurityIdentity.GROUP + principalId;
            case "SEAT" -> SecurityIdentity.SEAT + principalId;
            case "ROLE" -> SecurityIdentity.ROLE + principalId;
            default -> SecurityIdentity.USER + principalId;
        };
    }

    private static String principalType(String subject) {
        int i = subject.indexOf(':');
        return i < 0 ? "USER" : subject.substring(0, i).toUpperCase(Locale.ROOT);
    }

    private static String principalId(String subject) {
        int i = subject.indexOf(':');
        return i < 0 ? subject : subject.substring(i + 1);
    }

    private static boolean matches(String q, String... values) {
        String needle = q.toLowerCase(Locale.ROOT);
        for (String v : values) {
            if (v != null && v.toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static void require(Object value, String field) {
        if (value == null || (value instanceof String s && s.isBlank())) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", field + " is required").with("field", field);
        }
    }

    private static Instant start(LocalDate d) {
        return d.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static LocalDate date(Instant i) {
        return i == null ? null : LocalDate.ofInstant(i, ZoneOffset.UTC);
    }
}

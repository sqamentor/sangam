// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources.booking;

import org.sangam.core.layer.Jdbc;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.resources.ResourcesContext;
import org.sangam.resources.booking.BookingModel.AllotState;
import org.sangam.resources.booking.BookingModel.Allotment;
import org.sangam.resources.catalogue.Resource;
import org.sangam.resources.catalogue.ResourceCatalogue;
import org.sangam.resources.catalogue.ResourceType;
import org.sangam.resources.port.ResourcesPorts;
import org.sangam.resources.support.ResourcesException;
import org.sangam.resources.support.Sequences;
import org.sangam.resources.support.Sql;
import org.sangam.resources.support.VisibleQuery;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Long-term allotment (N01): occupancy of one capacity unit of a resource from {@code validFrom} to {@code validTo}
 * (open-ended allowed), with notice to vacate, vacate, extension and cancellation.
 *
 * <p>Exclusion: every allotment locks the {@code RES_RESOURCE} row (the R35 {@code resource:} exclusive lease within the
 * transaction, see {@link org.sangam.resources.dialect.ResourcesDialect#lockingSelect}) and checks the overlapping active
 * allotments of the chosen unit while the lock is held. For {@code BOTH}-mode types the slot claims in the range count
 * against the capacity too.
 */
public final class AllotmentService {

    /** Called when an allotment ends or is cancelled (waitlist promotion). */
    @FunctionalInterface
    public interface VacancyListener {
        void vacated(Connection c, SecurityIdentity actor, String resourceId, String typeId, Instant from);
    }

    static final String COLS = "tenant_id, allotment_id, allotment_ref, resource_id, unit_no, holder_id, beneficiary_id,"
            + " waitlist_id, state, valid_from, valid_to, vacate_due_at, vacated_at, order_ref, tariff_code, charge_amount,"
            + " currency, charge_ref, state_reason, attributes_json, row_version, created_at, updated_at";

    private final ResourcesContext ctx;
    private final ResourceCatalogue catalogue;
    private final ClaimLedger claims;
    private final BookingRules rules;
    private volatile VacancyListener vacancyListener = (c, a, r, t, f) -> { };

    public AllotmentService(ResourcesContext ctx, ResourceCatalogue catalogue, ClaimLedger claims, BookingRules rules) {
        this.ctx = ctx;
        this.catalogue = catalogue;
        this.claims = claims;
        this.rules = rules;
    }

    public void onVacancy(VacancyListener l) {
        this.vacancyListener = l;
    }

    public Allotment allot(Connection c, SecurityIdentity actor, BookingModel.AllotRequest req) {
        String t = actor.tenantId();
        if (req.resourceId() == null || req.holderId() == null || req.validFrom() == null) {
            throw ResourcesException.invalid("ALLOT_REQUEST", "resource, holder and valid-from are required");
        }
        if (req.validTo() != null && !req.validTo().isAfter(req.validFrom())) {
            throw ResourcesException.invalid("RANGE", "valid-to must be after valid-from");
        }
        ctx.guard().require(c, actor, ResourcesContext.RT_ALLOTMENT, ResourcesContext.P_APPROVE, req.resourceId());
        Resource r = catalogue.lock(c, t, req.resourceId());
        ResourceType type = catalogue.type(c, t, r.typeId());
        if (!type.allotable()) {
            throw ResourcesException.invalid("NOT_ALLOTABLE", "resources of type " + type.typeId() + " are not allotted");
        }
        if (!r.bookable()) {
            throw ResourcesException.unavailable("RESOURCE_" + r.status().name(), "the resource cannot be allotted now");
        }
        Instant now = ctx.now(c);
        Map<String, Object> in = rules.inputs(actor, type, r, req.holderId(), req.entitlementClass(), null, 1,
                req.validFrom(), req.validTo(), now, req.attributes());
        BookingModel.Entitlement ent = rules.entitlement(t, type, in, null);
        if (!ent.allowed()) {
            throw ResourcesException.notEntitled("NOT_ENTITLED", ent.reason() == null ? "the holder is not entitled to this resource" : ent.reason());
        }
        enforceOnePerHolder(c, t, type, req.holderId(), req.validFrom(), req.validTo());

        Set<Integer> busy = busyUnits(c, t, r.resourceId(), req.validFrom(), req.validTo());
        int slotLoad = 0;
        if (type.bookingMode() == ResourceType.BookingMode.BOTH) {
            long first = Sql.slotKey(req.validFrom());
            long last = req.validTo() == null ? Long.MAX_VALUE : Sql.slotKey(req.validTo());
            slotLoad = claims.usage(c, t, r.resourceId(), first, last).values().stream().mapToInt(Integer::intValue).max().orElse(0);
        }
        int unit;
        if (req.unitNo() != null) {
            unit = req.unitNo();
            if (unit < 0 || unit >= r.capacity()) {
                throw ResourcesException.invalid("UNIT", "unit " + unit + " does not exist on this resource");
            }
            if (busy.contains(unit)) {
                throw ResourcesException.unavailable("UNIT_OCCUPIED", "the unit is allotted for part of the period");
            }
        } else {
            unit = -1;
            for (int u = 0; u < r.capacity(); u++) {
                if (!busy.contains(u)) {
                    unit = u;
                    break;
                }
            }
            if (unit < 0) {
                throw ResourcesException.unavailable("NO_VACANCY", "no unit of the resource is free for the period");
            }
        }
        if (busy.size() + slotLoad + 1 > r.capacity()) {
            throw ResourcesException.unavailable("NO_VACANCY", "the resource is booked for part of the period");
        }

        ResourcesPorts.Quote q = ctx.billing().quote(c, t, ent.tariffCode(), rules.tariffInputs(t, type, in, ent.tariffCode()));
        String id = Sql.newId();
        String chargeRef = q.payable()
                ? ctx.billing().charge(c, t, "allotment:" + id + ":charge", req.holderId(), id, q) : null;
        String period = Integer.toString(req.validFrom().atZone(ZoneOffset.UTC).getYear());
        String prefix = Sql.str(type.extra().getOrDefault("allotmentRefPrefix", type.typeId().toUpperCase() + "-A"));
        String ref = Sequences.format(prefix, period, Sequences.next(c, ctx.dialect(), t, "allotment:" + type.typeId(), period), 6);
        Jdbc.update(c, "INSERT INTO RES_ALLOTMENT(" + COLS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                t, id, ref, r.resourceId(), unit, req.holderId(), req.beneficiaryId(), req.waitlistId(), AllotState.ACTIVE.name(),
                req.validFrom(), req.validTo(), null, null, req.orderRef(), q.tariffCode(),
                q.amount() == null ? null : q.amount().toPlainString(), q.currency(), chargeRef, null,
                Sql.toJson(req.attributes() == null ? Map.of() : req.attributes()), 1L, now, now);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("resourceId", r.resourceId());
        p.put("unitNo", unit);
        p.put("holderId", req.holderId());
        p.put("validFrom", req.validFrom().toString());
        p.put("validTo", req.validTo() == null ? null : req.validTo().toString());
        p.put("entitlementDecision", ent.decisionHash());
        p.put("chargeRef", chargeRef);
        ctx.event(c, actor, "ALLOTMENT", id, "ALLOTTED", null, req.orderRef(), p);
        ctx.notify(c, t, req.holderId(), "allotment.issued", Map.of("allotmentId", id, "allotmentRef", ref));
        return get(c, t, id);
    }

    /** Notice to vacate by a due date (audited; the holder is notified). */
    public Allotment noticeToVacate(Connection c, SecurityIdentity actor, String allotmentId, Instant dueAt, String reason,
                                    String authorityRef) {
        String t = actor.tenantId();
        ctx.guard().require(c, actor, ResourcesContext.RT_ALLOTMENT, ResourcesContext.P_APPROVE, allotmentId);
        Allotment a = lock(c, t, allotmentId);
        requireActive(a);
        update(c, a, "vacate_due_at=?, state_reason=?", dueAt, reason);
        ctx.event(c, actor, "ALLOTMENT", allotmentId, "NOTICE_TO_VACATE", reason, authorityRef, Map.of("dueAt", dueAt.toString()));
        ctx.notify(c, t, a.holderId(), "allotment.notice", Map.of("allotmentId", allotmentId,
                "dueAt", dueAt.toString(), "reason", reason == null ? "" : reason));
        return get(c, t, allotmentId);
    }

    public Allotment vacate(Connection c, SecurityIdentity actor, String allotmentId, Instant at, String reason) {
        String t = actor.tenantId();
        Allotment a = lock(c, t, allotmentId);
        if (!actor.personId().equals(a.holderId())) {
            ctx.guard().require(c, actor, ResourcesContext.RT_ALLOTMENT, ResourcesContext.P_MODIFY, allotmentId);
        }
        requireActive(a);
        Instant when = at == null ? ctx.now(c) : at;
        if (when.isBefore(a.validFrom())) {
            when = a.validFrom();
        }
        Instant end = a.validTo() == null || when.isBefore(a.validTo()) ? when : a.validTo();
        update(c, a, "state=?, vacated_at=?, valid_to=?, state_reason=?", AllotState.VACATED.name(), when,
                end.equals(a.validFrom()) ? a.validFrom().plusSeconds(1) : end, reason);
        ctx.event(c, actor, "ALLOTMENT", allotmentId, "VACATED", reason, null, Map.of("at", when.toString()));
        vacancyListener.vacated(c, actor, a.resourceId(), typeOf(c, t, a.resourceId()), end);
        return get(c, t, allotmentId);
    }

    public Allotment cancel(Connection c, SecurityIdentity actor, String allotmentId, String reason, String authorityRef) {
        String t = actor.tenantId();
        ctx.guard().require(c, actor, ResourcesContext.RT_ALLOTMENT, ResourcesContext.P_CANCEL, allotmentId);
        Allotment a = lock(c, t, allotmentId);
        requireActive(a);
        update(c, a, "state=?, state_reason=?", AllotState.CANCELLED.name(), reason);
        ctx.event(c, actor, "ALLOTMENT", allotmentId, "CANCELLED", reason, authorityRef, Map.of());
        ctx.notify(c, t, a.holderId(), "allotment.cancelled", Map.of("allotmentId", allotmentId));
        vacancyListener.vacated(c, actor, a.resourceId(), typeOf(c, t, a.resourceId()), a.validFrom());
        return get(c, t, allotmentId);
    }

    /** Extends (or shortens) the end date; the unit must stay free for the new range. */
    public Allotment extend(Connection c, SecurityIdentity actor, String allotmentId, Instant newValidTo, long expectedVersion,
                            String reason) {
        String t = actor.tenantId();
        ctx.guard().require(c, actor, ResourcesContext.RT_ALLOTMENT, ResourcesContext.P_APPROVE, allotmentId);
        Allotment a0 = get(c, t, allotmentId);
        catalogue.lock(c, t, a0.resourceId());
        Allotment a = lock(c, t, allotmentId);
        if (a.version() != expectedVersion) {
            throw ResourcesException.stale("allotment " + allotmentId + " was changed by someone else");
        }
        requireActive(a);
        if (newValidTo != null && !newValidTo.isAfter(a.validFrom())) {
            throw ResourcesException.invalid("RANGE", "valid-to must be after valid-from");
        }
        long clash = Jdbc.one(c, "SELECT COUNT(*) FROM RES_ALLOTMENT WHERE tenant_id=? AND resource_id=? AND unit_no=?"
                        + " AND state='ACTIVE' AND allotment_id <> ? AND valid_from < ? AND (valid_to IS NULL OR valid_to > ?)",
                rs -> rs.getLong(1), t, a.resourceId(), a.unitNo(), a.allotmentId(),
                newValidTo == null ? Instant.parse("9999-12-31T00:00:00Z") : newValidTo, a.validFrom()).orElse(0L);
        if (clash > 0) {
            throw ResourcesException.unavailable("UNIT_OCCUPIED", "the unit is allotted to someone else in the new range");
        }
        update(c, a, "valid_to=?, state_reason=?", newValidTo, reason);
        ctx.event(c, actor, "ALLOTMENT", allotmentId, "EXTENDED", reason, null,
                Map.of("validTo", newValidTo == null ? "open" : newValidTo.toString()));
        return get(c, t, allotmentId);
    }

    /** Visible active allotments past their end date or vacate-due date (input for eviction / penal-rent processes). */
    public List<Allotment> overdue(Connection c, SecurityIdentity actor, int limit) {
        Instant now = ctx.now(c);
        return VisibleQuery.on(ctx, actor, ResourcesContext.RT_ALLOTMENT, ResourcesContext.P_READ, "RES_ALLOTMENT", "a")
                .select(COLS).where("a.state='ACTIVE'")
                .where("(a.valid_to IS NOT NULL AND a.valid_to < ?) OR (a.vacate_due_at IS NOT NULL AND a.vacate_due_at < ?)", now, now)
                .orderBy("a.valid_to, a.allotment_id").list(c, AllotmentService::map, 0, limit <= 0 ? 100 : Math.min(limit, 1000));
    }

    /** All overdue active allotments of a tenant, unfiltered: for the system sweeper only (ResourcesSweepers). */
    public List<Allotment> overdueForSweep(Connection c, String tenantId, int limit) {
        Instant now = ctx.now(c);
        String sql = ctx.dialect().page("SELECT " + COLS + " FROM RES_ALLOTMENT WHERE tenant_id=? AND state='ACTIVE'"
                + " AND ((valid_to IS NOT NULL AND valid_to < ?) OR (vacate_due_at IS NOT NULL AND vacate_due_at < ?))"
                + " ORDER BY valid_to, allotment_id", 0, limit <= 0 ? 100 : Math.min(limit, 1000));
        return Jdbc.query(c, sql, AllotmentService::map, tenantId, now, now);
    }

    /** Get-by-id through the visibility predicate (hidden = NOT_FOUND). */
    public Allotment visible(Connection c, SecurityIdentity actor, String allotmentId) {
        return VisibleQuery.on(ctx, actor, ResourcesContext.RT_ALLOTMENT, ResourcesContext.P_READ, "RES_ALLOTMENT", "a")
                .select(COLS).require(c, "allotment_id", allotmentId, AllotmentService::map, "allotment");
    }

    public Optional<Allotment> find(Connection c, String tenantId, String allotmentId) {
        return Jdbc.one(c, "SELECT " + COLS + " FROM RES_ALLOTMENT WHERE tenant_id=? AND allotment_id=?",
                AllotmentService::map, tenantId, allotmentId);
    }

    public Allotment get(Connection c, String tenantId, String allotmentId) {
        return find(c, tenantId, allotmentId).orElseThrow(() -> ResourcesException.notFound("allotment", allotmentId));
    }

    public List<Allotment> list(Connection c, SecurityIdentity actor, String resourceId, String holderId, AllotState state,
                                int offset, int limit) {
        return listQuery(actor, resourceId, holderId, state).orderBy("a.valid_from, a.allotment_id")
                .list(c, AllotmentService::map, Math.max(0, offset), limit <= 0 ? 50 : Math.min(limit, 1000));
    }

    public VisibleQuery.CappedCount count(Connection c, SecurityIdentity actor, String resourceId, String holderId,
                                          AllotState state, int cap) {
        return listQuery(actor, resourceId, holderId, state).count(c, cap);
    }

    private VisibleQuery listQuery(SecurityIdentity actor, String resourceId, String holderId, AllotState state) {
        VisibleQuery q = VisibleQuery.on(ctx, actor, ResourcesContext.RT_ALLOTMENT, ResourcesContext.P_READ, "RES_ALLOTMENT", "a")
                .select(COLS);
        if (resourceId != null) {
            q.where("a.resource_id=?", resourceId);
        }
        if (holderId != null) {
            q.where("a.holder_id=?", holderId);
        }
        if (state != null) {
            q.where("a.state=?", state.name());
        }
        return q;
    }

    /** Resources of a type with at least one unit free for the range (candidate list for allotment from a waitlist). */
    public List<String> vacantResources(Connection c, SecurityIdentity actor, String typeId, Instant from, Instant to, int max) {
        String t = actor.tenantId();
        List<String> ids = Jdbc.query(c, ctx.dialect().page("SELECT resource_id FROM RES_RESOURCE WHERE tenant_id=? AND type_id=?"
                + " AND status='ACTIVE' ORDER BY code", 0, Math.max(1, Math.min(max * 20, 5000))), rs -> rs.getString(1), t, typeId);
        List<String> out = new ArrayList<>();
        for (String id : ids) {
            Resource r = catalogue.get(c, t, id);
            if (busyUnits(c, t, id, from, to).size() < r.capacity()) {
                out.add(id);
                if (out.size() >= max) {
                    break;
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    Set<Integer> busyUnits(Connection c, String t, String resourceId, Instant from, Instant to) {
        Set<Integer> s = new HashSet<>();
        Jdbc.query(c, "SELECT unit_no FROM RES_ALLOTMENT WHERE tenant_id=? AND resource_id=? AND state='ACTIVE'"
                        + " AND valid_from < ? AND (valid_to IS NULL OR valid_to > ?)", rs -> s.add(rs.getInt(1)),
                t, resourceId, to == null ? Instant.parse("9999-12-31T00:00:00Z") : to, from);
        return s;
    }

    private void enforceOnePerHolder(Connection c, String t, ResourceType type, String holder, Instant from, Instant to) {
        Integer max = Sql.intOrNull(type.extra().get("maxActivePerHolder"));
        if (max == null || max <= 0) {
            return;
        }
        long n = Jdbc.one(c, "SELECT COUNT(*) FROM RES_ALLOTMENT a JOIN RES_RESOURCE r ON r.tenant_id=a.tenant_id"
                        + " AND r.resource_id=a.resource_id WHERE a.tenant_id=? AND r.type_id=? AND a.holder_id=? AND a.state='ACTIVE'"
                        + " AND a.valid_from < ? AND (a.valid_to IS NULL OR a.valid_to > ?)", rs -> rs.getLong(1),
                t, type.typeId(), holder, to == null ? Instant.parse("9999-12-31T00:00:00Z") : to, from).orElse(0L);
        if (n >= max) {
            throw ResourcesException.notEntitled("LIMIT_REACHED", "the holder already has an allotment of this type");
        }
    }

    private String typeOf(Connection c, String t, String resourceId) {
        return catalogue.get(c, t, resourceId).typeId();
    }

    private static void requireActive(Allotment a) {
        if (a.state() != AllotState.ACTIVE) {
            throw ResourcesException.conflict("INVALID_STATE", "the allotment is " + a.state());
        }
    }

    private Allotment lock(Connection c, String t, String id) {
        return Jdbc.one(c, ctx.dialect().lockingSelect(COLS, "RES_ALLOTMENT", "tenant_id=? AND allotment_id=?"),
                AllotmentService::map, t, id).orElseThrow(() -> ResourcesException.notFound("allotment", id));
    }

    private void update(Connection c, Allotment a, String set, Object... binds) {
        List<Object> p = new ArrayList<>(java.util.Arrays.asList(binds));
        p.add(Sql.dbNow(c, ctx.dialect()));
        p.add(a.tenantId());
        p.add(a.allotmentId());
        p.add(a.version());
        int n = Jdbc.update(c, "UPDATE RES_ALLOTMENT SET " + set + ", updated_at=?, row_version=row_version+1"
                + " WHERE tenant_id=? AND allotment_id=? AND row_version=?", p.toArray());
        if (n == 0) {
            throw ResourcesException.stale("allotment " + a.allotmentId() + " was changed concurrently");
        }
    }

    static Allotment map(ResultSet rs) throws SQLException {
        return new Allotment(rs.getString("tenant_id"), rs.getString("allotment_id"), rs.getString("allotment_ref"),
                rs.getString("resource_id"), rs.getInt("unit_no"), rs.getString("holder_id"), rs.getString("beneficiary_id"),
                rs.getString("waitlist_id"), AllotState.valueOf(rs.getString("state")), Jdbc.instant(rs, "valid_from"),
                Jdbc.instant(rs, "valid_to"), Jdbc.instant(rs, "vacate_due_at"), Jdbc.instant(rs, "vacated_at"),
                rs.getString("order_ref"), rs.getString("tariff_code"), Sql.decimal(rs, "charge_amount"),
                rs.getString("currency"), rs.getString("charge_ref"), rs.getString("state_reason"),
                Sql.mapJson(Sql.text(rs, "attributes_json")), rs.getLong("row_version"));
    }
}

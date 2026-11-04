// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources.booking;

import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.resources.ResourcesContext;
import org.sangam.resources.booking.BookingModel.WaitState;
import org.sangam.resources.booking.BookingModel.WaitlistEntry;
import org.sangam.resources.catalogue.Resource;
import org.sangam.resources.catalogue.ResourceCatalogue;
import org.sangam.resources.catalogue.ResourceType;
import org.sangam.resources.support.ResourcesException;
import org.sangam.resources.support.Sql;
import org.sangam.resources.support.VisibleQuery;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;

/**
 * Waitlists and draws (N01, GP-28): priority by DMN (category, seniority), automatic promotion when capacity is
 * released (an offer is a hold the entrant must confirm before it lapses), allotment from a waitlist, and an audited
 * lottery with a published seed, an entrants hash and the full resulting order.
 *
 * <p>Scope keys: {@code resource:<id>} waits for one resource; {@code type:<typeId>} waits for any resource of a type
 * (with an optional wanted window).
 */
public final class WaitlistService implements BookingService.WaitlistSink {

    static final String COLS = "tenant_id, entry_id, scope_key, type_id, resource_id, requester_id, beneficiary_id, category,"
            + " priority_level, priority_score, seniority_at, wanted_from, wanted_to, quantity, state, offer_expires_at,"
            + " booking_id, allotment_id, draw_id, draw_position, attributes_json, row_version, created_at, updated_at";
    public static final String DRAW_ALGORITHM = "SHA256-SPLITTABLE-FISHER-YATES-V1";

    private final ResourcesContext ctx;
    private final ResourceCatalogue catalogue;
    private final BookingService bookings;
    private final AllotmentService allotments;
    private final BookingRules rules;

    public WaitlistService(ResourcesContext ctx, ResourceCatalogue catalogue, BookingService bookings,
                           AllotmentService allotments, BookingRules rules) {
        this.ctx = ctx;
        this.catalogue = catalogue;
        this.bookings = bookings;
        this.allotments = allotments;
        this.rules = rules;
    }

    /** Wires automatic promotion on every release of capacity. */
    public void wire() {
        bookings.waitlistSink(this);
        bookings.onRelease((c, actor, resourceId, typeId, from, to) -> {
            if (ctx.setting("autoPromoteWaitlist", 1) == 1) {
                promote(c, SecurityIdentity.system(actor.tenantId(), actor.personId()), resourceId, typeId, from, to);
            }
        });
        allotments.onVacancy((c, actor, resourceId, typeId, from) -> {
            if (ctx.setting("autoAllotFromWaitlist", 0) == 1) {
                allotNext(c, SecurityIdentity.system(actor.tenantId(), actor.personId()), "type:" + typeId, resourceId, from, null, null);
            }
        });
    }

    public static String resourceScope(String resourceId) {
        return "resource:" + resourceId;
    }

    public static String typeScope(String typeId) {
        return "type:" + typeId;
    }

    // ------------------------------------------------------------------ entries

    @Override
    public WaitlistEntry enqueue(Connection c, SecurityIdentity actor, BookingModel.WaitlistRequest req) {
        String t = actor.tenantId();
        ResourceType type;
        Resource r = null;
        if (req.resourceId() != null) {
            r = catalogue.get(c, t, req.resourceId());
            type = catalogue.type(c, t, r.typeId());
        } else if (req.typeId() != null) {
            type = catalogue.type(c, t, req.typeId());
        } else {
            throw ResourcesException.invalid("WAITLIST_SCOPE", "a waitlist entry needs a resource or a type");
        }
        ctx.guard().require(c, actor, ResourcesContext.RT_BOOKING, ResourcesContext.P_MODIFY,
                r != null ? r.resourceId() : "type:" + type.typeId());
        String requester = req.requesterId() == null ? actor.personId() : req.requesterId();
        String scope = r != null ? resourceScope(r.resourceId()) : typeScope(type.typeId());
        long dup = Jdbc.one(c, "SELECT COUNT(*) FROM RES_WAITLIST WHERE tenant_id=? AND scope_key=? AND requester_id=?"
                        + " AND state IN ('WAITING','OFFERED')", rs -> rs.getLong(1), t, scope, requester).orElse(0L);
        if (dup > 0 && !Sql.bool(type.extra().get("allowMultipleWaitlistEntries"), false)) {
            throw ResourcesException.conflict("ALREADY_WAITLISTED", "the requester is already on this waitlist");
        }
        Instant now = ctx.now(c);
        int qty = req.quantity() == null ? 1 : req.quantity();
        Map<String, Object> in = rules.inputs(actor, type, r, requester, req.category(), null, qty, req.wantedFrom(),
                req.wantedTo(), now, req.attributes());
        in.put("category", req.category());
        if (req.seniorityAt() != null) {
            in.put("seniorityDays", Duration.between(req.seniorityAt(), now).toDays());
        }
        long defaultScore = (req.seniorityAt() != null ? req.seniorityAt() : now).toEpochMilli();
        long[] prio = rules.priority(t, type, in, req.category(), req.priorityLevel(), defaultScore);
        String id = Sql.newId();
        Jdbc.update(c, "INSERT INTO RES_WAITLIST(" + COLS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                t, id, scope, type.typeId(), r == null ? null : r.resourceId(), requester, req.beneficiaryId(),
                req.category(), (int) prio[0], prio[1], req.seniorityAt(), req.wantedFrom(), req.wantedTo(), qty,
                WaitState.WAITING.name(), null, null, null, null, null,
                Sql.toJson(req.attributes() == null ? Map.of() : req.attributes()), 1L, now, now);
        ctx.event(c, actor, "WAITLIST", id, "WAITLISTED", null, null, Map.of("scope", scope, "priorityLevel", prio[0],
                "priorityScore", prio[1]));
        return get(c, t, id);
    }

    public WaitlistEntry withdraw(Connection c, SecurityIdentity actor, String entryId, String reason) {
        String t = actor.tenantId();
        WaitlistEntry e = lock(c, t, entryId);
        if (!actor.personId().equals(e.requesterId())) {
            ctx.guard().require(c, actor, ResourcesContext.RT_BOOKING, ResourcesContext.P_CANCEL, entryId);
        }
        if (e.state() != WaitState.WAITING && e.state() != WaitState.OFFERED) {
            throw ResourcesException.conflict("INVALID_STATE", "the entry is " + e.state());
        }
        if (e.state() == WaitState.OFFERED && e.bookingId() != null) {
            bookings.cancel(c, actor, e.bookingId(), "waitlist offer withdrawn", "REQUESTER");
        }
        setState(c, e, WaitState.WITHDRAWN, null, null, null);
        ctx.event(c, actor, "WAITLIST", entryId, "WITHDRAWN", reason, null, Map.of());
        return get(c, t, entryId);
    }

    /** Accepts an offer: confirms the offered hold and marks the entry PROMOTED. */
    public BookingModel.Booking acceptOffer(Connection c, SecurityIdentity actor, String entryId) {
        String t = actor.tenantId();
        WaitlistEntry e = lock(c, t, entryId);
        if (e.state() != WaitState.OFFERED || e.bookingId() == null) {
            throw ResourcesException.conflict("NO_OFFER", "the entry has no open offer");
        }
        BookingModel.Booking b = bookings.confirm(c, actor, e.bookingId());
        setState(c, e, WaitState.PROMOTED, e.bookingId(), null, null);
        ctx.event(c, actor, "WAITLIST", entryId, "PROMOTED", null, null, Map.of("bookingId", b.bookingId()));
        return b;
    }

    /** 1-based position among WAITING entries of the scope. */
    public int position(Connection c, String tenantId, String entryId) {
        WaitlistEntry e = get(c, tenantId, entryId);
        if (e.state() != WaitState.WAITING) {
            return 0;
        }
        long ahead = Jdbc.one(c, "SELECT COUNT(*) FROM RES_WAITLIST WHERE tenant_id=? AND scope_key=? AND state='WAITING'"
                        + " AND (priority_level < ? OR (priority_level = ? AND (priority_score < ? OR (priority_score = ?"
                        + " AND created_at < ?))))", rs -> rs.getLong(1), tenantId, e.scopeKey(), e.priorityLevel(),
                e.priorityLevel(), e.priorityScore(), e.priorityScore(), e.createdAt()).orElse(0L);
        return (int) ahead + 1;
    }

    /** Visible entries of a scope in queue order (VI.4 predicate in the statement). */
    public List<WaitlistEntry> list(Connection c, SecurityIdentity actor, String scopeKey, WaitState state, int offset, int limit) {
        return listQuery(actor, scopeKey, state).orderBy("w.priority_level, w.priority_score, w.created_at, w.entry_id")
                .list(c, WaitlistService::map, Math.max(0, offset), limit <= 0 ? 50 : Math.min(limit, 1000));
    }

    public VisibleQuery.CappedCount count(Connection c, SecurityIdentity actor, String scopeKey, WaitState state, int cap) {
        return listQuery(actor, scopeKey, state).count(c, cap);
    }

    private VisibleQuery listQuery(SecurityIdentity actor, String scopeKey, WaitState state) {
        return VisibleQuery.on(ctx, actor, ResourcesContext.RT_WAITLIST, ResourcesContext.P_READ, "RES_WAITLIST", "w")
                .select(COLS).where("w.scope_key=? AND w.state=?", scopeKey, (state == null ? WaitState.WAITING : state).name());
    }

    /** Get-by-id through the visibility predicate (hidden = NOT_FOUND). */
    public WaitlistEntry visible(Connection c, SecurityIdentity actor, String entryId) {
        return VisibleQuery.on(ctx, actor, ResourcesContext.RT_WAITLIST, ResourcesContext.P_READ, "RES_WAITLIST", "w")
                .select(COLS).require(c, "entry_id", entryId, WaitlistService::map, "waitlist entry");
    }

    // ------------------------------------------------------------------ promotion

    /**
     * Offers released capacity to the next waiting entrants of the resource scope and of the type scope whose wanted
     * window fits. Each offer is a hold (expiry = {@code offerMinutes}, default the type's hold); the entrant confirms
     * it with {@link #acceptOffer}. Lapsed offers return the capacity and the next entrant gets it.
     */
    public List<WaitlistEntry> promote(Connection c, SecurityIdentity system, String resourceId, String typeId,
                                       Instant from, Instant to) {
        String t = system.tenantId();
        expireOffers(c, system);
        ResourceType type = catalogue.type(c, t, typeId);
        int batch = ctx.setting("waitlistPromotionBatch", 10);
        int offerMinutes = Sql.intOr(type.extra().get("offerMinutes"), type.holdMinutes());
        List<WaitlistEntry> offered = new ArrayList<>();
        for (String scope : List.of(resourceScope(resourceId), typeScope(typeId))) {
            String sql = ctx.dialect().skipLockedSelect(qualified("w"), "RES_WAITLIST", "w",
                    "w.tenant_id=? AND w.scope_key=? AND w.state='WAITING' AND w.wanted_from IS NOT NULL AND w.wanted_to IS NOT NULL"
                            + " AND w.wanted_from >= ? AND w.wanted_to <= ?",
                    "w.priority_level, w.priority_score, w.created_at", batch);
            for (WaitlistEntry e : Jdbc.query(c, sql, WaitlistService::map, t, scope, from, to)) {
                BookingModel.HoldRequest req = new BookingModel.HoldRequest(resourceId, e.wantedFrom(), e.wantedTo(),
                        e.quantity(), e.requesterId(), e.beneficiaryId(), null, "waitlist offer", e.category(),
                        e.priorityLevel(), e.attributes(), null, false, null, null, false, offerMinutes);
                try {
                    BookingModel.Booking b = bookings.hold(c, system, req, null, e.entryId()).booking();
                    setState(c, e, WaitState.OFFERED, b.bookingId(), null, b.holdExpiresAt());
                    ctx.event(c, system, "WAITLIST", e.entryId(), "OFFERED", null, null,
                            Map.of("bookingId", b.bookingId(), "expiresAt", String.valueOf(b.holdExpiresAt())));
                    Map<String, Object> n = new LinkedHashMap<>();
                    n.put("entryId", e.entryId());
                    n.put("bookingId", b.bookingId());
                    n.put("bookingRef", b.bookingRef());
                    n.put("expiresAt", String.valueOf(b.holdExpiresAt()));
                    ctx.notify(c, t, e.requesterId(), "waitlist.offered", n);
                    offered.add(get(c, t, e.entryId()));
                } catch (ResourcesException ex) {
                    // not available for this entrant (quantity, window, entitlement): try the next one
                }
            }
        }
        return offered;
    }

    /** Offers whose hold lapsed or was cancelled go back to EXPIRED (the entrant may re-join). */
    public int expireOffers(Connection c, SecurityIdentity system) {
        String t = system.tenantId();
        List<WaitlistEntry> open = Jdbc.query(c, "SELECT " + COLS + " FROM RES_WAITLIST WHERE tenant_id=? AND state='OFFERED'",
                WaitlistService::map, t);
        int n = 0;
        for (WaitlistEntry e : open) {
            Optional<BookingModel.Booking> b = e.bookingId() == null ? Optional.empty() : bookings.find(c, t, e.bookingId());
            if (b.isEmpty() || !b.get().state().active()) {
                setState(c, e, WaitState.EXPIRED, e.bookingId(), null, null);
                ctx.event(c, system, "WAITLIST", e.entryId(), "OFFER_LAPSED", null, null, Map.of());
                n++;
            } else if (b.get().state() == BookingModel.State.CONFIRMED || b.get().state() == BookingModel.State.CHECKED_IN) {
                setState(c, e, WaitState.PROMOTED, e.bookingId(), null, null);
            }
        }
        return n;
    }

    /**
     * Allots the next waiting entrant of a scope to a vacant resource (long-term allotment from a waitlist).
     *
     * @param resourceId the vacant resource; null = the first vacant resource of the scope's type
     */
    public Optional<BookingModel.Allotment> allotNext(Connection c, SecurityIdentity actor, String scopeKey, String resourceId,
                                                      Instant from, Instant to, String orderRef) {
        String t = actor.tenantId();
        String sql = ctx.dialect().skipLockedSelect(qualified("w"), "RES_WAITLIST", "w",
                "w.tenant_id=? AND w.scope_key=? AND w.state='WAITING'", "w.priority_level, w.priority_score, w.created_at",
                ctx.setting("waitlistPromotionBatch", 10));
        for (WaitlistEntry e : Jdbc.query(c, sql, WaitlistService::map, t, scopeKey)) {
            Instant validFrom = e.wantedFrom() != null && e.wantedFrom().isAfter(from) ? e.wantedFrom() : from;
            Instant validTo = e.wantedTo() != null ? e.wantedTo() : to;
            String target = resourceId;
            if (target == null) {
                List<String> vacant = allotments.vacantResources(c, actor, e.typeId(), validFrom, validTo, 1);
                if (vacant.isEmpty()) {
                    return Optional.empty();
                }
                target = vacant.get(0);
            }
            try {
                BookingModel.Allotment a = allotments.allot(c, actor, new BookingModel.AllotRequest(target, null,
                        e.requesterId(), e.beneficiaryId(), validFrom, validTo, orderRef, e.entryId(), e.category(),
                        e.attributes()));
                setState(c, e, WaitState.PROMOTED, null, a.allotmentId(), null);
                ctx.event(c, actor, "WAITLIST", e.entryId(), "ALLOTTED", null, orderRef, Map.of("allotmentId", a.allotmentId()));
                return Optional.of(a);
            } catch (ResourcesException ex) {
                if (ex.code() == ResourcesException.Code.UNAVAILABLE) {
                    return Optional.empty();
                }
                ctx.event(c, actor, "WAITLIST", e.entryId(), "ALLOT_SKIPPED", ex.reason(), null, Map.of());
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ draw (lottery)

    /**
     * An audited draw over the WAITING entrants of a scope. The order is a Fisher-Yates shuffle driven by a
     * {@link SplittableRandom} seeded with SHA-256(seed | entrantsHash), where entrantsHash is SHA-256 of the sorted entry
     * ids: anyone holding the published seed and the entrant list can recompute the result. Entrants keep their
     * priority level (reserved categories draw within their level); the draw position becomes the score.
     */
    public BookingModel.Draw draw(Connection c, SecurityIdentity actor, String scopeKey, String seed) {
        if (seed == null || seed.isBlank()) {
            throw ResourcesException.invalid("SEED", "a published seed is required for a draw");
        }
        String t = actor.tenantId();
        ctx.guard().require(c, actor, ResourcesContext.RT_ALLOTMENT, ResourcesContext.P_APPROVE, scopeKey);
        List<String> ids = Jdbc.query(c, ctx.dialect().lockingSelect("entry_id", "RES_WAITLIST",
                "tenant_id=? AND scope_key=? AND state='WAITING' AND draw_id IS NULL"), rs -> rs.getString(1), t, scopeKey);
        ids = new ArrayList<>(ids);
        java.util.Collections.sort(ids);
        if (ids.isEmpty()) {
            throw ResourcesException.invalid("NO_ENTRANTS", "nobody is waiting in " + scopeKey);
        }
        String entrantsHash = Hashes.hex(Hashes.sha256(String.join("\n", ids).getBytes(StandardCharsets.UTF_8)));
        List<String> order = shuffle(ids, seed, entrantsHash);
        String drawId = Sql.newId();
        Instant now = ctx.now(c);
        Jdbc.update(c, "INSERT INTO RES_DRAW(tenant_id, draw_id, scope_key, seed, algorithm, entrants_hash, entrant_count,"
                        + " result_json, drawn_by, drawn_at) VALUES (?,?,?,?,?,?,?,?,?,?)", t, drawId, scopeKey, seed,
                DRAW_ALGORITHM, entrantsHash, order.size(), Sql.toJson(order), actor.personId(), now);
        for (int i = 0; i < order.size(); i++) {
            Jdbc.update(c, "UPDATE RES_WAITLIST SET draw_id=?, draw_position=?, priority_score=?, updated_at=?,"
                    + " row_version=row_version+1 WHERE tenant_id=? AND entry_id=?", drawId, i + 1, (long) (i + 1), now, t, order.get(i));
        }
        ctx.event(c, actor, "DRAW", drawId, "DRAWN", null, null, Map.of("scope", scopeKey, "seed", seed,
                "entrantsHash", entrantsHash, "entrants", order.size(), "algorithm", DRAW_ALGORITHM));
        return new BookingModel.Draw(drawId, scopeKey, seed, DRAW_ALGORITHM, entrantsHash, order.size(), order,
                actor.personId(), now);
    }

    /** A draw through the visibility predicate (hidden = empty). */
    public Optional<BookingModel.Draw> findDraw(Connection c, SecurityIdentity actor, String drawId) {
        return VisibleQuery.on(ctx, actor, ResourcesContext.RT_DRAW, ResourcesContext.P_READ, "RES_DRAW", "d")
                .select("draw_id, scope_key, seed, algorithm, entrants_hash, entrant_count, result_json, drawn_by, drawn_at")
                .byId(c, "draw_id", drawId, rs -> new BookingModel.Draw(
                        rs.getString("draw_id"), rs.getString("scope_key"), rs.getString("seed"), rs.getString("algorithm"),
                        rs.getString("entrants_hash"), rs.getInt("entrant_count"),
                        Sql.listJson(Sql.text(rs, "result_json")).stream().map(String::valueOf).toList(),
                        rs.getString("drawn_by"), Jdbc.instant(rs, "drawn_at")));
    }

    /** Recomputes a draw from its published inputs (public verification). */
    public static List<String> shuffle(List<String> sortedEntryIds, String seed, String entrantsHash) {
        byte[] h = Hashes.sha256((seed + "|" + entrantsHash).getBytes(StandardCharsets.UTF_8));
        long s = ByteBuffer.wrap(h, 0, 8).getLong();
        SplittableRandom rnd = new SplittableRandom(s);
        List<String> out = new ArrayList<>(sortedEntryIds);
        for (int i = out.size() - 1; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            String x = out.get(i);
            out.set(i, out.get(j));
            out.set(j, x);
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    public Optional<WaitlistEntry> find(Connection c, String tenantId, String entryId) {
        return Jdbc.one(c, "SELECT " + COLS + " FROM RES_WAITLIST WHERE tenant_id=? AND entry_id=?", WaitlistService::map,
                tenantId, entryId);
    }

    public WaitlistEntry get(Connection c, String tenantId, String entryId) {
        return find(c, tenantId, entryId).orElseThrow(() -> ResourcesException.notFound("waitlist entry", entryId));
    }

    private WaitlistEntry lock(Connection c, String t, String id) {
        return Jdbc.one(c, ctx.dialect().lockingSelect(COLS, "RES_WAITLIST", "tenant_id=? AND entry_id=?"),
                WaitlistService::map, t, id).orElseThrow(() -> ResourcesException.notFound("waitlist entry", id));
    }

    private void setState(Connection c, WaitlistEntry e, WaitState s, String bookingId, String allotmentId, Instant offerExpires) {
        int n = Jdbc.update(c, "UPDATE RES_WAITLIST SET state=?, booking_id=?, allotment_id=?, offer_expires_at=?, updated_at=?,"
                        + " row_version=row_version+1 WHERE tenant_id=? AND entry_id=? AND row_version=?", s.name(),
                bookingId != null ? bookingId : e.bookingId(), allotmentId != null ? allotmentId : e.allotmentId(), offerExpires,
                Sql.dbNow(c, ctx.dialect()), e.tenantId(), e.entryId(), e.version());
        if (n == 0) {
            throw ResourcesException.stale("waitlist entry " + e.entryId() + " was changed concurrently");
        }
    }

    private static String qualified(String alias) {
        StringBuilder sb = new StringBuilder();
        for (String col : COLS.split(",")) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(alias).append('.').append(col.trim());
        }
        return sb.toString();
    }

    static WaitlistEntry map(ResultSet rs) throws SQLException {
        int pos = rs.getInt("draw_position");
        Integer drawPos = rs.wasNull() ? null : pos;
        return new WaitlistEntry(rs.getString("tenant_id"), rs.getString("entry_id"), rs.getString("scope_key"),
                rs.getString("type_id"), rs.getString("resource_id"), rs.getString("requester_id"),
                rs.getString("beneficiary_id"), rs.getString("category"), rs.getInt("priority_level"),
                rs.getLong("priority_score"), Jdbc.instant(rs, "seniority_at"), Jdbc.instant(rs, "wanted_from"),
                Jdbc.instant(rs, "wanted_to"), rs.getInt("quantity"), WaitState.valueOf(rs.getString("state")),
                Jdbc.instant(rs, "offer_expires_at"), rs.getString("booking_id"), rs.getString("allotment_id"),
                rs.getString("draw_id"), drawPos, Sql.mapJson(Sql.text(rs, "attributes_json")), rs.getLong("row_version"),
                Jdbc.instant(rs, "created_at"));
    }
}

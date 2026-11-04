// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources.queue;

import org.sangam.core.layer.Jdbc;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.resources.ResourcesContext;
import org.sangam.resources.dialect.ResourcesDialect;
import org.sangam.resources.port.ResourcesPorts;
import org.sangam.resources.queue.QueueModel.CallKind;
import org.sangam.resources.queue.QueueModel.Channel;
import org.sangam.resources.queue.QueueModel.Counter;
import org.sangam.resources.queue.QueueModel.CounterStatus;
import org.sangam.resources.queue.QueueModel.ServicePoint;
import org.sangam.resources.queue.QueueModel.Token;
import org.sangam.resources.queue.QueueModel.TokenState;
import org.sangam.resources.rules.RuleEvaluator;
import org.sangam.resources.support.ResourcesException;
import org.sangam.resources.support.Sql;
import org.sangam.resources.support.VisibleQuery;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Queue and token management (capability N07).
 *
 * <ul>
 *   <li><b>Issue</b>: walk-in, kiosk, SMS, QR or check-in of an N01 booking. The display number is gap-free per service
 *       point, day and series ({@code RES_TOKEN_SEQ} row lock). Priority class, level and skill come from the
 *       service point's priority/routing DMN decisions, with configuration maps as the fallback.</li>
 *   <li><b>Call next</b>: the counter row is locked (serialises one counter), then the best WAITING token whose skill the
 *       counter has is claimed with {@code SKIP LOCKED}, so many counters call concurrently without taking the same
 *       token. Oracle applies ROWNUM before ORDER BY, so on Oracle the ordered candidates are read first without a lock
 *       and then claimed one by one with {@code SKIP LOCKED}.</li>
 *   <li><b>Recall, skip, serve, complete, transfer, cancel, no-show sweep</b>; every action writes a
 *       {@code RES_CALL_EVENT} row and publishes a {@link ResourcesPorts.FeedEvent} to the display feed.</li>
 *   <li><b>SLA and statistics</b>: {@code sla_due_at} at issue, breach list, wait and service time statistics pushed to
 *       the metrics port.</li>
 * </ul>
 * Every method runs in the caller's transaction and never commits it. Feed events are published inside the transaction;
 * the integrator routes the feed through the outbox when it must not precede the commit.
 */
public final class QueueService {

    private static final String TOKEN_COLS = "t.tenant_id, t.token_id, t.service_point_id, t.day_key, t.token_no,"
            + " t.display_no, t.channel, t.booking_id, t.party_id, t.priority_class, t.priority_level, t.skill, t.state,"
            + " t.counter_id, t.transferred_from, t.recall_count, t.issued_at, t.queued_at, t.called_at,"
            + " t.serve_started_at, t.served_at, t.sla_due_at, t.outcome, t.attributes_json, t.row_version";

    private static final int DEFAULT_LEVEL = 100;
    private static final String DEFAULT_SKILL = "GENERAL";
    private static final int ORACLE_CANDIDATES = 20;

    private final ResourcesContext ctx;
    private final ResourcesDialect d;

    public QueueService(ResourcesContext ctx) {
        this.ctx = ctx;
        this.d = ctx.dialect();
    }

    // ================================================================== service points and counters

    /** Creates or updates a service point. */
    public ServicePoint defineServicePoint(Connection c, SecurityIdentity actor, String servicePointId, String name,
                                           String resourceId, String calendarId, String tokenPrefix, int slaMinutes,
                                           String priorityDecision, String routingDecision, Map<String, Object> config) {
        ctx.guard().require(c, actor, ResourcesContext.RT_QUEUE, ResourcesContext.P_ADMIN, servicePointId);
        if (servicePointId == null || servicePointId.isBlank() || name == null || name.isBlank()) {
            throw ResourcesException.invalid("SERVICE_POINT", "service point id and name are required");
        }
        if (slaMinutes < 0) {
            throw ResourcesException.invalid("SLA", "slaMinutes must be >= 0");
        }
        String t = actor.tenantId();
        Instant now = Sql.dbNow(c, d);
        String cfg = Sql.toJson(config == null ? Map.of() : config);
        boolean inserted = d.insertIfAbsent(c, "INSERT INTO RES_SERVICE_POINT(tenant_id, service_point_id, name, resource_id,"
                        + " calendar_id, token_prefix, sla_minutes, priority_decision, routing_decision, config_json, status,"
                        + " updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,'ACTIVE',?)",
                t, servicePointId, name, resourceId, calendarId, tokenPrefix, slaMinutes, priorityDecision, routingDecision,
                cfg, now);
        if (!inserted) {
            Jdbc.update(c, "UPDATE RES_SERVICE_POINT SET name=?, resource_id=?, calendar_id=?, token_prefix=?, sla_minutes=?,"
                            + " priority_decision=?, routing_decision=?, config_json=?, updated_at=?"
                            + " WHERE tenant_id=? AND service_point_id=?",
                    name, resourceId, calendarId, tokenPrefix, slaMinutes, priorityDecision, routingDecision, cfg, now,
                    t, servicePointId);
        }
        ctx.event(c, actor, "SERVICE_POINT", servicePointId, inserted ? "SERVICE_POINT_CREATED" : "SERVICE_POINT_UPDATED",
                null, null, Map.of("name", name, "slaMinutes", slaMinutes));
        return spRaw(c, t, servicePointId);
    }

    /** ACTIVE or INACTIVE (an inactive service point issues no tokens). */
    public ServicePoint setServicePointStatus(Connection c, SecurityIdentity actor, String servicePointId, String status) {
        ctx.guard().require(c, actor, ResourcesContext.RT_QUEUE, ResourcesContext.P_ADMIN, servicePointId);
        if (!"ACTIVE".equals(status) && !"INACTIVE".equals(status)) {
            throw ResourcesException.invalid("STATUS", "status must be ACTIVE or INACTIVE");
        }
        int n = Jdbc.update(c, "UPDATE RES_SERVICE_POINT SET status=?, updated_at=? WHERE tenant_id=? AND service_point_id=?",
                status, Sql.dbNow(c, d), actor.tenantId(), servicePointId);
        if (n == 0) {
            throw ResourcesException.notFound("service point", servicePointId);
        }
        ctx.event(c, actor, "SERVICE_POINT", servicePointId, "SERVICE_POINT_" + status, null, null, Map.of());
        return spRaw(c, actor.tenantId(), servicePointId);
    }

    private ServicePoint spRaw(Connection c, String tenantId, String servicePointId) {
        return findSpRaw(c, tenantId, servicePointId)
                .orElseThrow(() -> ResourcesException.notFound("service point", servicePointId));
    }

    private Optional<ServicePoint> findSpRaw(Connection c, String tenantId, String servicePointId) {
        return Jdbc.one(c, "SELECT * FROM RES_SERVICE_POINT WHERE tenant_id=? AND service_point_id=?",
                QueueService::mapServicePoint, tenantId, servicePointId);
    }

    // ------------------------------------------------------------------ visible reads (VI.4 single read path)

    private VisibleQuery spQuery(SecurityIdentity actor) {
        return VisibleQuery.on(ctx, actor, ResourcesContext.RT_QUEUE, ResourcesContext.P_READ, "RES_SERVICE_POINT", "s")
                .select("s.*");
    }

    /** A visible service point, or NOT_FOUND (hidden is reported like missing). */
    public ServicePoint servicePoint(Connection c, SecurityIdentity actor, String servicePointId) {
        return spQuery(actor).require(c, "service_point_id", servicePointId, QueueService::mapServicePoint, "service point");
    }

    public Optional<ServicePoint> findServicePoint(Connection c, SecurityIdentity actor, String servicePointId) {
        return spQuery(actor).byId(c, "service_point_id", servicePointId, QueueService::mapServicePoint);
    }

    public List<ServicePoint> servicePoints(Connection c, SecurityIdentity actor, int offset, int limit) {
        return spQuery(actor).orderBy("s.service_point_id").list(c, QueueService::mapServicePoint, offset, limit);
    }

    public VisibleQuery.CappedCount countServicePoints(Connection c, SecurityIdentity actor, int cap) {
        return spQuery(actor).count(c, cap);
    }

    /** Creates or updates a counter and replaces its skills (no skills means the counter serves every skill). */
    public Counter defineCounter(Connection c, SecurityIdentity actor, String servicePointId, String counterId, String name,
                                 Collection<String> skills) {
        ctx.guard().require(c, actor, ResourcesContext.RT_QUEUE, ResourcesContext.P_ADMIN, servicePointId);
        String t = actor.tenantId();
        spRaw(c, t, servicePointId);
        if (counterId == null || counterId.isBlank()) {
            throw ResourcesException.invalid("COUNTER", "counter id is required");
        }
        Instant now = Sql.dbNow(c, d);
        boolean inserted = d.insertIfAbsent(c, "INSERT INTO RES_COUNTER(tenant_id, counter_id, service_point_id, name, status,"
                        + " operator_id, current_token_id, row_version, updated_at) VALUES (?,?,?,?,'CLOSED',NULL,NULL,0,?)",
                t, counterId, servicePointId, name == null ? counterId : name, now);
        if (!inserted) {
            Counter existing = counterRaw(c, t, counterId);
            if (!existing.servicePointId().equals(servicePointId)) {
                throw ResourcesException.conflict("COUNTER_ELSEWHERE", "counter belongs to another service point");
            }
            Jdbc.update(c, "UPDATE RES_COUNTER SET name=?, updated_at=?, row_version=row_version+1"
                    + " WHERE tenant_id=? AND counter_id=?", name == null ? counterId : name, now, t, counterId);
        }
        Jdbc.update(c, "DELETE FROM RES_COUNTER_SKILL WHERE tenant_id=? AND counter_id=?", t, counterId);
        Set<String> sk = new LinkedHashSet<>(skills == null ? List.of() : skills);
        for (String s : sk) {
            if (s != null && !s.isBlank()) {
                Jdbc.update(c, "INSERT INTO RES_COUNTER_SKILL(tenant_id, counter_id, skill) VALUES (?,?,?)", t, counterId, s);
            }
        }
        ctx.event(c, actor, "COUNTER", counterId, inserted ? "COUNTER_CREATED" : "COUNTER_UPDATED", null, null,
                Map.of("servicePointId", servicePointId, "skills", List.copyOf(sk)));
        return counterRaw(c, t, counterId);
    }

    public Counter openCounter(Connection c, SecurityIdentity actor, String counterId, String operatorId) {
        Counter k = lockCounter(c, actor, counterId);
        String op = operatorId == null ? actor.personId() : operatorId;
        updateCounter(c, k, CounterStatus.OPEN, op, k.currentTokenId());
        callEvent(c, actor, k.servicePointId(), "-", counterId, CallKind.COUNTER_OPEN, op);
        publish(c, k.servicePointId(), CallKind.COUNTER_OPEN, null, k, Map.of());
        return counterRaw(c, actor.tenantId(), counterId);
    }

    public Counter pauseCounter(Connection c, SecurityIdentity actor, String counterId, String reason) {
        Counter k = lockCounter(c, actor, counterId);
        updateCounter(c, k, CounterStatus.PAUSED, k.operatorId(), k.currentTokenId());
        callEvent(c, actor, k.servicePointId(), "-", counterId, CallKind.COUNTER_PAUSE, reason);
        publish(c, k.servicePointId(), CallKind.COUNTER_PAUSE, null, k, Map.of());
        return counterRaw(c, actor.tenantId(), counterId);
    }

    /** Closes a counter. A called (not yet served) token goes back to the queue; a token being served blocks closing. */
    public Counter closeCounter(Connection c, SecurityIdentity actor, String counterId) {
        Counter k = lockCounter(c, actor, counterId);
        if (k.currentTokenId() != null) {
            Token cur = tokenRaw(c, actor.tenantId(), k.currentTokenId());
            if (cur.state() == TokenState.SERVING) {
                throw ResourcesException.conflict("COUNTER_BUSY", "finish the token being served before closing the counter");
            }
            if (cur.state() == TokenState.CALLED) {
                updateToken(c, cur, "state='WAITING', counter_id=NULL, called_at=NULL", List.of());
                callEvent(c, actor, cur.servicePointId(), cur.tokenId(), counterId, CallKind.REQUEUE, "counter closed");
            }
        }
        updateCounter(c, k, CounterStatus.CLOSED, null, null);
        callEvent(c, actor, k.servicePointId(), "-", counterId, CallKind.COUNTER_CLOSE, null);
        publish(c, k.servicePointId(), CallKind.COUNTER_CLOSE, null, k, Map.of());
        return counterRaw(c, actor.tenantId(), counterId);
    }

    private Counter counterRaw(Connection c, String tenantId, String counterId) {
        Counter k = Jdbc.one(c, "SELECT * FROM RES_COUNTER WHERE tenant_id=? AND counter_id=?",
                rs -> mapCounter(rs, Set.of()), tenantId, counterId)
                .orElseThrow(() -> ResourcesException.notFound("counter", counterId));
        return withSkills(c, k);
    }

    private List<Counter> countersRaw(Connection c, String tenantId, String servicePointId) {
        List<Counter> out = new ArrayList<>();
        for (Counter k : Jdbc.query(c, "SELECT * FROM RES_COUNTER WHERE tenant_id=? AND service_point_id=? ORDER BY counter_id",
                rs -> mapCounter(rs, Set.of()), tenantId, servicePointId)) {
            out.add(withSkills(c, k));
        }
        return out;
    }

    private VisibleQuery counterQuery(SecurityIdentity actor) {
        return VisibleQuery.on(ctx, actor, ResourcesContext.RT_COUNTER, ResourcesContext.P_READ, "RES_COUNTER", "k")
                .select("k.*");
    }

    /** A visible counter with its skills, or NOT_FOUND. */
    public Counter counter(Connection c, SecurityIdentity actor, String counterId) {
        return withSkills(c, counterQuery(actor).require(c, "counter_id", counterId, rs -> mapCounter(rs, Set.of()), "counter"));
    }

    /** Visible counters of a visible service point. */
    public List<Counter> counters(Connection c, SecurityIdentity actor, String servicePointId) {
        servicePoint(c, actor, servicePointId);
        List<Counter> out = new ArrayList<>();
        for (Counter k : counterQuery(actor).where("k.service_point_id=?", servicePointId).orderBy("k.counter_id")
                .all(c, rs -> mapCounter(rs, Set.of()), VisibleQuery.MAX_PAGE_SIZE)) {
            out.add(withSkills(c, k));
        }
        return out;
    }

    // ================================================================== tokens

    /**
     * Issues a token. For a booking check-in ({@code bookingId} set) the booking must be CONFIRMED or CHECKED_IN, and an
     * active token already issued for it is returned unchanged (idempotent check-in).
     */
    public Token issue(Connection c, SecurityIdentity actor, QueueModel.IssueRequest req) {
        ctx.guard().require(c, actor, ResourcesContext.RT_QUEUE, ResourcesContext.P_WORK, req.servicePointId());
        String t = actor.tenantId();
        ServicePoint sp = spRaw(c, t, req.servicePointId());
        if (!"ACTIVE".equals(sp.status())) {
            throw ResourcesException.unavailable("SERVICE_POINT_INACTIVE", "the service point is not issuing tokens");
        }
        Channel channel = req.channel() == null ? (req.bookingId() != null ? Channel.APPOINTMENT : Channel.WALK_IN)
                : req.channel();
        Map<String, Object> attrs = new LinkedHashMap<>(req.attributes() == null ? Map.of() : req.attributes());
        String partyId = req.partyId();
        if (req.bookingId() != null) {
            Optional<Token> existing = Jdbc.one(c, "SELECT " + TOKEN_COLS + " FROM RES_TOKEN t WHERE t.tenant_id=?"
                            + " AND t.booking_id=? AND t.service_point_id=? AND t.state IN ('WAITING','CALLED','SERVING')",
                    QueueService::mapToken, t, req.bookingId(), sp.servicePointId());
            if (existing.isPresent()) {
                return existing.get();
            }
            Map<String, Object> b = Jdbc.one(c, "SELECT state, beneficiary_id, requester_id, type_id, resource_id, starts_at"
                            + " FROM RES_BOOKING WHERE tenant_id=? AND booking_id=?", rs -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("state", rs.getString("state"));
                        m.put("beneficiaryId", rs.getString("beneficiary_id"));
                        m.put("requesterId", rs.getString("requester_id"));
                        m.put("typeId", rs.getString("type_id"));
                        m.put("resourceId", rs.getString("resource_id"));
                        Instant s = Jdbc.instant(rs, "starts_at");
                        m.put("startsAt", s == null ? null : s.toString());
                        return m;
                    }, t, req.bookingId())
                    .orElseThrow(() -> ResourcesException.notFound("booking", req.bookingId()));
            String st = (String) b.get("state");
            if (!"CONFIRMED".equals(st) && !"CHECKED_IN".equals(st)) {
                throw ResourcesException.conflict("BOOKING_NOT_CONFIRMED", "only a confirmed booking can be checked in to a queue");
            }
            if (partyId == null) {
                partyId = b.get("beneficiaryId") != null ? (String) b.get("beneficiaryId") : (String) b.get("requesterId");
            }
            attrs.putIfAbsent("booking", b);
        }

        Map<String, Object> cfg = sp.config();
        String skill = req.skill();
        String priorityClass = req.priorityClass();
        Integer level = null;
        String series = null;
        String evidence = null;

        // routing (skill) — DMN first, then configuration
        Map<String, Object> inputs = new LinkedHashMap<>(attrs);
        inputs.put("servicePointId", sp.servicePointId());
        inputs.put("channel", channel.name());
        inputs.put("routingKey", req.routingKey());
        inputs.put("skill", skill);
        inputs.put("priorityClass", priorityClass);
        inputs.put("appointment", req.bookingId() != null);
        Optional<RuleEvaluator.Outcome> routing = ctx.rules().evaluate(t, sp.routingDecision(), inputs);
        if (routing.isPresent() && routing.get().first().isPresent()) {
            Map<String, Object> row = routing.get().first().get();
            if (row.get("skill") != null) {
                skill = row.get("skill").toString();
            }
            if (row.get("series") != null) {
                series = row.get("series").toString();
            }
            evidence = routing.get().key() + "@" + routing.get().hash();
        }
        if (skill == null && req.routingKey() != null) {
            Object s = Sql.asMap(cfg.get("skillRouting")).get(req.routingKey());
            skill = Sql.str(s);
        }
        if (skill == null) {
            skill = Sql.str(cfg.getOrDefault("defaultSkill", DEFAULT_SKILL));
        }

        // priority — DMN first, then configuration
        inputs.put("skill", skill);
        Optional<RuleEvaluator.Outcome> prio = ctx.rules().evaluate(t, sp.priorityDecision(), inputs);
        if (prio.isPresent() && prio.get().first().isPresent()) {
            Map<String, Object> row = prio.get().first().get();
            if (row.get("priorityClass") != null) {
                priorityClass = row.get("priorityClass").toString();
            }
            level = Sql.intOrNull(row.get("priorityLevel"));
            evidence = (evidence == null ? "" : evidence + ";") + prio.get().key() + "@" + prio.get().hash();
        }
        if (level == null) {
            Map<String, Object> levels = Sql.asMap(cfg.get("priorityLevels"));
            if (priorityClass != null && levels.containsKey(priorityClass)) {
                level = Sql.intOrNull(levels.get(priorityClass));
            } else if (req.bookingId() != null && cfg.get("appointmentLevel") != null) {
                level = Sql.intOrNull(cfg.get("appointmentLevel"));
            }
        }
        if (level == null) {
            level = DEFAULT_LEVEL;
        }
        if (series == null) {
            series = Sql.str(Sql.asMap(cfg.get("seriesBySkill")).get(skill));
        }
        if (series == null) {
            series = "";
        }
        if (evidence != null) {
            attrs.put("decisionEvidence", evidence);
        }

        Instant now = Sql.dbNow(c, d);
        Token tok = insertToken(c, sp, now, now, channel, req.bookingId(), partyId, priorityClass, level, skill, series,
                null, null, attrs);
        callEvent(c, actor, sp.servicePointId(), tok.tokenId(), null, CallKind.ISSUE, channel.name());
        publish(c, sp.servicePointId(), CallKind.ISSUE, tok, null, Map.of("skill", skill));
        ctx.metrics().record(t, "queue.token.issued", 1, Map.of("servicePointId", sp.servicePointId(), "skill", skill));
        return tok;
    }

    /** Calls the next eligible token to a counter; empty when nobody is waiting for the counter's skills. */
    public Optional<Token> callNext(Connection c, SecurityIdentity actor, String counterId) {
        Counter k = lockCounter(c, actor, counterId);
        if (k.status() != CounterStatus.OPEN) {
            throw ResourcesException.conflict("COUNTER_NOT_OPEN", "the counter is not open");
        }
        if (k.currentTokenId() != null) {
            Token cur = tokenRaw(c, actor.tenantId(), k.currentTokenId());
            if (cur.state() == TokenState.CALLED || cur.state() == TokenState.SERVING) {
                throw ResourcesException.conflict("COUNTER_BUSY", "complete, skip or transfer the current token first");
            }
        }
        String t = actor.tenantId();
        String where = "t.tenant_id=? AND t.service_point_id=? AND t.state='WAITING' AND (t.counter_id IS NULL OR t.counter_id=?)";
        List<Object> params = new ArrayList<>(List.of(t, k.servicePointId(), counterId));
        if (!k.skills().isEmpty()) {
            where += " AND t.skill IN (SELECT s.skill FROM RES_COUNTER_SKILL s WHERE s.tenant_id=? AND s.counter_id=?)";
            params.add(t);
            params.add(counterId);
        }
        String order = "t.priority_level, t.queued_at, t.token_no";
        Optional<String[]> picked;
        if (d == ResourcesDialect.ORACLE) {
            picked = Optional.empty();
            List<String> candidates = Jdbc.query(c, d.page("SELECT t.token_id FROM RES_TOKEN t WHERE " + where
                    + " ORDER BY " + order, 0, ORACLE_CANDIDATES), rs -> rs.getString(1), params.toArray());
            for (String id : candidates) {
                List<String[]> got = Jdbc.query(c, d.skipLockedSelect("t.token_id, t.row_version", "RES_TOKEN", "t",
                                "t.tenant_id=? AND t.token_id=? AND t.state='WAITING'", "t.token_id", 1),
                        rs -> new String[]{rs.getString(1), rs.getString(2)}, t, id);
                if (!got.isEmpty()) {
                    picked = Optional.of(got.get(0));
                    break;
                }
            }
        } else {
            picked = Jdbc.query(c, d.skipLockedSelect("t.token_id, t.row_version", "RES_TOKEN", "t", where, order, 1),
                    rs -> new String[]{rs.getString(1), rs.getString(2)}, params.toArray()).stream().findFirst();
        }
        if (picked.isEmpty()) {
            return Optional.empty();
        }
        Token tok = tokenRaw(c, t, picked.get()[0]);
        Instant now = Sql.dbNow(c, d);
        Token called = updateToken(c, tok, "state='CALLED', counter_id=?, called_at=?", List.of(counterId, now));
        updateCounter(c, k, k.status(), k.operatorId(), called.tokenId());
        callEvent(c, actor, k.servicePointId(), called.tokenId(), counterId, CallKind.CALL, null);
        publish(c, k.servicePointId(), CallKind.CALL, called, k, Map.of());
        notifyParty(c, t, spRaw(c, t, k.servicePointId()), called, k, "queue.token.called");
        long waited = Duration.between(called.queuedAt(), now).toSeconds();
        ctx.metrics().record(t, "queue.wait.seconds", waited, Map.of("servicePointId", k.servicePointId(),
                "counterId", counterId, "skill", called.skill()));
        return Optional.of(called);
    }

    /** Calls a specific waiting token to a counter (out-of-order call, audited). */
    public Token callToken(Connection c, SecurityIdentity actor, String counterId, String tokenId, String reason) {
        Counter k = lockCounter(c, actor, counterId);
        if (k.status() != CounterStatus.OPEN) {
            throw ResourcesException.conflict("COUNTER_NOT_OPEN", "the counter is not open");
        }
        if (k.currentTokenId() != null) {
            Token cur = tokenRaw(c, actor.tenantId(), k.currentTokenId());
            if (cur.state() == TokenState.CALLED || cur.state() == TokenState.SERVING) {
                throw ResourcesException.conflict("COUNTER_BUSY", "complete, skip or transfer the current token first");
            }
        }
        Token tok = tokenRaw(c, actor.tenantId(), tokenId);
        requireState(tok, TokenState.WAITING);
        if (!tok.servicePointId().equals(k.servicePointId())) {
            throw ResourcesException.conflict("OTHER_SERVICE_POINT", "the token is queued at another service point");
        }
        Instant now = Sql.dbNow(c, d);
        Token called = updateToken(c, tok, "state='CALLED', counter_id=?, called_at=?", List.of(counterId, now));
        updateCounter(c, k, k.status(), k.operatorId(), called.tokenId());
        callEvent(c, actor, k.servicePointId(), tokenId, counterId, CallKind.CALL, reason == null ? "out of order" : reason);
        publish(c, k.servicePointId(), CallKind.CALL, called, k, Map.of());
        notifyParty(c, actor.tenantId(), spRaw(c, actor.tenantId(), k.servicePointId()), called, k, "queue.token.called");
        return called;
    }

    /** Recalls the counter's current token; beyond {@code maxRecalls} the token becomes NO_SHOW. */
    public Token recall(Connection c, SecurityIdentity actor, String counterId) {
        Counter k = lockCounter(c, actor, counterId);
        Token cur = currentToken(c, actor, k);
        requireState(cur, TokenState.CALLED);
        ServicePoint sp = spRaw(c, actor.tenantId(), k.servicePointId());
        int max = Sql.intOr(sp.config().get("maxRecalls"), 2);
        if (cur.recallCount() >= max) {
            Token ns = updateToken(c, cur, "state='NO_SHOW', outcome='NO_SHOW'", List.of());
            updateCounter(c, k, k.status(), k.operatorId(), null);
            callEvent(c, actor, k.servicePointId(), cur.tokenId(), counterId, CallKind.NO_SHOW, "recall limit " + max);
            publish(c, k.servicePointId(), CallKind.NO_SHOW, ns, k, Map.of());
            return ns;
        }
        Instant now = Sql.dbNow(c, d);
        Token r = updateToken(c, cur, "recall_count=recall_count+1, called_at=?", List.of(now));
        callEvent(c, actor, k.servicePointId(), cur.tokenId(), counterId, CallKind.RECALL, String.valueOf(r.recallCount()));
        publish(c, k.servicePointId(), CallKind.RECALL, r, k, Map.of("recallCount", r.recallCount()));
        notifyParty(c, actor.tenantId(), sp, r, k, "queue.token.recalled");
        return r;
    }

    /**
     * Skips the counter's current (called) token. With {@code requeueOnSkip} the token returns to the queue behind the
     * tokens already waiting; otherwise it ends as SKIPPED.
     */
    public Token skip(Connection c, SecurityIdentity actor, String counterId, String reason) {
        Counter k = lockCounter(c, actor, counterId);
        Token cur = currentToken(c, actor, k);
        requireState(cur, TokenState.CALLED);
        ServicePoint sp = spRaw(c, actor.tenantId(), k.servicePointId());
        Token out;
        if (Sql.bool(sp.config().get("requeueOnSkip"), false)) {
            out = updateToken(c, cur, "state='WAITING', counter_id=NULL, called_at=NULL, queued_at=?",
                    List.of(Sql.dbNow(c, d)));
        } else {
            out = updateToken(c, cur, "state='SKIPPED', outcome='SKIPPED'", List.of());
        }
        updateCounter(c, k, k.status(), k.operatorId(), null);
        callEvent(c, actor, k.servicePointId(), cur.tokenId(), counterId, CallKind.SKIP, reason);
        publish(c, k.servicePointId(), CallKind.SKIP, out, k, Map.of());
        return out;
    }

    /** The party arrived at the counter: CALLED → SERVING. */
    public Token startServing(Connection c, SecurityIdentity actor, String counterId) {
        Counter k = lockCounter(c, actor, counterId);
        Token cur = currentToken(c, actor, k);
        requireState(cur, TokenState.CALLED);
        Token s = updateToken(c, cur, "state='SERVING', serve_started_at=?", List.of(Sql.dbNow(c, d)));
        callEvent(c, actor, k.servicePointId(), cur.tokenId(), counterId, CallKind.SERVE, null);
        publish(c, k.servicePointId(), CallKind.SERVE, s, k, Map.of());
        return s;
    }

    /** Completes the counter's current token with an outcome code. */
    public Token complete(Connection c, SecurityIdentity actor, String counterId, String outcome) {
        Counter k = lockCounter(c, actor, counterId);
        Token cur = currentToken(c, actor, k);
        if (cur.state() != TokenState.SERVING && cur.state() != TokenState.CALLED) {
            throw ResourcesException.conflict("TOKEN_STATE", "token is " + cur.state());
        }
        Instant now = Sql.dbNow(c, d);
        Instant started = cur.serveStartedAt() == null ? now : cur.serveStartedAt();
        Token done = updateToken(c, cur, "state='SERVED', served_at=?, serve_started_at=?, outcome=?",
                java.util.Arrays.asList(now, started, outcome == null ? "SERVED" : outcome));
        updateCounter(c, k, k.status(), k.operatorId(), null);
        callEvent(c, actor, k.servicePointId(), cur.tokenId(), counterId, CallKind.COMPLETE, outcome);
        publish(c, k.servicePointId(), CallKind.COMPLETE, done, k, Map.of());
        ctx.metrics().record(actor.tenantId(), "queue.serve.seconds", Duration.between(started, now).toSeconds(),
                Map.of("servicePointId", k.servicePointId(), "counterId", counterId, "skill", done.skill()));
        if (done.slaDueAt() != null && done.calledAt() != null && done.calledAt().isAfter(done.slaDueAt())) {
            ctx.metrics().record(actor.tenantId(), "queue.sla.breached", 1, Map.of("servicePointId", k.servicePointId()));
        }
        return done;
    }

    /**
     * Transfers a token to another skill, counter or service point. Within the same service point the token keeps its
     * number and is re-queued; to another service point a new token is issued there (gap-free numbering of the target)
     * and the old one ends as TRANSFERRED. With {@code keepQueuedAtOnTransfer} (default true) the original queue time is
     * kept, so a transfer never sends the party to the back of the line.
     */
    public Token transfer(Connection c, SecurityIdentity actor, String tokenId, String targetServicePointId,
                          String targetSkill, String targetCounterId, String reason) {
        String t = actor.tenantId();
        Token tok = lockToken(c, t, tokenId);
        if (!tok.state().active()) {
            throw ResourcesException.conflict("TOKEN_STATE", "token is " + tok.state());
        }
        ctx.guard().require(c, actor, ResourcesContext.RT_QUEUE, ResourcesContext.P_WORK, tok.servicePointId());
        String targetSp = targetServicePointId == null ? tok.servicePointId() : targetServicePointId;
        ServicePoint target = spRaw(c, t, targetSp);
        if (targetCounterId != null) {
            Counter tc = counterRaw(c, t, targetCounterId);
            if (!tc.servicePointId().equals(targetSp)) {
                throw ResourcesException.invalid("COUNTER", "target counter is not at the target service point");
            }
        }
        String skill = targetSkill == null ? tok.skill() : targetSkill;
        boolean keep = Sql.bool(target.config().get("keepQueuedAtOnTransfer"), true);
        Instant now = Sql.dbNow(c, d);
        Instant queuedAt = keep ? tok.queuedAt() : now;
        String fromCounter = tok.counterId();
        if (fromCounter != null) {
            releaseCounter(c, t, fromCounter, tok.tokenId());
        }
        Token result;
        if (targetSp.equals(tok.servicePointId())) {
            result = updateToken(c, tok, "state='WAITING', skill=?, counter_id=?, called_at=NULL, serve_started_at=NULL,"
                            + " queued_at=?, transferred_from=?",
                    java.util.Arrays.asList(skill, targetCounterId, queuedAt, fromCounter == null ? tok.servicePointId() : fromCounter));
            callEvent(c, actor, tok.servicePointId(), tok.tokenId(), fromCounter, CallKind.TRANSFER, reason);
            publish(c, tok.servicePointId(), CallKind.TRANSFER, result, null, Map.of("skill", skill));
        } else {
            if (!"ACTIVE".equals(target.status())) {
                throw ResourcesException.unavailable("SERVICE_POINT_INACTIVE", "the target service point is not active");
            }
            Map<String, Object> attrs = new LinkedHashMap<>(tok.attributes());
            attrs.put("transferredFromDisplayNo", tok.displayNo());
            String series = Sql.str(Sql.asMap(target.config().get("seriesBySkill")).get(skill));
            Token fresh = insertToken(c, target, now, queuedAt, Channel.TRANSFER, tok.bookingId(), tok.partyId(),
                    tok.priorityClass(), tok.priorityLevel(), skill, series == null ? "" : series, tok.tokenId(),
                    targetCounterId, attrs);
            updateToken(c, tok, "state='TRANSFERRED', outcome=?", List.of("TRANSFERRED:" + fresh.tokenId()));
            callEvent(c, actor, tok.servicePointId(), tok.tokenId(), fromCounter, CallKind.TRANSFER,
                    "to " + targetSp + (reason == null ? "" : ": " + reason));
            callEvent(c, actor, targetSp, fresh.tokenId(), null, CallKind.ISSUE, "transfer from " + tok.displayNo());
            publish(c, tok.servicePointId(), CallKind.TRANSFER, tok, null, Map.of("toServicePointId", targetSp,
                    "newDisplayNo", fresh.displayNo()));
            publish(c, targetSp, CallKind.ISSUE, fresh, null, Map.of("transferredFrom", tok.displayNo()));
            notifyParty(c, t, target, fresh, null, "queue.token.transferred");
            result = fresh;
        }
        return result;
    }

    /** Cancels an active token (the party left, or withdrew by SMS/QR). */
    public Token cancel(Connection c, SecurityIdentity actor, String tokenId, String reason) {
        String t = actor.tenantId();
        Token tok = lockToken(c, t, tokenId);
        if (!tok.state().active()) {
            throw ResourcesException.conflict("TOKEN_STATE", "token is " + tok.state());
        }
        ctx.guard().require(c, actor, ResourcesContext.RT_QUEUE, ResourcesContext.P_WORK, tok.servicePointId());
        if (tok.counterId() != null && tok.state() != TokenState.WAITING) {
            releaseCounter(c, t, tok.counterId(), tok.tokenId());
        }
        Token out = updateToken(c, tok, "state='CANCELLED', outcome=?", List.of(reason == null ? "CANCELLED" : reason));
        callEvent(c, actor, tok.servicePointId(), tokenId, tok.counterId(), CallKind.CANCEL, reason);
        publish(c, tok.servicePointId(), CallKind.CANCEL, out, null, Map.of());
        return out;
    }

    /**
     * No-show sweep: tokens called more than {@code noShowAfterMinutes} (default 10) ago and never served, and every
     * still-active token of an earlier day, end as NO_SHOW. Returns the number of tokens closed.
     */
    public int noShowSweep(Connection c, SecurityIdentity actor, String servicePointId) {
        ctx.guard().require(c, actor, ResourcesContext.RT_QUEUE, ResourcesContext.P_WORK, servicePointId);
        String t = actor.tenantId();
        ServicePoint sp = spRaw(c, t, servicePointId);
        Instant now = Sql.dbNow(c, d);
        Instant calledBefore = now.minus(Duration.ofMinutes(Sql.intOr(sp.config().get("noShowAfterMinutes"), 10)));
        String today = dayKey(c, sp, now);
        List<String> ids = Jdbc.query(c, "SELECT t.token_id FROM RES_TOKEN t WHERE t.tenant_id=? AND t.service_point_id=?"
                        + " AND ((t.state='CALLED' AND t.called_at < ?) OR (t.state IN ('WAITING','CALLED') AND t.day_key < ?))",
                rs -> rs.getString(1), t, servicePointId, calledBefore, today);
        int n = 0;
        for (String id : ids) {
            Token tok = lockToken(c, t, id);
            if (tok.state() != TokenState.CALLED && tok.state() != TokenState.WAITING) {
                continue;
            }
            if (tok.counterId() != null && tok.state() == TokenState.CALLED) {
                releaseCounter(c, t, tok.counterId(), tok.tokenId());
            }
            Token ns = updateToken(c, tok, "state='NO_SHOW', outcome='NO_SHOW'", List.of());
            callEvent(c, actor, servicePointId, id, tok.counterId(), CallKind.NO_SHOW, "sweep");
            publish(c, servicePointId, CallKind.NO_SHOW, ns, null, Map.of());
            n++;
        }
        return n;
    }

    private Token tokenRaw(Connection c, String tenantId, String tokenId) {
        return Jdbc.one(c, "SELECT " + TOKEN_COLS + " FROM RES_TOKEN t WHERE t.tenant_id=? AND t.token_id=?",
                QueueService::mapToken, tenantId, tokenId).orElseThrow(() -> ResourcesException.notFound("token", tokenId));
    }

    private VisibleQuery tokenQuery(SecurityIdentity actor) {
        return VisibleQuery.on(ctx, actor, ResourcesContext.RT_TOKEN, ResourcesContext.P_READ, "RES_TOKEN", "t")
                .select(TOKEN_COLS);
    }

    /** A visible token, or NOT_FOUND. */
    public Token token(Connection c, SecurityIdentity actor, String tokenId) {
        return tokenQuery(actor).require(c, "token_id", tokenId, QueueService::mapToken, "token");
    }

    private VisibleQuery tokensOf(SecurityIdentity actor, String servicePointId, TokenState state) {
        VisibleQuery q = tokenQuery(actor).where("t.service_point_id=?", servicePointId);
        return state == null ? q.where("t.state IN ('WAITING','CALLED','SERVING')") : q.where("t.state=?", state.name());
    }

    /** Visible tokens of a service point in a state (null = all active), queue order. */
    public List<Token> tokens(Connection c, SecurityIdentity actor, String servicePointId, TokenState state, int offset,
                              int limit) {
        return tokensOf(actor, servicePointId, state).orderBy("t.priority_level, t.queued_at, t.token_no")
                .list(c, QueueService::mapToken, offset, limit);
    }

    /** Capped count of visible tokens (A-16: "more than {@code cap}"). */
    public VisibleQuery.CappedCount countTokens(Connection c, SecurityIdentity actor, String servicePointId, TokenState state,
                                                int cap) {
        return tokensOf(actor, servicePointId, state).count(c, cap);
    }

    /**
     * Position of a visible waiting token in its queue (1-based, among tokens of the same skill) and its expected wait.
     * The position is an aggregate over the whole queue (it reveals no hidden row).
     */
    public Map<String, Object> position(Connection c, SecurityIdentity actor, String tokenId) {
        Token tok = token(c, actor, tokenId);
        String tenantId = actor.tenantId();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tokenId", tokenId);
        out.put("displayNo", tok.displayNo());
        out.put("state", tok.state().name());
        if (tok.state() != TokenState.WAITING) {
            return out;
        }
        long ahead = Jdbc.one(c, "SELECT COUNT(*) FROM RES_TOKEN t WHERE t.tenant_id=? AND t.service_point_id=?"
                        + " AND t.state='WAITING' AND t.skill=? AND (t.priority_level < ? OR (t.priority_level = ?"
                        + " AND (t.queued_at < ? OR (t.queued_at = ? AND t.token_no < ?))))",
                rs -> rs.getLong(1), tenantId, tok.servicePointId(), tok.skill(), tok.priorityLevel(), tok.priorityLevel(),
                tok.queuedAt(), tok.queuedAt(), tok.tokenNo()).orElse(0L);
        out.put("position", ahead + 1);
        Instant now = Sql.dbNow(c, d);
        QueueModel.QueueStats st = computeStats(c, tenantId, tok.servicePointId(), now.minus(Duration.ofHours(2)), now);
        long counters = Jdbc.one(c, "SELECT COUNT(*) FROM RES_COUNTER WHERE tenant_id=? AND service_point_id=? AND status='OPEN'",
                rs -> rs.getLong(1), tenantId, tok.servicePointId()).orElse(0L);
        if (st.avgServeSeconds() > 0 && counters > 0) {
            out.put("expectedWaitSeconds", (long) ((ahead + 1) * st.avgServeSeconds() / counters));
        }
        return out;
    }

    /** Visible call events of a service point after {@code afterEventId} (display feed replay). */
    public List<QueueModel.CallEvent> callEvents(Connection c, SecurityIdentity actor, String servicePointId,
                                                 long afterEventId, int limit) {
        return VisibleQuery.on(ctx, actor, ResourcesContext.RT_CALL_EVENT, ResourcesContext.P_READ, "RES_CALL_EVENT", "e")
                .select("e.*").where("e.service_point_id=? AND e.event_id > ?", servicePointId, afterEventId)
                .orderBy("e.event_id")
                .list(c, rs -> new QueueModel.CallEvent(rs.getLong("event_id"), rs.getString("service_point_id"),
                        rs.getString("token_id"), rs.getString("counter_id"), CallKind.valueOf(rs.getString("kind")),
                        rs.getString("actor_id"), rs.getString("detail"), Jdbc.instant(rs, "at_time")),
                        0, Math.max(limit, 1));
    }

    // ================================================================== SLA, statistics, display

    /** Visible waiting tokens whose SLA due time has passed without being called. */
    public List<Token> slaBreaches(Connection c, SecurityIdentity actor, String servicePointId) {
        Instant now = Sql.dbNow(c, d);
        return tokenQuery(actor).where("t.service_point_id=? AND t.state='WAITING' AND t.sla_due_at IS NOT NULL"
                        + " AND t.sla_due_at < ?", servicePointId, now)
                .orderBy("t.sla_due_at").all(c, QueueService::mapToken, VisibleQuery.MAX_PAGE_SIZE);
    }

    /**
     * Wait / service statistics for tokens issued in [from, to) of a visible service point; also pushed to the metrics
     * port. The figures are aggregates and are computed internally.
     */
    public QueueModel.QueueStats stats(Connection c, SecurityIdentity actor, String servicePointId, Instant from, Instant to) {
        servicePoint(c, actor, servicePointId);
        QueueModel.QueueStats s = computeStats(c, actor.tenantId(), servicePointId, from, to);
        Map<String, String> tags = Map.of("servicePointId", servicePointId);
        ctx.metrics().record(actor.tenantId(), "queue.stats.avg_wait_seconds", s.avgWaitSeconds(), tags);
        ctx.metrics().record(actor.tenantId(), "queue.stats.max_wait_seconds", s.maxWaitSeconds(), tags);
        ctx.metrics().record(actor.tenantId(), "queue.stats.avg_serve_seconds", s.avgServeSeconds(), tags);
        ctx.metrics().record(actor.tenantId(), "queue.stats.served", s.served(), tags);
        ctx.metrics().record(actor.tenantId(), "queue.stats.sla_breaches", s.slaBreaches(), tags);
        return s;
    }

    private QueueModel.QueueStats computeStats(Connection c, String tenantId, String servicePointId, Instant from, Instant to) {
        List<Token> all = Jdbc.query(c, "SELECT " + TOKEN_COLS + " FROM RES_TOKEN t WHERE t.tenant_id=?"
                        + " AND t.service_point_id=? AND t.issued_at >= ? AND t.issued_at < ?",
                QueueService::mapToken, tenantId, servicePointId, from, to);
        Instant now = Sql.dbNow(c, d);
        int served = 0, noShow = 0, cancelled = 0, waiting = 0, breaches = 0, waitN = 0, serveN = 0;
        double waitSum = 0, serveSum = 0;
        long waitMax = 0;
        Map<String, double[]> per = new TreeMap<>();   // counter -> {served, serveSum, waitSum, waitN}
        for (Token tk : all) {
            switch (tk.state()) {
                case SERVED -> served++;
                case NO_SHOW -> noShow++;
                case CANCELLED -> cancelled++;
                case WAITING -> waiting++;
                default -> { }
            }
            Instant calledOrNow = tk.calledAt() != null ? tk.calledAt() : (tk.state() == TokenState.WAITING ? now : null);
            if (calledOrNow != null) {
                long w = Math.max(0, Duration.between(tk.queuedAt(), calledOrNow).toSeconds());
                if (tk.calledAt() != null) {
                    waitSum += w;
                    waitN++;
                }
                waitMax = Math.max(waitMax, w);
                if (tk.slaDueAt() != null && calledOrNow.isAfter(tk.slaDueAt())) {
                    breaches++;
                }
            }
            if (tk.counterId() != null) {
                double[] a = per.computeIfAbsent(tk.counterId(), k -> new double[4]);
                if (tk.calledAt() != null) {
                    a[2] += Math.max(0, Duration.between(tk.queuedAt(), tk.calledAt()).toSeconds());
                    a[3]++;
                }
                if (tk.state() == TokenState.SERVED && tk.servedAt() != null) {
                    Instant st = tk.serveStartedAt() != null ? tk.serveStartedAt() : tk.calledAt();
                    double sv = st == null ? 0 : Math.max(0, Duration.between(st, tk.servedAt()).toSeconds());
                    a[0]++;
                    a[1] += sv;
                }
            }
            if (tk.state() == TokenState.SERVED && tk.servedAt() != null) {
                Instant st = tk.serveStartedAt() != null ? tk.serveStartedAt() : tk.calledAt();
                if (st != null) {
                    serveSum += Math.max(0, Duration.between(st, tk.servedAt()).toSeconds());
                    serveN++;
                }
            }
        }
        List<QueueModel.CounterStats> perCounter = new ArrayList<>();
        for (Map.Entry<String, double[]> e : per.entrySet()) {
            double[] a = e.getValue();
            perCounter.add(new QueueModel.CounterStats(e.getKey(), (int) a[0], a[0] == 0 ? 0 : a[1] / a[0],
                    a[3] == 0 ? 0 : a[2] / a[3]));
        }
        return new QueueModel.QueueStats(servicePointId, from, to, all.size(), served, noShow, cancelled, waiting,
                waitN == 0 ? 0 : waitSum / waitN, waitMax, serveN == 0 ? 0 : serveSum / serveN, breaches, perCounter);
    }

    /**
     * What the display board shows for a visible service point: the visible token at every visible counter and the next
     * {@code nextCount} visible waiting tokens. The waiting total is an aggregate and is counted internally.
     */
    public QueueModel.DisplaySnapshot displaySnapshot(Connection c, SecurityIdentity actor, String servicePointId,
                                                      int nextCount) {
        servicePoint(c, actor, servicePointId);
        String tenantId = actor.tenantId();
        Instant now = Sql.dbNow(c, d);
        List<QueueModel.CalledEntry> called = new ArrayList<>();
        for (Counter k : counters(c, actor, servicePointId)) {
            if (k.currentTokenId() == null || k.status() == CounterStatus.CLOSED) {
                continue;
            }
            Optional<Token> cur = tokenQuery(actor).byId(c, "token_id", k.currentTokenId(), QueueService::mapToken);
            if (cur.isPresent() && (cur.get().state() == TokenState.CALLED || cur.get().state() == TokenState.SERVING)) {
                Token tk = cur.get();
                called.add(new QueueModel.CalledEntry(k.counterId(), k.name(), tk.tokenId(), tk.displayNo(), tk.state(),
                        tk.calledAt()));
            }
        }
        List<QueueModel.WaitingEntry> next = new ArrayList<>();
        if (nextCount > 0) {
            for (Token tk : tokens(c, actor, servicePointId, TokenState.WAITING, 0, nextCount)) {
                next.add(new QueueModel.WaitingEntry(tk.tokenId(), tk.displayNo(), tk.priorityClass(), tk.skill(),
                        tk.queuedAt()));
            }
        }
        int waiting = Jdbc.one(c, "SELECT COUNT(*) FROM RES_TOKEN WHERE tenant_id=? AND service_point_id=? AND state='WAITING'",
                rs -> rs.getInt(1), tenantId, servicePointId).orElse(0);
        return new QueueModel.DisplaySnapshot(servicePointId, now, called, next, waiting);
    }

    // ================================================================== internals

    private Token insertToken(Connection c, ServicePoint sp, Instant issuedAt, Instant queuedAt, Channel channel,
                              String bookingId, String partyId, String priorityClass, int level, String skill,
                              String series, String transferredFrom, String counterId, Map<String, Object> attrs) {
        String t = sp.tenantId();
        String day = dayKey(c, sp, issuedAt);
        long no = nextNumber(c, t, sp.servicePointId(), day, series);
        int width = Sql.intOr(sp.config().get("numberWidth"), 3);
        String display = (sp.tokenPrefix() == null ? "" : sp.tokenPrefix()) + series
                + String.format("%0" + Math.max(1, width) + "d", no);
        int sla = sp.slaMinutes();
        if (priorityClass != null) {
            Integer byClass = Sql.intOrNull(Sql.asMap(sp.config().get("slaMinutesByClass")).get(priorityClass));
            if (byClass != null) {
                sla = byClass;
            }
        }
        Instant slaDue = sla > 0 ? queuedAt.plus(Duration.ofMinutes(sla)) : null;
        String id = Sql.newId();
        Jdbc.update(c, "INSERT INTO RES_TOKEN(tenant_id, token_id, service_point_id, day_key, token_no, display_no, channel,"
                        + " booking_id, party_id, priority_class, priority_level, skill, state, counter_id, transferred_from,"
                        + " recall_count, issued_at, queued_at, called_at, serve_started_at, served_at, sla_due_at, outcome,"
                        + " attributes_json, row_version) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,'WAITING',?,?,0,?,?,NULL,NULL,NULL,?,NULL,?,0)",
                t, id, sp.servicePointId(), day, (int) no, display, channel.name(), bookingId, partyId, priorityClass, level,
                skill, counterId, transferredFrom, issuedAt, queuedAt, slaDue, Sql.toJson(attrs));
        return tokenRaw(c, t, id);
    }

    /** Gap-free number per (service point, day, series): the sequence row is locked for the rest of the transaction. */
    private long nextNumber(Connection c, String tenantId, String servicePointId, String day, String series) {
        String where = "tenant_id=? AND service_point_id=? AND day_key=? AND series=?";
        Optional<Long> last = Jdbc.one(c, d.lockingSelect("last_no", "RES_TOKEN_SEQ", where), rs -> rs.getLong(1),
                tenantId, servicePointId, day, series);
        if (last.isEmpty()) {
            d.insertIfAbsent(c, "INSERT INTO RES_TOKEN_SEQ(tenant_id, service_point_id, day_key, series, last_no)"
                    + " VALUES (?,?,?,?,0)", tenantId, servicePointId, day, series);
            last = Jdbc.one(c, d.lockingSelect("last_no", "RES_TOKEN_SEQ", where), rs -> rs.getLong(1),
                    tenantId, servicePointId, day, series);
        }
        long next = last.orElseThrow(() -> new IllegalStateException("token sequence row missing")) + 1;
        Jdbc.update(c, "UPDATE RES_TOKEN_SEQ SET last_no=? WHERE " + where, next, tenantId, servicePointId, day, series);
        return next;
    }

    private String dayKey(Connection c, ServicePoint sp, Instant at) {
        return LocalDate.ofInstant(at, zone(c, sp)).toString();
    }

    private ZoneId zone(Connection c, ServicePoint sp) {
        Object z = sp.config().get("zone");
        if (z != null) {
            return ZoneId.of(z.toString());
        }
        if (sp.calendarId() != null) {
            Optional<String> cz = Jdbc.one(c, "SELECT zone_id FROM RES_CALENDAR WHERE tenant_id=? AND calendar_id=?",
                    rs -> rs.getString(1), sp.tenantId(), sp.calendarId());
            if (cz.isPresent() && cz.get() != null) {
                return ZoneId.of(cz.get());
            }
        }
        return ZoneOffset.UTC;
    }

    private Counter lockCounter(Connection c, SecurityIdentity actor, String counterId) {
        String t = actor.tenantId();
        Counter k = Jdbc.one(c, d.lockingSelect("*", "RES_COUNTER", "tenant_id=? AND counter_id=?"),
                rs -> mapCounter(rs, Set.of()), t, counterId)
                .orElseThrow(() -> ResourcesException.notFound("counter", counterId));
        ctx.guard().require(c, actor, ResourcesContext.RT_QUEUE, ResourcesContext.P_WORK, k.servicePointId());
        return withSkills(c, k);
    }

    private Token lockToken(Connection c, String tenantId, String tokenId) {
        Optional<String> id = Jdbc.one(c, d.lockingSelect("token_id", "RES_TOKEN", "tenant_id=? AND token_id=?"),
                rs -> rs.getString(1), tenantId, tokenId);
        if (id.isEmpty()) {
            throw ResourcesException.notFound("token", tokenId);
        }
        return tokenRaw(c, tenantId, tokenId);
    }

    private Token currentToken(Connection c, SecurityIdentity actor, Counter k) {
        if (k.currentTokenId() == null) {
            throw ResourcesException.conflict("NO_CURRENT_TOKEN", "the counter has no current token");
        }
        return tokenRaw(c, actor.tenantId(), k.currentTokenId());
    }

    private static void requireState(Token tok, TokenState expected) {
        if (tok.state() != expected) {
            throw ResourcesException.conflict("TOKEN_STATE", "token is " + tok.state() + ", expected " + expected);
        }
    }

    /** Optimistic update of a token (row_version), returning the fresh row. */
    private Token updateToken(Connection c, Token tok, String setClause, List<?> params) {
        List<Object> p = new ArrayList<>(params);
        p.add(tok.tenantId());
        p.add(tok.tokenId());
        p.add(tok.rowVersion());
        int n = Jdbc.update(c, "UPDATE RES_TOKEN SET " + setClause + ", row_version=row_version+1"
                + " WHERE tenant_id=? AND token_id=? AND row_version=?", p.toArray());
        if (n == 0) {
            throw ResourcesException.stale("token " + tok.tokenId() + " was changed concurrently");
        }
        return tokenRaw(c, tok.tenantId(), tok.tokenId());
    }

    private void updateCounter(Connection c, Counter k, CounterStatus status, String operatorId, String currentTokenId) {
        int n = Jdbc.update(c, "UPDATE RES_COUNTER SET status=?, operator_id=?, current_token_id=?, updated_at=?,"
                        + " row_version=row_version+1 WHERE tenant_id=? AND counter_id=? AND row_version=?",
                status.name(), operatorId, currentTokenId, Sql.dbNow(c, d), k.tenantId(), k.counterId(), k.rowVersion());
        if (n == 0) {
            throw ResourcesException.stale("counter " + k.counterId() + " was changed concurrently");
        }
    }

    /** Clears a counter's current token if it still points at {@code tokenId}. */
    private void releaseCounter(Connection c, String tenantId, String counterId, String tokenId) {
        Jdbc.update(c, "UPDATE RES_COUNTER SET current_token_id=NULL, updated_at=?, row_version=row_version+1"
                + " WHERE tenant_id=? AND counter_id=? AND current_token_id=?", Sql.dbNow(c, d), tenantId, counterId, tokenId);
    }

    private Counter withSkills(Connection c, Counter k) {
        List<String> skills = Jdbc.query(c, "SELECT skill FROM RES_COUNTER_SKILL WHERE tenant_id=? AND counter_id=? ORDER BY skill",
                rs -> rs.getString(1), k.tenantId(), k.counterId());
        return new Counter(k.tenantId(), k.counterId(), k.servicePointId(), k.name(), k.status(), k.operatorId(),
                k.currentTokenId(), Set.copyOf(skills), k.rowVersion());
    }

    private void callEvent(Connection c, SecurityIdentity actor, String servicePointId, String tokenId, String counterId,
                           CallKind kind, String detail) {
        Jdbc.update(c, "INSERT INTO RES_CALL_EVENT(tenant_id, service_point_id, token_id, counter_id, kind, actor_id, detail,"
                        + " at_time) VALUES (?,?,?,?,?,?,?," + d.now() + ")",
                actor.tenantId(), servicePointId, tokenId, counterId, kind.name(), actor.personId(), detail);
    }

    private void publish(Connection c, String servicePointId, CallKind kind, Token tok, Counter k,
                         Map<String, Object> data) {
        String tenant = tok != null ? tok.tenantId() : k.tenantId();
        ctx.feed(c, tenant, new ResourcesPorts.FeedEvent(servicePointId, kind.name(),
                tok == null ? null : tok.tokenId(), tok == null ? null : tok.displayNo(),
                k == null ? (tok == null ? null : tok.counterId()) : k.counterId(), k == null ? null : k.name(),
                Sql.dbNow(c, d), data));
    }

    private void notifyParty(Connection c, String tenantId, ServicePoint sp, Token tok, Counter k, String template) {
        if (tok.partyId() == null || !Sql.bool(sp.config().get("notifyOnCall"), false)) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("servicePointId", sp.servicePointId());
        data.put("servicePointName", sp.name());
        data.put("displayNo", tok.displayNo());
        if (k != null) {
            data.put("counterId", k.counterId());
            data.put("counterName", k.name());
        }
        ctx.notify(c, tenantId, tok.partyId(), template, data);
    }

    // ------------------------------------------------------------------ mappers

    private static ServicePoint mapServicePoint(ResultSet rs) throws SQLException {
        return new ServicePoint(rs.getString("tenant_id"), rs.getString("service_point_id"), rs.getString("name"),
                rs.getString("resource_id"), rs.getString("calendar_id"), rs.getString("token_prefix"),
                rs.getInt("sla_minutes"), rs.getString("priority_decision"), rs.getString("routing_decision"),
                Sql.mapJson(Sql.text(rs, "config_json")), rs.getString("status"), Jdbc.instant(rs, "updated_at"));
    }

    private static Counter mapCounter(ResultSet rs, Set<String> skills) throws SQLException {
        return new Counter(rs.getString("tenant_id"), rs.getString("counter_id"), rs.getString("service_point_id"),
                rs.getString("name"), CounterStatus.valueOf(rs.getString("status")), rs.getString("operator_id"),
                rs.getString("current_token_id"), skills, rs.getLong("row_version"));
    }

    private static Token mapToken(ResultSet rs) throws SQLException {
        return new Token(rs.getString("tenant_id"), rs.getString("token_id"), rs.getString("service_point_id"),
                rs.getString("day_key"), rs.getInt("token_no"), rs.getString("display_no"),
                Channel.valueOf(rs.getString("channel")), rs.getString("booking_id"), rs.getString("party_id"),
                rs.getString("priority_class"), rs.getInt("priority_level"), rs.getString("skill"),
                TokenState.valueOf(rs.getString("state")), rs.getString("counter_id"), rs.getString("transferred_from"),
                rs.getInt("recall_count"), Jdbc.instant(rs, "issued_at"), Jdbc.instant(rs, "queued_at"),
                Jdbc.instant(rs, "called_at"), Jdbc.instant(rs, "serve_started_at"), Jdbc.instant(rs, "served_at"),
                Jdbc.instant(rs, "sla_due_at"), rs.getString("outcome"), Sql.mapJson(Sql.text(rs, "attributes_json")),
                rs.getLong("row_version"));
    }
}

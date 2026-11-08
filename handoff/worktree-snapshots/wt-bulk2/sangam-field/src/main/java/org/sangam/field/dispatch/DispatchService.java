// SPDX-License-Identifier: Apache-2.0
package org.sangam.field.dispatch;

import com.fasterxml.jackson.core.type.TypeReference;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.field.FieldAudit;
import org.sangam.field.FieldConfig;
import org.sangam.field.FieldException;
import org.sangam.field.FieldJson;
import org.sangam.field.dispatch.DispatchModel.Candidate;
import org.sangam.field.dispatch.DispatchModel.Eta;
import org.sangam.field.dispatch.DispatchModel.Fix;
import org.sangam.field.dispatch.DispatchModel.Job;
import org.sangam.field.dispatch.DispatchModel.JobStatus;
import org.sangam.field.dispatch.DispatchModel.Leg;
import org.sangam.field.dispatch.DispatchModel.Offer;
import org.sangam.field.dispatch.DispatchModel.OfferStatus;
import org.sangam.field.dispatch.DispatchModel.PositionFix;
import org.sangam.field.dispatch.DispatchModel.Trip;
import org.sangam.field.dispatch.DispatchModel.TripStatus;
import org.sangam.field.dispatch.DispatchModel.Unit;
import org.sangam.field.dispatch.DispatchModel.UnitStatus;
import org.sangam.field.ports.FieldPorts.FieldEvents;
import org.sangam.field.support.FieldSql;
import org.sangam.geo.model.GeoMath;
import org.sangam.geo.model.GeoPoint;
import org.sangam.geo.rules.Nearest;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Dispatch of field units (N17): candidate units by distance and skill (N13 nearest-N, R82), offer → accept / decline
 * / timeout → re-dispatch, job and unit state machines kept in step, position pings with automatic ON_SCENE inside the
 * arrival radius, ETA, response-time SLA, closure with proof of service (an N05 packet), and trips with legs, odometer
 * and track distance, approved by someone other than the person who started the trip.
 */
public final class DispatchService {

    public record UnitInput(String id, String kind, String label, Set<String> skills, Double homeLat, Double homeLon, String orgUnitRef,
                            Map<String, Object> attributes) {
    }

    public record JobInput(String kind, String priority, double lat, double lon, String address, Set<String> requiredSkills,
                           Long slaTargetSeconds, String businessKey, Map<String, Object> attributes, boolean autoDispatch) {
    }

    private final Transactions tx;
    private final Dialect dialect;
    private final FieldConfig config;
    private final FieldAudit audit;
    private final FieldEvents events;
    private final Clock clock;

    public DispatchService(Transactions tx, Dialect dialect, FieldConfig config, FieldAudit audit, FieldEvents events, Clock clock) {
        this.tx = tx;
        this.dialect = dialect;
        this.config = config == null ? FieldConfig.defaults() : config;
        this.audit = audit;
        this.events = events;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    // ------------------------------------------------------------------ units

    public Unit upsertUnit(String tenantId, UnitInput in, String actor) {
        if (in.kind() == null || in.kind().isBlank()) {
            throw new FieldException("INVALID_REQUEST", "unit kind is required");
        }
        String id = in.id() == null || in.id().isBlank() ? FieldSql.newId() : in.id();
        return tx.inTransaction(c -> {
            Instant now = clock.instant();
            Optional<Unit> existing = findUnit(c, tenantId, id);
            if (existing.isPresent()) {
                Jdbc.update(c, "UPDATE FLD_UNIT SET kind = ?, label = ?, skills_json = ?, home_lat_e7 = ?, home_lon_e7 = ?, org_unit_ref = ?, "
                                + "attributes_json = ?, version = version + 1, updated_at = ? WHERE tenant_id = ? AND unit_id = ?",
                        in.kind(), in.label(), FieldJson.write(in.skills() == null ? Set.of() : in.skills()), FieldSql.e7(in.homeLat()),
                        FieldSql.e7(in.homeLon()), in.orgUnitRef(), FieldJson.write(in.attributes()), now, tenantId, id);
            } else {
                Jdbc.update(c, "INSERT INTO FLD_UNIT (tenant_id, unit_id, kind, label, skills_json, home_lat_e7, home_lon_e7, status, "
                                + "org_unit_ref, attributes_json, version, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?)",
                        tenantId, id, in.kind(), in.label(), FieldJson.write(in.skills() == null ? Set.of() : in.skills()),
                        FieldSql.e7(in.homeLat()), FieldSql.e7(in.homeLon()), UnitStatus.OFF_DUTY, in.orgUnitRef(),
                        FieldJson.write(in.attributes()), now);
            }
            return unit(c, tenantId, id);
        });
    }

    /** Manual status change (shift start/end, returning); job-linked states are driven by the job. */
    public Unit setUnitStatus(String tenantId, String unitId, UnitStatus to, String actor) {
        return tx.inTransaction(c -> {
            Unit u = unit(c, tenantId, unitId);
            if (u.currentJobId() != null && (to == UnitStatus.OFF_DUTY || to == UnitStatus.AVAILABLE)) {
                Job j = job(c, tenantId, u.currentJobId());
                if (!j.status().terminal() && j.status() != JobStatus.COMPLETED) {
                    throw new FieldException("INVALID_TRANSITION", "unit is on job " + j.id() + "; release or complete it first");
                }
            }
            moveUnit(c, tenantId, u, to, to == UnitStatus.AVAILABLE || to == UnitStatus.OFF_DUTY ? null : u.currentJobId(), actor);
            return unit(c, tenantId, unitId);
        });
    }

    public Unit getUnit(String tenantId, String id) {
        return tx.inTransaction(c -> unit(c, tenantId, id));
    }

    public List<Unit> listUnits(String tenantId, UnitStatus status) {
        return tx.inTransaction(c -> status == null
                ? Jdbc.query(c, "SELECT * FROM FLD_UNIT WHERE tenant_id = ? ORDER BY unit_id", DispatchService::mapUnit, tenantId)
                : Jdbc.query(c, "SELECT * FROM FLD_UNIT WHERE tenant_id = ? AND status = ? ORDER BY unit_id", DispatchService::mapUnit,
                tenantId, status.name()));
    }

    // ------------------------------------------------------------------ jobs

    public Job createJob(String tenantId, JobInput in, String actor) {
        Job created = tx.inTransaction(c -> {
            String id = FieldSql.newId();
            Instant now = clock.instant();
            long sla = in.slaTargetSeconds() != null ? in.slaTargetSeconds() : config.slaSeconds(in.priority());
            GeoPoint p = new GeoPoint(in.lat(), in.lon());
            Jdbc.update(c, "INSERT INTO FLD_JOB (tenant_id, job_id, kind, priority, lat_e7, lon_e7, address, required_skills_json, status, "
                            + "declined_json, sla_target_seconds, business_key, created_at, attributes_json, version, created_by) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?)",
                    tenantId, id, in.kind() == null ? "JOB" : in.kind(), in.priority() == null ? "NORMAL" : in.priority(), FieldSql.e7lat(p),
                    FieldSql.e7lon(p), in.address(), FieldJson.write(in.requiredSkills() == null ? Set.of() : in.requiredSkills()),
                    JobStatus.NEW, "[]", sla, in.businessKey(), now, FieldJson.write(in.attributes()), actor);
            audit.record(c, tenantId, id, FieldAudit.JOB_CREATED, actor, Map.of("priority", String.valueOf(in.priority()), "sla", sla));
            events.publish(c, tenantId, "dispatch.job.created", id, Map.of("priority", String.valueOf(in.priority())));
            return job(c, tenantId, id);
        });
        if (in.autoDispatch()) {
            dispatch(tenantId, created.id(), actor);
            return getJob(tenantId, created.id());
        }
        return created;
    }

    public Job getJob(String tenantId, String id) {
        return tx.inTransaction(c -> job(c, tenantId, id));
    }

    public List<Job> listJobs(String tenantId, JobStatus status, int limit) {
        int lim = Math.max(1, Math.min(500, limit <= 0 ? 100 : limit));
        return tx.inTransaction(c -> (status == null
                ? Jdbc.query(c, "SELECT * FROM FLD_JOB WHERE tenant_id = ? ORDER BY created_at DESC", DispatchService::mapJob, tenantId)
                : Jdbc.query(c, "SELECT * FROM FLD_JOB WHERE tenant_id = ? AND status = ? ORDER BY created_at DESC", DispatchService::mapJob,
                tenantId, status.name())).stream().limit(lim).toList());
    }

    /** Candidate units: dispatchable, with every required skill, not declined, nearest first (by last fix, else home base). */
    public List<Candidate> candidates(String tenantId, String jobId) {
        return tx.inTransaction(c -> candidates(c, tenantId, job(c, tenantId, jobId)));
    }

    List<Candidate> candidates(Connection c, String tenantId, Job j) {
        List<Unit> units = Jdbc.query(c, "SELECT * FROM FLD_UNIT WHERE tenant_id = ? AND status IN ('AVAILABLE', 'RETURNING')",
                DispatchService::mapUnit, tenantId);
        Set<String> declined = new HashSet<>(j.declinedUnits());
        Set<String> pending = new HashSet<>(Jdbc.query(c, "SELECT unit_id FROM FLD_OFFER WHERE tenant_id = ? AND status = 'OFFERED'",
                rs -> rs.getString(1), tenantId));
        Map<String, Unit> byId = new LinkedHashMap<>();
        List<Nearest.Located> located = new ArrayList<>();
        for (Unit u : units) {
            if (declined.contains(u.id()) || pending.contains(u.id())) {
                continue;
            }
            GeoPoint pos = u.lastFix() != null ? u.lastFix().position() : u.homeBase();
            byId.put(u.id(), u);
            located.add(new Nearest.Located(u.id(), pos, u.skills(), Map.of("kind", u.kind())));
        }
        List<Nearest.Ranked> ranked = Nearest.rank(j.location(), located, j.requiredSkills(), config.maxCandidates(),
                config.maxCandidateMeters(), null);
        List<Candidate> out = new ArrayList<>();
        for (Nearest.Ranked r : ranked) {
            Unit u = byId.get(r.item().id());
            out.add(new Candidate(u.id(), u.kind(), u.status(), r.distanceMeters(), etaSeconds(u.kind(), r.distanceMeters()), u.skills()));
        }
        return out;
    }

    /** Offers the job to the best candidate. Returns the offer, or empty when no unit qualifies (job stays NEW). */
    public Optional<Offer> dispatch(String tenantId, String jobId, String actor) {
        return tx.inTransaction(c -> dispatch(c, tenantId, jobId, actor));
    }

    Optional<Offer> dispatch(Connection c, String tenantId, String jobId, String actor) {
        Job j = job(c, tenantId, jobId);
        if (j.status() != JobStatus.NEW && j.status() != JobStatus.OFFERED) {
            throw new FieldException("INVALID_TRANSITION", "job " + jobId + " is " + j.status());
        }
        List<Candidate> cands = candidates(c, tenantId, j);
        if (cands.isEmpty()) {
            if (j.status() == JobStatus.OFFERED) {
                updateJob(c, tenantId, j,"status = ?", JobStatus.NEW.name());
            }
            audit.record(c, tenantId, jobId, FieldAudit.OFFER, actor, Map.of("result", "NO_CANDIDATE"));
            return Optional.empty();
        }
        Candidate best = cands.get(0);
        Instant now = clock.instant();
        String offerId = FieldSql.newId();
        Jdbc.update(c, "INSERT INTO FLD_OFFER (tenant_id, offer_id, job_id, unit_id, status, offered_at, expires_at, distance_m, eta_seconds) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", tenantId, offerId, jobId, best.unitId(), OfferStatus.OFFERED, now,
                now.plusSeconds(config.offerTimeoutSeconds()), Math.round(best.distanceMeters()), best.etaSeconds());
        j.status().check(JobStatus.OFFERED);
        updateJob(c, tenantId, j,"status = ?, offered_at = ?", JobStatus.OFFERED.name(), j.offeredAt() == null ? now : j.offeredAt());
        FieldSql.recordChange(c, dialect, tenantId, "OFFER", offerId, best.unitId(), "UPSERT", now);
        audit.record(c, tenantId, jobId, FieldAudit.OFFER, actor, Map.of("offerId", offerId, "unitId", best.unitId(),
                "distanceM", Math.round(best.distanceMeters()), "etaSeconds", best.etaSeconds()));
        events.publish(c, tenantId, "dispatch.offer.created", offerId, Map.of("jobId", jobId, "unitId", best.unitId()));
        return Optional.of(offer(c, tenantId, offerId));
    }

    /** Direct assignment by a dispatcher (no offer round). */
    public Job assign(String tenantId, String jobId, String unitId, String actor) {
        return tx.inTransaction(c -> {
            Job j = job(c, tenantId, jobId);
            Unit u = unit(c, tenantId, unitId);
            if (!u.status().dispatchable()) {
                throw new FieldException("INVALID_TRANSITION", "unit " + unitId + " is " + u.status());
            }
            withdrawOffers(c, tenantId, jobId, "assigned directly");
            bind(c, tenantId, j, u, actor);
            return job(c, tenantId, jobId);
        });
    }

    public Offer respond(String tenantId, String offerId, boolean accept, String reason, String actor) {
        return tx.inTransaction(c -> respond(c, tenantId, offerId, accept, reason, actor));
    }

    public Offer respond(Connection c, String tenantId, String offerId, boolean accept, String reason, String actor) {
        {
            Offer o = offer(c, tenantId, offerId);
            if (o.status() != OfferStatus.OFFERED) {
                throw new FieldException("CONFLICT", "offer " + offerId + " is " + o.status());
            }
            Instant now = clock.instant();
            if (accept && now.isAfter(o.expiresAt())) {
                expire(c, tenantId, o, actor);
                throw new FieldException("STALE", "offer " + offerId + " expired at " + o.expiresAt());
            }
            Jdbc.update(c, "UPDATE FLD_OFFER SET status = ?, responded_at = ?, reason = ? WHERE tenant_id = ? AND offer_id = ?",
                    accept ? OfferStatus.ACCEPTED : OfferStatus.DECLINED, now, reason, tenantId, offerId);
            Job j = job(c, tenantId, o.jobId());
            if (accept) {
                bind(c, tenantId, j, unit(c, tenantId, o.unitId()), actor);
            } else {
                declineAndRedispatch(c, tenantId, j, o.unitId(), actor);
            }
            audit.record(c, tenantId, o.jobId(), FieldAudit.OFFER, actor, Map.of("offerId", offerId, "response", accept ? "ACCEPTED" : "DECLINED",
                    "reason", reason == null ? "" : reason));
            return offer(c, tenantId, offerId);
        }
    }

    /** Expires overdue offers and re-dispatches their jobs; returns the number expired. */
    public int expireOffers(String tenantId) {
        return tx.inTransaction(c -> {
            List<Offer> due = Jdbc.query(c, "SELECT * FROM FLD_OFFER WHERE tenant_id = ? AND status = 'OFFERED' AND expires_at < ?",
                    DispatchService::mapOffer, tenantId, clock.instant());
            for (Offer o : due) {
                expire(c, tenantId, o, "system");
            }
            return due.size();
        });
    }

    private void expire(Connection c, String tenantId, Offer o, String actor) {
        Jdbc.update(c, "UPDATE FLD_OFFER SET status = ?, responded_at = ?, reason = 'timeout' WHERE tenant_id = ? AND offer_id = ? AND status = 'OFFERED'",
                OfferStatus.EXPIRED, clock.instant(), tenantId, o.id());
        declineAndRedispatch(c, tenantId, job(c, tenantId, o.jobId()), o.unitId(), actor);
    }

    private void declineAndRedispatch(Connection c, String tenantId, Job j, String unitId, String actor) {
        List<String> declined = new ArrayList<>(j.declinedUnits());
        if (!declined.contains(unitId)) {
            declined.add(unitId);
        }
        updateJob(c, tenantId, j,"declined_json = ?", FieldJson.write(declined));
        Job fresh = job(c, tenantId, j.id());
        if (fresh.status() == JobStatus.OFFERED || fresh.status() == JobStatus.NEW) {
            dispatch(c, tenantId, j.id(), actor);
        }
    }

    private void withdrawOffers(Connection c, String tenantId, String jobId, String reason) {
        Jdbc.update(c, "UPDATE FLD_OFFER SET status = ?, responded_at = ?, reason = ? WHERE tenant_id = ? AND job_id = ? AND status = 'OFFERED'",
                OfferStatus.WITHDRAWN, clock.instant(), reason, tenantId, jobId);
    }

    private void bind(Connection c, String tenantId, Job j, Unit u, String actor) {
        j.status().check(JobStatus.ASSIGNED);
        Instant now = clock.instant();
        updateJob(c, tenantId, j,"status = ?, assigned_unit_id = ?, assigned_at = ?", JobStatus.ASSIGNED.name(), u.id(), now);
        moveUnit(c, tenantId, u, UnitStatus.ASSIGNED, j.id(), actor);
        FieldSql.recordChange(c, dialect, tenantId, "JOB", j.id(), u.id(), "UPSERT", now);
        audit.record(c, tenantId, j.id(), FieldAudit.JOB_STATUS, actor, Map.of("to", "ASSIGNED", "unitId", u.id()));
        events.publish(c, tenantId, "dispatch.job.assigned", j.id(), Map.of("unitId", u.id()));
    }

    /** Job progress from the unit / dispatcher: EN_ROUTE, ON_SCENE, COMPLETED. Unit status follows. */
    public Job progress(String tenantId, String jobId, JobStatus to, String actor) {
        return tx.inTransaction(c -> progress(c, tenantId, jobId, to, actor));
    }

    public Job progress(Connection c, String tenantId, String jobId, JobStatus to, String actor) {
        Job j = job(c, tenantId, jobId);
        if (j.status() == to) {
            return j;
        }
        j.status().check(to);
        Instant now = clock.instant();
        Unit u = j.assignedUnitId() == null ? null : unit(c, tenantId, j.assignedUnitId());
        switch (to) {
            case EN_ROUTE -> {
                updateJob(c, tenantId, j,"status = ?, en_route_at = ?", to.name(), now);
                if (u != null) {
                    moveUnit(c, tenantId, u, UnitStatus.EN_ROUTE, j.id(), actor);
                }
            }
            case ON_SCENE -> {
                long resp = Duration.between(j.createdAt(), now).toSeconds();
                Boolean met = j.slaTargetSeconds() > 0 ? resp <= j.slaTargetSeconds() : null;
                updateJob(c, tenantId, j,"status = ?, on_scene_at = ?, response_seconds = ?, sla_met = ?", to.name(), now, resp, met);
                if (u != null) {
                    moveUnit(c, tenantId, u, UnitStatus.ON_SCENE, j.id(), actor);
                }
            }
            case COMPLETED -> {
                updateJob(c, tenantId, j,"status = ?, completed_at = ?", to.name(), now);
                if (u != null) {
                    moveUnit(c, tenantId, u, UnitStatus.RETURNING, null, actor);
                }
            }
            default -> throw new FieldException("INVALID_TRANSITION", "use close / cancel / redispatch for " + to);
        }
        FieldSql.recordChange(c, dialect, tenantId, "JOB", j.id(), j.assignedUnitId(), "UPSERT", now);
        audit.record(c, tenantId, j.id(), FieldAudit.JOB_STATUS, actor, Map.of("from", j.status().name(), "to", to.name()));
        events.publish(c, tenantId, "dispatch.job." + to.name().toLowerCase(java.util.Locale.ROOT), j.id(), Map.of());
        return job(c, tenantId, jobId);
    }

    /** Closure with proof of service (an N05 packet id) and an outcome code (data). Completes first if needed. */
    public Job close(String tenantId, String jobId, String outcome, String note, String proofPacketId, String actor) {
        return tx.inTransaction(c -> {
            Job j = job(c, tenantId, jobId);
            if (j.status() == JobStatus.ON_SCENE) {
                j = progress(c, tenantId, jobId, JobStatus.COMPLETED, actor);
            }
            j.status().check(JobStatus.CLOSED);
            Instant now = clock.instant();
            updateJob(c, tenantId, j,"status = ?, closed_at = ?, outcome = ?, outcome_note = ?, proof_packet_id = ?", JobStatus.CLOSED.name(), now,
                    outcome, note, proofPacketId);
            audit.record(c, tenantId, jobId, FieldAudit.JOB_STATUS, actor, Map.of("to", "CLOSED", "outcome", String.valueOf(outcome),
                    "proofPacketId", String.valueOf(proofPacketId)));
            events.publish(c, tenantId, "dispatch.job.closed", jobId, Map.of("outcome", String.valueOf(outcome)));
            return job(c, tenantId, jobId);
        });
    }

    public Job cancel(String tenantId, String jobId, String reason, String actor) {
        return tx.inTransaction(c -> {
            Job j = job(c, tenantId, jobId);
            j.status().check(JobStatus.CANCELLED);
            withdrawOffers(c, tenantId, jobId, "job cancelled");
            updateJob(c, tenantId, j,"status = ?, outcome = 'CANCELLED', outcome_note = ?, closed_at = ?", JobStatus.CANCELLED.name(), reason,
                    clock.instant());
            if (j.assignedUnitId() != null) {
                Unit u = unit(c, tenantId, j.assignedUnitId());
                if (jobId.equals(u.currentJobId())) {
                    moveUnit(c, tenantId, u, UnitStatus.AVAILABLE, null, actor);
                }
            }
            audit.record(c, tenantId, jobId, FieldAudit.JOB_STATUS, actor, Map.of("to", "CANCELLED", "reason", reason == null ? "" : reason));
            return job(c, tenantId, jobId);
        });
    }

    /** Re-dispatch: the assigned unit is released (breakdown, diverted) and the job goes back to the offer round. */
    public Optional<Offer> redispatch(String tenantId, String jobId, String reason, String actor) {
        return tx.inTransaction(c -> {
            Job j = job(c, tenantId, jobId);
            j.status().check(JobStatus.NEW);
            if (j.assignedUnitId() != null) {
                Unit u = unit(c, tenantId, j.assignedUnitId());
                if (jobId.equals(u.currentJobId())) {
                    moveUnit(c, tenantId, u, UnitStatus.AVAILABLE, null, actor);
                }
                List<String> declined = new ArrayList<>(j.declinedUnits());
                declined.add(j.assignedUnitId());
                updateJob(c, tenantId, j,"status = ?, assigned_unit_id = NULL, declined_json = ?", JobStatus.NEW.name(), FieldJson.write(declined));
            } else {
                updateJob(c, tenantId, j,"status = ?", JobStatus.NEW.name());
            }
            audit.record(c, tenantId, jobId, FieldAudit.JOB_STATUS, actor, Map.of("to", "NEW", "redispatch", reason == null ? "" : reason));
            return dispatch(c, tenantId, jobId, actor);
        });
    }

    // ------------------------------------------------------------------ positions and ETA

    /** Stores a ping (idempotent per unit + time), updates the unit's last fix and auto-arrives within the arrival radius. */
    public Unit ping(String tenantId, PositionFix f, String actor) {
        return tx.inTransaction(c -> ping(c, tenantId, f, actor));
    }

    public Unit ping(Connection c, String tenantId, PositionFix f, String actor) {
        Unit u = unit(c, tenantId, f.unitId());
        GeoPoint p = new GeoPoint(f.lat(), f.lon());
        String tripId = Jdbc.one(c, "SELECT trip_id FROM FLD_TRIP WHERE tenant_id = ? AND unit_id = ? AND status = 'OPEN'",
                rs -> rs.getString(1), tenantId, u.id()).orElse(null);
        Jdbc.insertIfAbsent(c, dialect, "INSERT INTO FLD_POSITION (tenant_id, unit_id, fix_at, lat_e7, lon_e7, accuracy_m, speed_cmps, heading_deg, "
                        + "source, trip_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", tenantId, u.id(), f.at(), FieldSql.e7lat(p), FieldSql.e7lon(p),
                FieldSql.round(f.accuracyM()), f.speedMps() == null ? null : Math.round(f.speedMps() * 100), f.headingDeg(), f.source(), tripId);
        if (u.lastFix() == null || f.at().isAfter(u.lastFix().at())) {
            Jdbc.update(c, "UPDATE FLD_UNIT SET last_lat_e7 = ?, last_lon_e7 = ?, last_accuracy_m = ?, last_fix_at = ? WHERE tenant_id = ? AND unit_id = ?",
                    FieldSql.e7lat(p), FieldSql.e7lon(p), FieldSql.round(f.accuracyM()), f.at(), tenantId, u.id());
            if (u.currentJobId() != null && (u.status() == UnitStatus.EN_ROUTE || u.status() == UnitStatus.ASSIGNED)) {
                Job j = job(c, tenantId, u.currentJobId());
                double acc = f.accuracyM() == null ? 0 : f.accuracyM();
                if (GeoMath.haversineMeters(p, j.location()) + acc <= config.arrivalRadiusMeters() && j.status().canMoveTo(JobStatus.ON_SCENE)) {
                    progress(c, tenantId, j.id(), JobStatus.ON_SCENE, actor == null ? "auto-arrival" : actor);
                }
            }
        }
        return unit(c, tenantId, u.id());
    }

    public Eta eta(String tenantId, String unitId, String jobId) {
        return tx.inTransaction(c -> {
            Unit u = unit(c, tenantId, unitId);
            GeoPoint from = u.lastFix() != null ? u.lastFix().position() : u.homeBase();
            if (from == null) {
                throw new FieldException("NO_POSITION", "unit " + unitId + " has no position");
            }
            return eta(u, from, job(c, tenantId, jobId).location());
        });
    }

    public Eta eta(Unit u, GeoPoint from, GeoPoint to) {
        double straight = GeoMath.haversineMeters(from, to);
        double road = straight * config.roadFactor();
        long secs = etaSeconds(u.kind(), straight);
        return new Eta(u.id(), straight, road, secs, clock.instant().plusSeconds(secs));
    }

    long etaSeconds(String kind, double straightMeters) {
        double mps = config.speedKmh(kind) / 3.6;
        return Math.round(straightMeters * config.roadFactor() / mps);
    }

    // ------------------------------------------------------------------ trips

    public Trip startTrip(String tenantId, String unitId, String jobId, String purpose, Long odometer, String actor) {
        return tx.inTransaction(c -> {
            Unit u = unit(c, tenantId, unitId);
            if (Jdbc.one(c, "SELECT COUNT(*) FROM FLD_TRIP WHERE tenant_id = ? AND unit_id = ? AND status = 'OPEN'", rs -> rs.getInt(1),
                    tenantId, unitId).orElse(0) > 0) {
                throw new FieldException("CONFLICT", "unit " + unitId + " already has an open trip");
            }
            String id = FieldSql.newId();
            Instant now = clock.instant();
            GeoPoint at = u.lastFix() == null ? null : u.lastFix().position();
            List<Leg> legs = List.of(new Leg("START", now, at == null ? null : at.lat(), at == null ? null : at.lon(), odometer));
            Jdbc.update(c, "INSERT INTO FLD_TRIP (tenant_id, trip_id, unit_id, job_id, purpose, status, started_at, start_odometer, legs_json, approved_by) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)", tenantId, id, unitId, jobId, purpose, TripStatus.OPEN, now, odometer,
                    FieldJson.write(Map.of("startedBy", actor == null ? "" : actor, "legs", legs)));
            audit.record(c, tenantId, jobId != null ? jobId : "trip:" + id, FieldAudit.TRIP, actor, Map.of("tripId", id, "event", "START"));
            return trip(c, tenantId, id);
        });
    }

    public Trip addLeg(String tenantId, String tripId, String label, Long odometer) {
        return tx.inTransaction(c -> {
            Trip t = trip(c, tenantId, tripId);
            if (t.status() != TripStatus.OPEN) {
                throw new FieldException("INVALID_TRANSITION", "trip " + tripId + " is " + t.status());
            }
            Unit u = unit(c, tenantId, t.unitId());
            GeoPoint at = u.lastFix() == null ? null : u.lastFix().position();
            List<Leg> legs = new ArrayList<>(t.legs());
            legs.add(new Leg(label, clock.instant(), at == null ? null : at.lat(), at == null ? null : at.lon(), odometer));
            Jdbc.update(c, "UPDATE FLD_TRIP SET legs_json = ? WHERE tenant_id = ? AND trip_id = ?",
                    FieldJson.write(Map.of("startedBy", t.startedBy() == null ? "" : t.startedBy(), "legs", legs)), tenantId, tripId);
            return trip(c, tenantId, tripId);
        });
    }

    /** Ends a trip; track distance is summed from the pings recorded during the trip. */
    public Trip endTrip(String tenantId, String tripId, Long odometer, String actor) {
        return tx.inTransaction(c -> {
            Trip t = trip(c, tenantId, tripId);
            if (t.status() != TripStatus.OPEN) {
                throw new FieldException("INVALID_TRANSITION", "trip " + tripId + " is " + t.status());
            }
            if (odometer != null && t.startOdometer() != null && odometer < t.startOdometer()) {
                throw new FieldException("INVALID_REQUEST", "end odometer is below the start odometer");
            }
            Instant now = clock.instant();
            List<GeoPoint> track = Jdbc.query(c, "SELECT lat_e7, lon_e7 FROM FLD_POSITION WHERE tenant_id = ? AND unit_id = ? AND fix_at >= ? "
                    + "AND fix_at <= ? ORDER BY fix_at", rs -> new GeoPoint(rs.getLong(1) / 1e7, rs.getLong(2) / 1e7), tenantId, t.unitId(),
                    t.startedAt(), now);
            double dist = 0;
            for (int i = 1; i < track.size(); i++) {
                dist += GeoMath.haversineMeters(track.get(i - 1), track.get(i));
            }
            List<Leg> legs = new ArrayList<>(t.legs());
            GeoPoint last = track.isEmpty() ? null : track.get(track.size() - 1);
            legs.add(new Leg("END", now, last == null ? null : last.lat(), last == null ? null : last.lon(), odometer));
            Jdbc.update(c, "UPDATE FLD_TRIP SET status = ?, ended_at = ?, end_odometer = ?, track_distance_m = ?, legs_json = ? "
                            + "WHERE tenant_id = ? AND trip_id = ?", TripStatus.ENDED, now, odometer, Math.round(dist),
                    FieldJson.write(Map.of("startedBy", t.startedBy() == null ? "" : t.startedBy(), "legs", legs)), tenantId, tripId);
            audit.record(c, tenantId, t.jobId() != null ? t.jobId() : "trip:" + tripId, FieldAudit.TRIP, actor,
                    Map.of("tripId", tripId, "event", "END", "trackDistanceM", Math.round(dist)));
            return trip(c, tenantId, tripId);
        });
    }

    /** Trip approval (vehicle logbook): the approver must not be the person who started the trip (maker-checker). */
    public Trip decideTrip(String tenantId, String tripId, boolean approve, String reason, String actor) {
        return tx.inTransaction(c -> {
            Trip t = trip(c, tenantId, tripId);
            if (t.status() != TripStatus.ENDED) {
                throw new FieldException("INVALID_TRANSITION", "trip " + tripId + " is " + t.status());
            }
            if (actor != null && actor.equals(t.startedBy())) {
                throw new FieldException("FORBIDDEN", "the person who made the trip cannot approve it");
            }
            Jdbc.update(c, "UPDATE FLD_TRIP SET status = ?, approved_by = ?, approved_at = ?, rejection_reason = ? WHERE tenant_id = ? AND trip_id = ?",
                    approve ? TripStatus.APPROVED : TripStatus.REJECTED, actor, clock.instant(), approve ? null : reason, tenantId, tripId);
            audit.record(c, tenantId, t.jobId() != null ? t.jobId() : "trip:" + tripId, FieldAudit.TRIP, actor,
                    Map.of("tripId", tripId, "event", approve ? "APPROVED" : "REJECTED"));
            return trip(c, tenantId, tripId);
        });
    }

    public Trip getTrip(String tenantId, String id) {
        return tx.inTransaction(c -> trip(c, tenantId, id));
    }

    public List<Trip> listTrips(String tenantId, String unitId) {
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT * FROM FLD_TRIP WHERE tenant_id = ? AND unit_id = ? ORDER BY started_at DESC",
                DispatchService::mapTrip, tenantId, unitId));
    }

    public List<Offer> offersForUnit(String tenantId, String unitId) {
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT * FROM FLD_OFFER WHERE tenant_id = ? AND unit_id = ? AND status = 'OFFERED' "
                + "ORDER BY offered_at", DispatchService::mapOffer, tenantId, unitId));
    }

    public Offer getOffer(String tenantId, String id) {
        return tx.inTransaction(c -> offer(c, tenantId, id));
    }

    /** Connection-scoped reads for callers already in a transaction (sync). */
    public Offer getOfferIn(Connection c, String tenantId, String id) {
        return offer(c, tenantId, id);
    }

    public Job getJobIn(Connection c, String tenantId, String id) {
        return job(c, tenantId, id);
    }

    public Job findJobIn(Connection c, String tenantId, String id) {
        return Jdbc.one(c, "SELECT * FROM FLD_JOB WHERE tenant_id = ? AND job_id = ?", DispatchService::mapJob, tenantId, id).orElse(null);
    }

    // ------------------------------------------------------------------ persistence helpers

    private void moveUnit(Connection c, String tenantId, Unit u, UnitStatus to, String jobId, String actor) {
        u.status().check(to);
        int n = Jdbc.update(c, "UPDATE FLD_UNIT SET status = ?, current_job_id = ?, version = version + 1, updated_at = ? WHERE tenant_id = ? "
                + "AND unit_id = ? AND version = ?", to, jobId, clock.instant(), tenantId, u.id(), u.version());
        if (n == 0) {
            throw new FieldException("VERSION_CONFLICT", "unit " + u.id() + " changed concurrently");
        }
        if (u.status() != to) {
            audit.record(c, tenantId, jobId != null ? jobId : "unit:" + u.id(), FieldAudit.UNIT_STATUS, actor,
                    Map.of("unitId", u.id(), "from", u.status().name(), "to", to.name()));
        }
    }

    private void updateJob(Connection c, String tenantId, Job j, String set, Object... params) {
        Object[] all = new Object[params.length + 3];
        System.arraycopy(params, 0, all, 0, params.length);
        all[params.length] = tenantId;
        all[params.length + 1] = j.id();
        all[params.length + 2] = j.version();
        int n = Jdbc.update(c, "UPDATE FLD_JOB SET " + set + ", version = version + 1 WHERE tenant_id = ? AND job_id = ? AND version = ?", all);
        if (n == 0) {
            throw new FieldException("VERSION_CONFLICT", "job " + j.id() + " changed concurrently");
        }
    }

    Unit unit(Connection c, String tenantId, String id) {
        return findUnit(c, tenantId, id).orElseThrow(() -> FieldException.notFound("unit", id));
    }

    Optional<Unit> findUnit(Connection c, String tenantId, String id) {
        return Jdbc.one(c, "SELECT * FROM FLD_UNIT WHERE tenant_id = ? AND unit_id = ?", DispatchService::mapUnit, tenantId, id);
    }

    Job job(Connection c, String tenantId, String id) {
        return Jdbc.one(c, "SELECT * FROM FLD_JOB WHERE tenant_id = ? AND job_id = ?", DispatchService::mapJob, tenantId, id)
                .orElseThrow(() -> FieldException.notFound("job", id));
    }

    Offer offer(Connection c, String tenantId, String id) {
        return Jdbc.one(c, "SELECT * FROM FLD_OFFER WHERE tenant_id = ? AND offer_id = ?", DispatchService::mapOffer, tenantId, id)
                .orElseThrow(() -> FieldException.notFound("offer", id));
    }

    Trip trip(Connection c, String tenantId, String id) {
        return Jdbc.one(c, "SELECT * FROM FLD_TRIP WHERE tenant_id = ? AND trip_id = ?", DispatchService::mapTrip, tenantId, id)
                .orElseThrow(() -> FieldException.notFound("trip", id));
    }

    private static Set<String> stringSet(String json) {
        return new java.util.LinkedHashSet<>(FieldJson.strings(json));
    }

    static Unit mapUnit(ResultSet rs) throws SQLException {
        GeoPoint last = FieldSql.point(rs, "last_lat_e7", "last_lon_e7");
        return new Unit(rs.getString("unit_id"), rs.getString("kind"), rs.getString("label"), stringSet(rs.getString("skills_json")),
                FieldSql.point(rs, "home_lat_e7", "home_lon_e7"), UnitStatus.valueOf(rs.getString("status")),
                last == null ? null : new Fix(last, FieldSql.lng(rs, "last_accuracy_m"), Jdbc.instant(rs, "last_fix_at")),
                rs.getString("current_job_id"), rs.getString("org_unit_ref"), FieldJson.map(rs.getString("attributes_json")),
                rs.getInt("version"), Jdbc.instant(rs, "updated_at"));
    }

    static Job mapJob(ResultSet rs) throws SQLException {
        return new Job(rs.getString("job_id"), rs.getString("kind"), rs.getString("priority"), FieldSql.point(rs, "lat_e7", "lon_e7"),
                rs.getString("address"), stringSet(rs.getString("required_skills_json")), JobStatus.valueOf(rs.getString("status")),
                rs.getString("assigned_unit_id"), FieldJson.strings(rs.getString("declined_json")), rs.getLong("sla_target_seconds"),
                rs.getString("business_key"), Jdbc.instant(rs, "created_at"), Jdbc.instant(rs, "offered_at"), Jdbc.instant(rs, "assigned_at"),
                Jdbc.instant(rs, "en_route_at"), Jdbc.instant(rs, "on_scene_at"), Jdbc.instant(rs, "completed_at"),
                Jdbc.instant(rs, "closed_at"), rs.getString("outcome"), rs.getString("outcome_note"), rs.getString("proof_packet_id"),
                FieldSql.lng(rs, "response_seconds"), FieldSql.bool(rs, "sla_met"), FieldJson.map(rs.getString("attributes_json")),
                rs.getInt("version"), rs.getString("created_by"));
    }

    static Offer mapOffer(ResultSet rs) throws SQLException {
        return new Offer(rs.getString("offer_id"), rs.getString("job_id"), rs.getString("unit_id"), OfferStatus.valueOf(rs.getString("status")),
                Jdbc.instant(rs, "offered_at"), Jdbc.instant(rs, "expires_at"), Jdbc.instant(rs, "responded_at"),
                FieldSql.lng(rs, "distance_m"), FieldSql.lng(rs, "eta_seconds"), rs.getString("reason"));
    }

    static Trip mapTrip(ResultSet rs) throws SQLException {
        Map<String, Object> doc = FieldJson.map(rs.getString("legs_json"));
        List<Leg> legs = FieldJson.MAPPER.convertValue(doc.getOrDefault("legs", List.of()), new TypeReference<List<Leg>>() { });
        Object by = doc.get("startedBy");
        return new Trip(rs.getString("trip_id"), rs.getString("unit_id"), rs.getString("job_id"), rs.getString("purpose"),
                TripStatus.valueOf(rs.getString("status")), Jdbc.instant(rs, "started_at"), Jdbc.instant(rs, "ended_at"),
                FieldSql.lng(rs, "start_odometer"), FieldSql.lng(rs, "end_odometer"), FieldSql.lng(rs, "track_distance_m"), legs,
                by == null || by.toString().isBlank() ? null : by.toString(), rs.getString("approved_by"), Jdbc.instant(rs, "approved_at"),
                rs.getString("rejection_reason"));
    }
}

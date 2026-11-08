// SPDX-License-Identifier: Apache-2.0
package org.sangam.field.dispatch;

import org.sangam.field.FieldException;
import org.sangam.geo.model.GeoPoint;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** N17 entities: Unit, UnitStatus, Job, Offer, Trip/Leg, PositionFix and ETA. Unit kinds, skills and priorities are data. */
public final class DispatchModel {

    private DispatchModel() {
    }

    public enum UnitStatus {
        AVAILABLE, ASSIGNED, EN_ROUTE, ON_SCENE, RETURNING, OFF_DUTY;

        private static final Map<UnitStatus, Set<UnitStatus>> NEXT = Map.of(
                AVAILABLE, EnumSet.of(ASSIGNED, OFF_DUTY),
                ASSIGNED, EnumSet.of(EN_ROUTE, ON_SCENE, AVAILABLE),
                EN_ROUTE, EnumSet.of(ON_SCENE, RETURNING, AVAILABLE),
                ON_SCENE, EnumSet.of(RETURNING, AVAILABLE),
                RETURNING, EnumSet.of(AVAILABLE, ASSIGNED, OFF_DUTY),
                OFF_DUTY, EnumSet.of(AVAILABLE));

        public boolean canMoveTo(UnitStatus to) {
            return this == to || NEXT.get(this).contains(to);
        }

        public void check(UnitStatus to) {
            if (!canMoveTo(to)) {
                throw new FieldException("INVALID_TRANSITION", "unit cannot move from " + this + " to " + to);
            }
        }

        /** Units that may receive an offer. */
        public boolean dispatchable() {
            return this == AVAILABLE || this == RETURNING;
        }
    }

    public enum JobStatus {
        NEW, OFFERED, ASSIGNED, EN_ROUTE, ON_SCENE, COMPLETED, CLOSED, CANCELLED;

        private static final Map<JobStatus, Set<JobStatus>> NEXT = Map.of(
                NEW, EnumSet.of(OFFERED, ASSIGNED, CANCELLED),
                OFFERED, EnumSet.of(ASSIGNED, NEW, OFFERED, CANCELLED),
                ASSIGNED, EnumSet.of(EN_ROUTE, ON_SCENE, NEW, CANCELLED),
                EN_ROUTE, EnumSet.of(ON_SCENE, NEW, CANCELLED),
                ON_SCENE, EnumSet.of(COMPLETED, CANCELLED),
                COMPLETED, EnumSet.of(CLOSED),
                CLOSED, EnumSet.noneOf(JobStatus.class),
                CANCELLED, EnumSet.noneOf(JobStatus.class));

        public boolean canMoveTo(JobStatus to) {
            return NEXT.get(this).contains(to);
        }

        public void check(JobStatus to) {
            if (!canMoveTo(to)) {
                throw new FieldException("INVALID_TRANSITION", "job cannot move from " + this + " to " + to);
            }
        }

        public boolean terminal() {
            return this == CLOSED || this == CANCELLED;
        }
    }

    public record Fix(GeoPoint position, Long accuracyM, Instant at) {
    }

    public record Unit(String id, String kind, String label, Set<String> skills, GeoPoint homeBase, UnitStatus status, Fix lastFix,
                       String currentJobId, String orgUnitRef, Map<String, Object> attributes, int version, Instant updatedAt) {
    }

    public record Job(String id, String kind, String priority, GeoPoint location, String address, Set<String> requiredSkills,
                      JobStatus status, String assignedUnitId, List<String> declinedUnits, long slaTargetSeconds, String businessKey,
                      Instant createdAt, Instant offeredAt, Instant assignedAt, Instant enRouteAt, Instant onSceneAt,
                      Instant completedAt, Instant closedAt, String outcome, String outcomeNote, String proofPacketId,
                      Long responseSeconds, Boolean slaMet, Map<String, Object> attributes, int version, String createdBy) {
    }

    public enum OfferStatus { OFFERED, ACCEPTED, DECLINED, EXPIRED, WITHDRAWN }

    public record Offer(String id, String jobId, String unitId, OfferStatus status, Instant offeredAt, Instant expiresAt,
                        Instant respondedAt, Long distanceM, Long etaSeconds, String reason) {
    }

    public enum TripStatus { OPEN, ENDED, APPROVED, REJECTED }

    public record Leg(String label, Instant at, Double lat, Double lon, Long odometer) {
    }

    public record Trip(String id, String unitId, String jobId, String purpose, TripStatus status, Instant startedAt, Instant endedAt,
                       Long startOdometer, Long endOdometer, Long trackDistanceM, List<Leg> legs, String startedBy,
                       String approvedBy, Instant approvedAt, String rejectionReason) {

        public Long odometerDistance() {
            return startOdometer == null || endOdometer == null ? null : endOdometer - startOdometer;
        }
    }

    /** A position ping (N12 AVL feed or the field app). */
    public record PositionFix(String unitId, Instant at, double lat, double lon, Double accuracyM, Double speedMps, Integer headingDeg,
                              String source) {
    }

    public record Eta(String unitId, double straightMeters, double roadMeters, long seconds, Instant arrivalAt) {
    }

    public record Candidate(String unitId, String kind, UnitStatus status, double distanceMeters, long etaSeconds, Set<String> skills) {
    }
}

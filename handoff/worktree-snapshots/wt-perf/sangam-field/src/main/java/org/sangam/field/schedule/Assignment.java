// SPDX-License-Identifier: Apache-2.0
package org.sangam.field.schedule;

import org.sangam.field.FieldException;
import org.sangam.geo.model.GeoPoint;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * N05 {@code Assignment}: an inspector seat/principal, a target, a template version, a due date, a visit window, a
 * fence and the randomisation selection it came from. {@code version} gives optimistic concurrency for offline edits.
 */
public record Assignment(String tenantId, String id, String templateKey, int templateVersion, String targetRef,
                         String targetLabel, String address, GeoPoint location, FenceSpec fence, String assignee, String seatId,
                         Status status, Instant dueAt, Instant windowStart, Instant windowEnd, String scheduleId,
                         String selectionId, String taskId, String businessKey, String submittedPacketId,
                         String submittedDeviceId, Map<String, Object> score, Map<String, Object> attributes, int version,
                         String createdBy, Instant createdAt, Instant updatedAt) {

    public enum Status {
        PLANNED, ASSIGNED, IN_PROGRESS, SUBMITTED, ACCEPTED, REJECTED, CANCELLED, OVERDUE;

        private static final Map<Status, Set<Status>> NEXT = Map.of(
                PLANNED, EnumSet.of(ASSIGNED, CANCELLED, OVERDUE),
                ASSIGNED, EnumSet.of(ASSIGNED, IN_PROGRESS, SUBMITTED, CANCELLED, OVERDUE),
                IN_PROGRESS, EnumSet.of(SUBMITTED, CANCELLED, OVERDUE, ASSIGNED),
                OVERDUE, EnumSet.of(ASSIGNED, IN_PROGRESS, SUBMITTED, CANCELLED),
                SUBMITTED, EnumSet.of(ACCEPTED, REJECTED),
                REJECTED, EnumSet.of(ASSIGNED, IN_PROGRESS, SUBMITTED, CANCELLED),
                ACCEPTED, EnumSet.noneOf(Status.class),
                CANCELLED, EnumSet.noneOf(Status.class));

        public boolean canMoveTo(Status to) {
            return NEXT.get(this).contains(to);
        }

        public void check(Status to) {
            if (!canMoveTo(to)) {
                throw new FieldException("INVALID_TRANSITION", "assignment cannot move from " + this + " to " + to);
            }
        }

        /** States in which a device may still capture against the assignment. */
        public boolean open() {
            return this == ASSIGNED || this == IN_PROGRESS || this == OVERDUE || this == REJECTED;
        }
    }

    public Map<String, Object> view() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", id);
        m.put("templateKey", templateKey);
        m.put("templateVersion", templateVersion);
        m.put("targetRef", targetRef);
        m.put("targetLabel", targetLabel);
        m.put("address", address);
        m.put("location", location == null ? null : Map.of("lat", location.lat(), "lon", location.lon()));
        m.put("fence", fence);
        m.put("assignee", assignee);
        m.put("seatId", seatId);
        m.put("status", status.name());
        m.put("dueAt", dueAt);
        m.put("windowStart", windowStart);
        m.put("windowEnd", windowEnd);
        m.put("scheduleId", scheduleId);
        m.put("selectionId", selectionId);
        m.put("taskId", taskId);
        m.put("businessKey", businessKey);
        m.put("submittedPacketId", submittedPacketId);
        m.put("score", score);
        m.put("attributes", attributes);
        m.put("version", version);
        m.put("createdAt", createdAt);
        m.put("updatedAt", updatedAt);
        return m;
    }
}

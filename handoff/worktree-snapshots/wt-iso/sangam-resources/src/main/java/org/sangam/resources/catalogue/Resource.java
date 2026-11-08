// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources.catalogue;

import java.time.Instant;
import java.util.Map;

/**
 * One bookable or allotable thing (or a container of them): a building with rooms in it, a ward with beds, a fleet with
 * vehicles, a layout with plots. Hierarchy is held as {@code parentId} plus a materialised {@code treePath}
 * ({@code /root/child/leaf}) so subtree queries are one indexed prefix scan on every dialect.
 */
public record Resource(String tenantId, String resourceId, String typeId, String code, String name, String parentId,
                       String treePath, String orgUnitId, String locationId, String geoLat, String geoLon, int capacity,
                       String unitLabel, String calendarId, Status status, Map<String, Object> attributes, long version,
                       Instant createdAt, Instant updatedAt) {

    public enum Status { ACTIVE, INACTIVE, UNDER_MAINTENANCE, RETIRED }

    public boolean bookable() {
        return status == Status.ACTIVE;
    }

    /** Ids of this resource and its ancestors, root first (from the materialised path). */
    public java.util.List<String> lineage() {
        return java.util.Arrays.stream(treePath.split("/")).filter(s -> !s.isEmpty()).toList();
    }

    /** Input for create. */
    public record Draft(String resourceId, String typeId, String code, String name, String parentId, String orgUnitId,
                        String locationId, String geoLat, String geoLon, Integer capacity, String unitLabel,
                        String calendarId, Map<String, Object> attributes) {
    }

    /** Partial update; null fields are unchanged. */
    public record Patch(String name, String orgUnitId, String locationId, String geoLat, String geoLon, Integer capacity,
                        String unitLabel, String calendarId, Map<String, Object> attributes) {
    }

    /** Catalogue search. Null fields do not filter. {@code ancestorId} selects a whole subtree. */
    public record Query(String typeId, String parentId, String ancestorId, String orgUnitId, String locationId,
                        Integer minCapacity, Status status, Map<String, String> attributes, String text, int offset,
                        int limit) {
    }
}

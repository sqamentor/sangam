// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources.catalogue;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

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

    /**
     * Catalogue search. Null fields do not filter. {@code ancestorId} selects a whole subtree. Paging is optional on
     * a request (SL-81): an omitted {@code offset} or {@code limit} is 0, and the catalogue reads a {@code limit} of 0
     * as its page default (50, at most 1000; ResourceCatalogue.search).
     */
    public record Query(String typeId, String parentId, String ancestorId, String orgUnitId, String locationId,
                        Integer minCapacity, Status status, Map<String, String> attributes, String text, int offset,
                        int limit) {

        /** Request binding: an omitted paging number takes the documented default above, never a guess. */
        @JsonCreator
        public static Query fromRequest(@JsonProperty("typeId") String typeId, @JsonProperty("parentId") String parentId,
                                        @JsonProperty("ancestorId") String ancestorId,
                                        @JsonProperty("orgUnitId") String orgUnitId,
                                        @JsonProperty("locationId") String locationId,
                                        @JsonProperty("minCapacity") Integer minCapacity,
                                        @JsonProperty("status") Status status,
                                        @JsonProperty("attributes") Map<String, String> attributes,
                                        @JsonProperty("text") String text, @JsonProperty("offset") Integer offset,
                                        @JsonProperty("limit") Integer limit) {
            return new Query(typeId, parentId, ancestorId, orgUnitId, locationId, minCapacity, status, attributes, text,
                    offset == null ? 0 : offset, limit == null ? 0 : limit);
        }
    }
}

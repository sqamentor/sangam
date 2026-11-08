// SPDX-License-Identifier: Apache-2.0
package org.sangam.geo.index;

import org.sangam.geo.model.BBox;
import org.sangam.geo.model.GeoPoint;
import org.sangam.geo.model.Geometry;

import java.sql.Connection;
import java.util.List;

/**
 * Candidate filter for spatial queries. An index only narrows the candidate set; the exact predicate is always
 * evaluated in Java ({@code GeoMath}) so results are identical on every database (A-15). Implementations:
 * {@link PortableGridIndex} (all dialects, geohash cells) and {@link PostgisSpatialIndex} (PostgreSQL with the PostGIS
 * extension installed; GiST).
 */
public interface SpatialIndex {

    String name();

    /** One-time setup (DDL that only this index needs). Idempotent. */
    default void ensure(Connection c) {
    }

    void index(Connection c, String tenantId, String layer, String featureId, Geometry geometry, int fixedPrecision);

    void remove(Connection c, String tenantId, String layer, String featureId);

    /** Active features of the layer whose geometry may contain the point. */
    List<String> containingCandidates(Connection c, String tenantId, String layer, GeoPoint point);

    /** Active features of the layer whose bounding box intersects the box. */
    List<String> intersectingCandidates(Connection c, String tenantId, String layer, BBox box);

    /**
     * Native k-nearest candidates, or {@code null} when the index has no native KNN (the service then expands a
     * bounding box).
     */
    default List<String> nearestCandidates(Connection c, String tenantId, String layer, GeoPoint point, int n) {
        return null;
    }
}

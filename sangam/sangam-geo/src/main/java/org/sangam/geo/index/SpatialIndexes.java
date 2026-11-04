// SPDX-License-Identifier: Apache-2.0
package org.sangam.geo.index;

import org.sangam.core.layer.Dialect;

import java.sql.Connection;

/** Picks the spatial index for a database: PostGIS when requested and installed, otherwise the portable index. */
public final class SpatialIndexes {

    private SpatialIndexes() {
    }

    /**
     * @param mode {@code auto} (PostGIS if available), {@code portable}, or {@code postgis} (fail if missing)
     */
    public static SpatialIndex select(Connection c, Dialect dialect, String mode) {
        String m = mode == null ? "auto" : mode;
        if ("portable".equals(m) || dialect != Dialect.POSTGRESQL) {
            if ("postgis".equals(m)) {
                throw new IllegalStateException("PostGIS index requested on " + dialect);
            }
            return new PortableGridIndex();
        }
        boolean ok = c != null && PostgisSpatialIndex.available(c);
        if (!ok && "postgis".equals(m)) {
            throw new IllegalStateException("PostGIS index requested but the postgis extension is not installed");
        }
        return ok ? new PostgisSpatialIndex() : new PortableGridIndex();
    }
}

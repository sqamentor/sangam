// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources.support;

import org.sangam.core.layer.Jdbc;
import org.sangam.resources.dialect.ResourcesDialect;

import java.sql.Connection;

/**
 * Gap-free human references per (tenant, series, period) on {@code RES_BOOKING_SEQ}: the first value is created with an
 * insert-if-absent; later values increment under a row lock, so concurrent callers serialise on the counter row only.
 * A rolled-back transaction gives its number back (no gaps).
 */
public final class Sequences {

    private Sequences() {
    }

    public static long next(Connection c, ResourcesDialect d, String tenantId, String series, String period) {
        if (d.insertIfAbsent(c, "INSERT INTO RES_BOOKING_SEQ(tenant_id, series, period, last_no) VALUES (?,?,?,1)",
                tenantId, series, period)) {
            return 1L;
        }
        long last = Jdbc.one(c, d.lockingSelect("last_no", "RES_BOOKING_SEQ", "tenant_id=? AND series=? AND period=?"),
                rs -> rs.getLong(1), tenantId, series, period).orElseThrow();
        Jdbc.update(c, "UPDATE RES_BOOKING_SEQ SET last_no=? WHERE tenant_id=? AND series=? AND period=?",
                last + 1, tenantId, series, period);
        return last + 1;
    }

    /** {@code PREFIX-PERIOD-000042}. */
    public static String format(String prefix, String period, long value, int width) {
        String n = Long.toString(value);
        StringBuilder sb = new StringBuilder(prefix).append('-').append(period).append('-');
        for (int i = n.length(); i < width; i++) {
            sb.append('0');
        }
        return sb.append(n).toString();
    }
}

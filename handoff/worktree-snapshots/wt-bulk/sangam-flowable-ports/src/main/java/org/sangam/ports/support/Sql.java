// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.support;

import org.sangam.core.layer.Dialect;

/** Small per-dialect SQL helpers not present in {@link Dialect} (A-15: every statement per dialect). */
public final class Sql {

    private Sql() {
    }

    /** Row-limit suffix for a query that already has an ORDER BY (Oracle 12c+, SQL Server 2012+). */
    public static String limit(Dialect d, int n) {
        if (n <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return switch (d) {
            case POSTGRESQL, MYSQL -> " LIMIT " + n;
            case ORACLE -> " FETCH FIRST " + n + " ROWS ONLY";
            case SQLSERVER -> " OFFSET 0 ROWS FETCH NEXT " + n + " ROWS ONLY";
        };
    }

    public static short flag(boolean b) {
        return (short) (b ? 1 : 0);
    }
}

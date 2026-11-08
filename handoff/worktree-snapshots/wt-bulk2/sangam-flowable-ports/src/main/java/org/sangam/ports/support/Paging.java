// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.support;

import org.sangam.core.layer.Dialect;

import java.util.List;

/**
 * Paging for the ports' query APIs (ARCHITECTURE §4 lists: {@code page/size}, capped total, A-16). Engine-neutral and
 * independent of sangam-rest; the server bridge maps it onto the REST {@code Page}.
 */
public final class Paging {

    /** Hard upper bound of a page and of an exact count ("more than N" beyond it). */
    public static final int MAX_PAGE = 500;
    public static final int COUNT_CAP = 10_000;

    private Paging() {
    }

    /** Deepest row offset a page may start at (deep OFFSET scans cost as much as a full count; use filters instead). */
    public static final int MAX_OFFSET = 100_000;

    public record PageRequest(int page, int size) {
        public PageRequest {
            if (page < 0) {
                throw new IllegalArgumentException("page must be >= 0");
            }
            size = size <= 0 ? 50 : Math.min(size, MAX_PAGE);
            if ((long) page * size > MAX_OFFSET) {
                throw new PortsProblem("PAGE_TOO_DEEP", 422, "page " + page + " x size " + size + " starts beyond row " + MAX_OFFSET
                        + "; narrow the filter (time range, definition, instance)");
            }
        }

        public int first() {
            return page * size;
        }
    }

    /** {@code capped=true}: there are more than {@code total} rows (A-16). */
    public record PageResult<T>(List<T> items, long total, boolean capped, int page, int size) {
    }

    /** {@code OFFSET/FETCH} suffix for a query that already has an ORDER BY. */
    public static String window(Dialect d, PageRequest p) {
        return switch (d) {
            case POSTGRESQL, MYSQL -> " LIMIT " + p.size() + " OFFSET " + p.first();
            case ORACLE, SQLSERVER -> " OFFSET " + p.first() + " ROWS FETCH NEXT " + p.size() + " ROWS ONLY";
        };
    }

    /** Count bounded at {@link #COUNT_CAP}+1 rows, so a huge result is never fully counted (A-16). */
    public static String cappedCount(Dialect d, String fromAndWhere) {
        int n = COUNT_CAP + 1;
        return switch (d) {
            case POSTGRESQL, MYSQL -> "SELECT COUNT(*) FROM (SELECT 1 AS o " + fromAndWhere + " LIMIT " + n + ") x";
            case ORACLE -> "SELECT COUNT(*) FROM (SELECT 1 AS o " + fromAndWhere + (fromAndWhere.contains(" WHERE ") ? " AND " : " WHERE ")
                    + "ROWNUM <= " + n + ")";
            case SQLSERVER -> "SELECT COUNT(*) FROM (SELECT TOP (" + n + ") 1 AS o " + fromAndWhere + ") x";
        };
    }

    /**
     * Result of a probed page (no {@code COUNT(*)}): {@code total} is the lower bound {@code first + items}, and
     * {@code capped=true} when a further row exists.
     */
    public static <T> PageResult<T> probed(List<T> items, boolean more, PageRequest p) {
        long lower = (long) p.first() + items.size() + (more ? 1 : 0);
        return new PageResult<>(items, Math.min(lower, COUNT_CAP), more || lower > COUNT_CAP, p.page(), p.size());
    }

    public static <T> PageResult<T> of(List<T> items, long total, PageRequest p) {
        boolean capped = total > COUNT_CAP;
        return new PageResult<>(items, capped ? COUNT_CAP : total, capped, p.page(), p.size());
    }
}

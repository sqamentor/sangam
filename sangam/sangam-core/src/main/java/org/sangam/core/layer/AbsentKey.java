// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.layer;

/**
 * The stored form of "no value" in a key column (primary key, unique key, lookup key) on all five dialects (SL-84).
 *
 * <p>The empty string cannot be that form: Oracle stores {@code ''} as NULL, so a NOT NULL key column rejects it
 * (ORA-01400), an UPDATE to it fails (ORA-01407) and {@code col = ''} never matches. A nullable key column is no
 * portable alternative either: a primary key cannot hold NULL on any dialect, and NULLs in unique indexes differ
 * (SQL Server allows one NULL, Oracle ignores all-NULL entries, PostgreSQL/MySQL/H2 allow any number). So an absent key
 * part is stored as the reserved, non-empty {@link #NONE}, the same value every product key already uses for "none"
 * ({@code AUTH_EFFECTIVE.cond_key}, the NEVER number-series period, the ledger account key parts).
 *
 * <p>The mapping is a bijection between "absent" and {@code NONE}: a real value equal to {@code NONE} is refused, so it
 * can never alias the absent key. Only key columns use this; free text that may legitimately be empty is a different
 * case (it needs a nullable column).
 */
public final class AbsentKey {

    /** Stored value of an absent key part. */
    public static final String NONE = "-";

    private AbsentKey() {
    }

    /** The value to bind for a key part: {@link #NONE} for null or empty, otherwise the value itself. */
    public static String encode(String value) {
        if (value == null || value.isEmpty()) {
            return NONE;
        }
        if (NONE.equals(value)) {
            throw new IllegalArgumentException("'" + NONE + "' is reserved for an absent key part and cannot be a value");
        }
        return value;
    }

    /**
     * The value read back from a key column: null for {@link #NONE}. An empty string (or NULL) also reads as absent, so
     * rows written before the stored form changed read the same way.
     */
    public static String decode(String stored) {
        return stored == null || stored.isEmpty() || NONE.equals(stored) ? null : stored;
    }
}

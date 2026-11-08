// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.bulk;

/**
 * Per-tenant quota port for bulk ingest (SL-44). Asked once per uploaded chunk, before any row is stored, for the
 * number of rows in it. A refusal becomes HTTP 429 with {@code Retry-After} and stores nothing.
 *
 * <p>Narrow on purpose: the per-tenant quota service (SL-43) implements this when it is installed; without one the
 * server binds {@link #UNLIMITED} and only the {@link BulkIngest.Limits} of the ingest service itself apply.
 */
@FunctionalInterface
public interface BulkQuota {

    /** The answer for one chunk. {@code retryAfterSeconds} is only meaningful when not allowed. */
    record Decision(boolean allowed, int retryAfterSeconds, String reason) {

        public static final Decision ALLOW = new Decision(true, 0, null);

        public static Decision deny(int retryAfterSeconds, String reason) {
            return new Decision(false, Math.max(1, retryAfterSeconds), reason);
        }
    }

    /** May {@code tenant} load {@code rows} more rows of {@code objectType} now? Consumes the allowance when allowed. */
    Decision acquire(String tenant, String objectType, int rows);

    BulkQuota UNLIMITED = (tenant, type, rows) -> Decision.ALLOW;
}

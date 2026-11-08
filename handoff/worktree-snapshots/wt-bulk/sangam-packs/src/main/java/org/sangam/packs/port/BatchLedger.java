// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.port;

import java.util.List;
import java.util.Map;

/**
 * Aggregation store for the GP-09 rule: large sets are processed as <b>one process instance per item</b>, and the
 * parent reads the aggregate here instead of joining thousands of child executions (no hot root, E6). Item outcomes
 * are written by each child in its own transaction; the parent polls on a timer.
 *
 * <p>The production implementation is a product table (WS-ENGINE persistence); the default is in memory.
 */
public interface BatchLedger {

    /** Opens round {@code round} of batch {@code batchId} with the given item keys. Idempotent. */
    void open(String tenantId, String batchId, int round, List<String> itemKeys);

    /** Records the terminal outcome of one item. Idempotent per (batch, round, item). */
    void record(String tenantId, String batchId, int round, String itemKey, String outcome, String reasonCode,
                Map<String, Object> data);

    BatchSummary summary(String tenantId, String batchId, int round);

    /** Item keys of round {@code round} whose outcome is not a success, with their reason codes. */
    Map<String, String> failures(String tenantId, String batchId, int round);

    /** Data recorded by each item of the round (scores, amounts), keyed by item. */
    Map<String, Map<String, Object>> itemData(String tenantId, String batchId, int round);

    /**
     * Totals of a round.
     *
     * @param total     items opened
     * @param done      items with a terminal outcome
     * @param succeeded items whose outcome is {@code SUCCESS}
     * @param byOutcome counts by outcome
     */
    record BatchSummary(int total, int done, int succeeded, Map<String, Integer> byOutcome) {
        public boolean complete() {
            return done >= total;
        }
    }
}

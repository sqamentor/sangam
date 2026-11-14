// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.packs;

import java.sql.Connection;
import java.util.Map;

/**
 * Owner decision 289: told when a pack operation reaches a final state ({@code COMPLETED}, {@code FAILED} or
 * {@code CANCELLED}), so the module that started the operation can record its own outcome (for example the data-object
 * side writes {@code DATA_OBJECT_RECORD_SETTLED}) without this module knowing what the operation was for.
 *
 * <p>Registered per operation kind with {@link PackOperationStore#onSettled}: the kind is the business key's prefix
 * before the first colon ({@code data-object} for {@code data-object:<key>}). The listener is called exactly once per
 * operation, by {@link PackOperationStore#settle} inside the settling transaction on its connection, right after the
 * row moved to the final state; a final row is never settled again. An exception rolls the settlement back.
 */
@FunctionalInterface
public interface PackSettlementListener {

    /**
     * One settlement.
     *
     * @param status     COMPLETED, FAILED or CANCELLED
     * @param reasonCode the failure or cancel code (null when COMPLETED)
     * @param output     the connector's output: for COMPLETED the same map whether the dispatch or a reconciliation
     *                   ({@code CONFIRM_SUCCESS}) settled it; for FAILED the failure output; never null
     * @param settledBy  who settled it: {@value PackSettlementListener#SYSTEM_ACTOR} for the dispatch and the gateway, otherwise the
     *                   resolving actor of a reconciliation ({@code system:reconciler} or the officer)
     */
    record Settlement(String tenantId, String operationId, String connector, String operation, String businessKey,
                      String processInstanceId, PackOperationStore.Status status, String reasonCode, String externalRef,
                      Map<String, Object> output, String settledBy) {
        public Settlement {
            output = output == null ? Map.of() : output;
            settledBy = settledBy == null || settledBy.isBlank() ? SYSTEM_ACTOR : settledBy;
        }
    }

    /** The actor of a settlement made by the dispatch or the gateway itself. */
    String SYSTEM_ACTOR = "system:integration";

    void settled(Connection c, Settlement settlement);

    /** The kind of an operation: its business key's prefix before the first colon, or null without one. */
    static String kindOf(String businessKey) {
        if (businessKey == null) {
            return null;
        }
        int i = businessKey.indexOf(':');
        return i <= 0 ? null : businessKey.substring(0, i);
    }
}

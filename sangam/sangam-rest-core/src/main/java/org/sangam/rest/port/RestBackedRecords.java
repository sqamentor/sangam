// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

import java.util.Map;

/**
 * EC-06, owner decisions 265 and 266: the connector seam of REST-backed data objects (R78). The data object's
 * {@code endpointRef} names the pack connector bound per tenant (R71); the host implements this port over its connector
 * gateway (sangam-server binds it only with sangam-integration, otherwise REST backing answers 501
 * CAPABILITY_NOT_INSTALLED). The port only moves data: every data-object rule (field validation, unknown fields
 * dropped, sensitive fields masked, which operations a data object may read) stays with the low-code adapter.
 */
public interface RestBackedRecords {

    /** The connector query operation that lists records (decision 266). */
    String LIST_OPERATION = "list";
    /** The connector effect operation that creates a record (decision 265). */
    String CREATE_OPERATION = "create";

    /** Connector answer to {@value #LIST_OPERATION}: {@code output.records} and the optional {@code output.total}. */
    record Listing(Object records, Object total) {
    }

    /**
     * What is known about one connector operation.
     *
     * @param status      PENDING | COMPLETED | FAILED | UNKNOWN | CANCELLED (the gateway's operation status)
     * @param reasonCode  machine reason for FAILED, UNKNOWN or CANCELLED
     * @param message     human text, English
     * @param externalRef reference issued by the external system
     * @param output      the connector's output (for a completed create, {@code output.record} is the created record)
     */
    record Outcome(String status, String reasonCode, String message, String externalRef, Map<String, Object> output) {
        public Outcome {
            output = output == null ? Map.of() : output;
        }
    }

    /** Thrown when the connector gives no usable answer; {@code reasonCode} is the gateway's reason, when it gave one. */
    final class ConnectorProblem extends RuntimeException {
        private final String reasonCode;

        public ConnectorProblem(String reasonCode, String message) {
            super(message);
            this.reasonCode = reasonCode;
        }

        public String reasonCode() {
            return reasonCode;
        }
    }

    /**
     * Owner decision 320: the reason code of a list the connector seam refused because the bound connector does not
     * declare the list request read-only. A permanent refusal (422), not a connector failure (503).
     */
    String NOT_READ_ONLY = "CONNECTOR_OPERATION_NOT_READ_ONLY";

    /**
     * Synchronous read through the connector query operation {@value #LIST_OPERATION} with payload
     * {@code {q, offset, limit}}. A failed or unavailable connector throws {@link ConnectorProblem}. The list is an API
     * read (owner decisions 320 and 321): the host calls the connector only when it declares this request read-only and
     * otherwise throws {@link ConnectorProblem} with {@value #NOT_READ_ONLY}, nothing called.
     */
    Listing list(String tenantId, String endpointRef, String q, int offset, int limit);

    /**
     * Definition-time probe (owner decision 320): why a list of {@code endpointRef} would be refused as not read-only,
     * or null when it would not be (also when the binding cannot be resolved yet). Nothing is called. The run-time check
     * in {@link #list} is the guard; this only tells the administrator at once. The default (a host that does not
     * probe) is null.
     */
    default String listRefusal(String tenantId, String endpointRef) {
        return null;
    }

    /**
     * Requests the {@value #CREATE_OPERATION} effect once per {@code operationId} with payload
     * {@code {dataObject, record}}; answers the gateway's outcome (normally PENDING).
     */
    Outcome submit(String tenantId, String endpointRef, String operationId, String dataObjectKey,
                   Map<String, Object> record);

    /** The outcome of an earlier {@link #submit} (gateway lookup). */
    Outcome lookup(String tenantId, String endpointRef, String operationId);
}

// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.util.LinkedHashMap;
import java.util.Map;

import org.sangam.integration.packs.ApiReadGateway;
import org.sangam.packs.core.Vars;
import org.sangam.packs.port.ConnectorGateway;
import org.sangam.packs.port.ConnectorRequest;
import org.sangam.packs.port.ConnectorResult;
import org.sangam.packs.port.OperationStatus;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.RestBackedRecords;

/**
 * EC-06, owner decisions 265 and 266: the REST-backed data object seam over the connector gateway (with
 * sangam-integration, {@code IntegrationConnectorGateway}). The data object's {@code endpointRef} is the pack connector
 * bound per tenant. Shapes only; the data-object rules are in sangam-rest {@code RestBackedDataObjects}.
 *
 * <ul>
 *   <li>list: an API read (owner decisions 320 and 321), so {@link ApiReadGateway#queryReadOnly} and never
 *       {@link ConnectorGateway#query}: operation {@value RestBackedRecords#LIST_OPERATION}, payload
 *       {@code {q, offset, limit}} ({@code q} left out when absent); COMPLETED answers {@code output.records} and
 *       {@code output.total}; FAILED is a {@link RestBackedRecords.ConnectorProblem} with the gateway's reason
 *       ({@code CONNECTOR_OPERATION_NOT_READ_ONLY} when the bound connector does not declare the request read-only;
 *       nothing was called); PENDING or UNKNOWN (no definite answer) is {@value #CONNECTOR_UNAVAILABLE}.
 *       {@link #listRefusal} asks the same question for a first-page list without calling anything.</li>
 *   <li>create: {@link ConnectorGateway#submit} operation {@value RestBackedRecords#CREATE_OPERATION} with the
 *       operationId, payload {@code {dataObject, record}}, its canonical hash, business key
 *       {@code data-object:<key>} and no process instance (outside any engine transaction, so the gateway opens its
 *       own).</li>
 *   <li>lookup: {@link ConnectorGateway#lookup}.</li>
 * </ul>
 */
public final class RestBackedRecordsGateway implements RestBackedRecords {

    public static final String CONNECTOR_UNAVAILABLE = "CONNECTOR_UNAVAILABLE";

    private final ConnectorGateway gateway;
    private final ApiReadGateway reads;

    /**
     * @param gateway effects and their outcomes (submit, lookup)
     * @param reads   the API-read entry of the same gateway (list)
     */
    public RestBackedRecordsGateway(ConnectorGateway gateway, ApiReadGateway reads) {
        this.gateway = java.util.Objects.requireNonNull(gateway, "gateway");
        this.reads = java.util.Objects.requireNonNull(reads, "reads: a list never goes through ConnectorGateway.query");
    }

    private static ConnectorRequest listRequest(String tenantId, String endpointRef, String q, int offset, int limit) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (q != null) {
            payload.put("q", q);
        }
        payload.put("offset", offset);
        payload.put("limit", limit);
        return new ConnectorRequest(tenantId, endpointRef, LIST_OPERATION, null, null, null, payload, Vars.hash(payload));
    }

    @Override
    public String listRefusal(String tenantId, String endpointRef) {
        return reads.readOnlyRefusal(listRequest(tenantId, endpointRef, null, 0, 1)).orElse(null);
    }

    @Override
    public Listing list(String tenantId, String endpointRef, String q, int offset, int limit) {
        ConnectorResult r = reads.queryReadOnly(listRequest(tenantId, endpointRef, q, offset, limit));
        if (r == null) {
            throw new ConnectorProblem(CONNECTOR_UNAVAILABLE, "no answer from " + endpointRef);
        }
        if (r.status() == OperationStatus.COMPLETED) {
            return new Listing(r.data().get("records"), r.data().get("total"));
        }
        if (r.status() == OperationStatus.FAILED) {
            throw new ConnectorProblem(r.reasonCode(), "the list of " + endpointRef + " failed"
                    + (r.message() == null ? "" : ": " + r.message()));
        }
        throw new ConnectorProblem(CONNECTOR_UNAVAILABLE, "no definite answer from " + endpointRef + " (" + r.status()
                + ")");
    }

    @Override
    public Outcome submit(String tenantId, String endpointRef, String operationId, String dataObjectKey,
                          Map<String, Object> record) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("dataObject", dataObjectKey);
        payload.put("record", record);
        String hash;
        try {
            hash = Vars.hash(payload);
        } catch (IllegalArgumentException e) {
            // R33: a json field carrying a non-integral number cannot be hashed canonically; decimals go as strings
            throw ApiProblem.badRequest("VALIDATION_FAILED", e.getMessage());
        }
        return outcome(gateway.submit(new ConnectorRequest(tenantId, endpointRef, CREATE_OPERATION, operationId,
                "data-object:" + dataObjectKey, null, payload, hash)));
    }

    @Override
    public Outcome lookup(String tenantId, String endpointRef, String operationId) {
        return outcome(gateway.lookup(tenantId, endpointRef, operationId));
    }

    private static Outcome outcome(ConnectorResult r) {
        if (r == null || r.status() == null) {
            throw new ConnectorProblem(CONNECTOR_UNAVAILABLE, "no answer from the connector gateway");
        }
        return new Outcome(r.status().name(), r.reasonCode(), r.message(), r.externalRef(), r.data());
    }
}

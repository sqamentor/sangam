// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.jdbc;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.LowCodePort.DataObjectDef;
import org.sangam.rest.port.LowCodePort.DataObjectField;
import org.sangam.rest.port.LowCodePort.RecordOperation;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.RestBackedRecords;

/**
 * EC-06, owner decisions 265 and 266: the rules of REST-backed data objects (R78) over the {@link RestBackedRecords}
 * connector seam.
 *
 * <ul>
 *   <li><b>List</b> (266): connector query operation {@value RestBackedRecords#LIST_OPERATION} with
 *       {@code {q, offset, limit}}; the answer is {@code output.records} (an array of objects) and the optional
 *       {@code output.total}. Each record keeps only {@code id} and the definition's fields (unknown fields dropped) and
 *       its sensitive fields are masked exactly as for DB backing. Without {@code output.total} the total is what was
 *       seen so far, capped (A-16: "more than") when the page came back full.</li>
 *   <li><b>Create</b> (265): the record, already validated against the definition as for DB backing, is submitted
 *       with a new operationId (UUID) and answered 202 with status {@code PENDING} (contract schema
 *       {@code DataObjectRecordOperation}, no record). The operation is recorded for the
 *       data object and audited ({@code DATA_OBJECT_RECORD_CREATE_ACCEPTED}, decision 289, with status PENDING and the
 *       operationId; the settled outcome is DATA_OBJECT_RECORD_SETTLED, written by sangam-integration) in the
 *       caller's transaction before the submit, so a refused submit leaves neither.</li>
 *   <li><b>Operation</b> (265): the outcome through the gateway lookup, only for an operation that this data object
 *       submitted in the caller's tenant (otherwise 404, so no other operation of the tenant can be read here). The
 *       contract states are PENDING, COMPLETED (with the record from {@code output.record}, projected and masked as on
 *       the list), FAILED (with {@code failureCode}) and UNKNOWN. A lookup that finds no record of the operation
 *       ({@code NOT_FOUND}) is PENDING ("the external system has no record of it yet"); a CANCELLED operation (no
 *       data-object route cancels one) is FAILED with its reason.</li>
 *   <li>Without the connector seam (no sangam-integration) every REST-backed call is 501 CAPABILITY_NOT_INSTALLED. A
 *       connector that gives no usable answer is 503 {@value #CONNECTOR_FAILED} with its reason code.</li>
 * </ul>
 */
public final class RestBackedDataObjects {

    public static final String CONNECTOR_FAILED = "DATA_OBJECT_CONNECTOR_FAILED";
    /** Decision 289: the audit event of an accepted REST-backed create (a DB-backed create is DATA_OBJECT_RECORD_CREATED). */
    public static final String ACCEPTED_EVENT = "DATA_OBJECT_RECORD_CREATE_ACCEPTED";
    public static final String OUTPUT_INVALID = "OUTPUT_INVALID";
    public static final String PENDING = "PENDING";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
    public static final String UNKNOWN = "UNKNOWN";
    /** The failure code when the gateway gave none (as IntegrationConnectorGateway does for a failed lookup). */
    static final String DOWNSTREAM_FAILED = "DOWNSTREAM_FAILED";
    static final int RETRY_AFTER_SECONDS = 5;
    private static final String MASK = "***";

    /** Which operations each data object submitted, per tenant. */
    public interface OperationLog {
        void submitted(RequestContext ctx, String operationId, String dataObjectKey, String endpointRef);

        boolean submitted(RequestContext ctx, String operationId, String dataObjectKey);
    }

    private final Supplier<RestBackedRecords> connector;
    private final OperationLog log;
    private final AuditPort audit;
    private final long countCap;

    public RestBackedDataObjects(Supplier<RestBackedRecords> connector, OperationLog log, AuditPort audit,
                                 long countCap) {
        this.connector = connector == null ? () -> null : connector;
        this.log = log;
        this.audit = audit;
        this.countCap = countCap;
    }

    /** 501 CAPABILITY_NOT_INSTALLED without the connector seam. */
    public void requireInstalled() {
        connector();
    }

    private RestBackedRecords connector() {
        RestBackedRecords c = connector.get();
        if (c == null) {
            throw ApiProblem.notInstalled("REST-backed data objects (R78)", "sangam-integration");
        }
        return c;
    }

    /**
     * Owner decision 320, at definition time: a REST-backed data object whose {@code endpointRef} is bound to a
     * connector that does not declare the list request read-only is refused (422 {@value RestBackedRecords#NOT_READ_ONLY}),
     * so the administrator learns at once. Without the connector seam, or with a binding that does not resolve yet,
     * nothing is refused here: the list itself is checked on every call.
     */
    public void requireReadOnlyList(RequestContext ctx, DataObjectDef d) {
        RestBackedRecords c = connector.get();
        String refusal = c == null ? null : c.listRefusal(JdbcSupport.tenant(ctx), d.endpointRef());
        if (refusal != null) {
            throw ApiProblem.unprocessable(RestBackedRecords.NOT_READ_ONLY, "endpointRef " + d.endpointRef()
                    + " cannot back a data object: " + refusal).with("field", "endpointRef");
        }
    }

    // ------------------------------------------------------------------ list (decision 266)

    public Page<Map<String, Object>> list(RequestContext ctx, DataObjectDef d, String q, Page.PageRequest page,
                                          boolean unmask) {
        RestBackedRecords c = connector();
        int offset = page.firstResult();
        int limit = page.size();
        RestBackedRecords.Listing answer;
        try {
            answer = c.list(JdbcSupport.tenant(ctx), d.endpointRef(), q, offset, limit);
        } catch (RestBackedRecords.ConnectorProblem e) {
            if (RestBackedRecords.NOT_READ_ONLY.equals(e.reasonCode())) {
                // decision 320: a permanent refusal of this data object's binding, never a retryable 503
                throw ApiProblem.unprocessable(RestBackedRecords.NOT_READ_ONLY, e.getMessage());
            }
            throw failed(e.reasonCode(), e.getMessage());
        }
        if (answer == null || !(answer.records() instanceof List<?> raw)) {
            throw failed(OUTPUT_INVALID, "the list answer of " + d.endpointRef() + " has no output.records array");
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Object o : raw) {
            if (!(o instanceof Map<?, ?> m)) {
                throw failed(OUTPUT_INVALID, "output.records of " + d.endpointRef() + " holds a non-object entry");
            }
            if (items.size() < limit) {
                items.add(project(d, m, unmask));
            }
        }
        Long total = total(answer.total(), d);
        if (total == null) {
            boolean more = raw.size() >= limit;
            return Page.of(items, (long) offset + items.size(), more);
        }
        boolean capped = total > countCap;
        return Page.of(items, capped ? countCap : total, capped);
    }

    private static Long total(Object t, DataObjectDef d) {
        if (t == null) {
            return null;
        }
        try {
            BigDecimal b = new BigDecimal(String.valueOf(t));
            long v = b.longValueExact();
            if (v >= 0) {
                return v;
            }
        } catch (NumberFormatException | ArithmeticException e) {
            // reported below
        }
        throw failed(OUTPUT_INVALID, "output.total of " + d.endpointRef() + " is not a whole number >= 0");
    }

    // ------------------------------------------------------------------ create (decision 265)

    /** {@code validated} holds the definition's fields only, type-checked as for DB backing. */
    public RecordOperation create(RequestContext ctx, DataObjectDef d, Map<String, Object> validated) {
        RestBackedRecords c = connector();
        String operationId = UUID.randomUUID().toString();
        log.submitted(ctx, operationId, d.key(), d.endpointRef());
        audit.record(ctx, "data:" + d.key(), ACCEPTED_EVENT,
                JdbcSupport.payload("dataObject", d.key(), "operationId", operationId, "status", PENDING,
                        "backing", "REST", "actor", ctx.actor()));
        RestBackedRecords.Outcome o;
        try {
            o = c.submit(JdbcSupport.tenant(ctx), d.endpointRef(), operationId, d.key(), Map.copyOf(validated));
        } catch (RestBackedRecords.ConnectorProblem e) {
            throw failed(e.reasonCode(), e.getMessage());
        }
        if (o == null || !(PENDING.equals(o.status()) || COMPLETED.equals(o.status()))) {
            throw failed(o == null ? null : o.reasonCode(), o == null ? "no answer"
                    : "the create of " + d.endpointRef() + " was refused: " + o.status()
                    + (o.message() == null ? "" : " (" + o.message() + ")"));
        }
        // decision 265: the create is answered as accepted; its outcome, even an immediate one, is read through the
        // operation
        return new RecordOperation(operationId, d.key(), PENDING, null, null);
    }

    // ------------------------------------------------------------------ operation outcome (decision 265)

    public RecordOperation operation(RequestContext ctx, DataObjectDef d, String operationId, boolean unmask) {
        RestBackedRecords c = connector();
        if (operationId == null || !log.submitted(ctx, operationId, d.key())) {
            throw ApiProblem.notFound("Data object operation", operationId);
        }
        RestBackedRecords.Outcome o;
        try {
            o = c.lookup(JdbcSupport.tenant(ctx), d.endpointRef(), operationId);
        } catch (RestBackedRecords.ConnectorProblem e) {
            throw failed(e.reasonCode(), e.getMessage());
        }
        if (o == null || o.status() == null) {
            throw failed(OUTPUT_INVALID, "no outcome for operation " + operationId);
        }
        return switch (o.status()) {
            case PENDING -> new RecordOperation(operationId, d.key(), PENDING, null, null);
            case COMPLETED -> new RecordOperation(operationId, d.key(), COMPLETED,
                    o.output().get("record") instanceof Map<?, ?> m ? project(d, m, unmask) : null, null);
            case UNKNOWN -> new RecordOperation(operationId, d.key(), UNKNOWN, null, null);
            case FAILED -> "NOT_FOUND".equals(o.reasonCode())
                    ? new RecordOperation(operationId, d.key(), PENDING, null, null)
                    : new RecordOperation(operationId, d.key(), FAILED, null, failureCode(o));
            case "CANCELLED" -> new RecordOperation(operationId, d.key(), FAILED, null,
                    o.reasonCode() == null ? "CANCELLED" : o.reasonCode());
            default -> throw failed(OUTPUT_INVALID, "operation " + operationId + " has the unknown status " + o.status());
        };
    }

    private static String failureCode(RestBackedRecords.Outcome o) {
        return o.reasonCode() == null || o.reasonCode().isBlank() ? DOWNSTREAM_FAILED : o.reasonCode();
    }

    // ------------------------------------------------------------------ helpers

    /** Keeps {@code id} and the definition's fields (unknown fields dropped); masks sensitive fields unless unmasked. */
    static Map<String, Object> project(DataObjectDef d, Map<?, ?> raw, boolean unmask) {
        Map<String, Object> r = new LinkedHashMap<>();
        if (raw.get("id") != null) {
            r.put("id", String.valueOf(raw.get("id")));
        }
        for (DataObjectField f : d.fields()) {
            if (raw.containsKey(f.name())) {
                r.put(f.name(), f.sensitive() && !unmask ? MASK : raw.get(f.name()));
            }
        }
        return r;
    }

    private static ApiProblem failed(String reasonCode, String detail) {
        ApiProblem p = ApiProblem.unavailable(CONNECTOR_FAILED, detail, RETRY_AFTER_SECONDS);
        return reasonCode == null ? p : p.with("reasonCode", reasonCode);
    }
}

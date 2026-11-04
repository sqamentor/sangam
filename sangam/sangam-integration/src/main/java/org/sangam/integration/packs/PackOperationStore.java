// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.packs;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/**
 * One row per pack operation ({@code PACK_OPERATION}, keyed by the pack's own operationId): the first result per
 * operationId and payload hash (ConnectorGateway contract, VI.3, R41), the mapping to the integration ledger's
 * operation id, and the state the {@code sangam.*} result messages are derived from.
 *
 * <p>States: {@code PENDING} (dispatch queued in the outbox) → {@code COMPLETED} | {@code FAILED} | {@code UNKNOWN} |
 * {@code CANCELLED}; {@code RETRY_REQUESTED} (reconciliation chose retry, or a lookup proved nothing happened) makes the
 * next {@code submit} with the same operationId dispatch again. {@code generation} counts dispatches and keeps the
 * inbox message ids of successive results distinct.
 */
public final class PackOperationStore {

    public enum Status { PENDING, COMPLETED, FAILED, UNKNOWN, CANCELLED, RETRY_REQUESTED }

    public record Row(String operationId, String tenantId, String connector, String operation, UUID ledgerOperationId,
                      String rootId, String processInstanceId, String businessKey, String payloadHash, String payloadJson,
                      Status status, String reasonCode, String externalRef, String resultJson, boolean cancelRequested,
                      int generation, boolean dispatched) {
    }

    private final Dialect dialect;

    public PackOperationStore(Dialect dialect) {
        this.dialect = dialect;
    }

    /** Inserts a new PENDING row; false when a row for the operationId exists. */
    public boolean insert(Connection c, Row r) {
        return Jdbc.insertIfAbsent(c, dialect,
                "INSERT INTO PACK_OPERATION(operation_id, tenant_id, connector, operation, ledger_operation_id, root_id,"
                        + " process_instance_id, business_key, payload_hash, payload_json, status, reason_code, external_ref,"
                        + " result_json, cancel_requested, generation, dispatched, created_at, updated_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,'PENDING',NULL,NULL,NULL,0,1,0," + dialect.now() + "," + dialect.now() + ")",
                r.operationId(), r.tenantId(), r.connector(), r.operation(), r.ledgerOperationId(), r.rootId(),
                r.processInstanceId(), r.businessKey(), r.payloadHash(), r.payloadJson());
    }

    public Optional<Row> find(Connection c, String tenantId, String operationId) {
        return Jdbc.one(c, "SELECT * FROM PACK_OPERATION WHERE tenant_id=? AND operation_id=?", PackOperationStore::map,
                tenantId, operationId);
    }

    public Optional<Row> lock(Connection c, String tenantId, String operationId) {
        return Jdbc.one(c, dialect.lockingSelect("*", "PACK_OPERATION", "tenant_id=? AND operation_id=?"),
                PackOperationStore::map, tenantId, operationId);
    }

    public Optional<Row> byLedgerOperation(Connection c, UUID ledgerOperationId) {
        return Jdbc.one(c, "SELECT * FROM PACK_OPERATION WHERE ledger_operation_id=?", PackOperationStore::map, ledgerOperationId);
    }

    public void settle(Connection c, String tenantId, String operationId, Status status, String reasonCode, String externalRef,
                       String resultJson) {
        Jdbc.update(c, "UPDATE PACK_OPERATION SET status=?, reason_code=?, external_ref=COALESCE(?, external_ref), result_json=?,"
                        + " updated_at=" + dialect.now() + " WHERE tenant_id=? AND operation_id=?",
                status, reasonCode, externalRef, resultJson, tenantId, operationId);
    }

    /** RETRY_REQUESTED → PENDING with a new generation (a new dispatch is queued by the caller). */
    public void redispatch(Connection c, String tenantId, String operationId) {
        Jdbc.update(c, "UPDATE PACK_OPERATION SET status='PENDING', reason_code=NULL, dispatched=0, generation=generation+1, updated_at="
                + dialect.now() + " WHERE tenant_id=? AND operation_id=?", tenantId, operationId);
    }

    /** Marks the current generation as handed to the connector (under the row lock, before the call). */
    public void markDispatched(Connection c, String tenantId, String operationId) {
        Jdbc.update(c, "UPDATE PACK_OPERATION SET dispatched=1, updated_at=" + dialect.now()
                + " WHERE tenant_id=? AND operation_id=?", tenantId, operationId);
    }

    public void requestCancel(Connection c, String tenantId, String operationId) {
        Jdbc.update(c, "UPDATE PACK_OPERATION SET cancel_requested=1, updated_at=" + dialect.now()
                + " WHERE tenant_id=? AND operation_id=?", tenantId, operationId);
    }

    private static Row map(ResultSet rs) throws SQLException {
        String ledger = rs.getString("ledger_operation_id");
        return new Row(rs.getString("operation_id"), rs.getString("tenant_id"), rs.getString("connector"), rs.getString("operation"),
                ledger == null ? null : UUID.fromString(ledger), rs.getString("root_id"), rs.getString("process_instance_id"),
                rs.getString("business_key"), rs.getString("payload_hash"), rs.getString("payload_json"),
                Status.valueOf(rs.getString("status")), rs.getString("reason_code"), rs.getString("external_ref"),
                rs.getString("result_json"), rs.getInt("cancel_requested") == 1, rs.getInt("generation"),
                rs.getInt("dispatched") == 1);
    }

    /** Reference DDL for sangam-persistence (WS-ENGINE). */
    public static final java.util.List<String> DDL = java.util.List.of(
            """
            CREATE TABLE PACK_OPERATION (
              operation_id         {str64} NOT NULL,
              tenant_id            {str64} NOT NULL,
              connector            {str} NOT NULL,
              operation            {str} NOT NULL,
              ledger_operation_id  {str64},
              root_id              {str64} NOT NULL,
              process_instance_id  {str64},
              business_key         {str},
              payload_hash         {str},
              payload_json         {text},
              status               {str64} NOT NULL,
              reason_code          {str},
              external_ref         {str},
              result_json          {text},
              cancel_requested     {small} NOT NULL,
              generation           {int} NOT NULL,
              dispatched           {small} NOT NULL,
              created_at           {ts} NOT NULL,
              updated_at           {ts} NOT NULL,
              PRIMARY KEY (tenant_id, operation_id)
            )""",
            "CREATE INDEX PACK_OPERATION_LEDGER ON PACK_OPERATION (ledger_operation_id)");
}

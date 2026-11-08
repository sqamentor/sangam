// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;

import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable call intent with provider handles (R37), one row per operationId in {@code CONNECTOR_CALL}. It complements
 * {@code OPERATION_LEDGER} (which holds identity, fencing and outcome) with what reconciliation needs to repeat a
 * lookup later: connector id, operation, request input and the provider reference, plus the classification of the last
 * attempt ({@code NO_EFFECT} is the proof that lets a class (c) operation be retried).
 *
 * <p>All methods run in the caller's transaction. The request input is personal data and is scrubbed by erasure
 * (VI.5) through {@link #erase}.
 */
public final class ConnectorCallJournal {

    /** Classification of the last attempt. */
    public enum LastResult { DISPATCHING, SUCCESS, BUSINESS_FAILURE, NO_EFFECT, MAYBE, UNKNOWN }

    public record Call(UUID operationId, String tenantId, String connectorId, String operation, String rootId,
                       String businessKey, String requestJson, String providerRef, LastResult lastResult, String lastError,
                       int attempts) {
    }

    private final Dialect dialect;

    public ConnectorCallJournal(Dialect dialect) {
        this.dialect = dialect;
    }

    /** Inserts the call on its first attempt; on later attempts increments the attempt count. Returns the row. */
    public Call recordAttempt(Connection c, UUID operationId, String tenantId, String connectorId, String operation,
                              String rootId, String businessKey, String requestJson) {
        boolean inserted = Jdbc.insertIfAbsent(c, dialect,
                "INSERT INTO CONNECTOR_CALL(operation_id, tenant_id, connector_id, operation, root_id, business_key, request_json,"
                        + " provider_ref, last_result, last_error, attempts, created_at, updated_at)"
                        + " VALUES (?,?,?,?,?,?,?,NULL,'DISPATCHING',NULL,1," + dialect.now() + "," + dialect.now() + ")",
                operationId, tenantId, connectorId, operation, rootId, businessKey, requestJson);
        Call before = find(c, operationId).orElseThrow();
        if (!inserted) {
            Jdbc.update(c, "UPDATE CONNECTOR_CALL SET attempts=attempts+1, updated_at=" + dialect.now() + " WHERE operation_id=?",
                    operationId);
        }
        return before;
    }

    public void recordResult(Connection c, UUID operationId, LastResult result, String providerRef, String error) {
        Jdbc.update(c, "UPDATE CONNECTOR_CALL SET last_result=?, provider_ref=COALESCE(?, provider_ref), last_error=?, updated_at="
                + dialect.now() + " WHERE operation_id=?", result, providerRef, truncate(error), operationId);
    }

    public Optional<Call> find(Connection c, UUID operationId) {
        return Jdbc.one(c, "SELECT operation_id, tenant_id, connector_id, operation, root_id, business_key, request_json, provider_ref,"
                        + " last_result, last_error, attempts FROM CONNECTOR_CALL WHERE operation_id=?",
                rs -> new Call(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getString(7), rs.getString(8), LastResult.valueOf(rs.getString(9)), rs.getString(10),
                        rs.getInt(11)),
                operationId);
    }

    /** Erasure (VI.5): scrubs request input of the given roots; identity and provider references stay. */
    public int erase(Connection c, String tenantId, List<String> rootIds) {
        int n = 0;
        for (String root : rootIds) {
            n += Jdbc.update(c, "UPDATE CONNECTOR_CALL SET request_json=NULL, last_error=NULL WHERE tenant_id=? AND root_id=?",
                    tenantId, root);
        }
        return n;
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 2000 ? s : s.substring(0, 2000);
    }
}

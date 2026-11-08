// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.inbox;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;

import java.sql.Connection;
import java.util.Optional;

/**
 * {@code uniqueStart} (SANGAM.md VI.2.6 rule 4, R38, T35; FLOWABLE-INTO-OPERATON FC-04 {@code storeAsUniqueReferenceId}): at most one
 * business start per (tenant, definition key, business key), enforced by the primary key of {@code UNIQUE_START} and
 * checked in the same transaction as the start. A database restore while the broker holds newer messages therefore
 * cannot produce a second start: the inbox dedups the message and, if the inbox row was lost with the restore, this
 * row (lost or kept together with the engine state) decides.
 */
public final class UniqueStartGuard {

    private final Dialect dialect;

    public UniqueStartGuard(Dialect dialect) {
        this.dialect = dialect;
    }

    /** Claims the start; false when this (tenant, definition, business key) already started. */
    public boolean claim(Connection c, String tenantId, String definitionKey, String businessKey, String source, String messageId) {
        return Jdbc.insertIfAbsent(c, dialect,
                "INSERT INTO UNIQUE_START(tenant_id, definition_key, business_key, instance_id, source, message_id, created_at)"
                        + " VALUES (?,?,?,NULL,?,?," + dialect.now() + ")",
                tenantId, definitionKey, businessKey, source, messageId);
    }

    /** Records the started instance id on the claim row (same transaction as the start). */
    public void bind(Connection c, String tenantId, String definitionKey, String businessKey, String instanceId) {
        Jdbc.update(c, "UPDATE UNIQUE_START SET instance_id=? WHERE tenant_id=? AND definition_key=? AND business_key=?",
                instanceId, tenantId, definitionKey, businessKey);
    }

    public Optional<String> existingInstance(Connection c, String tenantId, String definitionKey, String businessKey) {
        return Jdbc.one(c, "SELECT instance_id FROM UNIQUE_START WHERE tenant_id=? AND definition_key=? AND business_key=?",
                rs -> rs.getString(1), tenantId, definitionKey, businessKey);
    }
}

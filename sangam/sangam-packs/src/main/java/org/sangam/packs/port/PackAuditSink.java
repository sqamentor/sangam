// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.port;

/**
 * Receives the business audit events of X.4.0.3 that pack delegates raise. The production implementation (integrator)
 * appends them to the hash-chained audit log in the same transaction as the state change (L5, VI.1); the default
 * writes JSON log lines.
 */
public interface PackAuditSink {

    void record(PackAuditEvent event);

    /**
     * Records {@code event} as part of the transaction on {@code c} (decision 313: the integration result path writes
     * an event in the transaction that settles the operation, outside any engine command, so the event and the
     * settlement commit or roll back together and the event is written exactly once). A sink that writes to a
     * database MUST override this and append on {@code c}. The default ignores the connection and is correct only for
     * a sink with no transaction of its own (the logging default).
     */
    default void record(java.sql.Connection c, PackAuditEvent event) {
        record(event);
    }
}

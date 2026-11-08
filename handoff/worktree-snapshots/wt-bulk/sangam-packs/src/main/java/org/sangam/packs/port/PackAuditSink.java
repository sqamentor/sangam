// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.port;

/**
 * Receives the business audit events of X.4.0.3 that pack delegates raise. The production implementation (integrator)
 * appends them to the hash-chained audit log in the same transaction as the state change (L5, VI.1); the default
 * writes JSON log lines.
 */
public interface PackAuditSink {

    void record(PackAuditEvent event);
}

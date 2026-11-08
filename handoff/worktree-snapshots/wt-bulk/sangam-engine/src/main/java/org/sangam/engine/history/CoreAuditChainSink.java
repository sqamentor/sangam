// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.history;

import java.sql.Connection;
import java.util.Map;

import org.sangam.core.engine.AuditSink;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;

/**
 * Default {@link AuditSink}: the core class-B hash chain ({@link AuditChain}, X.2.3.3-4), one chain per root. The
 * chain append locks the root's {@code ROOT_SEQ} row, which is the L2 lock the command already holds.
 */
public final class CoreAuditChainSink implements AuditSink {

    private final AuditChain chain;

    public CoreAuditChainSink(Dialect coreDialect) {
        this.chain = new AuditChain(coreDialect);
    }

    @Override
    public void append(Connection connection, String tenantId, String rootId, String eventType, Map<String, Object> payload) {
        chain.append(connection, tenantId, rootId, eventType, payload);
    }
}

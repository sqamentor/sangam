// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.assignment;

import org.operaton.bpm.engine.impl.context.Context;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.sangam.security.dialect.SecurityDialect;

import java.sql.Connection;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The JDBC connection of the current Operaton command, so product rows (grants, SoD actors, audit) commit or roll back
 * with the engine state (VI.4 rule 2; same transaction). Used only inside engine listeners.
 */
public final class EngineConnections {

    private static final AtomicReference<SecurityDialect> DIALECT = new AtomicReference<>();

    private EngineConnections() {
    }

    public static Connection current() {
        CommandContext cc = Context.getCommandContext();
        if (cc == null) {
            throw new IllegalStateException("no Operaton command context: call only from engine listeners");
        }
        return cc.getDbSqlSession().getSqlSession().getConnection();
    }

    /** Dialect of the engine database, detected once. */
    public static SecurityDialect dialect(Connection c) {
        SecurityDialect d = DIALECT.get();
        if (d == null) {
            d = SecurityDialect.detect(c);
            DIALECT.compareAndSet(null, d);
        }
        return d;
    }
}

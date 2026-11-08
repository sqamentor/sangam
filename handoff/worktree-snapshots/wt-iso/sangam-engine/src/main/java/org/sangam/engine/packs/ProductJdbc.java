// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.packs;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Function;

import javax.sql.DataSource;

import org.operaton.bpm.engine.impl.context.Context;
import org.sangam.engine.db.EngineDialect;
import org.sangam.engine.tx.CommandState;

/**
 * Runs product SQL for port implementations that get no connection from their caller. Inside an Operaton command (a
 * pack delegate or listener) the work joins the <b>command's own connection and transaction</b> (Q1 guard
 * registered), so ledger postings commit or roll back with the engine state; outside a command it runs in its own
 * transaction on the DataSource.
 */
final class ProductJdbc {

    private final DataSource dataSource;
    private volatile EngineDialect dialect;

    ProductJdbc(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    <T> T run(Function<Connection, T> work) {
        if (Context.hasActiveCommandContext()) {
            Connection c = CommandState.connection(Context.getCommandContext());
            dialect(c);
            return work.apply(c);
        }
        try (Connection c = dataSource.getConnection()) {
            dialect(c);
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                T result = work.apply(c);
                c.commit();
                return result;
            } catch (RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("product JDBC failed", e);
        }
    }

    EngineDialect dialect(Connection c) {
        EngineDialect d = dialect;
        if (d == null) {
            d = EngineDialect.detect(c);
            dialect = d;
        }
        return d;
    }

    EngineDialect dialect() {
        return dialect;
    }
}

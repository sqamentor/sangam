// SPDX-License-Identifier: Apache-2.0
package org.sangam.assembly.host;

import java.util.Objects;

import javax.sql.DataSource;

import org.sangam.core.layer.Transactions;

/**
 * Transaction and connection seam (design note sections 1.3 and 10.1). The host hands over its {@code DataSource} and
 * its transaction manager seen as the core {@link Transactions}. {@link #sharesEngineConnection()} is the host's
 * statement that the engine and the product rows of one command use the same connection and commit once (P-1): the
 * Spring host keeps it through its transaction-aware data source, a JTA host through its transaction manager. A host
 * that cannot keep it says {@code false}; the {@link Capabilities#SHARED_TRANSACTION} capability is then missing and
 * every command writing product rows is refused, never run in two transactions.
 */
public interface ConnectionBinding {

    DataSource dataSource();

    /** The host's transaction manager as the core seam (own short transactions and engine-bound work). */
    Transactions transactions();

    /** True only when the engine and the product rows of one command share one connection and commit once. */
    boolean sharesEngineConnection();

    static ConnectionBinding of(DataSource dataSource, Transactions transactions, boolean sharesEngineConnection) {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(transactions, "transactions");
        return new ConnectionBinding() {
            @Override
            public DataSource dataSource() {
                return dataSource;
            }

            @Override
            public Transactions transactions() {
                return transactions;
            }

            @Override
            public boolean sharesEngineConnection() {
                return sharesEngineConnection;
            }
        };
    }
}

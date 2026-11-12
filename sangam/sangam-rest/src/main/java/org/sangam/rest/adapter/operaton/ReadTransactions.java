// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import java.util.function.Supplier;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One transaction around the engine reads of one request (SG-15). Engine queries outside a transaction each open,
 * use and commit their own; inside this one they join it, so a request with N reads makes one commit instead of N, on
 * one connection.
 *
 * <p>Propagation {@code REQUIRED} with the <b>default</b> isolation and no read-only flag: a non-default isolation
 * makes Spring read and restore {@code getTransactionIsolation()} on every transaction, a hidden round trip on
 * PostgreSQL ({@code TxSupport} uses READ_COMMITTED for that reason, which these reads do not need).
 *
 * <p>Rules for the work inside (both hold for {@link OperatonTaskAdapter}):
 * <ul>
 *   <li>No engine exception is swallowed inside: a failed engine command marks the joined transaction rollback-only,
 *       and a swallowed one would end in {@code UnexpectedRollbackException} instead of the mapped problem. Exceptions
 *       that leave the work roll back and are rethrown unchanged.</li>
 *   <li>No call inside asks for a second connection of the same pool (no {@code REQUIRES_NEW}, no audit write):
 *       that is the SL-43 pool-deadlock pattern.</li>
 * </ul>
 */
public final class ReadTransactions {

    private final TransactionTemplate template;

    /** @param txManager the transaction manager the engine and {@code TxSupport} use */
    public ReadTransactions(PlatformTransactionManager txManager) {
        this.template = new TransactionTemplate(txManager);
    }

    /** Joins the current transaction or starts one around {@code work}. */
    public <T> T inReadTx(Supplier<T> work) {
        return template.execute(status -> work.get());
    }
}

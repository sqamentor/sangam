// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.config;

import java.util.function.Supplier;

import org.sangam.rest.adapter.operaton.ReadTransactions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Spring host's {@link ReadTransactions} (M-141c): propagation REQUIRED with the default isolation and no read-only
 * flag, exactly as before the split (see {@link ReadTransactions} for why).
 */
public final class SpringReadTransactions implements ReadTransactions {

    private final TransactionTemplate template;

    /** @param txManager the transaction manager the engine and {@code TxSupport} use */
    public SpringReadTransactions(PlatformTransactionManager txManager) {
        this.template = new TransactionTemplate(txManager);
    }

    @Override
    public <T> T inReadTx(Supplier<T> work) {
        return template.execute(status -> work.get());
    }
}

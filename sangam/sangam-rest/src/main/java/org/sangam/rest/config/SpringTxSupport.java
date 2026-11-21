// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.config;

import org.sangam.core.api.ReadWork;
import java.sql.Connection;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.sangam.core.layer.Dialect;
import org.sangam.rest.support.TxSupport;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Spring host's {@link TxSupport} (M-141c): Spring-managed transactions on the transaction manager the Operaton
 * Spring Boot starter runs the engine on, so engine commands and product rows share one transaction (P-1). Behaviour
 * as before the split: REQUIRED and REQUIRES_NEW at READ COMMITTED, the connection bound by {@link DataSourceUtils},
 * after-commit work through a {@link TransactionSynchronization}.
 */
public final class SpringTxSupport extends TxSupport {

    private final TransactionTemplate required;
    private final TransactionTemplate requiresNew;

    public SpringTxSupport(DataSource dataSource, PlatformTransactionManager txManager, Dialect dialect) {
        super(dataSource, dialect);
        this.required = new TransactionTemplate(txManager);
        this.required.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.requiresNew = new TransactionTemplate(txManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.requiresNew.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public <T> T inTxWithStatus(Function<TxStatus, T> work) {
        return required.execute(status -> work.apply(view(status)));
    }

    @Override
    public <T> T inNewTx(Supplier<T> work) {
        return requiresNew.execute(status -> work.get());
    }

    @Override
    public <T> T withReadConnection(ReadWork<T> work) {
        return required.execute(status -> work.read(connection()));
    }

    @Override
    public <T> T inReadTx(org.sangam.core.api.ReadSupplier<T> work) {
        return required.execute(status -> work.read());
    }

    @Override
    public <T> T inNewReadTx(ReadWork<T> work) {
        return requiresNew.execute(status -> work.read(connection()));
    }

    @Override
    public Connection connection() {
        return DataSourceUtils.getConnection(dataSource());
    }

    @Override
    public void afterCommit(Runnable work) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    work.run();
                }
            });
        } else {
            work.run();
        }
    }

    private static TxStatus view(TransactionStatus status) {
        return new TxStatus() {
            @Override
            public void setRollbackOnly() {
                status.setRollbackOnly();
            }

            @Override
            public boolean isRollbackOnly() {
                return status.isRollbackOnly();
            }
        };
    }
}

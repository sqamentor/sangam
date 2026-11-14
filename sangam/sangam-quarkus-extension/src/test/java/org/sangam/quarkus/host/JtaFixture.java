// SPDX-License-Identifier: Apache-2.0
package org.sangam.quarkus.host;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.UUID;

import jakarta.transaction.TransactionManager;

import com.arjuna.ats.arjuna.common.ObjectStoreEnvironmentBean;
import com.arjuna.ats.internal.jta.transaction.arjunacore.TransactionSynchronizationRegistryImple;
import com.arjuna.common.internal.util.propertyservice.BeanPopulator;

import io.agroal.api.AgroalDataSource;
import io.agroal.api.configuration.supplier.AgroalDataSourceConfigurationSupplier;
import io.agroal.api.security.NamePrincipal;
import io.agroal.api.security.SimplePassword;
import io.agroal.narayana.NarayanaTransactionIntegration;

/**
 * The JTA pieces the Quarkus host is given, without booting Quarkus: Narayana's transaction manager and an Agroal pool
 * integrated with it (what quarkus-narayana-jta and quarkus-agroal wire), on a private in-memory H2 database.
 */
final class JtaFixture implements AutoCloseable {

    final TransactionManager transactionManager;
    final AgroalDataSource dataSource;

    private JtaFixture(TransactionManager transactionManager, AgroalDataSource dataSource) {
        this.transactionManager = transactionManager;
        this.dataSource = dataSource;
    }

    /** {@code objectStore}: a temporary folder for Narayana's object store. */
    static JtaFixture start(Path objectStore) throws SQLException {
        BeanPopulator.getDefaultInstance(ObjectStoreEnvironmentBean.class).setObjectStoreDir(objectStore.toString());
        TransactionManager tm = com.arjuna.ats.jta.TransactionManager.transactionManager();
        // MODE=PostgreSQL: the only H2 mode Sangam's database guard accepts
        String url = "jdbc:h2:mem:quarkus-host-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000;MODE=PostgreSQL";
        AgroalDataSourceConfigurationSupplier config = new AgroalDataSourceConfigurationSupplier()
                .connectionPoolConfiguration(pool -> pool
                        .maxSize(8)
                        .transactionIntegration(new NarayanaTransactionIntegration(tm,
                                new TransactionSynchronizationRegistryImple()))
                        .connectionFactoryConfiguration(factory -> factory
                                .jdbcUrl(url)
                                .principal(new NamePrincipal("sa"))
                                .credential(new SimplePassword(""))));
        return new JtaFixture(tm, AgroalDataSource.from(config));
    }

    @Override
    public void close() {
        dataSource.close();
    }
}

// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;

import org.sangam.core.layer.Transactions;
import org.sangam.rest.adapter.jdbc.DenialAudit;
import org.sangam.rest.support.TxSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * SL-132 (the SL-137 design): the leaf pool {@value #POOL_NAME} for security denial audits, on whether or not the
 * sangam-security module is on, because the REST task adapter records {@code SOD_VIOLATION_BLOCKED} through it in
 * either case.
 *
 * <p>A denial is recorded while the caller holds a main-pool connection and must commit on its own, because the
 * refusal rolls the caller back. A second main-pool connection there is the SL-43 pool deadlock; a connection of this
 * pool is never held while another is requested (its only work is one append on a denial-only chain), so waits for
 * it cannot form a cycle. Same database and settings as the main pool (derived as {@link ComplianceWiring#leafPool}),
 * {@code sangam.security.denial-audit.pool.maximum-size} (default 2) connections, on top of the main pool.
 *
 * <ul>
 *   <li>sangam-security's {@code SangamSecurity.Options.denialTx} ({@code AUTH_DENIED}, {@code POWER_LIMIT_EXCEEDED},
 *       ...) uses it ({@link SecurityWiring}).</li>
 *   <li>The REST {@link DenialAudit} of {@code OperatonTaskAdapter.checkSod} uses it
 *       ({@code SangamRestAutoConfiguration.sangamTaskPort}).</li>
 * </ul>
 * A main pool that is not HikariCP (an embedding host's own pool) gets no leaf pool: the denials then commit in a new
 * transaction of the main pool ({@link DenialAudit#mainPool}, the behaviour before SL-132), logged at start-up.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityAuditPoolWiring {

    private static final Logger LOG = LoggerFactory.getLogger(SecurityAuditPoolWiring.class);

    public static final String POOL_NAME = "sangam-security-audit";
    static final String SIZE_PROPERTY = "sangam.security.denial-audit.pool.maximum-size";

    /** The leaf pool, or none when the main pool is not HikariCP; closes the pool at shutdown. */
    public static final class SecurityAuditPool implements DisposableBean {
        private final HikariDataSource poolOrNull;

        SecurityAuditPool(HikariDataSource poolOrNull) {
            this.poolOrNull = poolOrNull;
        }

        /** Transactions on the leaf pool, or null without one. */
        public Transactions transactionsOrNull() {
            return poolOrNull == null ? null : Transactions.of(poolOrNull);
        }

        HikariDataSource poolOrNull() {
            return poolOrNull;
        }

        @Override
        public void destroy() {
            if (poolOrNull != null) {
                poolOrNull.close();
            }
        }
    }

    @Bean
    public SecurityAuditPool sangamSecurityAuditPool(DataSource ds, Environment env) {
        return pool(ds, env.getProperty(SIZE_PROPERTY, Integer.class, 2));
    }

    static SecurityAuditPool pool(DataSource ds, int maximumSize) {
        if (!ComplianceWiring.isHikari(ds)) {
            LOG.warn("SL-132: the main DataSource ({}) is not HikariCP, so security denial audits have no leaf pool and"
                    + " commit in a new main-pool transaction", ds.getClass().getName());
            return new SecurityAuditPool(null);
        }
        return new SecurityAuditPool(ComplianceWiring.leafPool(ds, maximumSize, POOL_NAME));
    }

    /** The REST adapters' denial writer on the leaf pool (falls back to {@link DenialAudit#mainPool} without one). */
    @Bean
    public DenialAudit sangamRestDenialAudit(SecurityAuditPool sangamSecurityAuditPool, TxSupport tx) {
        Transactions leaf = sangamSecurityAuditPool.transactionsOrNull();
        return leaf == null ? DenialAudit.mainPool(tx) : new DenialAudit(tx.dialect(), leaf);
    }
}

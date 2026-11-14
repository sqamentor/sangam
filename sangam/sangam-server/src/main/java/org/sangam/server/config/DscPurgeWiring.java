// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.time.Clock;
import java.time.Duration;

import javax.sql.DataSource;

import org.sangam.core.layer.Transactions;
import org.sangam.integration.dsc.JdbcDscPreparedStore;
import org.sangam.integration.relay.LeaderElection;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;
import org.sangam.server.bridge.DscPurgeScheduler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Decision 165 (W4-H REQUEST 01:24:24Z, item 17): the leader-only purge of expired DSC prepared state. Off unless
 * {@code sangam.dsc.enabled=true} (the same switch as DscSigningWiring); {@code sangam.dsc.purge-interval} (default 15
 * minutes) sets how often the leader purges entries expired before now. It works on the same table as the signing flow
 * through its own {@link JdbcDscPreparedStore} (stateless JDBC).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sangam.dsc.enabled", havingValue = "true")
public class DscPurgeWiring {

    static final String INTERVAL = "sangam.dsc.purge-interval";

    @Bean
    public DscPurgeScheduler sangamDscPurgeScheduler(TxSupport tx, DataSource ds, RestProperties rest, Environment env) {
        LeaderElection leader = new LeaderElection(tx.dialect(), Transactions.of(ds), rest.getNode(), Duration.ofMinutes(3));
        JdbcDscPreparedStore store = new JdbcDscPreparedStore(Transactions.of(ds));
        return new DscPurgeScheduler(store::purgeExpired, DscPurgeScheduler.Leader.of(leader),
                env.getProperty(INTERVAL, Duration.class, Duration.ofMinutes(15)), Clock.systemUTC());
    }
}

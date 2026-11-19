// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.util.List;

import org.sangam.core.layer.AuditChain;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.rest.support.TxSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Owner decision 37 (Q-04): the start is refused by name when one key serves two purposes
 * ({@link KeyPurposeSeparation}) or two environments ({@link KeyEnvironmentSeparation}, owner decision 385 (3)). Always
 * on, in every environment: a shared key is refused also outside production, and production refuses to start without
 * the other environments' fingerprint list or the key custodian's attestation (decisions 387 (4) and 390 (4)). The
 * one-time bootstrap of the first environment is recorded, and a second one refused, once the database is ready
 * ({@link KeyEnvironmentBootstrap}).
 */
@Configuration(proxyBeanMethods = false)
public class KeyPurposeSeparationWiring {

    private static final Logger LOG = LoggerFactory.getLogger(KeyPurposeSeparationWiring.class);

    /**
     * The outcome of the start-up check: the number of key entries compared, this environment's name (or null), the
     * number of its own fingerprints, the number of other environments' fingerprints compared, the number of key
     * settings the custodian attested, and whether this start is the one-time bootstrap.
     */
    public record Checked(int keys, String environment, int ownFingerprints, int foreignFingerprints, int attested,
                          boolean bootstrap) {
    }

    @Bean
    public Checked sangamKeyPurposeSeparation(Environment env, ObjectProvider<SecretResolver> secrets) {
        List<KeyPurposeSeparation.Entry> entries = KeyPurposeSeparation.entries(env,
                secrets.getIfAvailable(SecretResolver::fromEnvironment));
        int n = KeyPurposeSeparation.check(entries);
        KeyEnvironmentSeparation.Result r = KeyEnvironmentSeparation.check(env, entries,
                ProductionEnvironment.isProduction(env));
        LOG.info("Owner decisions 37 and 385 (3): {} configured key(s) checked, none serves two purposes; environment {}:"
                + " {} own fingerprint(s), {} of other environments compared, none shared; {} key setting(s) attested by"
                + " the custodian{}", n, r.environment() == null ? "(unnamed)" : r.environment(), r.own(), r.foreign(),
                r.attested(), r.bootstrap() ? "; one-time bootstrap (recorded once the database is ready)" : "");
        return new Checked(n, r.environment(), r.own(), r.foreign(), r.attested(), r.bootstrap());
    }

    /**
     * Decisions 387 (4) (a) and 390 (4): after the database is ready, a bootstrap start is recorded as a system audit
     * event, and refused when this environment bootstrapped before. Without the database a bootstrap is refused (fail
     * closed: it could not be recorded).
     */
    @Bean
    public SmartLifecycle sangamKeyEnvironmentBootstrap(Checked checked, ObjectProvider<TxSupport> txs) {
        return new BootstrapRecorder(checked, txs);
    }

    /**
     * Runs in the first lifecycle phase, before the web server starts (its lifecycle has a later phase) and before
     * any scheduler: a refused second bootstrap fails the refresh with no port open and nothing served. An
     * ApplicationRunner would run only after the server already serves.
     */
    static final class BootstrapRecorder implements SmartLifecycle {

        private final Checked checked;
        private final ObjectProvider<TxSupport> txs;
        private volatile boolean running;

        BootstrapRecorder(Checked checked, ObjectProvider<TxSupport> txs) {
            this.checked = checked;
            this.txs = txs;
        }

        @Override
        public void start() {
            record(checked, txs.getIfAvailable());
            running = true;
        }

        @Override
        public void stop() {
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public int getPhase() {
            return Integer.MIN_VALUE;
        }
    }

    static void record(Checked checked, TxSupport tx) {
        if (!checked.bootstrap()) {
            return;
        }
        if (tx == null) {
            throw new IllegalStateException(KeyEnvironmentSeparation.LIST_REQUIRED + ": "
                    + KeyEnvironmentSeparation.BOOTSTRAP + " needs the database, where the one-time bootstrap is recorded");
        }
        tx.withConnection(c -> {
            KeyEnvironmentBootstrap.record(c, new AuditChain(tx.dialect()), checked.environment(),
                    checked.ownFingerprints());
            return null;
        });
        LOG.warn("Owner decisions 387 (4) and 390: environment {} started with the one-time key bootstrap ({}), recorded"
                + " as {}; the next start needs {} or {}", checked.environment(), KeyEnvironmentSeparation.BOOTSTRAP,
                KeyEnvironmentBootstrap.EVENT, KeyEnvironmentSeparation.FOREIGN, KeyEnvironmentSeparation.ATTESTATION);
    }
}

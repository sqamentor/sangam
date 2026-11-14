// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.sangam.core.layer.AuditCheckpoints;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.rest.support.TxSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * M-073 (SL-152): the signed Merkle checkpoint scheduler over the audit chains ({@link AuditCheckpoints}).
 *
 * <p>On only when {@code sangam.audit.checkpoint.private-key-ref} is set (owner Q-04: the custodian is named later, so
 * the shipped configuration carries no key). Both keys are secret references resolved through the
 * {@link SecretResolver} ({@code secret://...}); a literal key in the configuration is refused at start-up. A key of its
 * own per purpose and environment: {@code private-key-ref} (base64 PKCS#8 Ed25519) and {@code public-key-ref} (base64
 * X.509).
 *
 * <p>Every minute ({@code scheduleWithFixedDelay}) each node runs {@link AuditCheckpoints#runDue()}: a tenant gets a
 * checkpoint when {@code interval} (1h) has passed or {@code max-entries} (10000) events were written since the last
 * one; two nodes racing for the same number write it once. With {@code export-dir} set, every checkpoint not yet
 * exported is then written there ({@link AuditCheckpoints#directory}); point it at storage under another
 * administrative account. {@code overlap} (1h) must exceed the longest transaction. A failed pass is logged and the
 * next pass retries.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sangam.audit.checkpoint.private-key-ref")
public class AuditCheckpointWiring {

    private static final Logger LOG = LoggerFactory.getLogger(AuditCheckpointWiring.class);
    static final String PREFIX = "sangam.audit.checkpoint.";
    static final Duration PERIOD = Duration.ofMinutes(1);

    /** Checkpoint settings from {@code sangam.audit.checkpoint.*}; defaults Q-04 (1h, 10000, overlap 1h). */
    static AuditCheckpoints.Settings settings(Environment env) {
        AuditCheckpoints.Settings d = AuditCheckpoints.Settings.defaults();
        return new AuditCheckpoints.Settings(env.getProperty(PREFIX + "interval", Duration.class, d.interval()),
                env.getProperty(PREFIX + "max-entries", Long.class, d.maxEntries()),
                env.getProperty(PREFIX + "overlap", Duration.class, d.overlap()));
    }

    /**
     * Decision 126 part 1 (C-11, B-05): signed audit checkpoints are required for every tenant in production. This
     * wiring is on only with {@code private-key-ref}, and once on its scheduler checkpoints every tenant that has audit
     * events ({@link AuditCheckpoints#runDue()}), so production without that key is refused. No waiver exists. Called
     * from an always-on bean ({@code SecurityConfig}), because this configuration is absent exactly when the key is.
     */
    static void requireInProduction(Environment env) {
        if (!ProductionEnvironment.isProduction(env)) {
            return;
        }
        String ref = env.getProperty(PREFIX + "private-key-ref");
        if (ref == null || ref.isBlank()) {
            throw new IllegalStateException("signed audit checkpoints are required for every tenant in production"
                    + " (decision 126): set " + PREFIX + "private-key-ref and " + PREFIX + "public-key-ref"
                    + " (secret references, secret://...)");
        }
    }

    /** The value behind a key reference; refuses a literal (keys are never configuration values). */
    static String key(SecretResolver secrets, Environment env, String property) {
        String ref = env.getProperty(PREFIX + property);
        if (ref == null || ref.isBlank()) {
            throw new IllegalStateException(PREFIX + property + " is required with " + PREFIX + "private-key-ref");
        }
        if (!ref.startsWith("secret://")) {
            throw new IllegalStateException(PREFIX + property + " must be a secret reference (secret://...), never the key");
        }
        return secrets.require(ref);
    }

    @Bean
    public AuditCheckpoints sangamAuditCheckpoints(DataSource ds, TxSupport tx, ObjectProvider<SecretResolver> secrets,
                                                   Environment env) {
        SecretResolver resolver = secrets.getIfAvailable(SecretResolver::fromEnvironment);
        AuditCheckpoints.Signer signer = AuditCheckpoints.ed25519(key(resolver, env, "private-key-ref"),
                key(resolver, env, "public-key-ref"));
        LOG.info("M-073 audit checkpoints on: key {}", signer.keyId());
        return new AuditCheckpoints(Transactions.of(ds), tx.dialect(), signer, settings(env));
    }

    @Bean(destroyMethod = "close")
    public Scheduler sangamAuditCheckpointScheduler(AuditCheckpoints sangamAuditCheckpoints, Environment env) {
        String dir = env.getProperty(PREFIX + "export-dir");
        AuditCheckpoints.Exporter exporter = dir == null || dir.isBlank() ? null : AuditCheckpoints.directory(Path.of(dir));
        return new Scheduler(sangamAuditCheckpoints, exporter).start(PERIOD);
    }

    /** One daemon thread: a checkpoint pass, then the export of pending checkpoints, every {@link #PERIOD}. */
    public static final class Scheduler implements AutoCloseable {
        private final AuditCheckpoints checkpoints;
        private final AuditCheckpoints.Exporter exporterOrNull;
        private ScheduledExecutorService executor;

        Scheduler(AuditCheckpoints checkpoints, AuditCheckpoints.Exporter exporterOrNull) {
            this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
            this.exporterOrNull = exporterOrNull;
        }

        Scheduler start(Duration period) {
            executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sangam-audit-checkpoint");
                t.setDaemon(true);
                return t;
            });
            executor.scheduleWithFixedDelay(this::runOnce, period.toMillis(), period.toMillis(), TimeUnit.MILLISECONDS);
            return this;
        }

        /** One pass; never throws (the next pass retries). Returns the number of checkpoints written. */
        int runOnce() {
            try {
                List<AuditCheckpoints.Checkpoint> written = checkpoints.runDue();
                if (!written.isEmpty()) {
                    LOG.info("audit checkpoints written: {}", written.size());
                }
                if (exporterOrNull != null) {
                    for (String tenant : checkpoints.tenants()) {
                        checkpoints.exportPending(tenant, exporterOrNull);
                    }
                }
                return written.size();
            } catch (RuntimeException e) {
                LOG.warn("audit checkpoint pass failed; retried on the next pass: {}", e.toString());
                return 0;
            }
        }

        @Override
        public void close() {
            if (executor != null) {
                executor.shutdownNow();
            }
        }
    }
}

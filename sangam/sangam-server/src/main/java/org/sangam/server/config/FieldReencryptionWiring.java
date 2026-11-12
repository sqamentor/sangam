// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import javax.sql.DataSource;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.sangam.core.layer.Transactions;
import org.sangam.core.security.FieldEncryptor;
import org.sangam.rest.port.AdminPort;
import org.sangam.security.SangamSecurity;
import org.sangam.security.crypto.AesGcmFieldEncryptor;
import org.sangam.security.crypto.FieldReencryptionJob;
import org.sangam.security.crypto.ReencryptionSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * M-085: the scheduled, resumable, tenant-scoped field re-encryption after a key rotation
 * ({@link FieldReencryptionJob}). Off unless {@code sangam.security.reencryption.enabled=true}.
 *
 * <p>Every {@code period} (15 min) one pass runs, for every tenant (the tenant directory plus
 * {@code sangam.security.reencryption.tenants}, {@link ScheduleTenants}) and every {@link ReencryptionSource} bean. Each
 * source gets at most {@code max-batches} batches of {@code batch-size} rows per pass. A source that is already done for
 * the tenant's active key costs one progress-row read. Each batch is its own transaction and moves the cursor with the
 * rows, so a stopped node resumes where it was; nodes serialise on the progress row. With {@code dry-run=true} a pass
 * only counts the rows not under the active key.
 *
 * <p>The job needs the tenant-key encryptor ({@link AesGcmFieldEncryptor}). With any other {@link FieldEncryptor}, or no
 * source beans, the scheduler logs why and does nothing. Idempotency responses are not swept (owner decision 30): they
 * expire, and no {@link ReencryptionSource} exists for them.
 *
 * <p>Metric: counter {@value #METRIC} with tags {@code source} and {@code outcome} (rewritten, skipped, failed, pending).
 * A failure in one tenant or source is logged and the pass continues; the next pass retries.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sangam.security.reencryption.enabled", havingValue = "true")
public class FieldReencryptionWiring {

    private static final Logger LOG = LoggerFactory.getLogger(FieldReencryptionWiring.class);
    static final String PREFIX = "sangam.security.reencryption.";
    static final String METRIC = "sangam.field.reencrypt.rows";

    /** Settings from {@code sangam.security.reencryption.*}. */
    record Settings(Duration period, int batchSize, int maxBatches, boolean dryRun) {
        Settings {
            if (period.isNegative() || period.isZero()) {
                throw new IllegalStateException(PREFIX + "period must be positive");
            }
            if (batchSize < 1 || batchSize > 10_000) {
                throw new IllegalStateException(PREFIX + "batch-size must be 1..10000");
            }
            if (maxBatches < 1) {
                throw new IllegalStateException(PREFIX + "max-batches must be at least 1");
            }
        }
    }

    static Settings settings(Environment env) {
        return new Settings(env.getProperty(PREFIX + "period", Duration.class, Duration.ofMinutes(15)),
                env.getProperty(PREFIX + "batch-size", Integer.class, FieldReencryptionJob.DEFAULT_BATCH),
                env.getProperty(PREFIX + "max-batches", Integer.class, 50),
                env.getProperty(PREFIX + "dry-run", Boolean.class, false));
    }

    /** The micrometer counter per source and outcome; no registry, no metric. */
    static FieldReencryptionJob.Metrics metrics(MeterRegistry meters) {
        if (meters == null) {
            return FieldReencryptionJob.Metrics.NONE;
        }
        return (tenant, source, outcome, rows) -> Counter.builder(METRIC)
                .description("Field re-encryption rows by source and outcome (M-085)")
                .tag("source", source).tag("outcome", outcome)
                .register(meters).increment(rows);
    }

    @Bean(destroyMethod = "close")
    public Scheduler sangamFieldReencryptionScheduler(SangamSecurity security, ObjectProvider<FieldEncryptor> encryptor,
                                                      ObjectProvider<ReencryptionSource> sources, DataSource dataSource,
                                                      ObjectProvider<AdminPort> admin, ObjectProvider<MeterRegistry> meters,
                                                      Environment env) {
        Settings s = settings(env);
        List<ReencryptionSource> list = sources.orderedStream().toList();
        FieldEncryptor fe = encryptor.getIfAvailable();
        if (!(fe instanceof AesGcmFieldEncryptor aes)) {
            LOG.warn("M-085 field re-encryption is enabled but the field encryptor is not the tenant-key encryptor ({});"
                    + " nothing to re-encrypt", fe == null ? "none" : fe.getClass().getSimpleName());
            return new Scheduler(null, List.of(), List::of, s);
        }
        if (list.isEmpty()) {
            LOG.warn("M-085 field re-encryption is enabled but no ReencryptionSource bean exists; nothing to re-encrypt");
        }
        FieldReencryptionJob job = new FieldReencryptionJob(security.dialect(), Transactions.of(dataSource), aes,
                security.audit(), metrics(meters.getIfAvailable()), s.batchSize());
        ScheduleTenantsMonitor monitor = ScheduleTenantsMonitor.of("field-reencryption",
                "field re-encryption after a key rotation", meters);
        Supplier<List<String>> tenants = monitor.observing(
                () -> ScheduleTenants.resolve(env, admin, PREFIX + "tenants", FieldReencryptionJob.ACTOR, monitor));
        LOG.info("M-085 field re-encryption on: {} source(s) {}, every {}, batch {} x {}{}", list.size(),
                list.stream().map(ReencryptionSource::name).toList(), s.period(), s.batchSize(), s.maxBatches(),
                s.dryRun() ? " (dry run)" : "");
        return new Scheduler(job, list, tenants, s).start();
    }

    /** One daemon thread: a pass over every tenant and source, every {@code period}. */
    public static final class Scheduler implements AutoCloseable {
        private final FieldReencryptionJob jobOrNull;
        private final List<ReencryptionSource> sources;
        private final Supplier<List<String>> tenants;
        private final Settings settings;
        private ScheduledExecutorService executor;

        Scheduler(FieldReencryptionJob jobOrNull, List<ReencryptionSource> sources, Supplier<List<String>> tenants,
                  Settings settings) {
            this.jobOrNull = jobOrNull;
            this.sources = List.copyOf(sources);
            this.tenants = Objects.requireNonNull(tenants, "tenants");
            this.settings = Objects.requireNonNull(settings, "settings");
        }

        Scheduler start() {
            if (jobOrNull == null || sources.isEmpty()) {
                return this;
            }
            executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sangam-field-reencryption");
                t.setDaemon(true);
                return t;
            });
            long ms = settings.period().toMillis();
            executor.scheduleWithFixedDelay(this::runOnce, ms, ms, TimeUnit.MILLISECONDS);
            return this;
        }

        /** One pass; never throws. Returns the results of the (tenant, source) pairs that ran. */
        List<FieldReencryptionJob.Result> runOnce() {
            if (jobOrNull == null || sources.isEmpty()) {
                return List.of();
            }
            List<String> ts;
            try {
                ts = tenants.get();
            } catch (RuntimeException e) {
                LOG.warn("field re-encryption: tenant list unavailable; retried on the next pass: {}", e.toString());
                return List.of();
            }
            java.util.ArrayList<FieldReencryptionJob.Result> out = new java.util.ArrayList<>();
            for (String tenant : ts) {
                for (ReencryptionSource source : sources) {
                    try {
                        FieldReencryptionJob.Result r = settings.dryRun() ? jobOrNull.dryRun(tenant, source)
                                : jobOrNull.run(tenant, source, settings.maxBatches());
                        out.add(r);
                        if (r.rewritten() > 0 || r.failed() > 0 || r.pending() > 0) {
                            LOG.info("field re-encryption {} {} to {}: scanned {}, rewritten {}, failed {}, pending {}{}",
                                    tenant, source.name(), r.targetKeyId(), r.scanned(), r.rewritten(), r.failed(),
                                    r.pending(), r.done() ? ", done" : "");
                        }
                    } catch (RuntimeException e) {
                        LOG.warn("field re-encryption {} {} failed; retried on the next pass: {}", tenant, source.name(),
                                e.toString());
                    }
                }
            }
            return out;
        }

        boolean running() {
            return executor != null;
        }

        @Override
        public void close() {
            if (executor != null) {
                executor.shutdownNow();
            }
        }
    }
}

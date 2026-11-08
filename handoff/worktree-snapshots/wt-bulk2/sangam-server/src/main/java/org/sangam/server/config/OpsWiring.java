// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.operaton.bpm.engine.ProcessEngine;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Transactions;
import org.sangam.core.security.FieldEncryptor;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.integration.relay.LeaderElection;
import org.sangam.ops.OpsSchema;
import org.sangam.ops.listener.GlobalTaskListeners;
import org.sangam.ops.metrics.UsageMetrics;
import org.sangam.ops.retention.HierarchyRetention;
import org.sangam.ops.search.GlobalSearch;
import org.sangam.ops.search.SearchIndexMaintenance;
import org.sangam.ops.secrets.SecretAdmin;
import org.sangam.ops.sequence.SequenceAdmin;
import org.sangam.ops.vars.ScopedVariables;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.modules.ops.OpsApi;
import org.sangam.rest.port.AdminPort;
import org.sangam.rest.support.TxSupport;
import org.sangam.server.bridge.CaseFieldScheduler;
import org.sangam.server.bridge.OpsBindings;
import org.sangam.server.bridge.OpsTaskListenerPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Wires sangam-ops; off unless {@code sangam.modules.ops.enabled=true}. Leader-elected jobs: daily usage rollup, index
 * drift check, retention (planned and logged; purging needs {@code sangam.ops.retention.auto-execute=true}). Retention
 * ({@code sangam.ops.retention-json}: key to ISO duration) never touches definitions with an Operaton historyTimeToLive.
 * Task listener rules ({@code sangam.ops.task-listeners-json}) name {@code GlobalTaskListeners.Handler} beans.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sangam.modules.ops.enabled", havingValue = "true")
public class OpsWiring {

    private static final Logger LOG = LoggerFactory.getLogger(OpsWiring.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Static and name-resolved at call time: this plugin is created before the engine, handler beans may need the engine. */
    @Bean
    public static OpsTaskListenerPlugin sangamOpsTaskListeners(Environment env, BeanFactory beans) {
        List<GlobalTaskListeners.Rule> rules = new java.util.ArrayList<>();
        Map<String, GlobalTaskListeners.Handler> handlers = new LinkedHashMap<>();
        for (Map<String, Object> m : parse(env.getProperty("sangam.ops.task-listeners-json"), new TypeReference<List<Map<String, Object>>>() {
        }, List.of())) {
            String handler = String.valueOf(m.get("handler"));
            List<String> events = m.get("events") instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
            rules.add(new GlobalTaskListeners.Rule(String.valueOf(m.get("id")), events,
                    m.get("processKey") == null ? null : Pattern.compile(String.valueOf(m.get("processKey"))), handler,
                    Boolean.TRUE.equals(m.get("failTask"))));
            handlers.put(handler, (task, ruleId) -> beans.getBean(handler, GlobalTaskListeners.Handler.class).onTask(task, ruleId));
        }
        return new OpsTaskListenerPlugin(new GlobalTaskListeners(rules, handlers));
    }

    /** Engine expressions: {@code ${sangamVars.get(execution.tenantId, 'name')}}. */
    @Bean("sangamVars")
    public ScopedVariables sangamVars(TxSupport tx, DataSource ds, Environment env) {
        Dialect d = tx.dialect();
        CaseWiring.devSchema(env, tx, "OPS_VARIABLE", c -> {
            try {
                OpsSchema.create(c, d);
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException("cannot create the sangam-ops schema", e);
            }
        });
        return new ScopedVariables(d, Transactions.of(ds), new AuditChain(d), env.getProperty("sangam.ops.vars.cache-millis", Long.class, 30_000L));
    }

    @Bean
    public SecretAdmin sangamSecretAdmin(TxSupport tx, DataSource ds, FieldEncryptor encryptor) {
        return new SecretAdmin(tx.dialect(), Transactions.of(ds), new AuditChain(tx.dialect()), encryptor);
    }

    /** Tenant-scoped resolver over the encrypted secret store; values only ever leave through this. */
    @Bean
    public Function<String, SecretResolver> sangamTenantSecrets(SecretAdmin secrets) {
        return secrets::resolver;
    }

    /**
     * A-13: every search source uses the sangam-security compiler (deny wins at every scope). Without the security module there
     * are no sources, so search and outlier reads return nothing (fail closed).
     */
    private static List<GlobalSearch.Source> searchSources(org.sangam.security.SangamSecurity sec) {
        if (sec == null) {
            LOG.warn("sangam-ops: no security module, global search returns nothing");
            return List.of();
        }
        return GlobalSearch.engineSources((type, alias) -> (c, who) -> sec.compiler().visible(c, who, type, org.sangam.security.policy.Permission.READ, alias));
    }

    @Bean
    public OpsApi sangamOpsApi(ScopedVariables vars, SecretAdmin secrets, TxSupport tx, DataSource ds, ProcessEngine engine, Environment env,
            ObjectProvider<org.sangam.security.SangamSecurity> security) {
        Dialect d = tx.dialect();
        Transactions t = Transactions.of(ds);
        return new OpsApi(new GlobalSearch(t, searchSources(security.getIfAvailable())), usage(d, t), vars, secrets,
                new SequenceAdmin(d, t, new AuditChain(d), List.of(new SequenceAdmin.Table("RES_BOOKING_SEQ", "tenant_id", "series", "period", "last_no"))),
                new SearchIndexMaintenance(d, t), OpsBindings.engineReaders(t),
                OpsBindings.retention(new HierarchyRetention(t, new AuditChain(d)), engine, retention(env)),
                () -> OpsBindings.migrationStatus(t, new org.sangam.ops.migrate.LegacyHistoryMigrator(null, t, d, new AuditChain(d), null)), t,
                java.util.Set.copyOf(CaseWiring.list(env, "sangam.ops.outliers.allowed-variables")));
    }

    @Bean
    public CaseFieldScheduler sangamOpsJobs(OpsApi api, TxSupport tx, DataSource ds, RestProperties rest, Environment env, ObjectProvider<AdminPort> admin) {
        Transactions t = Transactions.of(ds);
        boolean purge = env.getProperty("sangam.ops.retention.auto-execute", Boolean.class, false);
        int limit = env.getProperty("sangam.ops.retention.batch", Integer.class, 500);
        return new CaseFieldScheduler("sangam-ops-jobs", new LeaderElection(tx.dialect(), t, rest.getNode(), Duration.ofMinutes(3)),
                () -> CaseWiring.tenants(env, admin, "sangam.ops.tenants"),
                List.of(new CaseFieldScheduler.Task("usage-rollup", Duration.ofHours(1), false,
                                x -> api.usage().rollup(LocalDate.now(ZoneOffset.UTC).minusDays(1))),
                        new CaseFieldScheduler.Task("index-drift", Duration.ofHours(1), true, tenant -> api.index().drift(tenant, api.readers()).stream()
                                .filter(SearchIndexMaintenance.Drift::drifted).forEach(x -> LOG.warn("search index drift in {}: {}", tenant, x))),
                        new CaseFieldScheduler.Task("retention", Duration.ofHours(24), true, tenant -> {
                            if (purge) {
                                LOG.info("retention purged {} trees in {}", api.retention().execute(tenant, limit, "system:retention", "scheduled retention (sangam.ops.retention.auto-execute)"), tenant);
                            } else {
                                LOG.info("retention plan for {}: {} trees due", tenant, api.retention().plan(tenant, limit).size());
                            }
                        })),
                Duration.ofMinutes(1));
    }

    /**
     * SL-44 bulk ingest. Sinks: the built-in tenant-variable sink plus every {@link org.sangam.ops.bulk.BulkIngest.Sink}
     * bean. Quota: the SL-43 {@code TenantQuotas} (batch size per chunk). Limits: {@code sangam.ops.bulk.*}.
     */
    @Bean
    public org.sangam.ops.bulk.BulkIngest sangamBulkIngest(TxSupport tx, DataSource ds, ScopedVariables vars, RestProperties rest, Environment env,
            ObjectProvider<org.sangam.ops.bulk.BulkIngest.Sink> sinks) {
        Dialect d = tx.dialect();
        org.sangam.ops.bulk.BulkIngest.Limits def = org.sangam.ops.bulk.BulkIngest.Limits.DEFAULTS;
        org.sangam.ops.bulk.BulkIngest.Limits limits = new org.sangam.ops.bulk.BulkIngest.Limits(
                env.getProperty("sangam.ops.bulk.max-rows-per-chunk", Integer.class, def.maxRowsPerChunk()),
                env.getProperty("sangam.ops.bulk.max-chunk-bytes", Integer.class, def.maxChunkBytes()),
                env.getProperty("sangam.ops.bulk.max-chunks-per-job", Integer.class, def.maxChunksPerJob()),
                env.getProperty("sangam.ops.bulk.max-rows-per-job", Long.class, def.maxRowsPerJob()),
                env.getProperty("sangam.ops.bulk.max-active-jobs-per-tenant", Integer.class, def.maxActiveJobsPerTenant()),
                env.getProperty("sangam.ops.bulk.max-attempts", Integer.class, def.maxAttempts()));
        org.sangam.ops.bulk.BulkIngest ingest = new org.sangam.ops.bulk.BulkIngest(d, Transactions.of(ds), new AuditChain(d), limits, rest.getNode());
        ingest.register(org.sangam.ops.bulk.BulkSinks.variables(vars, org.sangam.rest.modules.ops.OpsController.ADMIN));
        sinks.orderedStream().forEach(ingest::register);
        return ingest;
    }

    /** Applies bulk rows on every node (SKIP LOCKED claims); resumes this node's rows at start. */
    @Bean(initMethod = "start", destroyMethod = "stop")
    public org.sangam.ops.bulk.BulkIngestWorker sangamBulkIngestWorker(org.sangam.ops.bulk.BulkIngest ingest, Environment env) {
        return new org.sangam.ops.bulk.BulkIngestWorker(ingest,
                env.getProperty("sangam.ops.bulk.worker.batch", Integer.class, 100),
                env.getProperty("sangam.ops.bulk.worker.poll-millis", Long.class, 2_000L),
                env.getProperty("sangam.ops.bulk.worker.stale-millis", Long.class, 300_000L));
    }

    private static UsageMetrics usage(Dialect d, Transactions t) {
        return new UsageMetrics(d, t, null);
    }

    private static Map<String, Duration> retention(Environment env) {
        Map<String, String> raw = parse(env.getProperty("sangam.ops.retention-json"), new TypeReference<Map<String, String>>() {
        }, Map.of());
        Map<String, Duration> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> out.put(k, Duration.parse(v)));
        return out;
    }

    private static <T> T parse(String json, TypeReference<T> type, T fallback) {
        if (json == null || json.isBlank()) {
            return fallback;
        }
        try {
            return JSON.readValue(json, type);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("invalid sangam.ops JSON property: " + e.getMessage(), e);
        }
    }
}

// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;

import org.sangam.config.admin.ConfigAdminService;
import org.sangam.config.api.ConfigConnector;
import org.sangam.config.master.ClassificationRegistry;
import org.sangam.config.master.MasterCode;
import org.sangam.config.master.SeedLoader;
import org.sangam.config.profile.LayerModel;
import org.sangam.config.profile.ProfileDocument;
import org.sangam.config.profile.ProfileLoader;
import org.sangam.config.profile.ProfileResolver;
import org.sangam.config.profile.ProfileValidator;
import org.sangam.config.store.ConfigSchema;
import org.sangam.config.store.JdbcConfigStore;
import org.sangam.core.engine.SangamClockHolder;
import org.sangam.integration.connector.ConnectorRegistry;
import org.sangam.integration.packs.PackConnectorBindings;
import org.sangam.rest.support.TxSupport;
import org.sangam.server.bridge.ConfigCaseBindingPlugin;
import org.sangam.server.bridge.ConfigConnectorBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Wires sangam-config (README "Integration notes"):
 * <ul>
 *   <li>{@link JdbcConfigStore} on the product database through its own leaf pool ({@link ConfigStorePool}):
 *       profiles, change register, master codes, number series and
 *       case bindings ({@code CFG_*} tables; Liquibase from sangam-persistence, {@code ConfigSchema.create} for the
 *       dev schema only).</li>
 *   <li>The layer model {@code profiles/layers.yaml}, {@link ProfileResolver}, {@link ProfileValidator} and the
 *       maker-checker {@link ConfigAdminService} (change log in {@code CFG_CHANGE}).</li>
 *   <li>Connector bindings: {@code config.profile}, {@code config.documentType}, {@code config.classification},
 *       {@code config.series}, {@code config.crosswalk} → one {@link ConfigConnector} (registry id {@code config}; the pack request carries the
 *       operation).</li>
 *   <li>Start-up: {@code ProfileLoader.loadIndex(cl, "profiles/profiles.idx")} imported as ACTIVE release profiles
 *       (only versions not yet stored, so an administrator's later activation is never overwritten), then the master
 *       code seeds listed under {@code seeds:} of each profile ({@link SeedLoader}, upsert by key).</li>
 *   <li>Engine plugin {@code sangamConfigPlugin} ({@link ConfigCaseBindingPlugin}): the profile binding of a case.</li>
 * </ul>
 * Properties: {@code sangam.modules.config.enabled} (default true), {@code sangam.config.profiles-index}
 * (default {@code profiles/profiles.idx}), {@code sangam.config.layers} (default {@code profiles/layers.yaml}),
 * {@code sangam.config.load-on-start} (default true), {@code sangam.config.seeds-on-start} (default true).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sangam.modules.config.enabled", havingValue = "true", matchIfMissing = true)
public class ConfigWiring {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigWiring.class);

    /**
     * The pack connector ids bound to the {@code config} connector, one per id its javadoc names (the request carries
     * the operation; SL-123 adds {@code config.crosswalk} for {@code crosswalk.map}).
     */
    public static final List<String> PACK_CONNECTOR_IDS = List.of("config.profile", "config.documentType",
            "config.classification", "config.series", "config.crosswalk");

    /** The config store's leaf pool (see {@link ConfigStorePool}). */
    public static final String STORE_POOL_NAME = "sangam-config";
    static final String STORE_POOL_SIZE_PROPERTY = "sangam.config.store.pool.maximum-size";
    static final int STORE_POOL_DEFAULT_SIZE = 4;

    /**
     * The leaf pool {@value #STORE_POOL_NAME} for the config store's own short transactions
     * ({@code JdbcConfigStore.inTx}: change marker, profile and master-code reads, number series, change register, case
     * binding). The store is read while the caller holds a connection of the main pool: a process start runs
     * {@link ConfigCaseBindingPlugin} inside the engine command, which asks {@code ProfileResolver} for the change
     * marker. A second connection from the main pool there is the SL-43 pool-locking deadlock once every slot is held
     * by a start waiting for its second one. A connection of this pool is never held while another connection is
     * requested (every {@code inTx} of the store is plain SQL on its own connection), so waits for it cannot form a
     * cycle. Same database and settings as the main pool, derived by the one leaf-pool helper
     * ({@link ComplianceWiring#leafPool}; X1-05, the precedent of SL-43 and SL-161);
     * {@value #STORE_POOL_SIZE_PROPERTY} (default 4) connections on top of the main pool. A main pool that is not
     * HikariCP (an embedding host's own pool) gets no leaf pool and the store stays on the main pool, as before, with a
     * warning (the SL-132 rule).
     */
    public static final class ConfigStorePool implements DisposableBean {
        private final HikariDataSource poolOrNull;

        ConfigStorePool(HikariDataSource poolOrNull) {
            this.poolOrNull = poolOrNull;
        }

        HikariDataSource poolOrNull() {
            return poolOrNull;
        }

        /** The leaf pool, or {@code main} when there is none. */
        DataSource dataSource(DataSource main) {
            return poolOrNull == null ? main : poolOrNull;
        }

        @Override
        public void destroy() {
            if (poolOrNull != null) {
                poolOrNull.close();
            }
        }
    }

    @Bean
    public ConfigStorePool sangamConfigStorePool(TxSupport tx, Environment env) {
        return storePool(tx.dataSource(), env.getProperty(STORE_POOL_SIZE_PROPERTY, Integer.class, STORE_POOL_DEFAULT_SIZE));
    }

    static ConfigStorePool storePool(DataSource main, int maximumSize) {
        if (!ComplianceWiring.isHikari(main)) {
            LOG.warn("The main DataSource ({}) is not HikariCP, so the config store has no leaf pool and reads on a second"
                    + " main-pool connection (pool-locking risk under load, SL-43)", main.getClass().getName());
            return new ConfigStorePool(null);
        }
        return new ConfigStorePool(ComplianceWiring.leafPool(main, maximumSize, STORE_POOL_NAME));
    }

    @Bean
    public JdbcConfigStore sangamConfigStore(TxSupport tx, Environment env, ConfigStorePool sangamConfigStorePool) {
        if (env.getProperty("sangam.persistence.dev-schema", Boolean.class, false)) {
            devSchema(tx, "CFG_PROFILE", () -> tx.withConnection(c -> {
                try {
                    ConfigSchema.create(c, tx.dialect());
                    return null;
                } catch (java.sql.SQLException e) {
                    throw new IllegalStateException("cannot create the CFG_* dev tables", e);
                }
            }));
        }
        return new JdbcConfigStore(sangamConfigStorePool.dataSource(tx.dataSource()), tx.dialect());
    }

    @Bean
    public LayerModel sangamConfigLayers(Environment env) {
        String resource = env.getProperty("sangam.config.layers", "profiles/layers.yaml");
        ClassLoader cl = ConfigWiring.class.getClassLoader();
        if (cl.getResource(resource) == null) {
            LOG.warn("No layer model at {}: open layer model (any order)", resource);
            return LayerModel.open();
        }
        Map<String, Object> tree = ProfileLoader.parseTree(ProfileLoader.readResource(cl, resource));
        return LayerModel.from(tree.get("layers"));
    }

    @Bean
    public ProfileResolver sangamProfileResolver(JdbcConfigStore store, LayerModel layers) {
        return new ProfileResolver(store, layers);
    }

    /**
     * The tenant business clock: business dates in the tenant's {@code locale.zone}, never the JVM zone
     * (fallback UTC when a tenant has no profile; see {@link org.sangam.config.locale.ProfileBusinessClock}).
     */
    @Bean
    public org.sangam.core.time.BusinessClock sangamBusinessClock(ProfileResolver resolver) {
        org.sangam.core.time.BusinessClock b = new org.sangam.config.locale.ProfileBusinessClock(resolver, engineClock());
        BusinessClockInstallation.install(b);   // process-wide, for value types built without injection
        return b;
    }

    /** Gives the process-wide business clock back when this context closes ({@link BusinessClockInstallation}). */
    @Bean
    public org.springframework.beans.factory.DisposableBean sangamBusinessClockRelease(
            org.sangam.core.time.BusinessClock sangamBusinessClock) {
        return () -> BusinessClockInstallation.release(sangamBusinessClock);
    }

    @Bean
    public ProfileValidator sangamProfileValidator(LayerModel layers) {
        // SL-35: the document-type catalogue's integrity check (every type's approval chain exists) runs on every
        // validation and activation, so a tenant's new document type cannot reach a running pack with a broken chain
        return new ProfileValidator(layers).with(org.sangam.config.catalogue.DocumentTypeCatalogue::profileCheck);
    }

    @Bean
    public ConfigAdminService sangamConfigAdmin(JdbcConfigStore store, ProfileResolver resolver,
                                                ProfileValidator validator) {
        return new ConfigAdminService(store, resolver, validator, store, engineClock());
    }

    @Bean
    public ClassificationRegistry sangamClassificationRegistry(JdbcConfigStore store) {
        return new ClassificationRegistry(store);
    }

    @Bean
    public ConfigConnector sangamConfigConnector(ProfileResolver resolver, ClassificationRegistry registry,
                                                 JdbcConfigStore store, org.sangam.core.time.BusinessClock businessClock) {
        // SL-119: a bound case reads the profile versions it stored at start (CFG_CASE_BINDING, ConfigCaseBindingPlugin);
        // SL-124: inside an engine command on that command's connection, so the start transaction sees its own binding
        return new ConfigConnector(resolver, registry, store, businessClock,
                new org.sangam.server.bridge.CommandCaseBindings(store));
    }

    /** {@code config.*} pack connector ids → the {@code config} connector (the request carries the operation). */
    @Bean
    public InitializingBean sangamConfigConnectorRegistration(ConfigConnector connector,
            ObjectProvider<ConnectorRegistry> registry, ObjectProvider<PackConnectorBindings> bindings) {
        return () -> {
            ConfigConnectorBinder binder = new ConfigConnectorBinder(registry, bindings);
            if (binder.register(connector)) {
                Map<String, String> ids = new java.util.LinkedHashMap<>();
                for (String id : PACK_CONNECTOR_IDS) {
                    ids.put(id, null);
                }
                LOG.info("config connector registered; {} pack connector ids bound", binder.bind("config", ids));
            }
        };
    }

    /** Case binding at instance start (engine plugin; no dependency on the engine bean). */
    @Bean
    public ConfigCaseBindingPlugin sangamConfigPlugin(ObjectProvider<ProfileResolver> profiles, TxSupport tx) {
        return new ConfigCaseBindingPlugin(profiles, tx.dialect(),
                new org.sangam.config.locale.ProfileBusinessClock(profiles::getIfAvailable, engineClock()));
    }

    /** Release profiles and their master-code seeds, at start-up, idempotently. */
    @Bean
    public ApplicationRunner sangamConfigStartupLoader(ConfigAdminService admin, JdbcConfigStore store, Environment env) {
        return args -> {
            if (!env.getProperty("sangam.config.load-on-start", Boolean.class, true)) {
                return;
            }
            // The release indexes: the base set and the international set (generic PFM reference, regime presets,
            // country profiles with ISO 3-letter ids). A missing index is skipped; a broken one fails start-up.
            String first = env.getProperty("sangam.config.profiles-index", "profiles/profiles.idx");
            List<String> indexes = new ArrayList<>(List.of(first));
            for (String extra : env.getProperty("sangam.config.extra-profile-indexes", "profiles/intl.idx").split(",")) {
                if (!extra.isBlank() && !indexes.contains(extra.trim())) {
                    indexes.add(extra.trim());
                }
            }
            ClassLoader cl = ConfigWiring.class.getClassLoader();
            List<ProfileDocument> docs = new ArrayList<>();
            Map<ProfileDocument, String> baseOf = new java.util.IdentityHashMap<>();
            for (String index : indexes) {
                if (cl.getResource(index) == null) {
                    LOG.info("No configuration profile index at {} (skipped)", index);
                    continue;
                }
                List<ProfileDocument> loaded;
                try {
                    loaded = ProfileLoader.loadIndex(cl, index);
                } catch (RuntimeException e) {
                    throw new IllegalStateException("Configuration profile index " + index + " is broken: "
                            + e.getMessage(), e);
                }
                String base = index.contains("/") ? index.substring(0, index.lastIndexOf('/') + 1) : "";
                for (ProfileDocument d : loaded) {
                    baseOf.put(d, base);
                }
                docs.addAll(loaded);
                LOG.info("Configuration profile index {}: {} profiles", index, loaded.size());
            }
            if (docs.isEmpty()) {
                return;
            }
            List<ProfileDocument> fresh = new ArrayList<>();
            for (ProfileDocument d : docs) {
                if (store.get(d.id(), d.version()).isEmpty()) {
                    fresh.add(d);
                }
            }
            if (!fresh.isEmpty()) {
                // one import, so a country profile can reference a base or regime profile from the other index
                admin.importActive(fresh, "release");
            }
            int seeded = 0;
            if (env.getProperty("sangam.config.seeds-on-start", Boolean.class, true)) {
                for (ProfileDocument d : docs) {
                    seeded += seeds(cl, baseOf.get(d), d, store);
                }
            }
            LOG.info("Configuration profiles: {} in {} indexes, {} imported; {} master codes seeded", docs.size(),
                    indexes.size(), fresh.size(), seeded);
        };
    }

    private static int seeds(ClassLoader cl, String base, ProfileDocument d, JdbcConfigStore store) {
        Object section = d.get("seeds");
        if (!(section instanceof List<?> list)) {
            return 0;
        }
        int n = 0;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m) || m.get("file") == null) {
                continue;
            }
            String file = String.valueOf(m.get("file"));
            String text;
            try {
                text = ProfileLoader.readResource(cl, base + file);
            } catch (RuntimeException e) {
                LOG.warn("seed {} of profile {} not found: {}", file, d.id(), e.getMessage());
                continue;
            }
            String scope = m.get("scope") == null ? d.id() : String.valueOf(m.get("scope"));
            List<MasterCode> codes = file.endsWith(".csv") ? SeedLoader.csv(scope, text) : SeedLoader.yaml(scope, text);
            n += SeedLoader.load(store, codes);
        }
        return n;
    }

    /** Creates a module's dev tables when {@code table} is missing (development only; production uses Liquibase). */
    static void devSchema(TxSupport tx, String table, Runnable create) {
        boolean exists = tx.withConnection(c -> {
            try {
                for (String t : new String[]{table, table.toLowerCase(java.util.Locale.ROOT)}) {
                    try (var rs = c.getMetaData().getTables(null, null, t, new String[]{"TABLE"})) {
                        if (rs.next()) {
                            return true;
                        }
                    }
                }
                return false;
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
        });
        if (!exists) {
            create.run();
            LOG.info("Created the development tables of {}", table);
        }
    }

    /**
     * The tenant business clock bean, or UTC business dates when the config module is off (no profiles, so no
     * tenant zone). Call at use time, not at wiring time.
     */
    static org.sangam.core.time.BusinessClock businessClock(ObjectProvider<org.sangam.core.time.BusinessClock> clocks) {
        org.sangam.core.time.BusinessClock b = clocks == null ? null : clocks.getIfAvailable();
        return b != null ? b : org.sangam.core.time.BusinessClock.utc(engineClock());
    }

    /** {@link Clock} over the engine clock (one clock per JVM, FLOWABLE-INTO-OPERATON D-10). */
    static Clock engineClock() {
        return new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return java.time.ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return Clock.system(zone);
            }

            @Override
            public java.time.Instant instant() {
                return SangamClockHolder.now();
            }
        };
    }
}

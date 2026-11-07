// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
 *   <li>{@link JdbcConfigStore} on the product DataSource: profiles, change register, master codes, number series and
 *       case bindings ({@code CFG_*} tables; Liquibase from sangam-persistence, {@code ConfigSchema.create} for the
 *       dev schema only).</li>
 *   <li>The layer model {@code profiles/layers.yaml}, {@link ProfileResolver}, {@link ProfileValidator} and the
 *       maker-checker {@link ConfigAdminService} (change log in {@code CFG_CHANGE}).</li>
 *   <li>Connector bindings: {@code config.profile}, {@code config.documentType}, {@code config.classification},
 *       {@code config.series} → one {@link ConfigConnector} (registry id {@code config}; the pack request carries the
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

    @Bean
    public JdbcConfigStore sangamConfigStore(TxSupport tx, Environment env) {
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
        return new JdbcConfigStore(tx.dataSource(), tx.dialect());
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

    @Bean
    public ProfileValidator sangamProfileValidator(LayerModel layers) {
        return new ProfileValidator(layers);
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
                                                 JdbcConfigStore store) {
        return new ConfigConnector(resolver, registry, store);
    }

    /** {@code config.*} pack connector ids → the {@code config} connector (the request carries the operation). */
    @Bean
    public InitializingBean sangamConfigConnectorRegistration(ConfigConnector connector,
            ObjectProvider<ConnectorRegistry> registry, ObjectProvider<PackConnectorBindings> bindings) {
        return () -> {
            ConfigConnectorBinder binder = new ConfigConnectorBinder(registry, bindings);
            if (binder.register(connector)) {
                Map<String, String> ids = new java.util.LinkedHashMap<>();
                for (String id : List.of("config.profile", "config.documentType", "config.classification", "config.series")) {
                    ids.put(id, null);
                }
                LOG.info("config connector registered; {} pack connector ids bound", binder.bind("config", ids));
            }
        };
    }

    /** Case binding at instance start (engine plugin; no dependency on the engine bean). */
    @Bean
    public ConfigCaseBindingPlugin sangamConfigPlugin(ObjectProvider<ProfileResolver> profiles, TxSupport tx) {
        return new ConfigCaseBindingPlugin(profiles, tx.dialect());
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

    /** {@link Clock} over the engine clock (one clock per JVM, D-10). */
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

// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import javax.sql.DataSource;

import io.micrometer.core.instrument.MeterRegistry;

import org.operaton.bpm.engine.ProcessEngine;
import org.sangam.compliance.SangamCompliance;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.ConnectorInvoker;
import org.sangam.integration.connector.ConnectorRegistry;
import org.sangam.integration.relay.OutboxWriter;
import org.sangam.integration.relay.PublisherRegistry;
import org.sangam.ports.content.ContentService;
import org.sangam.registry.RegistryModule;
import org.sangam.registry.RegistryPorts;
import org.sangam.registry.RegistrySchema;
import org.sangam.registry.asset.AssetService;
import org.sangam.registry.asset.MaintenanceService;
import org.sangam.registry.custody.CustodyService;
import org.sangam.registry.instrument.InstrumentService;
import org.sangam.registry.instrument.VerificationService;
import org.sangam.registry.kernel.ObjectTypeService;
import org.sangam.registry.kernel.RegisteredObjectService;
import org.sangam.registry.numbering.NumberingService;
import org.sangam.registry.party.PartyService;
import org.sangam.registry.register.RegisterBookService;
import org.sangam.registry.support.Ed25519Signer;
import org.sangam.registry.support.HmacIdentifierVault;
import org.sangam.rest.port.RegistryPort;
import org.sangam.rest.support.TxSupport;
import org.sangam.security.SangamSecurity;
import org.sangam.server.bridge.RegistryPortBridges;
import org.sangam.server.bridge.RegistryPublicRateLimiter;
import org.sangam.server.bridge.RegistrySeedLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;

/**
 * Wires sangam-registry (registry README "Integration notes" §2–§8). One {@link RegistryModule} per engine, installed
 * for the BPMN delegates, with its services exposed as beans; the module's own Spring controllers are registered by
 * sangam-rest's {@code RegistryRestConfiguration}.
 *
 * <p>Port bindings: events → integration outbox (same transaction); visibility → sangam-security VI.4 predicate;
 * privacy → security field masks; consent → sangam-compliance purpose binding; decisions → Operaton DMN; documents →
 * flowable-ports renderer + content store; publisher → outbox → document-locker connector after commit; system of
 * record → connectors (facade mode); metrics → Micrometer; signer / identifier vault → configured keys (development
 * profiles may run on the module's ephemeral defaults; the module refuses them elsewhere).
 *
 * <p>Properties: {@code sangam.modules.registry.enabled}; {@code sangam.registry.signer.private-key} /
 * {@code public-key} (base64 PKCS#8 / X.509 Ed25519); {@code sangam.registry.vault.hmac-pepper} (base64);
 * {@code sangam.registry.publish.connector} (default {@code digilocker}); {@code sangam.registry.system-of-record.<typeKey>}
 * (connector id); {@code sangam.registry.public.rate-per-minute} (default 60); {@code sangam.registry.seeds.enabled}
 * (default true).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sangam.modules.registry.enabled", havingValue = "true", matchIfMissing = true)
public class RegistryWiring {

    private static final Logger LOG = LoggerFactory.getLogger(RegistryWiring.class);

    @Bean
    public RegistryModule sangamRegistryModule(TxSupport tx, DataSource ds, Environment env, ProcessEngine engine,
            ObjectProvider<OutboxWriter> outbox, ObjectProvider<SangamSecurity> security,
            ObjectProvider<SangamCompliance> compliance, ObjectProvider<ContentService> content,
            ObjectProvider<RegistryPort> templates, ObjectProvider<ConnectorRegistry> connectors,
            ObjectProvider<MeterRegistry> meters) {
        if (env.getProperty("sangam.persistence.dev-schema", Boolean.class, false)) {
            devSchema(tx);
        }
        RegistryPorts ports = RegistryPorts.defaults()
                .withEvents(RegistryPortBridges.events(outbox.getIfAvailable(), tx))
                .withDecisions(RegistryPortBridges.decisions(engine.getDecisionService()))
                .withPublisher(RegistryPortBridges.publisher(tx,
                        env.getProperty("sangam.registry.publish.connector", "digilocker")));
        SangamSecurity sec = security.getIfAvailable();
        if (sec != null) {
            ports = ports.withVisibility(RegistryPortBridges.visibility(sec, tx))
                    .withPrivacy(RegistryPortBridges.privacy(sec, tx));
        } else {
            LOG.warn("sangam-security off: registry visibility is tenant-only and views are not masked");
        }
        SangamCompliance cmp = compliance.getIfAvailable();
        if (cmp != null) {
            ports = ports.withConsent(RegistryPortBridges.consent(cmp, tx));
        }
        ContentService cs = content.getIfAvailable();
        RegistryPort reg = templates.getIfAvailable();
        if (cs != null && reg != null) {
            ports = ports.withDocuments(RegistryPortBridges.documents(cs, reg));
        }
        ConnectorRegistry conns = connectors.getIfAvailable();
        if (conns != null) {
            Function<String, String> sor = type -> env.getProperty("sangam.registry.system-of-record." + type);
            ports = ports.withRecords(RegistryPortBridges.systemOfRecord(conns, sor, tx));
        }
        MeterRegistry m = meters.getIfAvailable();
        if (m != null) {
            ports = ports.withMetrics(RegistryPortBridges.metrics(m));
        }
        String priv = env.getProperty("sangam.registry.signer.private-key");
        String pub = env.getProperty("sangam.registry.signer.public-key");
        if (priv != null && !priv.isBlank() && pub != null && !pub.isBlank()) {
            ports = ports.withSigner(Ed25519Signer.fromBase64(priv, pub));
        }
        String pepper = env.getProperty("sangam.registry.vault.hmac-pepper");
        if (pepper != null && !pepper.isBlank()) {
            ports = ports.withVault(HmacIdentifierVault.fromBase64(pepper));
        }
        // The side pool for unique-only number blocks (registry README §2: Transactions.of(plainPool)).
        RegistryModule module = new RegistryModule(tx.dialect(), ports, Transactions.of(ds),
                Arrays.asList(env.getActiveProfiles()));
        return module.install();
    }

    @Bean
    public ObjectTypeService sangamRegistryTypes(RegistryModule m) {
        return m.types();
    }

    @Bean
    public NumberingService sangamRegistryNumbering(RegistryModule m) {
        return m.numbering();
    }

    @Bean
    public RegisteredObjectService sangamRegistryObjects(RegistryModule m) {
        return m.objects();
    }

    @Bean
    public RegisterBookService sangamRegistryRegisters(RegistryModule m) {
        return m.registers();
    }

    @Bean
    public InstrumentService sangamRegistryInstruments(RegistryModule m) {
        return m.instruments();
    }

    @Bean
    public VerificationService sangamRegistryVerification(RegistryModule m) {
        return m.verification();
    }

    /** The party registry: payees (billing) and venue booking parties (resources) consume it by type. */
    @Bean
    public PartyService sangamRegistryParties(RegistryModule m) {
        return m.parties();
    }

    @Bean
    public AssetService sangamRegistryAssets(RegistryModule m) {
        return m.assets();
    }

    @Bean
    public MaintenanceService sangamRegistryMaintenance(RegistryModule m) {
        return m.maintenance();
    }

    @Bean
    public CustodyService sangamRegistryCustody(RegistryModule m) {
        return m.custody();
    }

    /** After-commit delivery of document-locker pushes and system-of-record pushes ({@code sangam.registry.publish}). */
    @Bean
    public org.springframework.beans.factory.InitializingBean sangamRegistryPublishDestinations(
            ObjectProvider<PublisherRegistry> publishers, ObjectProvider<ConnectorInvoker> invoker) {
        return () -> {
            PublisherRegistry p = publishers.getIfAvailable();
            ConnectorInvoker inv = invoker.getIfAvailable();
            if (p != null && inv != null) {
                p.register(RegistryPortBridges.DEST_PUBLISH, new RegistryPortBridges.PublishDelivery(inv));
            } else {
                LOG.warn("sangam-integration off: registry document-locker pushes stay in the outbox undelivered");
            }
        };
    }

    /** Party identifier-type and match-configuration seeds from the packs (registry README §6). */
    @Bean
    public ApplicationRunner sangamRegistrySeeds(RegistryModule module, Environment env) {
        return args -> {
            if (env.getProperty("sangam.registry.seeds.enabled", Boolean.class, true)) {
                RegistrySeedLoader.load(module.parties(), RegistryWiring.class.getClassLoader());
            }
        };
    }

    /**
     * The public verification endpoints ({@code /instruments/public/**}, {@code /registers/public/**}) without
     * authentication but rate-limited (registry README §2). A separate, earlier filter chain matched only to those
     * paths; the main chain is unchanged.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 20)
    public SecurityFilterChain sangamRegistryPublicChain(HttpSecurity http, Environment env) throws Exception {
        http.securityMatcher("/api/v1/registry/instruments/public/**", "/api/v1/registry/registers/public/**")
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .addFilterBefore(new RegistryPublicRateLimiter(
                        env.getProperty("sangam.registry.public.rate-per-minute", Integer.class, 60)),
                        SecurityContextHolderFilter.class);
        return http.build();
    }

    private static void devSchema(TxSupport tx) {
        tx.withConnection(c -> {
            if (!exists(c, "REG_OBJECT_TYPE")) {
                RegistrySchema.create(c, tx.dialect());
                LOG.info("Created the sangam-registry development tables");
            }
            return null;
        });
    }

    static boolean exists(Connection c, String table) {
        try {
            for (String t : List.of(table, table.toLowerCase())) {
                try (ResultSet rs = c.getMetaData().getTables(null, null, t, new String[]{"TABLE"})) {
                    if (rs.next()) {
                        return true;
                    }
                }
            }
            return false;
        } catch (SQLException e) {
            throw new IllegalStateException("cannot read database metadata", e);
        }
    }
}

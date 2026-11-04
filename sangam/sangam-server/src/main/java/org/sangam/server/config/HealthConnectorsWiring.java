// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.net.URI;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.sangam.connectors.health.HealthConnectors;
import org.sangam.connectors.health.cdshooks.CdsHooksConnector;
import org.sangam.connectors.health.cdshooks.CdsHooksService;
import org.sangam.connectors.health.fhir.FhirClient;
import org.sangam.connectors.health.fhir.FhirConnector;
import org.sangam.connectors.health.fhir.FhirSubscriptionReceiver;
import org.sangam.connectors.health.fhir.SmartBackendAuth;
import org.sangam.connectors.health.pas.PasConnector;
import org.sangam.connectors.health.sim.HealthSimulator;
import org.sangam.connectors.health.x12.X12Connector;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.SangamConnector;
import org.sangam.integration.connector.ConnectorRegistry;
import org.sangam.integration.connector.EgressPolicy;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.integration.inbox.ChannelBinding;
import org.sangam.integration.inbox.InboxService;
import org.sangam.integration.packs.PackConnectorBindings;
import org.sangam.rest.modules.connectorshealth.HealthHooksPort;
import org.sangam.server.bridge.ConfigConnectorBinder;
import org.sangam.server.bridge.HealthHooksBridge;
import org.sangam.server.bridge.HealthRenamedConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Wires sangam-connectors-health (README "Integration notes"):
 * <ul>
 *   <li>Per tenant ({@code sangam.connectors-health.tenants.<tenant>}), the FHIR, Da Vinci PAS, CDS Hooks (CRD) and
 *       X12 connectors are registered on the integration {@code ConnectorRegistry} under the neutral ids {@code fhir},
 *       {@code pas}, {@code crd}, {@code x12}: mode {@code simulator} binds them to one in-process
 *       {@link HealthSimulator} (loopback only, air-gapped); mode {@code live} builds them from configuration with the
 *       tenant's egress rules, secrets and keys by reference ({@link SecretResolver}).</li>
 *   <li>Pack bindings ({@code HealthConnectors.packBindings()} re-targeted to the neutral ids, plus the
 *       {@code fhir.*} pack ids with the request's own operation).</li>
 *   <li>{@code POST /api/v1/hooks/fhir/{channel}}: one {@link FhirSubscriptionReceiver} per
 *       {@code sangam.connectors-health.fhir-channels.<channel>} (sink {@code inboxService::receive}; default event type
 *       {@code ClaimResponse} → {@code sangam.ushealth.pasUpdate}); {@code /cds-services/**} on the shared
 *       {@link CdsHooksService} (packs register their hook services on that bean).</li>
 * </ul>
 * Properties: {@code sangam.modules.connectors-health.enabled}, and {@link HealthConnectorsProperties}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sangam.modules.connectors-health.enabled", havingValue = "true", matchIfMissing = true)
public class HealthConnectorsWiring {

    private static final Logger LOG = LoggerFactory.getLogger(HealthConnectorsWiring.class);
    private static final Map<String, String> NEUTRAL = Map.of("fhir-sim", "fhir", "pas-sim", "pas", "crd-sim", "crd",
            "x12-sim", "x12");
    /** {@code fhir.*} pack ids of the {@code sec.ushealth.*} packs (operation from the request). */
    private static final List<String> FHIR_PACK_IDS = List.of("fhir.adt", "fhir.ehiExport", "fhir.transition");

    @Bean
    public HealthConnectorsProperties sangamHealthConnectorsProperties() {
        return new HealthConnectorsProperties();
    }

    /** The shared CDS Hooks service registry ({@code /cds-services}); packs add their services to it. */
    @Bean
    public CdsHooksService sangamCdsHooksService() {
        return new CdsHooksService();
    }

    /** The in-process health simulator, started only when a tenant uses mode {@code simulator}. */
    @Bean
    public HealthSimulatorHolder sangamHealthSimulator(HealthConnectorsProperties props, Environment env) {
        boolean prod = Arrays.stream(env.getActiveProfiles()).anyMatch(p -> p.contains("prod"));
        boolean any = props.getTenants().values().stream().anyMatch(t -> "simulator".equalsIgnoreCase(t.getMode()));
        if (!any) {
            return new HealthSimulatorHolder(null);
        }
        if (prod) {
            throw new IllegalStateException("sangam.connectors-health: simulator mode is not allowed on a production profile");
        }
        HealthSimulator sim = new HealthSimulator().start();
        LOG.warn("Health connectors: SIMULATOR at {} (development)", sim.baseUri());
        return new HealthSimulatorHolder(sim);
    }

    /** Nullable holder (a bean method may not return null); closes the simulator at shutdown. */
    public record HealthSimulatorHolder(HealthSimulator simulator) implements DisposableBean {
        @Override
        public void destroy() {
            if (simulator != null) {
                simulator.close();
            }
        }
    }

    @Bean
    public InitializingBean sangamHealthConnectorRegistration(HealthConnectorsProperties props,
            HealthSimulatorHolder sim, ObjectProvider<ConnectorRegistry> registry,
            ObjectProvider<PackConnectorBindings> bindings, ObjectProvider<SecretResolver> secrets) {
        return () -> {
            ConfigConnectorBinder binder = new ConfigConnectorBinder(registry, bindings);
            SecretResolver resolver = secrets.getIfAvailable(SecretResolver::fromEnvironment);
            int tenants = 0;
            for (Map.Entry<String, HealthConnectorsProperties.TenantConfig> e : props.getTenants().entrySet()) {
                Map<String, SangamConnector> connectors = "live".equalsIgnoreCase(e.getValue().getMode())
                        ? live(e.getKey(), e.getValue(), resolver)
                        : HealthConnectors.simulator(sim.simulator());
                for (Map.Entry<String, SangamConnector> c : connectors.entrySet()) {
                    String id = NEUTRAL.getOrDefault(c.getKey(), c.getKey());
                    binder.register(e.getKey(), new HealthRenamedConnector(id, c.getValue()));
                }
                tenants++;
            }
            if (tenants == 0) {
                return;
            }
            Map<String, String[]> pack = HealthConnectors.packBindings();
            Map<String, Map<String, String>> byConnector = new LinkedHashMap<>();
            pack.forEach((packId, target) -> byConnector.computeIfAbsent(NEUTRAL.getOrDefault(target[0], target[0]),
                    k -> new LinkedHashMap<>()).put(packId, target[1]));
            FHIR_PACK_IDS.forEach(id -> byConnector.computeIfAbsent("fhir", k -> new LinkedHashMap<>()).put(id, null));
            int bound = 0;
            for (Map.Entry<String, Map<String, String>> b : byConnector.entrySet()) {
                bound += binder.bind(b.getKey(), b.getValue());
            }
            LOG.info("Health connectors registered for {} tenant(s); {} pack connector ids bound", tenants, bound);
        };
    }

    @Bean
    public HealthHooksPort sangamHealthHooks(HealthConnectorsProperties props, CdsHooksService cds,
            ObjectProvider<InboxService> inbox, ObjectProvider<SecretResolver> secrets, Environment env) {
        Map<String, FhirSubscriptionReceiver> receivers = new LinkedHashMap<>();
        InboxService in = inbox.getIfAvailable();
        boolean dev = Arrays.asList(env.getActiveProfiles()).contains("h2");
        SecretResolver resolver = secrets.getIfAvailable(SecretResolver::fromEnvironment);
        if (in != null) {
            for (Map.Entry<String, HealthConnectorsProperties.Channel> e : props.getFhirChannels().entrySet()) {
                HealthConnectorsProperties.Channel ch = e.getValue();
                if (ch.getTenant() == null || ch.getTenant().isBlank()) {
                    throw new IllegalStateException("sangam.connectors-health.fhir-channels." + e.getKey()
                            + ".tenant is required");
                }
                String secret = ch.getSecretRef() == null ? null : resolver.require(ch.getSecretRef());
                if (secret == null && !dev) {
                    throw new IllegalStateException("sangam.connectors-health.fhir-channels." + e.getKey()
                            + ".secret-ref is required outside development (the channel would be unauthenticated)");
                }
                Map<String, String> types = new LinkedHashMap<>(ch.getEventTypes());
                types.putIfAbsent("ClaimResponse", "sangam.ushealth.pasUpdate");
                String source = "fhir-sub:" + (ch.getServer() == null ? e.getKey() : ch.getServer());
                ChannelBinding binding = new ChannelBinding(source, ch.getTenant(), Set.of(ch.getTenant()),
                        "system:fhir-subscription");
                receivers.put(e.getKey(), new FhirSubscriptionReceiver(in::receive, binding, ch.getSecretHeader(), secret,
                        types, null));
            }
        } else if (!props.getFhirChannels().isEmpty()) {
            LOG.warn("FHIR subscription channels configured but sangam-integration is off: notifications are refused");
        }
        return new HealthHooksBridge(receivers, cds);
    }

    // ---------------------------------------------------------------- live connectors

    private static Map<String, SangamConnector> live(String tenant, HealthConnectorsProperties.TenantConfig t,
                                                     SecretResolver secrets) {
        List<EgressPolicy.Rule> rules = new ArrayList<>();
        for (String spec : t.getEgress()) {
            rules.add(EgressPolicy.Rule.parse(tenant, spec, false));
        }
        HttpTransport transport = new HttpTransport(rules.isEmpty() ? EgressPolicy.denyAll()
                : EgressPolicy.of(rules, 3, 32L * 1024 * 1024), null, Duration.ofSeconds(10), Duration.ofSeconds(30), null);
        Map<String, SangamConnector> m = new LinkedHashMap<>();
        HealthConnectorsProperties.Fhir f = t.getFhir();
        if (f.getBaseUrl() != null) {
            SmartBackendAuth.Mode mode = SmartBackendAuth.Mode.valueOf(f.getAuthMode().toUpperCase(java.util.Locale.ROOT));
            SmartBackendAuth auth = mode == SmartBackendAuth.Mode.NONE ? null : new SmartBackendAuth(
                    new SmartBackendAuth.Config(mode, f.getTokenUri() == null ? null : URI.create(f.getTokenUri()),
                            f.getClientId(), f.getScope(), key(secrets, f.getPrivateKeyRef()), f.getKeyId(),
                            secrets.require(f.getSecretRef())), transport, ConfigWiring.engineClock());
            FhirClient client = new FhirClient(new FhirClient.Config(URI.create(f.getBaseUrl()), transport, auth,
                    Duration.ofSeconds(20), null));
            m.put("fhir", new FhirConnector("fhir", client, ConnectorProfile.LIVE));
            m.put("pas", new PasConnector("pas", client, ConnectorProfile.LIVE));
        }
        HealthConnectorsProperties.Cds c = t.getCds();
        if (c.getBaseUrl() != null) {
            m.put("crd", new CdsHooksConnector(new CdsHooksConnector.Config("crd", URI.create(c.getBaseUrl()),
                    c.getClientIss(), key(secrets, c.getPrivateKeyRef()), c.getKeyId(), transport, ConnectorProfile.LIVE,
                    Duration.ofSeconds(5))));
        }
        HealthConnectorsProperties.X12 x = t.getX12();
        if (x.getBaseUrl() != null) {
            m.put("x12", new X12Connector(new X12Connector.Config("x12", URI.create(x.getBaseUrl()), x.getSenderId(),
                    x.getReceiverId(), x.isProduction(), x.getRealtimePath(), x.getBatchPath(), x.getStatusPath(),
                    secrets.require(x.getAuthorizationRef()), transport, ConnectorProfile.LIVE, Duration.ofSeconds(20))));
        }
        return m;
    }

    /** A PKCS#8 private key (PEM or bare base64) by secret reference; null when none is configured. */
    private static PrivateKey key(SecretResolver secrets, String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        String pem = secrets.require(ref);
        String b64 = pem.replaceAll("-----(BEGIN|END) [A-Z ]*-----", "").replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(b64);
        for (String alg : new String[]{"RSA", "EC"}) {
            try {
                return KeyFactory.getInstance(alg).generatePrivate(new PKCS8EncodedKeySpec(der));
            } catch (java.security.GeneralSecurityException ignored) {
                // try the next algorithm
            }
        }
        throw new IllegalStateException("secret " + ref + " is not a PKCS#8 RSA or EC private key");
    }
}

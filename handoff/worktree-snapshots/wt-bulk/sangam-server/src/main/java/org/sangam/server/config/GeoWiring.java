// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Transactions;
import org.sangam.geo.GeoSchema;
import org.sangam.geo.GeoService;
import org.sangam.geo.bpmn.GeoDelegates;
import org.sangam.geo.connector.ExternalLayerGateway;
import org.sangam.geo.connector.GisHttp;
import org.sangam.geo.connector.GisSimulatorConnector;
import org.sangam.geo.connector.WfsConnector;
import org.sangam.geo.connector.WmsConnector;
import org.sangam.geo.feel.GeoEnginePlugin;
import org.sangam.geo.feel.GeoFeelFunctionProvider;
import org.sangam.geo.rest.GeoRestPort;
import org.sangam.geo.rest.GeoRestService;
import org.sangam.integration.connector.ConnectorRegistry;
import org.sangam.rest.support.TxSupport;
import org.sangam.server.bridge.GeoEngineClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Wires sangam-geo (geo README "Integration notes" §2–§3): {@link GeoService} on the Spring-joined transaction (the
 * engine's), the FEEL geo functions as an engine plugin ({@code sangamGeoPlugin}), the BPMN delegate beans
 * {@code geoJurisdiction}, {@code geoFence} and {@code geoNearest}, the REST service (sangam-rest exposes the module's
 * {@code GeoController}), and the WFS/WMS connectors per tenant in the integration {@code ConnectorRegistry}.
 *
 * <p>Properties: {@code sangam.modules.geo.enabled}; {@code sangam.geo.index-mode} ({@code auto} | {@code grid} |
 * {@code postgis}, default auto); {@code sangam.geo.admin-group} (default {@code sangam-tenant-admin});
 * {@code sangam.geo.connectors[n]} = {tenant (optional: platform-wide when absent), id, type (wfs | wms | simulator),
 * base-url, allowed-hosts (comma list), default-type-name, version, default-layers, timeout (ISO-8601, default PT20S),
 * max-bytes (default 20 MiB), headers.*}; {@code sangam.geo.simulator} (default: on for the h2 profile) registers
 * {@code gis-sim-wfs} and {@code gis-sim-wms} simulators.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sangam.modules.geo.enabled", havingValue = "true", matchIfMissing = true)
public class GeoWiring {

    private static final Logger LOG = LoggerFactory.getLogger(GeoWiring.class);

    @Bean
    public GeoService sangamGeoService(TxSupport tx, Environment env) {
        if (env.getProperty("sangam.persistence.dev-schema", Boolean.class, false)) {
            tx.withConnection(c -> {
                if (!RegistryWiring.exists(c, GeoSchema.tables().get(0))) {
                    GeoSchema.create(c, tx.dialect());
                    LOG.info("Created the sangam-geo development tables");
                }
                return null;
            });
        }
        Transactions joined = new Transactions() {
            @Override
            public <T> T inTransaction(Function<java.sql.Connection, T> work) {
                return tx.withConnection(work);
            }
        };
        return GeoService.create(joined, tx.dialect(), env.getProperty("sangam.geo.index-mode", "auto"),
                new AuditChain(tx.dialect()), new GeoEngineClock(ZoneOffset.UTC));
    }

    /** FEEL geo functions for DMN and expressions (geo README §2). Order: after the Sangam/security/integration/ports plugins. */
    @Bean
    public GeoEnginePlugin sangamGeoPlugin(ObjectProvider<GeoService> geo, Environment env) {
        return new GeoEnginePlugin(new GeoFeelFunctionProvider(geo.getObject(), defaultTenant(env)));
    }

    @Bean(name = "geoJurisdiction")
    public GeoDelegates.Jurisdiction geoJurisdiction(GeoService geo, Environment env) {
        return new GeoDelegates.Jurisdiction(geo, defaultTenant(env));
    }

    @Bean(name = "geoFence")
    public GeoDelegates.Fence geoFence(GeoService geo, Environment env) {
        return new GeoDelegates.Fence(geo, defaultTenant(env));
    }

    @Bean(name = "geoNearest")
    public GeoDelegates.NearestN geoNearest(GeoService geo, Environment env) {
        return new GeoDelegates.NearestN(geo, defaultTenant(env));
    }

    @Bean
    public GeoRestPort sangamGeoRestPort(GeoService geo, ObjectProvider<ConnectorRegistry> connectors, Environment env) {
        ConnectorRegistry registry = connectors.getIfAvailable();
        ExternalLayerGateway gateway = new ExternalLayerGateway(geo, (tenant, id) -> {
            if (registry == null) {
                throw new IllegalStateException("sangam-integration is off: no connector registry for external layer " + id);
            }
            return registry.require(tenant, id);
        });
        return new GeoRestService(geo, gateway, env.getProperty("sangam.geo.admin-group", "sangam-tenant-admin"));
    }

    /** WFS / WMS connectors per tenant (geo README §3), and the GIS simulators on the h2 profile. */
    @Bean
    public InitializingBean sangamGeoConnectors(ObjectProvider<ConnectorRegistry> connectors, Environment env) {
        return () -> {
            ConnectorRegistry registry = connectors.getIfAvailable();
            if (registry == null) {
                return;
            }
            boolean sim = env.getProperty("sangam.geo.simulator", Boolean.class,
                    Arrays.asList(env.getActiveProfiles()).contains("h2"));
            if (sim) {
                registry.register(new GisSimulatorConnector("gis-sim-wfs", "wfs"));
                registry.register(new GisSimulatorConnector("gis-sim-wms", "wms"));
            }
            for (Map<String, Object> m : connectorsConfig(env)) {
                String id = str(m, "id");
                String type = str(m, "type");
                if (id == null || type == null) {
                    throw new IllegalStateException("sangam.geo.connectors entries need id and type: " + m);
                }
                var connector = switch (type.toLowerCase()) {
                    case "wfs" -> new WfsConnector(id, config(m), str(m, "default-type-name"), str(m, "version"));
                    case "wms" -> new WmsConnector(id, config(m), str(m, "default-layers"));
                    case "simulator" -> new GisSimulatorConnector(id, str(m, "kind") == null ? "wfs" : str(m, "kind"));
                    default -> throw new IllegalStateException("unknown geo connector type " + type);
                };
                String tenant = str(m, "tenant");
                if (tenant == null || tenant.isBlank()) {
                    registry.register(connector);
                } else {
                    registry.register(tenant, connector);
                }
                LOG.info("Geo connector {} ({}) registered for {}", id, type, tenant == null ? "every tenant" : tenant);
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> connectorsConfig(Environment env) {
        Map<String, Object> root = Binder.get(env).bind("sangam.geo", Bindable.mapOf(String.class, Object.class))
                .orElse(Map.of());
        Object c = root.get("connectors");
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        if (c instanceof Map<?, ?> indexed) {
            // Binder maps a list property ([0], [1] ...) to a map keyed by index.
            new java.util.TreeMap<>((Map<String, Object>) indexed).values().forEach(v -> {
                if (v instanceof Map<?, ?> m) {
                    out.add((Map<String, Object>) m);
                }
            });
        } else if (c instanceof List<?> l) {
            l.forEach(v -> {
                if (v instanceof Map<?, ?> m) {
                    out.add((Map<String, Object>) m);
                }
            });
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static GisHttp.Config config(Map<String, Object> m) {
        Set<String> hosts = new HashSet<>();
        String h = str(m, "allowed-hosts");
        if (h != null) {
            for (String x : h.split(",")) {
                if (!x.isBlank()) {
                    hosts.add(x.trim());
                }
            }
        }
        Map<String, String> headers = new LinkedHashMap<>();
        if (m.get("headers") instanceof Map<?, ?> hm) {
            ((Map<String, Object>) hm).forEach((k, v) -> headers.put(k, String.valueOf(v)));
        }
        String timeout = str(m, "timeout");
        String max = str(m, "max-bytes");
        return new GisHttp.Config(str(m, "base-url"), hosts, headers,
                timeout == null ? Duration.ofSeconds(20) : Duration.parse(timeout),
                max == null ? 20L * 1024 * 1024 : Long.parseLong(max));
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : String.valueOf(v);
    }

    static String defaultTenant(Environment env) {
        return env.getProperty("sangam.tenant.default", env.getProperty("sangam.security.dev-token.tenant", "dev"));
    }
}

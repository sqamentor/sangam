// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.sangam.compliance.config.ComplianceConfig;
import org.sangam.compliance.jurisdiction.JurisdictionRegistry;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * {@link ComplianceConfig.Provider} over the Spring environment. The jurisdiction packs named in
 * {@code sangam.compliance.jurisdictions} (default {@code IN}) must be installed ({@code META-INF/sangam/jurisdictions.idx});
 * a tenant uses {@code sangam.compliance.tenant-jurisdictions.<tenant>}, else the first configured pack. Every key under
 * {@code sangam.compliance} is a {@link ComplianceConfig} property; {@code sangam.compliance.tenant.<tenant>.<key>}
 * overrides it for one tenant. Bound configurations are cached per tenant (configuration, not business state).
 */
public final class ComplianceConfigProvider implements ComplianceConfig.Provider {

    private final JurisdictionRegistry registry;
    private final Map<String, String> base;
    private final String defaultJurisdiction;
    private final Map<String, ComplianceConfig> cache = new ConcurrentHashMap<>();

    public ComplianceConfigProvider(JurisdictionRegistry registry, Environment env) {
        this.registry = registry;
        Binder binder = Binder.get(env);
        List<String> codes = binder.bind("sangam.compliance.jurisdictions", Bindable.listOf(String.class))
                .orElse(List.of("IN"));
        if (codes.isEmpty()) {
            codes = List.of("IN");
        }
        for (String code : codes) {
            registry.require(code.trim()); // fail at start-up when a configured pack is not installed
        }
        this.defaultJurisdiction = codes.get(0).trim();
        this.base = binder.bind("sangam.compliance", Bindable.mapOf(String.class, String.class)).orElse(Map.of());
    }

    public JurisdictionRegistry registry() {
        return registry;
    }

    @Override
    public ComplianceConfig forTenant(String tenantId) {
        return cache.computeIfAbsent(tenantId == null ? "" : tenantId, this::bind);
    }

    private ComplianceConfig bind(String tenant) {
        Map<String, String> props = new LinkedHashMap<>();
        String tenantPrefix = "tenant." + tenant + ".";
        base.forEach((k, v) -> {
            if (!k.startsWith("tenant.") && !k.startsWith("tenant-jurisdictions.")) {
                props.put(k, v);
            }
        });
        base.forEach((k, v) -> {
            if (k.startsWith(tenantPrefix)) {
                props.put(k.substring(tenantPrefix.length()), v);
            }
        });
        String code = base.getOrDefault("tenant-jurisdictions." + tenant, props.getOrDefault("jurisdiction", defaultJurisdiction));
        props.put("jurisdiction", code);
        return ComplianceConfig.fromProperties(registry, props);
    }
}

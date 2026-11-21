// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.config;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.bind.BindResult;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.Environment;

/**
 * Per-tenant configuration under a prefix, such as {@code sangam.channels.egress.<tenant>}. A tenant id is a map key, never
 * a part of a property name: {@code GOI.FIN} is not a valid property name element (upper case, and the dot would split it
 * into two levels), so building {@code "sangam.channels.egress." + tenant} throws
 * {@code InvalidConfigurationPropertyNameException} or binds the wrong level. Configuration names such a tenant with Spring's
 * bracket notation, {@code sangam.channels.egress[GOI.FIN][0]=...}; a simple id ({@code acme}) keeps working without
 * brackets.
 */
public final class TenantConfig {

    /**
     * Tenant ids that can carry per-tenant configuration: letters, digits, '_', '-' and '.', which covers every id the
     * tenant admin API accepts ({@code [A-Za-z0-9][A-Za-z0-9_.-]{0,59}}) and every id accepted here before; never a
     * bracket, so the id cannot escape its map key.
     */
    public static final Pattern TENANT = Pattern.compile("[A-Za-z0-9_.-]+");

    private TenantConfig() {
    }

    /** True when {@code tenant} is a tenant id usable as a configuration map key (at most 64 characters). */
    public static boolean valid(String tenant) {
        return tenant != null && tenant.length() <= 64 && TENANT.matcher(tenant).matches();
    }

    /**
     * Binds the value configured for {@code tenant} under {@code prefix}: first {@code prefix[tenant]} (bracket notation,
     * relaxed binding for simple ids as before), else the {@code tenant} key of the map bound at {@code prefix} (an upper
     * case simple id written without brackets). Empty for an id that is not {@link #valid}.
     */
    @SuppressWarnings("unchecked")
    public static <T> Optional<T> bind(Environment env, String prefix, String tenant, ResolvableType type) {
        if (!valid(tenant)) {
            return Optional.empty();
        }
        Binder binder = Binder.get(env);
        ConfigurationPropertyName name = ConfigurationPropertyName.of(prefix).append("[" + tenant + "]");
        BindResult<T> direct = binder.bind(name, Bindable.<T>of(type));
        if (direct.isBound()) {
            return Optional.of(direct.get());
        }
        ResolvableType all = ResolvableType.forClassWithGenerics(Map.class, ResolvableType.forClass(String.class), type);
        Map<String, T> byTenant = (Map<String, T>) binder.bind(prefix, Bindable.of(all)).orElse(Map.of());
        return Optional.ofNullable(byTenant.get(tenant));
    }
}

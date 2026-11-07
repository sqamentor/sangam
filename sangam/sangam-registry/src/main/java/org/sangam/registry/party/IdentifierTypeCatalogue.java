// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry.party;

import org.sangam.registry.RegistryException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Identifier types per tenant, in two layers:
 * <ol>
 *   <li><b>saved</b>: types saved by the tenant's administrators in {@code REG_PARTY_CONFIG}. They survive restarts
 *       and win over the seed. A saved RETIRED key hides a seeded type for that tenant;</li>
 *   <li><b>seed</b>: the default from pack configuration, loaded by the integrator at start-up with
 *       {@link #define}. Types under tenant {@code *} apply to every tenant unless the tenant defines the same key.
 *       Types under {@link #jurisdictionScope} apply only to tenants whose profile resolves to that jurisdiction
 *       (ADR-D-20: national identifiers are jurisdiction data).</li>
 * </ol>
 * The saved layer is refreshed from the table by {@link PartyService} whenever the table changes, which keeps
 * every node consistent. When {@code allowUnconfigured} is set, an unknown key is accepted as an open, non-unique,
 * non-sensitive type.
 */
public final class IdentifierTypeCatalogue {

    public static final String ALL_TENANTS = "*";

    private record Saved(Map<String, IdentifierType> types, Set<String> retired) {
    }

    private final Map<String, Map<String, IdentifierType>> seed = new ConcurrentHashMap<>();
    private final Map<String, Saved> saved = new ConcurrentHashMap<>();
    private volatile boolean allowUnconfigured;
    private volatile java.util.function.Function<String, String> jurisdictionOf = t -> null;

    /** The seed key of a jurisdiction's types. */
    public static String jurisdictionScope(String jurisdiction) {
        return "jurisdiction:" + jurisdiction;
    }

    /** Sets how a tenant maps to its jurisdiction (null when it has none: jurisdiction seeds then do not apply). */
    public void jurisdictionResolver(java.util.function.Function<String, String> resolver) {
        this.jurisdictionOf = resolver == null ? t -> null : resolver;
    }

    private Map<String, IdentifierType> jurisdictionSeed(String tenant) {
        String j = jurisdictionOf.apply(tenant);
        return j == null ? null : seed.get(jurisdictionScope(j));
    }

    /** Seeds (adds or replaces) the given pack types for the tenant; other seeded types are kept. */
    public List<IdentifierType> define(String tenant, List<Map<String, Object>> definitions) {
        List<IdentifierType> parsed = new ArrayList<>();
        for (Map<String, Object> d : definitions) {
            parsed.add(IdentifierType.from(d));
        }
        Map<String, IdentifierType> m = seed.computeIfAbsent(tenant, t -> new ConcurrentHashMap<>());
        parsed.forEach(t -> m.put(t.typeKey(), t));
        return parsed;
    }

    /** Removes a seeded type (pack unload). */
    public void remove(String tenant, String typeKey) {
        Map<String, IdentifierType> m = seed.get(tenant);
        if (m != null) {
            m.remove(typeKey);
        }
    }

    /** Replaces the saved layer of a tenant (called after reading {@code REG_PARTY_CONFIG}). */
    void replaceSaved(String tenant, Map<String, IdentifierType> types, Set<String> retired) {
        saved.put(tenant, new Saved(Map.copyOf(types), Set.copyOf(retired)));
    }

    public void allowUnconfigured(boolean allow) {
        this.allowUnconfigured = allow;
    }

    public IdentifierType get(String tenant, String typeKey) {
        IdentifierType t = find(tenant, typeKey);
        if (t != null) {
            return t;
        }
        if (allowUnconfigured) {
            return IdentifierType.open(typeKey);
        }
        throw RegistryException.invalid("identifier type " + typeKey + " is not configured");
    }

    public IdentifierType find(String tenant, String typeKey) {
        Saved s = saved.get(tenant);
        if (s != null) {
            if (s.retired().contains(typeKey)) {
                return null;
            }
            IdentifierType t = s.types().get(typeKey);
            if (t != null) {
                return t;
            }
        }
        Map<String, IdentifierType> m = seed.get(tenant);
        IdentifierType t = m == null ? null : m.get(typeKey);
        if (t == null) {
            Map<String, IdentifierType> scoped = jurisdictionSeed(tenant);
            t = scoped == null ? null : scoped.get(typeKey);
        }
        if (t == null) {
            Map<String, IdentifierType> all = seed.get(ALL_TENANTS);
            t = all == null ? null : all.get(typeKey);
        }
        return t;
    }

    public List<IdentifierType> list(String tenant) {
        Map<String, IdentifierType> out = new LinkedHashMap<>();
        Map<String, IdentifierType> all = seed.get(ALL_TENANTS);
        if (all != null) {
            out.putAll(all);
        }
        Map<String, IdentifierType> scoped = jurisdictionSeed(tenant);
        if (scoped != null) {
            out.putAll(scoped);
        }
        Map<String, IdentifierType> m = seed.get(tenant);
        if (m != null) {
            out.putAll(m);
        }
        Saved s = saved.get(tenant);
        if (s != null) {
            out.putAll(s.types());
            s.retired().forEach(out::remove);
        }
        return List.copyOf(out.values());
    }
}

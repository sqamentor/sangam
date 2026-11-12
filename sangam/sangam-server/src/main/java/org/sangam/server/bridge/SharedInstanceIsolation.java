// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * M-016, owner decision 97 (Q-W3H-5 option A): a <b>shared</b> instance (no {@value DedicatedTenant#PROPERTY}) never
 * serves a tenant whose tenant-directory record promises more isolation than a shared database gives: isolation
 * {@code DATABASE} (its own database, served only by its dedicated instance) or {@code SCHEMA} (no schema-per-tenant
 * realisation exists). Such a request is answered 421 {@value DedicatedTenantFilter#CODE} by
 * {@link DedicatedTenantFilter}; serving it would silently downgrade the promise.
 *
 * <p>The isolation is read through {@code lookup} (tenant id to recorded isolation, null when there is no record) and
 * cached per tenant for {@link #TTL}, so a changed record takes effect within that time. A tenant without a record, a
 * request without a tenant and the platform wildcard {@code *} are served (the later layers answer them as before). A
 * lookup that fails is not cached and propagates, so the request is not served (fail-closed). Plain Java.
 */
public final class SharedInstanceIsolation {

    /** Isolation values a shared instance refuses (decision 97). */
    public static final Set<String> REFUSED = Set.of("DATABASE", "SCHEMA");

    /** How long one tenant's recorded isolation is reused. */
    public static final Duration TTL = Duration.ofSeconds(5);

    private record Entry(String isolation, long expiresAtMs) {
    }

    private final Function<String, String> lookup;
    private final LongSupplier clockMs;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public SharedInstanceIsolation(Function<String, String> lookup) {
        this(lookup, System::currentTimeMillis);
    }

    SharedInstanceIsolation(Function<String, String> lookup, LongSupplier clockMs) {
        this.lookup = lookup;
        this.clockMs = clockMs;
    }

    /** True when a shared instance may serve a tenant recorded with this isolation (null = no record = SHARED). */
    public static boolean servedOnShared(String isolation) {
        return isolation == null || !REFUSED.contains(isolation.trim().toUpperCase(Locale.ROOT));
    }

    /** True when this shared instance serves {@code tenant}. */
    public boolean serves(String tenant) {
        if (tenant == null || tenant.isBlank() || "*".equals(tenant.trim())) {
            return true;
        }
        String id = tenant.trim();
        long now = clockMs.getAsLong();
        Entry e = cache.get(id);
        if (e == null || e.expiresAtMs() <= now) {
            e = new Entry(lookup.apply(id), now + TTL.toMillis());
            cache.put(id, e);
        }
        return servedOnShared(e.isolation());
    }
}

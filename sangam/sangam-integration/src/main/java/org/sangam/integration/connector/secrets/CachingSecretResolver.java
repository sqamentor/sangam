// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.secrets;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.sangam.integration.connector.SecretResolver;

/**
 * Keeps a found secret for a short time (M-075), so a connector that reads its credential on every call (SL-18, SL-61)
 * does not call the secret provider on every call. Only found values are kept: a reference that was not found, and a
 * provider failure, are asked again next time. A rotated secret is picked up when its entry expires (default five
 * minutes). At most {@value #MAX_ENTRIES} entries; when full, expired entries are dropped first, then everything.
 */
public final class CachingSecretResolver implements SecretResolver {

    public static final int MAX_ENTRIES = 10_000;

    private record Entry(String value, Instant expiresAt) {
    }

    private final SecretResolver delegate;
    private final Duration ttl;
    private final Clock clock;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public CachingSecretResolver(SecretResolver delegate, Duration ttl, Clock clock) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.ttl = ttl == null || ttl.isNegative() ? Duration.ofMinutes(5) : ttl;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public Optional<String> resolve(String reference) {
        if (ttl.isZero()) {
            return delegate.resolve(reference);
        }
        Instant now = clock.instant();
        Entry e = cache.get(reference);
        if (e != null && now.isBefore(e.expiresAt())) {
            return Optional.of(e.value());
        }
        Optional<String> v = delegate.resolve(reference);
        if (v != null && v.isPresent()) {
            if (cache.size() >= MAX_ENTRIES) {
                cache.values().removeIf(x -> !now.isBefore(x.expiresAt()));
                if (cache.size() >= MAX_ENTRIES) {
                    cache.clear();
                }
            }
            cache.put(reference, new Entry(v.get(), now.plus(ttl)));
            return v;
        }
        cache.remove(reference);
        return Optional.empty();
    }

    /** Drops every kept value (after a known rotation). */
    public void clear() {
        cache.clear();
    }
}

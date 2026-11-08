// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.quota;

import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.core.quota.QuotaLimit;
import org.sangam.core.quota.TenantQuotaExceededException;
import org.sangam.core.quota.TenantQuotas;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.security.tenant.TenantContext;

import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Database-backed per-tenant quotas (SL-43, R28).
 *
 * <p><b>Configuration only.</b> Effective limit = the tenant's override in {@code SEC_TENANT.quotas} (set at
 * onboarding, {@code tenant.quotas}, or by {@code TenantAdminService.configure}) else the deployment default
 * ({@code sangam.quotas.defaults.<key>}) else the built-in {@link QuotaLimit#safeDefault()}. A value below zero
 * disables that limit. Overrides are cached for {@code overrideTtl} (default 30 s), so a change reaches every node
 * within that time.
 *
 * <p><b>Cluster-safe counting: fixed windows in the shared database.</b> One row per (tenant, limit, window start)
 * in {@code SEC_TENANT_QUOTA_WINDOW}; admission is a single conditional
 * {@code UPDATE ... SET used = used + n WHERE ... AND used + n <= limit}, which the database serialises on the row, so
 * the sum admitted by all nodes never exceeds the limit (no over-admission, no lost updates). The first request of a
 * window inserts the row (a lost insert race falls back to the update). Chosen over a node-local token bucket, which
 * multiplies the limit by the node count, and over Redis, which the air-gapped and DB-per-tenant deployments do not
 * have; the cost is one short row update per counted request on a per-tenant hot row, which the limits themselves
 * bound. The same approach backs Dataverse's per-window service protection counters (requests per 300 s sliding
 * window) and Salesforce's rolling API request allocation; a fixed window lets a burst of up to 2x the limit straddle
 * a boundary, which is acceptable for a protection limit and documented.
 *
 * <p><b>Audit.</b> The first refusal of each (tenant, limit, window) appends {@code TENANT_QUOTA_EXCEEDED} to the
 * tenant chain {@code quota:<tenant>} in the quota's own transaction (it survives the rollback of the refused
 * command; one event per window, not per refused call, so a flood cannot flood the audit chain). Size refusals are
 * deduplicated per minute the same way.
 *
 * <p><b>Connections (SL-43-DEADLOCK).</b> Quotas are checked from inside units of work that already hold a pool
 * connection (an engine command, a REST transaction). Taking a second connection from the same pool there is a
 * pool-locking deadlock: with as many concurrent callers as pool slots, every caller holds one connection and waits for
 * a second one. So:
 * <ul>
 *   <li>override reads run on the caller's own connection when it has one ({@code ambient}; read committed, read
 *       only), and otherwise through {@code tx}, single-flight per tenant;</li>
 *   <li>the counting {@code UPDATE} and the refusal audit must commit on their own (a counted call that rolls back is
 *       still counted, the audit survives the refused command's rollback, and the per-tenant window row must not stay
 *       locked until the caller commits), so they run through {@code tx}, which must be a <b>leaf</b> pool: a pool
 *       separate from the one callers hold, whose connections are never held while any other connection is
 *       requested. A wait for a leaf connection can then never be part of a wait cycle. The server wires a dedicated
 *       small quota pool ({@code QuotaWiring}).</li>
 * </ul>
 */
public final class JdbcTenantQuotas implements TenantQuotas {

    /** Tenant overrides by tenant id (e.g. {@code SEC_TENANT.quotas}); null or empty = none. */
    @FunctionalInterface
    public interface OverrideSource {
        Map<String, Object> overrides(String tenantId);
    }

    /** Tenant overrides read on a given connection (the caller's or a leaf connection). */
    @FunctionalInterface
    public interface ConnectionOverrideSource {
        Map<String, Object> overrides(Connection c, String tenantId);
    }

    /** The connection of the caller's current transaction, or null when the caller holds none. */
    @FunctionalInterface
    public interface AmbientConnection {
        Connection current();

        AmbientConnection NONE = () -> null;
    }

    private record Flight(long generation, CompletableFuture<Map<String, Object>> result) {
    }

    public static final String AUDIT_EVENT = "TENANT_QUOTA_EXCEEDED";
    private static final long SIZE_AUDIT_WINDOW_MS = 60_000;
    private static final long KEEP_WINDOWS_MS = Duration.ofHours(1).toMillis();

    private record Cached(Map<String, Object> overrides, long loadedAt) {
    }

    private final Dialect dialect;
    private final Transactions tx;
    private final ConnectionOverrideSource source;
    private final AmbientConnection ambient;
    private final Map<QuotaLimit, Long> defaults;
    private final AuditChain audit;
    private final Clock clock;
    private final long overrideTtlMs;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> generations = new ConcurrentHashMap<>();
    private final Map<String, Flight> inFlight = new ConcurrentHashMap<>();

    /** Overrides from a source that needs no connection (tests, static configuration). */
    public JdbcTenantQuotas(Dialect dialect, Transactions tx, OverrideSource source, Map<QuotaLimit, Long> defaults,
                            AuditChain auditOrNull, Clock clock, Duration overrideTtl) {
        this(dialect, tx, source == null ? null : (c, t) -> source.overrides(t), AmbientConnection.NONE, defaults,
                auditOrNull, clock, overrideTtl);
    }

    /**
     * @param tx      leaf transactions for counting, refusal audit and override reads of callers without a connection
     *                (see the class comment: never the pool the callers hold)
     * @param ambient the caller's current connection, on which overrides are read when present
     */
    public JdbcTenantQuotas(Dialect dialect, Transactions tx, ConnectionOverrideSource source, AmbientConnection ambient,
                            Map<QuotaLimit, Long> defaults, AuditChain auditOrNull, Clock clock, Duration overrideTtl) {
        this.dialect = dialect;
        this.tx = tx;
        this.source = source == null ? (c, t) -> Map.of() : source;
        this.ambient = ambient == null ? AmbientConnection.NONE : ambient;
        this.defaults = new EnumMap<>(QuotaLimit.class);
        for (QuotaLimit l : QuotaLimit.values()) {
            Long v = defaults == null ? null : defaults.get(l);
            this.defaults.put(l, v == null ? l.safeDefault() : v);
        }
        this.audit = auditOrNull;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.overrideTtlMs = overrideTtl == null ? 30_000 : overrideTtl.toMillis();
    }

    /** Deployment defaults from flat properties ({@code key -> value}), e.g. {@code sangam.quotas.defaults.*}. */
    public static Map<QuotaLimit, Long> defaultsFrom(Function<String, String> property) {
        Map<QuotaLimit, Long> m = new EnumMap<>(QuotaLimit.class);
        for (QuotaLimit l : QuotaLimit.values()) {
            String v = property.apply(l.key());
            if (v != null && !v.isBlank()) {
                m.put(l, parse(l, v));
            }
        }
        return m;
    }

    @Override
    public long limit(String tenantId, QuotaLimit limit) {
        Object v = overrides(tenantId).get(limit.key());
        return v == null ? defaults.get(limit) : parse(limit, v);
    }

    @Override
    public void acquire(String tenantId, QuotaLimit limit, long permits) {
        if (!limit.isRate()) {
            throw new IllegalArgumentException(limit + " is not a rate limit");
        }
        if (tenantId == null || tenantId.isBlank() || permits <= 0) {
            return;
        }
        long max = limit(tenantId, limit);
        if (max < 0) {
            return;
        }
        long now = clock.millis();
        long windowMs = limit.window().toMillis();
        long start = now - Math.floorMod(now, windowMs);
        int retryAfter = (int) Math.max(1, (start + windowMs - now + 999) / 1000);
        boolean admitted = permits <= max && tx.inTransaction(c -> admit(c, tenantId, limit, start, permits, max));
        if (!admitted) {
            refuse(tenantId, limit, start, max, permits, retryAfter);
        }
    }

    @Override
    public void checkSize(String tenantId, QuotaLimit limit, long amount) {
        if (limit.isRate()) {
            throw new IllegalArgumentException(limit + " is a rate limit");
        }
        if (tenantId == null || tenantId.isBlank()) {
            return;
        }
        long max = limit(tenantId, limit);
        if (TenantQuotas.within(max, amount)) {
            return;
        }
        long now = clock.millis();
        refuse(tenantId, limit, now - Math.floorMod(now, SIZE_AUDIT_WINDOW_MS), max, amount, null);
    }

    private boolean admit(java.sql.Connection c, String tenant, QuotaLimit limit, long start, long permits, long max) {
        if (increment(c, tenant, limit, start, permits, max)) {
            return true;
        }
        if (Jdbc.insertIfAbsent(c, dialect, "INSERT INTO SEC_TENANT_QUOTA_WINDOW(tenant_id, quota_name, window_start,"
                + " used, refused) VALUES (?,?,?,?,0)", tenant, limit.key(), start, permits)) {
            // housekeeping on the first request of a window: old windows of this tenant and limit
            Jdbc.update(c, "DELETE FROM SEC_TENANT_QUOTA_WINDOW WHERE tenant_id=? AND quota_name=? AND window_start<?",
                    tenant, limit.key(), start - KEEP_WINDOWS_MS);
            return true;
        }
        // another node created the row first: its count now decides
        return increment(c, tenant, limit, start, permits, max);
    }

    private static boolean increment(java.sql.Connection c, String tenant, QuotaLimit limit, long start, long permits,
                                     long max) {
        return Jdbc.update(c, "UPDATE SEC_TENANT_QUOTA_WINDOW SET used=used+? WHERE tenant_id=? AND quota_name=?"
                + " AND window_start=? AND used+?<=?", permits, tenant, limit.key(), start, permits, max) == 1;
    }

    private void refuse(String tenant, QuotaLimit limit, long windowStart, long max, long requested, Integer retryAfter) {
        TenantQuotaExceededException e = new TenantQuotaExceededException(tenant, limit, max, requested, retryAfter);
        if (audit != null) {
            tx.inTransaction(c -> {
                Jdbc.insertIfAbsent(c, dialect, "INSERT INTO SEC_TENANT_QUOTA_WINDOW(tenant_id, quota_name, window_start,"
                        + " used, refused) VALUES (?,?,?,0,0)", tenant, limit.key(), windowStart);
                if (Jdbc.update(c, "UPDATE SEC_TENANT_QUOTA_WINDOW SET refused=1 WHERE tenant_id=? AND quota_name=?"
                        + " AND window_start=? AND refused=0", tenant, limit.key(), windowStart) == 1) {
                    Map<String, Object> payload = new LinkedHashMap<>(e.details());
                    payload.put("code", TenantQuotaExceededException.CODE);
                    payload.put("window_start_ms", windowStart);
                    TenantContext.current().map(SecurityIdentity::subject).ifPresent(s -> payload.put("subject", s));
                    audit.append(c, tenant, "quota:" + tenant, AUDIT_EVENT, payload);
                }
                return null;
            });
        }
        throw e;
    }

    private Map<String, Object> overrides(String tenantId) {
        if (tenantId == null) {
            return Map.of();
        }
        long now = clock.millis();
        Cached cached = cache.get(tenantId);
        if (cached != null && now - cached.loadedAt() < overrideTtlMs) {
            return cached.overrides();
        }
        Connection own = ambient.current();
        if (own != null) {
            // on the caller's connection: no second connection, no waiting for another thread
            long gen = generation(tenantId);
            return publish(tenantId, gen, now, source.overrides(own, tenantId));
        }
        return loadSingleFlight(tenantId, now);
    }

    /** One leaf read per tenant at a time; callers arriving meanwhile share it unless an invalidation intervened. */
    private Map<String, Object> loadSingleFlight(String tenantId, long now) {
        while (true) {
            long gen = generation(tenantId);
            Flight mine = new Flight(gen, new CompletableFuture<>());
            Flight running = inFlight.putIfAbsent(tenantId, mine);
            if (running != null) {
                if (running.generation() != gen) {
                    // the running read started before a configure: it may return the old values
                    running.result().handle((v, e) -> null).join();
                    continue;
                }
                return join(running.result());
            }
            try {
                Map<String, Object> o = publish(tenantId, gen, now, tx.inTransaction(c -> source.overrides(c, tenantId)));
                mine.result().complete(o);
                return o;
            } catch (RuntimeException | Error e) {
                mine.result().completeExceptionally(e);
                throw e;
            } finally {
                inFlight.remove(tenantId, mine);
            }
        }
    }

    private static Map<String, Object> join(CompletableFuture<Map<String, Object>> f) {
        try {
            return f.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException r) {
                throw r;
            }
            if (e.getCause() instanceof Error err) {
                throw err;
            }
            throw e;
        }
    }

    /** Caches a read unless the tenant was invalidated since the read began (it may predate the change). */
    private Map<String, Object> publish(String tenantId, long gen, long loadedAt, Map<String, Object> read) {
        Map<String, Object> o = read == null ? Map.of() : Map.copyOf(withoutNulls(read));
        cache.compute(tenantId, (k, old) -> generation(tenantId) != gen ? old
                : old != null && old.loadedAt() > loadedAt ? old : new Cached(o, loadedAt));
        return o;
    }

    private long generation(String tenantId) {
        return generations.computeIfAbsent(tenantId, k -> new AtomicLong()).get();
    }

    /**
     * Drops the cached overrides of a tenant; call it after a configure commits on this node (other nodes see the
     * change within {@code overrideTtl}). A read already running when this is called is not cached.
     */
    @Override
    public void invalidate(String tenantId) {
        if (tenantId == null) {
            return;
        }
        generations.computeIfAbsent(tenantId, k -> new AtomicLong()).incrementAndGet();
        cache.remove(tenantId);
    }

    private static Map<String, Object> withoutNulls(Map<String, Object> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> {
            if (k != null && v != null) {
                out.put(k, v);
            }
        });
        return out;
    }

    /** A quota value: an integer, {@code -1} (or any negative) for no limit. */
    public static long parse(QuotaLimit limit, Object v) {
        if (v instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) {
            return n.longValue();
        }
        try {
            return Long.parseLong(v.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("quota " + limit.key() + " must be an integer (negative = unlimited): " + v);
        }
    }

    /** Validates the quota entries this class knows; other keys are left to their owners. */
    public static void validate(Map<String, Object> quotas) {
        if (quotas == null) {
            return;
        }
        quotas.forEach((k, v) -> QuotaLimit.of(k).ifPresent(l -> {
            if (v != null) {
                parse(l, v);
            }
        }));
    }
}

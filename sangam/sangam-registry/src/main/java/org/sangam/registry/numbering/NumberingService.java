// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry.numbering;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.registry.RegistryContext;
import org.sangam.registry.RegistryException;
import org.sangam.registry.RegistryPorts;
import org.sangam.registry.support.Db;
import org.sangam.registry.support.Json;
import org.sangam.registry.support.Maps;
import org.sangam.registry.support.RegistryAudit;

import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Number allocation (N09), concurrency-safe on every Tier-1 dialect.
 *
 * <p><b>Gap-free series.</b> One counter row per (tenant, series, scope, period) in {@code REG_NUMBER_COUNTER}. The
 * allocation runs in the caller's business transaction: the counter row is created if absent (PostgreSQL
 * {@code ON CONFLICT DO NOTHING}; Oracle/SQL Server insert and duplicate-key tolerance; MySQL lock-first then
 * {@code ON DUPLICATE KEY UPDATE}, which avoids the shared-lock deadlock of a failed insert), then locked
 * ({@link Dialect#lockingSelect}: {@code FOR UPDATE}, Oracle {@code FOR UPDATE WAIT 2}, SQL Server
 * {@code UPDLOCK, ROWLOCK}), incremented and the number recorded in {@code REG_NUMBER_ALLOCATION}. If the business
 * transaction rolls back, the counter increment rolls back with it, so no number is lost; once committed a number is
 * never reused (a cancelled use is VOIDED with its reason, which explains the gap). The lock is held until commit,
 * so callers allocate as late as possible in the transaction, after their L2 root lock (VI.1 rule 1); the wait time
 * is reported to {@link RegistryPorts.MetricsPort#counterLockWait}. Scoping series per office or unit spreads the hot
 * row under high load.
 *
 * <p><b>Unique-only series.</b> No lock in the business transaction: numbers come from an in-memory block reserved
 * in a separate short transaction ({@code blockSize} values per reservation), so throughput is bounded by the block
 * size, not by a row lock. Numbers are unique across nodes but may skip (unused block values on shutdown).
 */
public final class NumberingService {

    /** One allocated number. */
    public record Allocation(String seriesKey, String scopeKey, String periodKey, long seq, String formatted,
                             boolean gapFree) {
        public Map<String, Object> toMap() {
            return Maps.of("seriesKey", seriesKey, "scope", scopeKey, "period", periodKey, "seq", seq,
                    "number", formatted, "gapFree", gapFree);
        }
    }

    /** One recorded gap-free number. */
    public record AllocationRow(String seriesKey, String scopeKey, String periodKey, long seq, String formatted,
                                String status, String refType, String refId, Instant allocatedAt, String allocatedBy,
                                String voidReason) {
    }

    /** Continuity report of one counter. */
    public record GapReport(String seriesKey, String scopeKey, String periodKey, long issued, long used, long voided,
                            long allocatedOnly, long missing, boolean closed) {
    }

    private record Block(long next, long end) {
    }

    private final Dialect dialect;
    private final RegistryAudit audit;
    private final RegistryPorts ports;
    private final Transactions sideTransactions;
    private final Map<String, Block> blocks = new ConcurrentHashMap<>();

    /**
     * @param sideTransactions a transaction source independent of the caller's (a plain pool), used only for
     *                         unique-only block reservations; null makes unique-only series lock like gap-free ones
     */
    public NumberingService(Dialect dialect, RegistryAudit audit, RegistryPorts ports, Transactions sideTransactions) {
        this.dialect = dialect;
        this.audit = audit;
        this.ports = ports;
        this.sideTransactions = sideTransactions;
    }

    // ------------------------------------------------------------------------------------------ definitions

    public NumberSeriesDefinition define(Connection c, RegistryContext ctx, Map<String, Object> definition) {
        NumberSeriesDefinition def = NumberSeriesDefinition.from(definition);
        Instant now = Db.now(c, dialect);
        int updated = Jdbc.update(c, "UPDATE REG_NUMBER_SERIES SET version=version+1, definition_json=?, updated_at=?,"
                + " updated_by=? WHERE tenant_id=? AND series_key=?", Json.write(def.toMap()), now, ctx.actor(), ctx.tenant(), def.seriesKey());
        if (updated == 0) {
            boolean inserted = Jdbc.insertIfAbsent(c, dialect, "INSERT INTO REG_NUMBER_SERIES(tenant_id, series_key, version,"
                            + " definition_json, updated_at, updated_by) VALUES (?,?,?,?,?,?)",
                    ctx.tenant(), def.seriesKey(), 1, Json.write(def.toMap()), now, ctx.actor());
            if (!inserted) {
                throw RegistryException.conflict("series " + def.seriesKey() + " was defined concurrently; retry");
            }
        }
        audit.record(c, ctx, seriesRoot(ctx.tenant(), def.seriesKey()), "NUMBER_SERIES_DEFINED", def.toMap());
        return def;
    }

    public NumberSeriesDefinition series(Connection c, String tenant, String seriesKey) {
        return Jdbc.one(c, "SELECT definition_json FROM REG_NUMBER_SERIES WHERE tenant_id=? AND series_key=?",
                        rs -> NumberSeriesDefinition.from(Json.map(rs.getString(1))), tenant, seriesKey)
                .orElseThrow(() -> RegistryException.notFound("number series", seriesKey));
    }

    public List<NumberSeriesDefinition> listSeries(Connection c, String tenant) {
        return Jdbc.query(c, "SELECT definition_json FROM REG_NUMBER_SERIES WHERE tenant_id=? ORDER BY series_key",
                rs -> NumberSeriesDefinition.from(Json.map(rs.getString(1))), tenant);
    }

    // ------------------------------------------------------------------------------------------- allocation

    /**
     * Allocates the next number.
     *
     * @param scope   series scope (office, unit, register); null or blank = the whole tenant
     * @param date    business date that selects the period (null = today in the tenant zone)
     * @param attrs   values for {@code {attr:name}} tokens
     * @param refType what the number is used for (object, register entry, work order), may be null
     * @param refId   the user's id, may be null (then the number stays ALLOCATED until {@link #markUsed})
     */
    public Allocation allocate(Connection c, RegistryContext ctx, String seriesKey, String scope, LocalDate date,
                               Map<String, ?> attrs, String refType, String refId) {
        NumberSeriesDefinition def = series(c, ctx.tenant(), seriesKey);
        LocalDate d = date == null ? LocalDate.now(ctx.zone()) : date;
        String scopeKey = scope == null || scope.isBlank() ? "-" : scope;
        String period = def.periodKey(d);
        if (def.gapFree() || sideTransactions == null) {
            long seq = lockAndIncrement(c, ctx.tenant(), def, scopeKey, period);
            String formatted = def.format(seq, "-".equals(scopeKey) ? "" : scopeKey, d, attrs);
            if (def.gapFree()) {
                Instant now = Db.now(c, dialect);
                try {
                    Jdbc.update(c, "INSERT INTO REG_NUMBER_ALLOCATION(tenant_id, series_key, scope_key, period_key, seq_value,"
                                    + " formatted, status, ref_type, ref_id, allocated_at, allocated_by) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                            ctx.tenant(), seriesKey, scopeKey, period, seq, formatted, refId == null ? "ALLOCATED" : "USED",
                            refType, refId, now, ctx.actor());
                } catch (Jdbc.LayerSqlException e) {
                    if (dialect.isUniqueViolation(e.sql())) {
                        throw RegistryException.conflict("number " + formatted + " already exists in series " + seriesKey
                                + " (the pattern must distinguish scope and period)");
                    }
                    throw e;
                }
            }
            return new Allocation(seriesKey, scopeKey, period, seq, formatted, def.gapFree());
        }
        long seq = fromBlock(ctx.tenant(), def, scopeKey, period);
        return new Allocation(seriesKey, scopeKey, period, seq, def.format(seq, "-".equals(scopeKey) ? "" : scopeKey, d, attrs), false);
    }

    private long lockAndIncrement(Connection c, String tenant, NumberSeriesDefinition def, String scopeKey, String period) {
        long t0 = System.nanoTime();
        Counter row = lockCounter(c, tenant, def, scopeKey, period);
        ports.metrics().counterLockWait(tenant, def.seriesKey(), System.nanoTime() - t0);
        if (row.closed) {
            throw new RegistryException(RegistryException.Code.CLOSED,
                    "series " + def.seriesKey() + " is closed for " + scopeKey + " / " + period);
        }
        long seq = row.next;
        if (def.maxValue() != null && seq > def.maxValue()) {
            throw RegistryException.state("series " + def.seriesKey() + " exhausted at " + def.maxValue());
        }
        Jdbc.update(c, "UPDATE REG_NUMBER_COUNTER SET next_value=?, updated_at=" + dialect.now()
                        + " WHERE tenant_id=? AND series_key=? AND scope_key=? AND period_key=?",
                seq + 1, tenant, def.seriesKey(), scopeKey, period);
        return seq;
    }

    private record Counter(long next, boolean closed) {
    }

    private Counter lockCounter(Connection c, String tenant, NumberSeriesDefinition def, String scopeKey, String period) {
        String select = dialect.lockingSelect("next_value, closed_flag", "REG_NUMBER_COUNTER",
                "tenant_id=? AND series_key=? AND scope_key=? AND period_key=?");
        Jdbc.RowMapper<Counter> map = rs -> new Counter(rs.getLong(1), rs.getInt(2) == 1);
        if (dialect == Dialect.MYSQL) {
            var row = Jdbc.one(c, select, map, tenant, def.seriesKey(), scopeKey, period);
            if (row.isPresent()) {
                return row.get();
            }
            Jdbc.update(c, "INSERT INTO REG_NUMBER_COUNTER(tenant_id, series_key, scope_key, period_key, next_value, closed_flag,"
                            + " updated_at) VALUES (?,?,?,?,?,0," + dialect.now() + ") ON DUPLICATE KEY UPDATE next_value = next_value",
                    tenant, def.seriesKey(), scopeKey, period, def.start());
            return Jdbc.one(c, select, map, tenant, def.seriesKey(), scopeKey, period).orElseThrow();
        }
        Jdbc.insertIfAbsent(c, dialect, "INSERT INTO REG_NUMBER_COUNTER(tenant_id, series_key, scope_key, period_key,"
                        + " next_value, closed_flag, updated_at) VALUES (?,?,?,?,?,0," + dialect.now() + ")",
                tenant, def.seriesKey(), scopeKey, period, def.start());
        return Jdbc.one(c, select, map, tenant, def.seriesKey(), scopeKey, period).orElseThrow();
    }

    private long fromBlock(String tenant, NumberSeriesDefinition def, String scopeKey, String period) {
        String key = tenant + "|" + def.seriesKey() + "|" + scopeKey + "|" + period;
        synchronized (blocks.computeIfAbsent(key + "#lock", k -> new Block(0, 0))) {
            Block b = blocks.get(key);
            if (b == null || b.next() >= b.end()) {
                int size = Math.max(1, def.blockSize());
                long first = sideTransactions.inTransaction(c -> {
                    long start = lockCounter(c, tenant, def, scopeKey, period).next;
                    Jdbc.update(c, "UPDATE REG_NUMBER_COUNTER SET next_value=?, updated_at=" + dialect.now()
                                    + " WHERE tenant_id=? AND series_key=? AND scope_key=? AND period_key=?",
                            start + size, tenant, def.seriesKey(), scopeKey, period);
                    return start;
                });
                b = new Block(first, first + size);
            }
            if (def.maxValue() != null && b.next() > def.maxValue()) {
                throw RegistryException.state("series " + def.seriesKey() + " exhausted at " + def.maxValue());
            }
            blocks.put(key, new Block(b.next() + 1, b.end()));
            return b.next();
        }
    }

    /** Records the use of an ALLOCATED gap-free number. */
    public void markUsed(Connection c, RegistryContext ctx, String seriesKey, String formatted, String refType, String refId) {
        int n = Jdbc.update(c, "UPDATE REG_NUMBER_ALLOCATION SET status='USED', ref_type=?, ref_id=? WHERE tenant_id=?"
                + " AND series_key=? AND formatted=? AND status='ALLOCATED'", refType, refId, ctx.tenant(), seriesKey, formatted);
        if (n == 0) {
            throw RegistryException.state("number " + formatted + " is not an unused allocation of " + seriesKey);
        }
    }

    /**
     * Voids a gap-free number (spoilt form, cancelled before use, entered in error). The row stays, with the reason,
     * so the sequence remains continuous and every gap is explained; a voided number is never reissued.
     */
    public void voidNumber(Connection c, RegistryContext ctx, String seriesKey, String formatted, String reason) {
        if (reason == null || reason.isBlank()) {
            throw RegistryException.invalid("a reason is required to void a number");
        }
        int n = Jdbc.update(c, "UPDATE REG_NUMBER_ALLOCATION SET status='VOIDED', void_reason=?, voided_by=? WHERE tenant_id=?"
                + " AND series_key=? AND formatted=? AND status<>'VOIDED'", reason, ctx.actor(), ctx.tenant(), seriesKey, formatted);
        if (n == 0) {
            throw RegistryException.notFound("allocated number", formatted);
        }
        audit.record(c, ctx, seriesRoot(ctx.tenant(), seriesKey), "NUMBER_VOIDED",
                Maps.of("seriesKey", seriesKey, "number", formatted, "reason", reason));
    }

    /** Closes a counter period (year-end freeze): no further numbers in that scope and period. */
    public void closePeriod(Connection c, RegistryContext ctx, String seriesKey, String scope, String periodKey) {
        NumberSeriesDefinition def = series(c, ctx.tenant(), seriesKey);
        String scopeKey = scope == null || scope.isBlank() ? "-" : scope;
        lockCounter(c, ctx.tenant(), def, scopeKey, periodKey);
        Jdbc.update(c, "UPDATE REG_NUMBER_COUNTER SET closed_flag=1, updated_at=" + dialect.now()
                + " WHERE tenant_id=? AND series_key=? AND scope_key=? AND period_key=?", ctx.tenant(), seriesKey, scopeKey, periodKey);
        audit.record(c, ctx, seriesRoot(ctx.tenant(), seriesKey), "NUMBER_PERIOD_CLOSED",
                Maps.of("seriesKey", seriesKey, "scope", scopeKey, "period", periodKey));
    }

    /** Continuity check of a gap-free counter: every value from start to next-1 must have a row. */
    public GapReport gapReport(Connection c, RegistryContext ctx, String seriesKey, String scope, String periodKey) {
        NumberSeriesDefinition def = series(c, ctx.tenant(), seriesKey);
        String scopeKey = scope == null || scope.isBlank() ? "-" : scope;
        var counter = Jdbc.one(c, "SELECT next_value, closed_flag FROM REG_NUMBER_COUNTER WHERE tenant_id=? AND series_key=?"
                        + " AND scope_key=? AND period_key=?", rs -> new Counter(rs.getLong(1), rs.getInt(2) == 1),
                ctx.tenant(), seriesKey, scopeKey, periodKey);
        if (counter.isEmpty()) {
            return new GapReport(seriesKey, scopeKey, periodKey, 0, 0, 0, 0, 0, false);
        }
        long issued = counter.get().next() - def.start();
        long[] counts = Jdbc.one(c, "SELECT SUM(CASE WHEN status='USED' THEN 1 ELSE 0 END), SUM(CASE WHEN status='VOIDED' THEN 1 ELSE 0 END),"
                        + " SUM(CASE WHEN status='ALLOCATED' THEN 1 ELSE 0 END), COUNT(*) FROM REG_NUMBER_ALLOCATION"
                        + " WHERE tenant_id=? AND series_key=? AND scope_key=? AND period_key=?",
                rs -> new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)},
                ctx.tenant(), seriesKey, scopeKey, periodKey).orElse(new long[4]);
        long missing = def.gapFree() ? Math.max(0, issued - counts[3]) : 0;
        return new GapReport(seriesKey, scopeKey, periodKey, issued, counts[0], counts[1], counts[2], missing,
                counter.get().closed());
    }

    public List<AllocationRow> allocations(Connection c, RegistryContext ctx, String seriesKey, String scope,
                                           String periodKey, int page, int size) {
        String scopeKey = scope == null || scope.isBlank() ? "-" : scope;
        return Db.page(c, dialect, "SELECT a.series_key, a.scope_key, a.period_key, a.seq_value, a.formatted, a.status,"
                        + " a.ref_type, a.ref_id, a.allocated_at, a.allocated_by, a.void_reason FROM REG_NUMBER_ALLOCATION a",
                "a.tenant_id=? AND a.series_key=? AND a.scope_key=? AND a.period_key=?",
                List.of(ctx.tenant(), seriesKey, scopeKey, periodKey), null, "a.seq_value", page, size,
                rs -> new AllocationRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getString(5),
                        rs.getString(6), rs.getString(7), rs.getString(8), Jdbc.instant(rs, "allocated_at"), rs.getString(10),
                        rs.getString(11)));
    }

    static String seriesRoot(String tenant, String seriesKey) {
        return "series:" + org.sangam.core.layer.Hashes.uuidV5(java.util.UUID.fromString("0b7f4a1e-2f53-4f0e-8a51-6c1d2e3f4a5b"),
                tenant + "|" + seriesKey);
    }
}

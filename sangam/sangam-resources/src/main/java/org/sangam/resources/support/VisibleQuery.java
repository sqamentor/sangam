// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources.support;

import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.SqlPredicate;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.resources.ResourcesContext;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The single read path of the module (VI.4 rule 3, A-3, A-16), shaped like sangam-security's
 * {@code AuthorizedQueryBuilder}: list, capped count, page and get-by-id all AND the same visibility predicate
 * ({@link ResourcesContext#visibility()}) and the identity's tenant into the statement. Nothing is filtered after the
 * query, so a hidden row never appears in a list, a count or a single read.
 *
 * <pre>{@code
 * List<Booking> rows = VisibleQuery.on(ctx, actor, RT_BOOKING, P_READ, "RES_BOOKING", "b")
 *         .select(COLS).where("b.resource_id=?", id).orderBy("b.starts_at").list(c, mapper, offset, limit);
 * }</pre>
 */
public final class VisibleQuery {

    public static final int MAX_PAGE_SIZE = 1000;
    public static final int DEFAULT_COUNT_CAP = 1000;

    public record CappedCount(long value, boolean capped) {
    }

    public record Page<T>(List<T> items, int offset, int limit, CappedCount total) {
    }

    private final ResourcesContext ctx;
    private final SecurityIdentity actor;
    private final String resourceType;
    private final String permission;
    private final String table;
    private final String alias;
    private String columns;
    private final StringBuilder joins = new StringBuilder();
    private final StringBuilder where = new StringBuilder();
    private final List<Object> params = new ArrayList<>();
    private String orderBy;

    private VisibleQuery(ResourcesContext ctx, SecurityIdentity actor, String resourceType, String permission, String table,
                         String alias) {
        this.ctx = ctx;
        this.actor = actor;
        this.resourceType = resourceType;
        this.permission = permission;
        this.table = table;
        this.alias = alias;
        this.columns = alias + ".*";
    }

    public static VisibleQuery on(ResourcesContext ctx, SecurityIdentity actor, String resourceType, String permission,
                                  String table, String alias) {
        return new VisibleQuery(ctx, actor, resourceType, permission, table, alias);
    }

    /** Columns, unqualified names are qualified with the alias ({@code "a, b"} → {@code "x.a, x.b"}). */
    public VisibleQuery select(String cols) {
        StringBuilder sb = new StringBuilder();
        for (String col : cols.split(",")) {
            String s = col.trim();
            if (s.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(s.contains(".") || s.contains("(") ? s : alias + "." + s);
        }
        this.columns = sb.toString();
        return this;
    }

    public VisibleQuery join(String joinSql) {
        joins.append(' ').append(joinSql);
        return this;
    }

    public VisibleQuery where(String sql, Object... binds) {
        where.append(" AND (").append(sql).append(')');
        params.addAll(java.util.Arrays.asList(binds));
        return this;
    }

    public VisibleQuery orderBy(String order) {
        this.orderBy = order;
        return this;
    }

    private String fromWhere(Connection c, List<Object> outParams) {
        SqlPredicate p = ctx.visibility().visible(c, actor, resourceType, permission, alias);
        outParams.add(actor.tenantId());
        outParams.addAll(params);
        outParams.addAll(p.params());
        return " FROM " + table + " " + alias + joins + " WHERE " + alias + ".tenant_id=?" + where + " AND (" + p.sql() + ")";
    }

    public <T> List<T> list(Connection c, Jdbc.RowMapper<T> mapper, int offset, int limit) {
        List<Object> p = new ArrayList<>();
        String sql = "SELECT " + columns + fromWhere(c, p) + " ORDER BY " + (orderBy == null ? alias + ".tenant_id" : orderBy);
        sql = ctx.dialect().page(sql, Math.max(0, offset), limit <= 0 ? 50 : Math.min(limit, MAX_PAGE_SIZE));
        return Jdbc.query(c, sql, mapper, p.toArray());
    }

    /** All visible rows without paging (bounded by {@code hardCap}; callers use it for per-parent child lists). */
    public <T> List<T> all(Connection c, Jdbc.RowMapper<T> mapper, int hardCap) {
        return list(c, mapper, 0, Math.min(Math.max(hardCap, 1), MAX_PAGE_SIZE));
    }

    /** Counts at most {@code cap + 1} visible rows: "more than {@code cap}" when capped (A-16, R65). */
    public CappedCount count(Connection c, int cap) {
        int n = (cap <= 0 ? DEFAULT_COUNT_CAP : cap) + 1;
        List<Object> p = new ArrayList<>();
        String inner = ctx.dialect().page("SELECT 1 AS x" + fromWhere(c, p) + " ORDER BY " + alias + ".tenant_id", 0, n);
        long v = Jdbc.one(c, "SELECT COUNT(*) FROM (" + inner + ") q", rs -> rs.getLong(1), p.toArray()).orElse(0L);
        return v > n - 1 ? new CappedCount(n - 1, true) : new CappedCount(v, false);
    }

    public <T> Page<T> page(Connection c, Jdbc.RowMapper<T> mapper, int offset, int limit, int countCap) {
        List<T> items = list(c, mapper, offset, limit);
        return new Page<>(items, offset, limit, count(c, countCap));
    }

    /** Get-by-id through the same predicate; empty when the row does not exist <i>or</i> is not visible. */
    public <T> Optional<T> byId(Connection c, String idColumn, Object id, Jdbc.RowMapper<T> mapper) {
        where(alias + "." + idColumn + "=?", id);
        List<Object> p = new ArrayList<>();
        String sql = "SELECT " + columns + fromWhere(c, p);
        List<T> rows = Jdbc.query(c, sql, mapper, p.toArray());
        return rows.stream().findFirst();
    }

    /** {@link #byId} or NOT_FOUND (a hidden row is reported exactly like a missing one). */
    public <T> T require(Connection c, String idColumn, Object id, Jdbc.RowMapper<T> mapper, String what) {
        return byId(c, idColumn, id, mapper).orElseThrow(() -> ResourcesException.notFound(what, String.valueOf(id)));
    }
}

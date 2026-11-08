// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.policy;

import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.SqlPredicate;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.security.tenant.TenantGuard;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The single read path for protected resources (VI.4 rule 3, K2, FLOWABLE-INTO-OPERATON D-15): list, count, search, export, dashboards and
 * get-by-id all go through here, so every read carries the VI.4 predicate and the tenant binding. Results are never
 * filtered after the query (A-3). The REST layer and the console must not call engine queries for protected types
 * directly (to be enforced by an ArchUnit rule in {@code sangam-server}; see README).
 *
 * <pre>
 * var q = builder.query(identity, ResourceRegistry.TASK, Permission.READ_TASK)
 *         .select("r.ID_, r.NAME_, r.CREATE_TIME_")
 *         .where("r.SUSPENSION_STATE_ = ?", 1)
 *         .requestTenant(tenantParam)          // only a filter inside the allowed set (VI.4 rule 6)
 *         .orderBy("r.CREATE_TIME_ DESC, r.ID_");
 * List&lt;Row&gt; page = q.list(c, mapper, 0, 50);
 * CappedCount total = q.count(c, 1000);         // "more than 1000" (A-16)
 * </pre>
 */
public final class AuthorizedQueryBuilder {

    /** A count bounded at {@code cap}: when {@code capped} is true there are more than {@code value} rows (A-16). */
    public record CappedCount(long value, boolean capped) {
    }

    /** A page plus its capped total. */
    public record Page<T>(List<T> items, int offset, int limit, CappedCount total) {
    }

    public static final int DEFAULT_COUNT_CAP = 1000;
    /** R28: {@code queryMaxResultsLimit} 1,000. */
    public static final int MAX_PAGE_SIZE = 1000;

    private final PolicyCompiler compiler;

    public AuthorizedQueryBuilder(PolicyCompiler compiler) {
        this.compiler = compiler;
    }

    public Query query(SecurityIdentity who, String resourceType, Permission permission) {
        return new Query(who, resourceType, permission);
    }

    public final class Query {
        private final SecurityIdentity who;
        private final String resourceType;
        private final Permission permission;
        private String select = "r.*";
        private String joins = "";
        private final List<String> filters = new ArrayList<>();
        private final List<Object> filterParams = new ArrayList<>();
        private String orderBy;
        private String requestTenant;

        private Query(SecurityIdentity who, String resourceType, Permission permission) {
            this.who = Objects.requireNonNull(who);
            this.resourceType = resourceType;
            this.permission = permission;
        }

        /** Select list over alias {@code r} (and any joined aliases). */
        public Query select(String columns) {
            this.select = columns;
            return this;
        }

        /** Extra joins for projections only (never for authorization). */
        public Query join(String joinSql) {
            this.joins = this.joins + " " + joinSql;
            return this;
        }

        public Query where(String sql, Object... params) {
            filters.add("(" + sql + ")");
            filterParams.addAll(List.of(params));
            return this;
        }

        public Query orderBy(String orderBy) {
            this.orderBy = orderBy;
            return this;
        }

        /** A tenant id supplied by the request: a filter inside the allowed set only (VI.4 rule 6, K1). */
        public Query requestTenant(String tenantId) {
            this.requestTenant = tenantId;
            return this;
        }

        private SqlPredicate predicate(Connection c) {
            if (!TenantGuard.requestTenantAllowed(who, requestTenant)) {
                return SqlPredicate.alwaysFalse();
            }
            SqlPredicate p = compiler.visible(c, who, resourceType, permission, "r");
            if (!filters.isEmpty()) {
                p = p.and(new SqlPredicate(String.join(" AND ", filters), filterParams));
            }
            return p;
        }

        private String fromWhere(SqlPredicate p) {
            ResourceDescriptor d = compiler.registry().get(resourceType);
            return "FROM " + d.table() + " r" + joins + " WHERE " + p.sql();
        }

        public <T> List<T> list(Connection c, Jdbc.RowMapper<T> mapper, int offset, int limit) {
            int size = Math.min(limit, MAX_PAGE_SIZE);
            SqlPredicate p = predicate(c);
            ResourceDescriptor d = compiler.registry().get(resourceType);
            String order = orderBy != null ? orderBy : "r." + d.idColumn();
            String sql = compiler.dialect().page("SELECT " + select + " " + fromWhere(p) + " ORDER BY " + order, offset, size);
            return Jdbc.query(c, sql, mapper, p.params().toArray());
        }

        public CappedCount count(Connection c, int cap) {
            SqlPredicate p = predicate(c);
            long n = Jdbc.one(c, compiler.dialect().cappedCount(fromWhere(p), cap), rs -> rs.getLong(1), p.params().toArray())
                    .orElse(0L);
            return n > cap ? new CappedCount(cap, true) : new CappedCount(n, false);
        }

        public <T> Page<T> page(Connection c, Jdbc.RowMapper<T> mapper, int offset, int limit, int countCap) {
            return new Page<>(list(c, mapper, offset, limit), offset, Math.min(limit, MAX_PAGE_SIZE), count(c, countCap));
        }

        /** Get-by-id through the same predicate; empty when invisible (no existence leak: 404, not 403). */
        public <T> java.util.Optional<T> byId(Connection c, String id, Jdbc.RowMapper<T> mapper) {
            ResourceDescriptor d = compiler.registry().get(resourceType);
            where("r." + d.idColumn() + " = ?", id);
            List<T> rows = list(c, mapper, 0, 2);
            return rows.stream().findFirst();
        }
    }
}

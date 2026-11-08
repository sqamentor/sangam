// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.search;

import org.sangam.core.layer.SqlPredicate;
import org.sangam.core.layer.Transactions;
import org.sangam.core.security.SecurityIdentity;

import java.sql.Connection;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Global search over tasks, instances and definitions. Each source runs its own query with the visibility predicate in SQL, capped
 * per type. {@code more} says only that the visible rows exceed the cap, so hidden records never affect results or counts. Matching
 * is a case-insensitive substring and so scans; the {@link SearchIndexMaintenance} table can serve as a source when that is too slow.
 * Client-side section matching belongs to the UI. Visibility is bound by the integrator to the sangam-security
 * {@code PolicyCompiler} (deny wins at every scope, A-13). There is no default predicate in this module.
 */
public final class GlobalSearch {

    public record Hit(String id, String title) {
    }

    public record TypeResult(List<Hit> hits, boolean more) {
    }

    /**
     * @param from       table with alias {@code r}
     * @param idColumn   qualified id column
     * @param titleColumn qualified title column
     * @param matchColumns qualified columns searched
     * @param visibility READ predicate over alias {@code r}, computed in the query's transaction
     */
    public record Source(String type, String from, String idColumn, String titleColumn, List<String> matchColumns, Visibility visibility) {
        public Source {
            java.util.Objects.requireNonNull(visibility, "visibility");
        }
    }

    @FunctionalInterface
    public interface Visibility {
        SqlPredicate visible(Connection c, SecurityIdentity who);
    }

    private final Transactions tx;
    private final List<Source> sources;

    public GlobalSearch(Transactions tx, List<Source> sources) {
        this.tx = tx;
        this.sources = List.copyOf(sources);
    }

    /**
     * The engine's task, historic instance and definition tables. {@code visibility} receives the sangam-security resource type
     * ({@code TASK}, {@code HISTORIC_PROCESS_INSTANCE}, {@code PROCESS_DEFINITION}) and the alias.
     */
    public static List<Source> engineSources(java.util.function.BiFunction<String, String, Visibility> visibility) {
        return List.of(
                new Source("task", "ACT_RU_TASK r", "r.ID_", "r.NAME_", List.of("r.NAME_", "r.DESCRIPTION_"), visibility.apply("TASK", "r")),
                new Source("process-instance", "ACT_HI_PROCINST r", "r.ID_", "r.BUSINESS_KEY_", List.of("r.BUSINESS_KEY_", "r.PROC_DEF_KEY_"),
                        visibility.apply("HISTORIC_PROCESS_INSTANCE", "r")),
                new Source("process-definition", "ACT_RE_PROCDEF r", "r.ID_", "r.NAME_", List.of("r.NAME_", "r.KEY_"), visibility.apply("PROCESS_DEFINITION", "r")));
    }

    /** The visibility of one source type, for other queries over the same table (outlier analysis). */
    public Optional<Visibility> visibility(String type) {
        return sources.stream().filter(x -> x.type().equals(type)).findFirst().map(Source::visibility);
    }

    public Map<String, TypeResult> search(SecurityIdentity who, String text, int capPerType) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("search text is required");
        }
        String needle = "%" + text.strip().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        Map<String, TypeResult> out = new LinkedHashMap<>();
        for (Source s : sources) {
            out.put(s.type(), one(s, who, needle, capPerType));
        }
        return out;
    }

    private TypeResult one(Source s, SecurityIdentity who, String needle, int cap) {
        StringBuilder match = new StringBuilder();
        for (String col : s.matchColumns()) {
            match.append(match.isEmpty() ? "" : " OR ").append("LOWER(").append(col).append(") LIKE ? ESCAPE '\\'");
        }
        return tx.inTransaction(c -> {
            SqlPredicate visible = s.visibility().visible(c, who);
            String sql = "SELECT " + s.idColumn() + ", " + s.titleColumn() + " FROM " + s.from() + " WHERE (" + visible.sql() + ") AND (" + match + ") ORDER BY " + s.idColumn();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setMaxRows(cap + 1);
                int i = visible.bind(ps, 1);
                for (int k = 0; k < s.matchColumns().size(); k++) {
                    ps.setString(i++, needle);
                }
                List<Hit> hits = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        hits.add(new Hit(rs.getString(1), rs.getString(2)));
                    }
                }
                boolean more = hits.size() > cap;
                return new TypeResult(more ? List.copyOf(hits.subList(0, cap)) : hits, more);
            } catch (SQLException e) {
                throw new org.sangam.core.layer.Jdbc.LayerSqlException(sql, e);
            }
        });
    }
}

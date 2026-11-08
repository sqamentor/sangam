// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.sangam.core.layer.Jdbc;
import org.sangam.security.dialect.SecurityDialect;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** JDBC and JSON helpers for the security layer. Statements run on the caller's connection and never commit it. */
public final class Sql {

    public static final ObjectMapper JSON = new ObjectMapper();

    private Sql() {
    }

    /** Insert that tolerates an existing row with the same key; returns true if a row was inserted. */
    public static boolean insertIfAbsent(Connection c, SecurityDialect d, String insertSql, Object... params) {
        if (d.supportsOnConflictDoNothing()) {
            return Jdbc.update(c, insertSql + " ON CONFLICT DO NOTHING", params) == 1;
        }
        try (PreparedStatement ps = c.prepareStatement(insertSql)) {
            Jdbc.bind(ps, 1, params);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            if (d.isUniqueViolation(e)) {
                return false;
            }
            throw new Jdbc.LayerSqlException(insertSql, e);
        }
    }

    /** Database time (A-1), truncated to milliseconds (MySQL P-1 precision floor). */
    public static Instant dbNow(Connection c, SecurityDialect d) {
        return Jdbc.one(c, "SELECT " + d.now() + " AS n" + d.fromDual(), rs -> Jdbc.instant(rs, "n"))
                .orElseThrow().truncatedTo(ChronoUnit.MILLIS);
    }

    public static String placeholders(int n) {
        if (n <= 0) {
            throw new IllegalArgumentException("need at least one bind parameter");
        }
        return String.join(",", Collections.nCopies(n, "?"));
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    /** "valid at t" predicate for effective-dated rows: {@code valid_from <= t AND (valid_to IS NULL OR valid_to > t)}. */
    public static String validAt(String alias) {
        String p = alias == null || alias.isEmpty() ? "" : alias + ".";
        return "(" + p + "valid_from <= ? AND (" + p + "valid_to IS NULL OR " + p + "valid_to > ?))";
    }

    public static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("not serialisable as JSON", e);
        }
    }

    public static Map<String, Object> jsonMap(String text) {
        if (text == null || text.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.readValue(text, new TypeReference<Map<String, Object>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("invalid JSON object", e);
        }
    }

    public static List<String> jsonList(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(text, new TypeReference<List<String>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("invalid JSON array", e);
        }
    }

    public static String decimal(BigDecimal v) {
        return v == null ? null : v.stripTrailingZeros().toPlainString();
    }

    public static BigDecimal decimal(String v) {
        return v == null || v.isBlank() ? null : new BigDecimal(v);
    }

    public static <T> List<T> copy(Collection<T> c) {
        return c == null ? List.of() : List.copyOf(c);
    }
}

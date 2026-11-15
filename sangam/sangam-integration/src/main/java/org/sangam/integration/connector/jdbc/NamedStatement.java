// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.jdbc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * One configured JDBC statement with named parameters ({@code :name}), parsed once when the connector is built into the
 * positional SQL the driver prepares and the ordered parameter names. The parser skips quoted text ({@code '...'},
 * {@code "..."}), line comments ({@code -- ...}), block comments and the PostgreSQL cast {@code ::type}, so a colon in
 * any of them is never a parameter. The SQL text only ever comes from configuration; values only ever reach the driver
 * as bound parameters.
 *
 * @param kind {@link Kind#QUERY} returns rows; {@link Kind#UPDATE} returns the update count
 */
public record NamedStatement(String name, String sql, Kind kind, String positionalSql, List<String> parameters) {

    public enum Kind { QUERY, UPDATE }

    public NamedStatement {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        parameters = List.copyOf(parameters);
    }

    /** Parses {@code sql}; refuses an empty statement, a {@code ?} placeholder and an unterminated quote or comment. */
    public static NamedStatement parse(String name, String sql, Kind kind) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("statement '" + name + "' has no SQL");
        }
        StringBuilder out = new StringBuilder(sql.length());
        List<String> params = new ArrayList<>();
        int n = sql.length();
        int i = 0;
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"') {
                int end = sql.indexOf(c, i + 1);
                while (end > 0 && end + 1 < n && sql.charAt(end + 1) == c) {
                    end = sql.indexOf(c, end + 2);    // doubled quote inside the literal
                }
                if (end < 0) {
                    throw new IllegalArgumentException("statement '" + name + "' has an unterminated quote");
                }
                out.append(sql, i, end + 1);
                i = end + 1;
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                int end = sql.indexOf('\n', i);
                end = end < 0 ? n : end;
                out.append(sql, i, end);
                i = end;
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) {
                    throw new IllegalArgumentException("statement '" + name + "' has an unterminated comment");
                }
                out.append(sql, i, end + 2);
                i = end + 2;
            } else if (c == ':' && i + 1 < n && sql.charAt(i + 1) == ':') {
                out.append("::");
                i += 2;
            } else if (c == ':' && i + 1 < n && Character.isJavaIdentifierStart(sql.charAt(i + 1))) {
                int j = i + 1;
                while (j < n && Character.isJavaIdentifierPart(sql.charAt(j))) {
                    j++;
                }
                params.add(sql.substring(i + 1, j));
                out.append('?');
                i = j;
            } else if (c == '?') {
                throw new IllegalArgumentException("statement '" + name + "' uses '?'; name every parameter (:name)");
            } else {
                out.append(c);
                i++;
            }
        }
        return new NamedStatement(name, sql, kind, out.toString(), params);
    }

    /**
     * True for a declared select (owner decision 320): kind {@link Kind#QUERY} whose SQL, after leading white space and
     * comments, starts with the keyword {@code SELECT}. A query that starts with anything else ({@code WITH}, which may
     * carry a data-modifying statement, {@code INSERT … RETURNING}, a call) is not counted as a read, and neither is a
     * {@code SELECT … INTO} or a locking {@code SELECT … FOR UPDATE / FOR SHARE}. What a selected function does is the
     * operator's statement and is not visible here.
     */
    public boolean isSelect() {
        if (kind != Kind.QUERY || positionalSql == null) {
            return false;
        }
        String code = positionalSql.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("--[^\\n]*", " ")
                .replaceAll("(?s)'(?:[^']|'')*'", "''").strip().toLowerCase(Locale.ROOT);
        // one statement only: a second one after ';' is not a select the first keyword vouches for
        return code.matches("(?s)select\\b.*") && !code.replaceAll(";\\s*$", "").contains(";") && !code.matches("(?s).*\\b(into|for\\s+(update|share|no\\s+key\\s+update|key\\s+share))\\b.*");
    }

    /** Parses a kind name ({@code query} or {@code update}, any case). */
    public static Kind kind(String s) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException("statement kind is required (query or update)");
        }
        return Kind.valueOf(s.trim().toUpperCase(Locale.ROOT));
    }
}

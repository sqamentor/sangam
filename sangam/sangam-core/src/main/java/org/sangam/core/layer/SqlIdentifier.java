// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.layer;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * A validated SQL identifier (table, column, schema-qualified table, notification channel). SQL cannot bind an
 * identifier as a {@code ?} parameter, so every place that must put a name into statement text takes it through this
 * type: the only way to obtain an instance is a factory below, and each one refuses anything outside a strict grammar.
 *
 * <p><b>Grammar.</b> A simple identifier is an ASCII letter or underscore followed by ASCII letters, digits and
 * underscores, at most {@value #MAX_LENGTH} characters (the longest limit of the supported databases: SQL Server and
 * Oracle 128, MySQL 64, PostgreSQL 63). No quote, space, semicolon, comment marker, dot or non-ASCII character can pass,
 * so the text is one token in every dialect and needs no quoting: {@link #sql()} is the name as given, unquoted (the
 * product's own SQL writes every identifier unquoted; a quoted form would change the case folding per database). A
 * reserved word is not refused: it is a syntax error at the database, never an injection.
 *
 * <p>The variants narrow or extend this grammar for the places that need it: {@link #upperCase} (engine and legacy
 * {@code ACT_*} names), {@link #lowerCase} (PostgreSQL {@code LISTEN}/{@code NOTIFY} channels, which fold to lower
 * case) and {@link #qualified} (operator-configured {@code schema.table}).
 */
public final class SqlIdentifier {

    /** Longest simple identifier accepted. */
    public static final int MAX_LENGTH = 128;

    /** Most dot-separated parts of a qualified name ({@code server.database.schema.object} on SQL Server). */
    public static final int MAX_PARTS = 4;

    private final String value;

    private SqlIdentifier(String value) {
        this.value = value;
    }

    /** A simple identifier in either case. */
    public static SqlIdentifier of(String name) {
        requireSimple(name, name, MAX_LENGTH);
        return new SqlIdentifier(name);
    }

    /** A simple identifier without lower-case letters ({@code ACT_HI_PROCINST}, {@code ID_}). */
    public static SqlIdentifier upperCase(String name) {
        requireSimple(name, name, MAX_LENGTH);
        if (!name.equals(name.toUpperCase(Locale.ROOT))) {
            throw refused(name, "must be upper case");
        }
        return new SqlIdentifier(name);
    }

    /**
     * A simple identifier without upper-case letters, at most {@code maxLength} characters (a PostgreSQL channel:
     * 63, and an unquoted name folds to lower case, so only a lower-case name is the one the server reports back).
     */
    public static SqlIdentifier lowerCase(String name, int maxLength) {
        if (maxLength < 1 || maxLength > MAX_LENGTH) {
            throw new IllegalArgumentException("maxLength must be 1.." + MAX_LENGTH + ": " + maxLength);
        }
        requireSimple(name, name, maxLength);
        if (!name.equals(name.toLowerCase(Locale.ROOT))) {
            throw refused(name, "must be lower case");
        }
        return new SqlIdentifier(name);
    }

    /**
     * One to {@value #MAX_PARTS} simple identifiers joined by single dots ({@code schema.table}). Empty parts
     * ({@code a..b}, a leading or trailing dot) are refused.
     */
    public static SqlIdentifier qualified(String name) {
        if (name == null || name.isEmpty()) {
            throw refused(name, "is empty");
        }
        String[] parts = name.split("\\.", -1);
        if (parts.length > MAX_PARTS) {
            throw refused(name, "has more than " + MAX_PARTS + " parts");
        }
        for (String part : parts) {
            requireSimple(part, name, MAX_LENGTH);
        }
        return new SqlIdentifier(name);
    }

    /** Every name of {@code names} as a simple identifier, in order. */
    public static List<SqlIdentifier> allOf(Collection<String> names) {
        return names.stream().map(SqlIdentifier::of).toList();
    }

    /** Every name of {@code names}, validated as a simple identifier, as text in the same order (unmodifiable). */
    public static List<String> names(Collection<String> names) {
        return names.stream().map(n -> of(n).sql()).toList();
    }

    // ---- statement text made only of validated identifiers and bind marks: nothing in it comes from a plain String

    /** {@code SELECT COUNT(*) FROM <table>}. */
    public static String countSql(SqlIdentifier table) {
        return "SELECT COUNT(*) FROM " + table.value;
    }

    /**
     * {@code INSERT INTO <table> (<c1>, <c2>, ...) VALUES (?,?,...)}: one bind mark per column, in the columns'
     * order. An empty column list gives the text as it stands ({@code () VALUES ()}), which the database refuses.
     */
    public static String insertSql(SqlIdentifier table, List<SqlIdentifier> columns) {
        StringBuilder names = new StringBuilder();
        StringBuilder marks = new StringBuilder();
        for (SqlIdentifier column : columns) {
            names.append(names.isEmpty() ? "" : ", ").append(column.value);
            marks.append(marks.isEmpty() ? "?" : ",?");
        }
        return "INSERT INTO " + table.value + " (" + names + ") VALUES (" + marks + ")";
    }

    /** The identifier as statement text (unquoted, exactly the validated name). */
    public String sql() {
        return value;
    }

    /** Same as {@link #sql()}, so string concatenation and {@code String.join} yield the validated name. */
    @Override
    public String toString() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SqlIdentifier other && value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(value);
    }

    private static void requireSimple(String part, String whole, int maxLength) {
        if (part == null || part.isEmpty()) {
            throw refused(whole, "is empty or has an empty part");
        }
        if (part.length() > maxLength) {
            throw refused(whole, "is longer than " + maxLength + " characters");
        }
        for (int i = 0; i < part.length(); i++) {
            char ch = part.charAt(i);
            boolean letter = (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') || ch == '_';
            boolean digit = ch >= '0' && ch <= '9';
            if (!(letter || (digit && i > 0))) {
                throw refused(whole, i == 0 && digit ? "starts with a digit"
                        : "has a character outside A-Z, a-z, 0-9 and _ at index " + i);
            }
        }
    }

    /** The message names the refused text at a bounded length: it is operator configuration or metadata, not a secret. */
    private static IllegalArgumentException refused(String name, String why) {
        String shown = name == null ? "null" : name.length() > 80 ? name.substring(0, 80) + "..." : name;
        return new IllegalArgumentException("invalid SQL identifier (" + why + "): " + shown);
    }
}

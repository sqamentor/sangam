// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.scim;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The SCIM list filter subset Sangam supports (RFC 7644 section 3.4.2.2, as published in the contract): comparisons
 * {@code eq}, {@code co} and {@code sw} on {@code userName}, {@code externalId} and {@code displayName}, joined by
 * {@code and}. Attribute names and operators are case-insensitive; an attribute may carry the core schema URN prefix.
 * Anything else ({@code or}, {@code not}, grouping, value paths, other operators or attributes) is 400
 * {@code invalidFilter}, never silently ignored.
 */
public final class ScimFilter {

    /** Longest filter text accepted. */
    public static final int MAX_LENGTH = 1000;

    public enum Attribute { USER_NAME, EXTERNAL_ID, DISPLAY_NAME }

    public enum Op { EQ, CO, SW }

    public record Clause(Attribute attribute, Op op, String value) {
    }

    private static final String CORE_USER = "urn:ietf:params:scim:schemas:core:2.0:user:";
    private static final String CORE_GROUP = "urn:ietf:params:scim:schemas:core:2.0:group:";

    private final String text;
    private int pos;

    private ScimFilter(String text) {
        this.text = text;
    }

    /** The clauses, all of which must hold; an absent or blank filter gives none. */
    public static List<Clause> parse(String filter) {
        if (filter == null || filter.isBlank()) {
            return List.of();
        }
        if (filter.length() > MAX_LENGTH) {
            throw ScimException.invalidFilter("filter longer than " + MAX_LENGTH + " characters");
        }
        return new ScimFilter(filter).clauses();
    }

    private List<Clause> clauses() {
        List<Clause> out = new ArrayList<>();
        out.add(clause());
        while (true) {
            skipSpaces();
            if (pos >= text.length()) {
                return out;
            }
            String word = word();
            if (!"and".equalsIgnoreCase(word)) {
                throw ScimException.invalidFilter("only 'and' joins comparisons; found '" + word + "'");
            }
            out.add(clause());
        }
    }

    private Clause clause() {
        skipSpaces();
        String attr = word();
        if (attr.isEmpty()) {
            throw ScimException.invalidFilter("an attribute name is expected at position " + (pos + 1));
        }
        Attribute a = attribute(attr);
        skipSpaces();
        String op = word();
        Op o = switch (op.toLowerCase(Locale.ROOT)) {
            case "eq" -> Op.EQ;
            case "co" -> Op.CO;
            case "sw" -> Op.SW;
            default -> throw ScimException.invalidFilter("operator '" + op + "' is not supported (eq, co, sw)");
        };
        skipSpaces();
        return new Clause(a, o, string());
    }

    static Attribute attribute(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.startsWith(CORE_USER)) {
            n = n.substring(CORE_USER.length());
        } else if (n.startsWith(CORE_GROUP)) {
            n = n.substring(CORE_GROUP.length());
        }
        return switch (n) {
            case "username" -> Attribute.USER_NAME;
            case "externalid" -> Attribute.EXTERNAL_ID;
            case "displayname" -> Attribute.DISPLAY_NAME;
            default -> throw ScimException.invalidFilter("attribute '" + name + "' cannot be filtered (userName, externalId, displayName)");
        };
    }

    /** A run of characters up to a space, quote, or bracket; brackets and parentheses themselves are refused. */
    private String word() {
        int start = pos;
        while (pos < text.length()) {
            char ch = text.charAt(pos);
            if (ch == ' ' || ch == '"') {
                break;
            }
            if (ch == '(' || ch == ')' || ch == '[' || ch == ']') {
                throw ScimException.invalidFilter("grouping and value paths are not supported");
            }
            pos++;
        }
        return text.substring(start, pos);
    }

    /** A JSON string literal: double quotes, with the escapes \" \\ \/ \b \f \n \r \t and \\uXXXX. */
    private String string() {
        if (pos >= text.length() || text.charAt(pos) != '"') {
            throw ScimException.invalidFilter("a quoted string value is expected at position " + (pos + 1)
                    + " (only string comparisons are supported)");
        }
        pos++;
        StringBuilder sb = new StringBuilder();
        while (pos < text.length()) {
            char ch = text.charAt(pos++);
            if (ch == '"') {
                return sb.toString();
            }
            if (ch != '\\') {
                sb.append(ch);
                continue;
            }
            if (pos >= text.length()) {
                break;
            }
            char e = text.charAt(pos++);
            switch (e) {
                case '"', '\\', '/' -> sb.append(e);
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    if (pos + 4 > text.length()) {
                        throw ScimException.invalidFilter("incomplete \\u escape");
                    }
                    try {
                        sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw ScimException.invalidFilter("invalid \\u escape");
                    }
                    pos += 4;
                }
                default -> throw ScimException.invalidFilter("invalid escape \\" + e);
            }
        }
        throw ScimException.invalidFilter("unterminated string value");
    }

    private void skipSpaces() {
        while (pos < text.length() && text.charAt(pos) == ' ') {
            pos++;
        }
    }

    /**
     * The LIKE pattern for {@code co} / {@code sw} with {@code !} as the escape character ({@code ESCAPE '!'}, the same on
     * all five dialects, unlike a backslash, which MySQL also treats as a string escape). {@code [} opens a character
     * class on SQL Server only, so it is escaped there only: Oracle refuses an escape character before anything but
     * {@code %}, {@code _} or itself (ORA-01424).
     */
    public static String likePattern(Op op, String value, boolean bracketIsSpecial) {
        StringBuilder sb = new StringBuilder();
        if (op == Op.CO) {
            sb.append('%');
        }
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '!' || ch == '%' || ch == '_' || (bracketIsSpecial && ch == '[')) {
                sb.append('!');
            }
            sb.append(ch);
        }
        return sb.append('%').toString();
    }
}

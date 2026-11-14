// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * M-105 named-parameter SQL: {@code :name} becomes {@code ?} in order (a repeated name is bound at each place), and
 * nothing inside quotes, quoted identifiers, comments or a {@code ::} cast is taken for a parameter; a positional
 * {@code ?}, an unterminated quote or comment and empty SQL are refused.
 */
class NamedStatementTest {

    @Test
    void namedParametersBecomePositionalInOrder() {
        NamedStatement s = NamedStatement.parse("find", "select * from t where a = :a and b = :b or a = :a",
                NamedStatement.Kind.QUERY);
        assertEquals("select * from t where a = ? and b = ? or a = ?", s.positionalSql());
        assertEquals(List.of("a", "b", "a"), s.parameters());
        assertEquals("find", s.name());
    }

    @Test
    void quotesCommentsAndCastsAreNotParameters() {
        NamedStatement s = NamedStatement.parse("q", "select ':x', 'it''s :y', \"col:z\", v::text -- :c\n"
                + "/* :d */ from t where id = :id", NamedStatement.Kind.QUERY);
        assertEquals(List.of("id"), s.parameters());
        assertEquals("select ':x', 'it''s :y', \"col:z\", v::text -- :c\n/* :d */ from t where id = ?", s.positionalSql());
    }

    @Test
    void positionalPlaceholdersUnterminatedTextAndEmptySqlAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> NamedStatement.parse("p", "select * from t where a = ?",
                NamedStatement.Kind.QUERY));
        assertThrows(IllegalArgumentException.class, () -> NamedStatement.parse("p", "select 'abc", NamedStatement.Kind.QUERY));
        assertThrows(IllegalArgumentException.class, () -> NamedStatement.parse("p", "select 'a''", NamedStatement.Kind.QUERY));
        assertThrows(IllegalArgumentException.class, () -> NamedStatement.parse("p", "select /* x", NamedStatement.Kind.QUERY));
        assertThrows(IllegalArgumentException.class, () -> NamedStatement.parse("p", " ", NamedStatement.Kind.UPDATE));
        assertThrows(IllegalArgumentException.class, () -> NamedStatement.parse("p", null, NamedStatement.Kind.UPDATE));
    }

    @Test
    void kindNamesAreCaseInsensitiveAndRequired() {
        assertEquals(NamedStatement.Kind.QUERY, NamedStatement.kind("Query"));
        assertEquals(NamedStatement.Kind.UPDATE, NamedStatement.kind(" update "));
        assertThrows(IllegalArgumentException.class, () -> NamedStatement.kind(null));
        assertThrows(IllegalArgumentException.class, () -> NamedStatement.kind("call"));
    }
}

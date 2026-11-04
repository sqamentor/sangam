package org.sangam.spike.layer;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * A SQL boolean expression with its positional bind parameters, to be AND-ed into a caller's
 * {@code WHERE} clause. Parameters are bound in order, starting at the index the caller passes.
 */
public record SqlPredicate(String sql, List<Object> params) {

    public SqlPredicate {
        params = List.copyOf(params);
    }

    public static SqlPredicate alwaysFalse() {
        return new SqlPredicate("1=0", List.of());
    }

    /** Binds the parameters starting at {@code firstIndex}; returns the next free index. */
    public int bind(PreparedStatement ps, int firstIndex) throws SQLException {
        Jdbc.bind(ps, firstIndex, params.toArray());
        return firstIndex + params.size();
    }

    /** {@code (this) AND (other)}. */
    public SqlPredicate and(SqlPredicate other) {
        List<Object> all = new ArrayList<>(params);
        all.addAll(other.params);
        return new SqlPredicate("(" + sql + ") AND (" + other.sql + ")", all);
    }
}

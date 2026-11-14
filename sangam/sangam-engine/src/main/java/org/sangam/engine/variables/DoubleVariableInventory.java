// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.variables;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.sangam.core.layer.Jdbc;

/**
 * Decision 138 migration inventory: which stored process variables use Operaton's binary {@code double} type, grouped
 * by tenant, process definition key and variable name, in the runtime ({@code ACT_RU_VARIABLE.TYPE_}) and in history
 * ({@code ACT_HI_VARINST.VAR_TYPE_}). Read-only: it changes nothing and converts nothing. A {@code double} value is
 * never turned into a decimal automatically (its binary value is not the decimal the writer meant); each listed
 * variable needs an owner decision (rewrite from the source amount, or keep as a non-money value).
 */
public final class DoubleVariableInventory {

    /** Operaton's serializer name for {@code Double} values. */
    public static final String DOUBLE_TYPE = "double";

    private static final Pattern PREFIX = Pattern.compile("[A-Za-z0-9_.]*");

    /** Where a row was found. */
    public enum Source { RUNTIME, HISTORY }

    /** One group: {@code count} variables named {@code variableName} of definition {@code definitionKey}. */
    public record Entry(Source source, String tenantId, String definitionKey, String variableName, long count) {
    }

    private DoubleVariableInventory() {
    }

    /**
     * Lists the {@code double} variables on {@code c}. {@code tablePrefix} is the engine's table prefix ("" or a
     * schema prefix such as {@code "ENGINE."}); only letters, digits, {@code _} and {@code .} are accepted.
     * Variables outside a process (case or standalone task) have a {@code null} definition key.
     */
    public static List<Entry> scan(Connection c, String tablePrefix) {
        String p = tablePrefix == null ? "" : tablePrefix;
        if (!PREFIX.matcher(p).matches()) {
            throw new IllegalArgumentException("table prefix may hold only letters, digits, '_' and '.'");
        }
        List<Entry> out = new ArrayList<>();
        out.addAll(Jdbc.query(c, "SELECT v.TENANT_ID_, d.KEY_, v.NAME_, COUNT(*) FROM " + p + "ACT_RU_VARIABLE v"
                        + " LEFT JOIN " + p + "ACT_RE_PROCDEF d ON d.ID_ = v.PROC_DEF_ID_"
                        + " WHERE v.TYPE_ = ? GROUP BY v.TENANT_ID_, d.KEY_, v.NAME_"
                        + " ORDER BY v.TENANT_ID_, d.KEY_, v.NAME_",
                rs -> new Entry(Source.RUNTIME, rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)),
                DOUBLE_TYPE));
        out.addAll(Jdbc.query(c, "SELECT h.TENANT_ID_, h.PROC_DEF_KEY_, h.NAME_, COUNT(*) FROM " + p
                        + "ACT_HI_VARINST h WHERE h.VAR_TYPE_ = ? GROUP BY h.TENANT_ID_, h.PROC_DEF_KEY_, h.NAME_"
                        + " ORDER BY h.TENANT_ID_, h.PROC_DEF_KEY_, h.NAME_",
                rs -> new Entry(Source.HISTORY, rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)),
                DOUBLE_TYPE));
        return List.copyOf(out);
    }
}

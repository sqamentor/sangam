// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import org.sangam.core.layer.Jdbc;
import org.sangam.core.tenant.ExecutionTenantPolicy;
import org.sangam.core.tenant.TenantRefusal;
import org.sangam.engine.job.JobIdentitySupport;
import org.sangam.packs.core.Vars;

/**
 * Owner decision 145 (and 199) migration inventory: the running process instances that the removed {@code tenantId}
 * variable fallback used to carry, and that the engine-tenant-only rule now refuses. Read-only: it changes nothing,
 * moves no instance to a tenant and trusts no variable. Each entry is a work item for the controlled, audited migration,
 * which repairs an instance only on authoritative ownership evidence; the variable values listed here are the untrusted
 * claims, and the definition's deployment tenant is listed beside them as one piece of evidence, never as the answer.
 *
 * <p>A root instance is listed when, read as {@link ExecutionTenantPolicy} reads it ({@link ExecutionTenantPolicy#normalise}):
 * <ul>
 *   <li>it has no engine tenant ({@link TenantRefusal#MISSING_ENGINE_TENANT}): with claims, it relied on the fallback;
 *       without, tenant-owned work on it fails closed and only the migration can tell shared work from tenant work;</li>
 *   <li>a tenant variable differs from its engine tenant ({@link TenantRefusal#CONFLICTING_TENANT}).</li>
 * </ul>
 * The tenant variables are the ones execution code read as a tenant: {@link Vars#TENANT_VARIABLE} (packs) and
 * {@link JobIdentitySupport#TENANT_VARIABLE} (jobs), at any scope of the instance. Instances whose engine tenant agrees
 * with every claim are not listed. CMMN case instances are not covered (Sangam's cases run as process instances).
 */
public final class TenantFallbackInventory {

    /** The variable names execution code read as a tenant before decision 145. */
    public static final List<String> TENANT_VARIABLES = List.of(Vars.TENANT_VARIABLE, JobIdentitySupport.TENANT_VARIABLE);

    private static final Pattern PREFIX = Pattern.compile("[A-Za-z0-9_.]*");

    /** One tenant variable of an instance, as stored (untrusted business data). */
    public record Claim(String variableName, String value) {
    }

    /**
     * One root instance the engine-tenant-only rule refuses. {@code engineTenant} is null when the instance has none;
     * {@code definitionTenant} is the deployment tenant of its definition (null for a shared deployment).
     */
    public record Entry(String processInstanceId, String definitionKey, String engineTenant, String definitionTenant,
                        TenantRefusal finding, List<Claim> claims) {

        /** True when the instance carried a tenant only in a variable: the case the fallback served. */
        public boolean reliedOnTheFallback() {
            return finding == TenantRefusal.MISSING_ENGINE_TENANT && claims.stream()
                    .anyMatch(c -> ExecutionTenantPolicy.normalise(c.value()) != null);
        }
    }

    private TenantFallbackInventory() {
    }

    /**
     * Lists the refused root instances on {@code c}, ordered by instance id. {@code tablePrefix} is the engine's table
     * prefix ("" or a schema prefix such as {@code "ENGINE."}); only letters, digits, {@code _} and {@code .} are
     * accepted.
     */
    public static List<Entry> scan(Connection c, String tablePrefix) {
        String p = tablePrefix == null ? "" : tablePrefix;
        if (!PREFIX.matcher(p).matches()) {
            throw new IllegalArgumentException("table prefix may hold only letters, digits, '_' and '.'");
        }
        record Row(String instance, String key, String engine, String definition, String name, String type, String text,
                   Long number) {
        }
        List<Row> rows = Jdbc.query(c, "SELECT e.ID_, d.KEY_, e.TENANT_ID_, d.TENANT_ID_, v.NAME_, v.TYPE_, v.TEXT_,"
                        + " v.LONG_ FROM " + p + "ACT_RU_EXECUTION e"
                        + " LEFT JOIN " + p + "ACT_RE_PROCDEF d ON d.ID_ = e.PROC_DEF_ID_"
                        + " LEFT JOIN " + p + "ACT_RU_VARIABLE v ON v.PROC_INST_ID_ = e.ID_ AND v.NAME_ IN (?, ?)"
                        + " WHERE e.ID_ = e.PROC_INST_ID_ ORDER BY e.ID_, v.NAME_, v.ID_",
                rs -> {
                    String instance = rs.getString(1);
                    String key = rs.getString(2);
                    String engine = rs.getString(3);
                    String definition = rs.getString(4);
                    String name = rs.getString(5);
                    String type = rs.getString(6);
                    String text = rs.getString(7);
                    long n = rs.getLong(8);
                    return new Row(instance, key, engine, definition, name, type, text, rs.wasNull() ? null : n);
                },
                TENANT_VARIABLES.get(0), TENANT_VARIABLES.get(1));
        Map<String, List<Row>> byInstance = new LinkedHashMap<>();
        for (Row r : rows) {
            byInstance.computeIfAbsent(r.instance(), k -> new ArrayList<>()).add(r);
        }
        List<Entry> out = new ArrayList<>();
        for (List<Row> group : byInstance.values()) {
            Row first = group.get(0);
            List<Claim> claims = group.stream().filter(r -> r.name() != null)
                    .map(r -> new Claim(r.name(), r.text() != null || r.number() == null ? r.text()
                            : String.valueOf(r.number())))
                    .toList();
            TenantRefusal finding = finding(first.engine(), claims);
            if (finding != null) {
                out.add(new Entry(first.instance(), first.key(), first.engine(), first.definition(), finding, claims));
            }
        }
        return List.copyOf(out);
    }

    /** The refusal the engine-tenant-only rule gives this instance's tenant-owned work, or null when it has none. */
    static TenantRefusal finding(String engineTenant, List<Claim> claims) {
        String engine = ExecutionTenantPolicy.normalise(engineTenant);
        if (engine == null) {
            return TenantRefusal.MISSING_ENGINE_TENANT;
        }
        for (Claim claim : claims) {
            String claimed = ExecutionTenantPolicy.normalise(claim.value());
            if (claimed != null && !Objects.equals(engine, claimed)) {
                return TenantRefusal.CONFLICTING_TENANT;
            }
        }
        return null;
    }
}

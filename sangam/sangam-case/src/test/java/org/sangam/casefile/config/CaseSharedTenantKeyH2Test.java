// SPDX-License-Identifier: Apache-2.0
package org.sangam.casefile.config;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sangam.casefile.CaseFileSchema;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.TenantKey;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression SL-86 (case): a shared case configuration (no tenant) was stored with tenant_id '' and looked up with
 * {@code tenant_id IN (?, '')}. Oracle stores '' as NULL: the insert failed (ORA-01400) and the shared fallback never
 * matched. It is stored under the reserved shared key now, and a tenant falls back to it. CaseConfigRegistry uses plain
 * SQL and CURRENT_TIMESTAMP only, so this runs in all five H2 modes (DDL with the PostgreSQL type tokens).
 */
class CaseSharedTenantKeyH2Test {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"Oracle", "PostgreSQL", "MSSQLServer", "MySQL", "Regular"})
    void sharedConfigurationIsStoredUnderTheSharedKeyAndIsTheTenantFallback(String mode) throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=" + mode + ";DB_CLOSE_DELAY=-1");
        try (Connection c = ds.getConnection()) {
            CaseFileSchema.create(c, Dialect.POSTGRESQL);
            CaseConfigRegistry r = new CaseConfigRegistry(Dialect.POSTGRESQL);

            assertEquals(1, r.publish(c, null, CaseConfig.COMMITTEE_KIND, Map.of("key", "dpc", "name", "Shared"), "ops"));
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT tenant_id FROM CF_CONFIG")) {
                rs.next();
                assertEquals(TenantKey.SHARED, rs.getString(1), mode + ": a shared row has the reserved shared key");
            }
            CaseConfigRegistry.ConfigEntry viaFallback = r.latest(c, "RJ", CaseConfig.COMMITTEE_KIND, "dpc", true).orElseThrow();
            assertEquals(TenantKey.SHARED, viaFallback.tenantId(), mode + ": a tenant without its own falls back to shared");
            assertEquals(1, r.list(c, "RJ", CaseConfig.COMMITTEE_KIND).size(), mode + ": the shared row is listed for a tenant");
            assertEquals("Shared", r.committeeKind(c, null, "dpc").name(), mode + ": the shared scope reads its own row");

            r.publish(c, "RJ", CaseConfig.COMMITTEE_KIND, Map.of("key", "dpc", "name", "Own"), "ops");
            assertEquals("RJ", r.latest(c, "RJ", CaseConfig.COMMITTEE_KIND, "dpc", true).orElseThrow().tenantId(),
                    mode + ": the tenant's own definition wins");
            assertEquals(TenantKey.SHARED, r.latest(c, "MP", CaseConfig.COMMITTEE_KIND, "dpc", true).orElseThrow().tenantId());
        }
    }
}

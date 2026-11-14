// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.layer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/**
 * RM-49 (W4-A REQUEST): the two core entry points of every audit sink, {@link AuditChain#append} and
 * {@link PendingAudit#record}, refuse a null or blank tenant before they touch the connection, so nothing is written
 * (the connection passed here is null: any database access would fail differently). The shared key is a named tenant
 * and passes. Written for the test phase (not run here).
 */
class AuditTenantGuardTest {

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "	"})
    void appendRefusesATenantlessEvent(String tenant) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new AuditChain(Dialect.POSTGRESQL).append(null, tenant, "root-1", "CASE_CREATED", Map.of()));
        assertTrue(e.getMessage().contains("CASE_CREATED") && e.getMessage().contains("RM-49"), e.getMessage());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  "})
    void pendingRecordRefusesATenantlessEvent(String tenant) {
        PendingAudit pending = new PendingAudit(Transactions.of(new org.h2.jdbcx.JdbcDataSource()), Dialect.POSTGRESQL,
                new AuditChain(Dialect.POSTGRESQL));
        assertThrows(IllegalArgumentException.class,
                () -> pending.record(null, tenant, "ledger:t", "LEDGER_POSTED", Map.of()));
    }

    @Test
    void namedAndSharedTenantsPassTheGuard() {
        assertEquals("t1", AuditChain.requireTenant("t1", "E"));
        assertEquals(TenantKey.SHARED, AuditChain.requireTenant(TenantKey.SHARED, "E"));
    }
}

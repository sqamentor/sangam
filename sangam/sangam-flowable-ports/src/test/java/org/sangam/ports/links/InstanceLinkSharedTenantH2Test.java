// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.links;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.TenantKey;
import org.sangam.core.layer.Transactions;
import org.sangam.ports.PortsSchema;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression SL-86 (ports): the history handler links the tasks and sub-processes of an untenanted instance (a shared
 * definition started by a timer) with tenant null, which was stored as '' in INSTANCE_LINK.tenant_id (NOT NULL, in the
 * unique key). Oracle stores '' as NULL: ORA-01400 inside the history handler rolled the engine command back. The row now
 * has the reserved shared key and is found again under "no tenant".
 *
 * <p>Runs in H2's Oracle mode with the Oracle dialect (plain insert-if-absent, SYSTIMESTAMP) and in PostgreSQL mode with
 * the PostgreSQL dialect (ON CONFLICT DO NOTHING, which H2 accepts only in PostgreSQL mode).
 */
class InstanceLinkSharedTenantH2Test {

    @ParameterizedTest(name = "{0}")
    @CsvSource({"Oracle,ORACLE", "PostgreSQL,POSTGRESQL"})
    void anUntenantedLinkIsStoredUnderTheSharedKey(String mode, Dialect dialect) throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=" + mode + ";DB_CLOSE_DELAY=-1");
        try (Connection c = ds.getConnection()) {
            PortsSchema.create(c, Dialect.POSTGRESQL);
        }
        InstanceLinkService links = new InstanceLinkService(dialect, Transactions.of(ds));
        Transactions tx = Transactions.of(ds);
        assertEquals(Boolean.TRUE, tx.inTransaction(c -> links.link(c, null, "child", "PARENT_CHILD", "process", "p1",
                "task", "t1", "engine")));   // ORA-01400 before the fix

        try (Connection c = ds.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT tenant_id FROM INSTANCE_LINK")) {
            rs.next();
            assertEquals(TenantKey.SHARED, rs.getString(1), mode + ": an untenanted link has the reserved shared key");
        }
        assertEquals(1, links.outgoing(null, "process", "p1").size(), mode + ": found again under no tenant");
        assertEquals(0, links.outgoing("RJ", "process", "p1").size(), mode + ": never visible to a real tenant");
    }
}

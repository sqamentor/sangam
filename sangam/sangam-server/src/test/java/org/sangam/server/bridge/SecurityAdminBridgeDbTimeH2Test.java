// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.persistence.SangamSchema;
import org.sangam.rest.port.AdminPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;
import org.sangam.security.SangamSecurity;
import org.sangam.security.dialect.SecurityDialect;
import org.sangam.security.org.OrgModel;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * SL-108 (rest of SL-83): every "now" of an effective-dated read or write in {@code SecurityAdminBridge} is the product
 * database's time. With the JVM clock (finer than the database's milliseconds) an org unit or seat created "now" started
 * after the database's now of the read that follows, so the API answered 404 for what it had just created, and a
 * posting ended "now" was reported as not ended. Each step here is created and read back at once, many times, so a
 * sub-millisecond start would be caught.
 */
class SecurityAdminBridgeDbTimeH2Test {

    static final String T = "t-sl108";
    static final int ROUNDS = 25;

    private SangamSecurity security;
    private Transactions tx;
    private SecurityAdminBridge bridge;
    private final RequestContext ctx = new RequestContext(T, "sl108-admin", Set.of("sangam-system"), null, null, null,
            null, null, "SYSTEM");

    @BeforeEach
    void schema() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:sl108" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamSchema.apply(ds);
        try (Connection c = ds.getConnection()) {
            security = new SangamSecurity(SecurityDialect.detect(c), null);
        }
        tx = Transactions.of(ds);
        Instant past = Instant.parse("2020-01-01T00:00:00Z");
        SecurityIdentity system = SecurityIdentity.system(T, "sl108-admin");
        tx.inTransaction(c -> {
            Jdbc.update(c, "INSERT INTO SEC_TENANT(tenant_id, name_en, status, config, quotas, four_eyes, created_at)"
                    + " VALUES (?,?,?,?,?,?,?)", T, "t", "ACTIVE", "{}", "{}", 0, past);
            Jdbc.update(c, "INSERT INTO SEC_PERSON(tenant_id, person_id, display_name, external_subject, status, created_at)"
                    + " VALUES (?,?,?,?,?,?)", T, "p1", "P One", "sub-1", "ACTIVE", past);
            security.orgUnits().defineLevels(c, system, List.of("HEAD_OFFICE", "FIELD_OFFICE"));
            return null;
        });
        bridge = new SecurityAdminBridge(security,
                new TxSupport(ds, new DataSourceTransactionManager(ds), TxSupport.detectDialect(ds)), null, new RestProperties());
    }

    @Test
    void anOrgUnitAndASeatCreatedNowAreReadBackAtOnce() {
        bridge.createOrgUnit(ctx, new AdminPort.OrgUnit("HQ", T, null, "HQ", "Head office", Map.of(), "HEAD_OFFICE", true,
                Map.of(), 0));
        for (int i = 0; i < ROUNDS; i++) {
            String unit = "U" + i;
            AdminPort.OrgUnit u = bridge.createOrgUnit(ctx, new AdminPort.OrgUnit(unit, T, "HQ", unit, "Unit " + i,
                    Map.of(), "FIELD_OFFICE", true, Map.of(), 0));
            assertEquals(unit, u.id(), "before SL-108 the read after the create could answer 404");
            String seat = "S" + i;
            AdminPort.Seat s = bridge.createSeat(ctx, new AdminPort.Seat(seat, T, unit, seat, "Clerk", "Clerk", 1, null,
                    List.of(), true, Map.of(), 0));
            assertEquals(unit, s.orgUnitId(), "the seat's placement is in force at once");
        }
    }

    @Test
    void anAssignmentEndedNowIsReportedAsEnded() {
        bridge.createOrgUnit(ctx, new AdminPort.OrgUnit("HQ", T, null, "HQ", "Head office", Map.of(), "HEAD_OFFICE", true,
                Map.of(), 0));
        bridge.createSeat(ctx, new AdminPort.Seat("S1", T, "HQ", "S1", "Clerk", "Clerk", 1, null, List.of(), true, Map.of(), 0));
        Instant past = Instant.parse("2020-01-01T00:00:00Z");
        String posting = tx.inTransaction(c -> security.postings().post(c, SecurityIdentity.system(T, "sl108-admin"), "S1",
                "p1", OrgModel.PostingKind.REGULAR, past, null, "order-1", null).postingId());
        assertFalse(bridge.assignment(ctx, posting).ended());
        assertTrue(bridge.endAssignment(ctx, posting, null, "SL-108").ended(),
                "valid_to = the database's now is no longer in force (Sql.validAt: valid_to > at)");
    }
}

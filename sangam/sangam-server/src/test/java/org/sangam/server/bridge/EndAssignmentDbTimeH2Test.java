// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.persistence.SangamSchema;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;
import org.sangam.security.SangamSecurity;
import org.sangam.security.dialect.SecurityDialect;
import org.sangam.security.org.OrgModel;
import org.sangam.security.support.Sql;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * SL-83: {@code SecurityAdminBridge#endAssignment} without an end date ended the posting at the JVM's
 * {@code Instant.now()} (sub-millisecond precision, the JVM clock), while every effective-dated read uses the product
 * database's time {@link Sql#dbNow} (A-1, truncated to milliseconds); the same race {@code ResolvedIdentityCacheH2Test}
 * found. Ended "now", the posting must end at database time: no sub-millisecond part, not after the database's now,
 * and no longer in force for the next read at database time.
 */
class EndAssignmentDbTimeH2Test {

    static final String T = "t-sl83";

    @Test
    void endingAnAssignmentNowUsesTheDatabaseTime() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:sl83" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamSchema.apply(ds);
        SangamSecurity security;
        try (Connection c = ds.getConnection()) {
            security = new SangamSecurity(SecurityDialect.detect(c), null);
        }
        Transactions tx = Transactions.of(ds);
        SecurityIdentity system = SecurityIdentity.system(T, "sl83-admin");
        Instant past = Instant.parse("2020-01-01T00:00:00Z");
        String postingId = tx.inTransaction(c -> {
            Jdbc.update(c, "INSERT INTO SEC_TENANT(tenant_id, name_en, status, config, quotas, four_eyes, created_at)"
                    + " VALUES (?,?,?,?,?,?,?)", T, "t", "ACTIVE", "{}", "{}", 0, past);
            Jdbc.update(c, "INSERT INTO SEC_PERSON(tenant_id, person_id, display_name, external_subject, status, created_at)"
                    + " VALUES (?,?,?,?,?,?)", T, "p1", "P One", "sub-1", "ACTIVE", past);
            Jdbc.update(c, "INSERT INTO SEC_SEAT(tenant_id, seat_id, designation, rank_no, pool_flag, escalation_seat_id, status,"
                    + " created_at, abolished_at) VALUES (?,?,?,?,?,?,?,?,NULL)", T, "S1", "Clerk", 1, 0, null, "ACTIVE", past);
            return security.postings().post(c, system, "S1", "p1", OrgModel.PostingKind.REGULAR, past, null, "order-1", null)
                    .postingId();
        });
        SecurityAdminBridge bridge = new SecurityAdminBridge(security,
                new TxSupport(ds, new DataSourceTransactionManager(ds), TxSupport.detectDialect(ds)), null, new RestProperties());
        RequestContext ctx = new RequestContext(T, "sl83-admin", Set.of("sangam-system"), null, null, null, null, null,
                "SYSTEM");

        bridge.endAssignment(ctx, postingId, null, "SL-83");

        Instant validTo = tx.inTransaction(c -> security.postings().posting(c, T, postingId).orElseThrow().validTo());
        Instant dbNowAfter = tx.inTransaction(c -> Sql.dbNow(c, security.dialect()));
        assertEquals(0, validTo.getNano() % 1_000_000, "database time (milliseconds), not the JVM clock: " + validTo);
        assertFalse(validTo.isAfter(dbNowAfter), validTo + " after the database's now " + dbNowAfter);
        assertTrue(tx.inTransaction(c -> security.postings().seatsOf(c, T, "p1", Sql.dbNow(c, security.dialect())))
                .stream().noneMatch(h -> h.seatId().equals("S1")), "no longer in force for the next read");
    }
}

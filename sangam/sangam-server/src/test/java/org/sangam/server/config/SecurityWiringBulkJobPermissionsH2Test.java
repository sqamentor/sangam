// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;
import org.sangam.ops.OpsSchema;
import org.sangam.ops.bulk.BulkIngest;
import org.sangam.server.bridge.BulkJobPermissions;

/**
 * Owner decision 273 (option A), the INT-H2 side of the contract agreed with W4-B (board 05:13:32Z, ACK 05:17:56Z):
 * SecurityWiring's resolver answers the stored job's sink permission, and empty (never an exception) for an unknown
 * job, a job of another tenant, a blank id, or no BulkIngest, so the bridge refuses those replays.
 */
class SecurityWiringBulkJobPermissionsH2Test {

    static final Dialect D = Dialect.POSTGRESQL;

    BulkIngest ingest;

    static BulkIngest.Sink sink(String type, String permission) {
        return new BulkIngest.Sink() {
            @Override
            public String objectType() {
                return type;
            }

            @Override
            public String permission() {
                return permission;
            }

            @Override
            public String apply(Connection c, String tenant, String actor, String externalId, Map<String, Object> data,
                                String existingRef) {
                return "item:" + externalId;
            }
        };
    }

    @BeforeEach
    void db() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=TRUE");
        try (Connection c = ds.getConnection()) {
            Schema.create(c, D);
            OpsSchema.create(c, D);
        }
        ingest = new BulkIngest(D, Transactions.of(ds), new AuditChain(D), null, "node-1")
                .register(sink("test.item", "test.load"))
                .register(sink("test.party", "registry.party.write"));
    }

    @Test
    void theResolverAnswersTheSinkPermissionOfTheStoredJob() {
        BulkJobPermissions r = SecurityWiring.bulkJobPermissions(() -> ingest);
        String item = ingest.create("t1", "test.item", null, "alice").id();
        String party = ingest.create("t1", "test.party", null, "alice").id();
        assertEquals(Optional.of("test.load"), r.sinkPermission("t1", item));
        assertEquals(Optional.of("registry.party.write"), r.sinkPermission("t1", party), "per job, not per tenant");
    }

    @Test
    void anUnknownJobOrAJobOfAnotherTenantResolvesToNothing() {
        BulkJobPermissions r = SecurityWiring.bulkJobPermissions(() -> ingest);
        String item = ingest.create("t1", "test.item", null, "alice").id();
        assertTrue(r.sinkPermission("t1", "no-such-job").isEmpty(), "unknown job: refused, not thrown");
        assertTrue(r.sinkPermission("t2", item).isEmpty(), "another tenant's job: refused");
        assertTrue(r.sinkPermission("t1", "").isEmpty());
        assertTrue(r.sinkPermission("t1", null).isEmpty());
        assertTrue(r.sinkPermission(null, item).isEmpty());
        assertTrue(r.sinkPermission(" ", item).isEmpty());
    }

    @Test
    void withoutBulkIngestOrOnAFailureNothingResolves() {
        assertTrue(SecurityWiring.bulkJobPermissions(() -> null).sinkPermission("t1", "j-1").isEmpty(),
                "no ops module: every bulk replay stays refused");
        assertTrue(SecurityWiring.bulkJobPermissions(() -> {
            throw new IllegalStateException("store down");
        }).sinkPermission("t1", "j-1").isEmpty(), "a failure refuses, never throws");
    }
}

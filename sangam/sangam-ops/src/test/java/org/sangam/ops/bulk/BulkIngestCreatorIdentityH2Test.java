// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.bulk;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.ops.OpsSchema;
import org.sangam.ops.bulk.BulkIngest.BulkException;
import org.sangam.ops.bulk.BulkIngest.JobState;
import org.sangam.ops.bulk.BulkIngest.RowInput;
import org.sangam.ops.bulk.BulkIngest.RowState;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M-130, owner decision 59: a job of an {@link IdentitySink} keeps a snapshot of its creator's identity, and every row
 * is applied as that creator, never as the system principal.
 */
class BulkIngestCreatorIdentityH2Test {

    static final Dialect D = Dialect.POSTGRESQL;
    static final String TYPE = "test.guarded";

    Transactions tx;
    final List<SecurityIdentity> seen = new CopyOnWriteArrayList<>();
    volatile Optional<SecurityIdentity> bound = Optional.empty();

    static SecurityIdentity alice(String tenant) {
        return new SecurityIdentity(tenant, "p-alice", "alice", Set.of("g2", "g1"), Set.of("loader"), Set.of("seat-9"),
                Set.of(), "oidc", "10.0.0.9", null, false, Map.of("email", "alice@example.test"));
    }

    @BeforeEach
    void db() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=TRUE");
        try (Connection c = ds.getConnection()) {
            Schema.create(c, D);
            OpsSchema.create(c, D);
        }
        tx = Transactions.of(ds);
    }

    BulkIngest ingest() {
        return new BulkIngest(D, tx, new AuditChain(D), null, "node-1").creatorIdentity(() -> bound).register(new IdentitySink() {
            public String objectType() {
                return TYPE;
            }

            public String permission() {
                return "test.load";
            }

            public String apply(Connection c, SecurityIdentity creator, String ext, Map<String, Object> data, String existingRef) {
                seen.add(creator);
                if (!creator.roles().contains("loader")) {
                    throw new BulkIngest.RowRejectedException("NOT_ENTITLED", "the creator may not load " + ext);
                }
                return "ref:" + ext;
            }
        });
    }

    static void drain(BulkIngest b) {
        new BulkIngestWorker(b, 7, 1000, 60_000).tick();
    }

    String snapshot(String jobId) {
        return tx.inTransaction(c -> Jdbc.one(c, "SELECT creator_identity FROM OPS_BULK_JOB WHERE id = ?", rs -> rs.getString(1), jobId)).orElse(null);
    }

    @Test
    void rowsAreAppliedAsTheSnapshottedCreator() {
        BulkIngest b = ingest();
        bound = Optional.of(alice("t1"));
        BulkIngest.Job job = b.create("t1", TYPE, 1, "p-alice");
        bound = Optional.empty(); // the worker has no request: only the snapshot can be used
        String snap = snapshot(job.id());
        assertNotNull(snap);
        assertTrue(snap.contains("\"groups\":[\"g1\",\"g2\"]"), "groups are kept, sorted: " + snap);
        assertFalse(snap.contains("10.0.0.9"), "the client address is not kept");
        assertFalse(snap.contains("alice@example.test"), "further claims are not kept");

        b.upload("t1", job.id(), 0, List.of(new RowInput("a", Map.of()), new RowInput("b", Map.of())), "p-alice");
        b.close("t1", job.id(), "p-alice");
        drain(b);

        assertEquals(JobState.COMPLETED, b.job("t1", job.id()).state());
        assertEquals(2L, b.job("t1", job.id()).rows().get(RowState.DONE));
        assertEquals(2, seen.size());
        for (SecurityIdentity s : seen) {
            assertEquals("t1", s.tenantId());
            assertEquals("p-alice", s.personId());
            assertEquals("alice", s.subject());
            assertEquals(Set.of("g1", "g2"), s.groups());
            assertEquals(Set.of("loader"), s.roles());
            assertEquals(Set.of("seat-9"), s.seats());
            assertFalse(s.system(), "a row is never applied as the system principal");
        }
    }

    @Test
    void creatingWithoutAResolvedIdentityIsRefused() {
        BulkIngest b = ingest();
        BulkException e = assertThrows(BulkException.class, () -> b.create("t1", TYPE, 1, "p-alice"));
        assertEquals("CREATOR_IDENTITY_REQUIRED", e.code());
    }

    @Test
    void anIdentityOfAnotherTenantOrPrincipalIsRefused() {
        BulkIngest b = ingest();
        bound = Optional.of(alice("t2"));
        assertEquals("CREATOR_IDENTITY_REQUIRED", assertThrows(BulkException.class, () -> b.create("t1", TYPE, 1, "p-alice")).code());
        bound = Optional.of(alice("t1"));
        assertEquals("CREATOR_IDENTITY_REQUIRED", assertThrows(BulkException.class, () -> b.create("t1", TYPE, 1, "p-bob")).code());
    }

    @Test
    void theSystemPrincipalIsNeverTheCreator() {
        BulkIngest b = ingest();
        SecurityIdentity sys = SecurityIdentity.system("t1", null);
        bound = Optional.of(sys);
        assertEquals("SYSTEM_CREATOR_REFUSED", assertThrows(BulkException.class, () -> b.create("t1", TYPE, 1, sys.personId())).code());
        assertEquals("SYSTEM_CREATOR_REFUSED", assertThrows(BulkException.class, () -> b.create("t1", TYPE, 1, sys)).code());
    }

    @Test
    void anExplicitCreatorIsSnapshottedWithoutARequest() {
        BulkIngest b = ingest();
        BulkIngest.Job job = b.create("t1", TYPE, 1, alice("t1"));
        assertNotNull(snapshot(job.id()));
        assertEquals(alice("t1").personId(), CreatorSnapshot.forRow(snapshot(job.id()), "t1").personId());
    }

    @Test
    void aRowOfAJobWithoutASnapshotIsRejectedNotAppliedAsTheSystem() {
        BulkIngest b = ingest();
        bound = Optional.of(alice("t1"));
        BulkIngest.Job job = b.create("t1", TYPE, 1, "p-alice");
        tx.inTransaction(c -> Jdbc.update(c, "UPDATE OPS_BULK_JOB SET creator_identity = NULL WHERE id = ?", job.id()));
        b.upload("t1", job.id(), 0, List.of(new RowInput("a", Map.of())), "p-alice");
        b.close("t1", job.id(), "p-alice");
        drain(b);

        assertTrue(seen.isEmpty(), "the sink is never called without the creator");
        List<BulkIngest.RowStatus> rows = b.rows("t1", job.id(), null, 0, 10).items();
        assertEquals(RowState.FAILED, rows.get(0).state());
        assertEquals("CREATOR_IDENTITY_MISSING", rows.get(0).errorCode());
    }

    @Test
    void aSnapshotOfAnotherTenantIsRejected() {
        String other = CreatorSnapshot.write(alice("t2"));
        assertEquals("CREATOR_IDENTITY_INVALID", assertThrows(BulkIngest.RowRejectedException.class,
                () -> CreatorSnapshot.forRow(other, "t1")).code());
        assertEquals("CREATOR_IDENTITY_INVALID", assertThrows(BulkIngest.RowRejectedException.class,
                () -> CreatorSnapshot.forRow("{not json", "t1")).code());
    }
}

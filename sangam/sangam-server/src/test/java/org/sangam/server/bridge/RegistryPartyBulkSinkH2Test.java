// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.SqlPredicate;
import org.sangam.core.layer.Transactions;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.ops.OpsSchema;
import org.sangam.ops.bulk.BulkIngest;
import org.sangam.ops.bulk.BulkIngest.RowInput;
import org.sangam.ops.bulk.BulkIngest.RowState;
import org.sangam.ops.bulk.BulkIngestWorker;
import org.sangam.registry.RegistryContext;
import org.sangam.registry.RegistryModule;
import org.sangam.registry.RegistryPorts;
import org.sangam.registry.RegistrySchema;
import org.sangam.registry.party.PartyRecords.Party;
import org.sangam.registry.party.PartyService;
import org.sangam.security.tenant.TenantContext;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M-130 registry parties (owner decision 93): the bulk party sink keys each row by a unique bulk external-key
 * identifier, finds a repeated row by that key, never merges automatically, and runs every row as the job's creator
 * (owner decision 59), so the registry's visibility port sees the creator. H2 in PostgreSQL mode, with a visibility
 * port that shows a person only the parties they created (unless they hold {@code registry-all}).
 */
class RegistryPartyBulkSinkH2Test {

    static final Dialect D = Dialect.POSTGRESQL;
    static final String KEY = RegistryPartyBulkSink.DEFAULT_KEY_TYPE;

    static final SecurityIdentity ALICE = person("alice");
    static final SecurityIdentity BOB = person("bob");

    JdbcDataSource ds;
    Transactions tx;
    PartyService parties;
    RegistryPartyBulkSink sink;
    /** The identity bound to TenantContext at every visibility check. */
    final List<String> seenBy = new CopyOnWriteArrayList<>();

    static SecurityIdentity person(String id) {
        return new SecurityIdentity("t1", id, "user:" + id, Set.of(), Set.of("clerk"), Set.of(), Set.of(), "OIDC", null, null,
                false, Map.of());
    }

    static RegistryContext ctx(SecurityIdentity id) {
        return new RegistryContext(id.tenantId(), id.personId(), null, null, null, java.time.ZoneOffset.UTC);
    }

    @BeforeEach
    void db() throws Exception {
        ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:m130party" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=TRUE");
        try (Connection c = ds.getConnection()) {
            Schema.create(c, D);
            OpsSchema.create(c, D);
            RegistrySchema.create(c, D);
        }
        tx = Transactions.of(ds);
        RegistryPorts.VisibilityPort visibility = (c, rc, resourceType, alias) -> {
            SecurityIdentity id = TenantContext.current().orElse(null);
            seenBy.add(id == null ? "<none>" : id.personId() + (id.system() ? "(system)" : ""));
            if (id == null) {
                return new SqlPredicate("1 = 0", List.of());
            }
            if (id.roles().contains("registry-all")) {
                return new SqlPredicate(alias + ".tenant_id = ?", List.of(rc.tenant()));
            }
            return new SqlPredicate(alias + ".tenant_id = ? AND " + alias + ".created_by = ?", List.of(rc.tenant(), id.personId()));
        };
        RegistryModule module = new RegistryModule(D, RegistryPorts.defaults().withVisibility(visibility), null, List.of("test"));
        parties = module.parties();
        sink = new RegistryPartyBulkSink(parties, KEY);
    }

    @AfterEach
    void unbind() {
        TenantContext.clear();
    }

    /** Runs {@code work} on one committed transaction as {@code id} (as a request would). */
    <T> T as(SecurityIdentity id, Function<Connection, T> work) {
        TenantContext.set(id);
        try {
            return tx.inTransaction(work);
        } finally {
            TenantContext.clear();
        }
    }

    String apply(SecurityIdentity creator, String ext, Map<String, Object> data, String existingRef) {
        return tx.inTransaction(c -> sink.apply(c, creator, ext, data, existingRef));
    }

    static Map<String, Object> asha(String name) {
        return Map.of("kind", "PERSON", "names", Map.of("full", name), "birthDate", "1990-01-01", "gender", "F");
    }

    long count(String sql, Object... params) {
        return tx.inTransaction(c -> Jdbc.one(c, sql, rs -> rs.getLong(1), params).orElse(0L));
    }

    BulkIngest ingest(SecurityIdentity creator) {
        return new BulkIngest(D, tx, new AuditChain(D), null, "node-1").creatorIdentity(() -> Optional.of(creator)).register(sink);
    }

    @Test
    void aRowCreatesOnePartyKeyedByItsExternalIdAndARepeatFindsIt() {
        String ref = apply(ALICE, "ext-1", asha("Asha Devi"), null);
        assertTrue(ref.startsWith(RegistryPartyBulkSink.REF));
        String id = ref.substring(RegistryPartyBulkSink.REF.length());

        List<Party> byKey = as(ALICE, c -> parties.list(c, ctx(ALICE), null, null, null, KEY, "ext-1", 0, 5));
        assertEquals(List.of(id), byKey.stream().map(Party::id).toList(), "the party carries the bulk key");
        long version = byKey.get(0).version();

        assertEquals(ref, apply(ALICE, "ext-1", asha("Asha Devi"), ref), "a repeat finds the same party");
        Party same = as(ALICE, c -> parties.get(c, ctx(ALICE), id));
        assertEquals(version, same.version(), "an unchanged repeat writes nothing");

        assertEquals(ref, apply(ALICE, "ext-1", asha("Asha Kumari"), ref), "a changed row updates the same party");
        Party changed = as(ALICE, c -> parties.get(c, ctx(ALICE), id));
        assertEquals("Asha Kumari", changed.names().get("full"));
        assertEquals(version + 1, changed.version());
        assertEquals(1L, count("SELECT COUNT(*) FROM REG_PARTY WHERE tenant_id = ?", "t1"));
    }

    @Test
    void aLikelyDuplicateIsQueuedForReviewNeverMergedAutomatically() {
        as(ALICE, c -> parties.saveMatchConfig(c, ctx(ALICE), Map.of("autoMergeEnabled", true,
                "rules", List.of(Map.of("key", "same-person", "fields", List.of("name", "birthDate", "gender"), "decision", "AUTO_MERGE")))));
        String existing = as(ALICE, c -> parties.create(c, ctx(ALICE), Map.of("kind", "PERSON", "names", Map.of("full", "Asha Devi"),
                "birthDate", "1990-01-01", "gender", "F", "match", false)).party().id());

        String id = apply(ALICE, "ext-2", asha("Asha Devi"), null).substring(RegistryPartyBulkSink.REF.length());

        assertNotEquals(existing, id);
        assertEquals("ACTIVE", as(ALICE, c -> parties.get(c, ctx(ALICE), id)).status(), "not merged into the existing party");
        assertEquals("ACTIVE", as(ALICE, c -> parties.get(c, ctx(ALICE), existing)).status());
        assertEquals(0L, count("SELECT COUNT(*) FROM REG_MERGE_EVENT WHERE tenant_id = ?", "t1"));
        assertEquals(1L, count("SELECT COUNT(*) FROM REG_MATCH_CANDIDATE WHERE tenant_id = ? AND status = 'OPEN'", "t1"),
                "the pair is queued for a reviewer");
    }

    @Test
    void everyRowRunsAsTheCreatorAndTheBindingIsRestored() {
        seenBy.clear();
        apply(ALICE, "ext-3", asha("Asha Devi"), null);
        assertFalse(seenBy.isEmpty());
        assertTrue(seenBy.stream().allMatch("alice"::equals), "the visibility port saw only the creator: " + seenBy);
        assertTrue(TenantContext.current().isEmpty(), "the worker thread is left unbound");

        TenantContext.set(BOB);
        apply(ALICE, "ext-4", asha("Asha Devi"), null);
        assertEquals(Optional.of(BOB), TenantContext.current(), "a binding present before the row is restored");
    }

    @Test
    void aKeyHeldByAPartyTheCreatorCannotSeeIsRejectedAndRolledBack() {
        as(BOB, c -> parties.create(c, ctx(BOB), Map.of("kind", "PERSON", "names", Map.of("full", "Ravi"), "match", false,
                "identifiers", List.of(Map.of("type", KEY, "value", "ext-9")))));
        long before = count("SELECT COUNT(*) FROM REG_PARTY WHERE tenant_id = ?", "t1");

        BulkIngest b = ingest(ALICE);
        BulkIngest.Job job = b.create("t1", RegistryPartyBulkSink.OBJECT_TYPE, 1, "alice");
        b.upload("t1", job.id(), 0, List.of(new RowInput("ext-9", asha("Asha Devi"))), "alice");
        b.close("t1", job.id(), "alice");
        new BulkIngestWorker(b, 7, 1000, 60_000).tick();

        BulkIngest.RowStatus row = b.rows("t1", job.id(), null, 0, 10).items().get(0);
        assertEquals(RowState.FAILED, row.state());
        assertEquals("EXTERNAL_KEY_HELD_FOR_REVIEW", row.errorCode());
        assertEquals(before, count("SELECT COUNT(*) FROM REG_PARTY WHERE tenant_id = ?", "t1"), "no second party was kept");
    }

    @Test
    void aJobAppliesRowsEndToEndAndARepeatedPayloadIsSkipped() {
        BulkIngest b = ingest(ALICE);
        for (int run = 0; run < 2; run++) {
            BulkIngest.Job job = b.create("t1", RegistryPartyBulkSink.OBJECT_TYPE, 1, "alice");
            b.upload("t1", job.id(), 0, List.of(new RowInput("ext-5", asha("Asha Devi")), new RowInput("ext-6", asha("Meena"))), "alice");
            b.close("t1", job.id(), "alice");
            new BulkIngestWorker(b, 7, 1000, 60_000).tick();
            assertEquals(2L, b.job("t1", job.id()).rows().get(run == 0 ? RowState.DONE : RowState.SKIPPED));
        }
        assertEquals(2L, count("SELECT COUNT(*) FROM REG_PARTY WHERE tenant_id = ?", "t1"));
    }

    @Test
    void aKeyTypeThatIsNotUniqueFailsClosed() {
        as(ALICE, c -> parties.saveIdentifierTypes(c, ctx(ALICE), List.of(Map.of("typeKey", KEY, "unique", false))));
        BulkIngest.RowRejectedException e = assertThrows(BulkIngest.RowRejectedException.class,
                () -> apply(ALICE, "ext-7", asha("Asha Devi"), null));
        assertEquals("KEY_TYPE_NOT_UNIQUE", e.code());
    }

    @Test
    void aRowCannotCarryADifferentValueOfTheKeyOrChangeTheKind() {
        Map<String, Object> wrongKey = Map.of("kind", "PERSON", "names", Map.of("full", "Asha"),
                "identifiers", List.of(Map.of("type", KEY, "value", "other")));
        assertEquals("KEY_MISMATCH", assertThrows(BulkIngest.RowRejectedException.class,
                () -> apply(ALICE, "ext-8", wrongKey, null)).code());

        String ref = apply(ALICE, "ext-8", asha("Asha"), null);
        assertEquals("KIND_CHANGED", assertThrows(BulkIngest.RowRejectedException.class,
                () -> apply(ALICE, "ext-8", Map.of("kind", "ORGANISATION", "names", Map.of("full", "Asha")), ref)).code());
    }

    @Test
    void aPartyThisKeyCreatedThatIsNoLongerFoundIsNotSilentlyCreatedAgain() {
        assertEquals("PARTY_KEY_MISSING", assertThrows(BulkIngest.RowRejectedException.class,
                () -> apply(ALICE, "ext-10", asha("Asha"), RegistryPartyBulkSink.REF + "gone")).code());
    }
}

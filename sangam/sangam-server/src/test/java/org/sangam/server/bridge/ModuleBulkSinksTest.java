// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.sangam.config.store.ConfigSchema;
import org.sangam.config.store.JdbcConfigStore;
import org.sangam.core.layer.Dialect;
import org.sangam.core.security.SangamSecurityException;
import org.sangam.ops.bulk.BulkIngest;
import org.sangam.resources.support.ResourcesException;

import java.sql.Connection;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** M-130: the module bulk sinks' row mapping, idempotency and failure classification. */
class ModuleBulkSinksTest {

    @Test
    void permanentModuleRefusalsAreRowRejectionsAndTransientOnesAreRetried() {
        assertEquals("CODE_CHANGED", assertThrows(BulkIngest.RowRejectedException.class, () -> ModuleBulkSinks.rejecting(() -> {
            throw ResourcesException.invalid("CODE_CHANGED", "no");
        })).code());
        assertEquals("DENIED", assertThrows(BulkIngest.RowRejectedException.class, () -> ModuleBulkSinks.rejecting(() -> {
            throw ResourcesException.notEntitled("DENIED", "no");
        })).code());
        assertThrows(BulkIngest.RowRejectedException.class, () -> ModuleBulkSinks.rejecting(() -> {
            throw new SangamSecurityException("FORBIDDEN", 403, "no");
        }));
        ResourcesException stale = ResourcesException.stale("changed");
        assertSame(stale, assertThrows(ResourcesException.class, () -> ModuleBulkSinks.rejecting(() -> {
            throw stale;
        })), "a stale version is retried, not rejected");
        assertThrows(ResourcesException.class, () -> ModuleBulkSinks.rejecting(() -> {
            throw ResourcesException.unavailable("DOWN", "later");
        }), "an unavailable dependency is retried, not rejected");
    }

    @Test
    void rowFieldsAreValidatedAsRejections() {
        assertEquals("FIELD_REQUIRED", assertThrows(BulkIngest.RowRejectedException.class,
                () -> ModuleBulkSinks.required(Map.of(), "periodId")).code());
        assertEquals("INVALID_DATE", assertThrows(BulkIngest.RowRejectedException.class,
                () -> ModuleBulkSinks.date(Map.of("day", "12/11/2026"), "day", true)).code());
        assertEquals(LocalDate.of(2026, 11, 12), ModuleBulkSinks.date(Map.of("day", "2026-11-12"), "day", true));
        assertEquals("INVALID_NUMBER", assertThrows(BulkIngest.RowRejectedException.class,
                () -> ModuleBulkSinks.integer(Map.of("capacity", 2.5), "capacity")).code());
        assertEquals(7, ModuleBulkSinks.integer(Map.of("capacity", "7"), "capacity"));
        assertEquals("INVALID_OBJECT", assertThrows(BulkIngest.RowRejectedException.class,
                () -> ModuleBulkSinks.map(Map.of("attributes", "x"), "attributes")).code());
    }

    @Test
    void masterCodesAreUpsertedInTheTenantScopeOnTheRowConnection() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        try (Connection c = ds.getConnection()) {
            ConfigSchema.create(c, Dialect.POSTGRESQL);
        }
        JdbcConfigStore store = new JdbcConfigStore(ds, Dialect.POSTGRESQL);
        BulkIngest.Sink sink = ModuleBulkSinks.masterCodes(store);
        Map<String, Object> row = Map.of("scheme", "HOA", "segment", "MAJOR", "code", "2054", "name", Map.of("en", "Treasury"),
                "validFrom", "2026-04-01");

        String ref;
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            ref = sink.apply(c, "t1", "alice", "row-1", row, null);
            assertEquals(ref, sink.apply(c, "t1", "alice", "row-1", row, ref), "a repeat is idempotent");
            c.commit();
        }
        assertEquals(1, store.find("t1", "HOA", "MAJOR", null, "2054").size());

        try (Connection c = ds.getConnection()) {
            assertEquals("SCOPE_NOT_OWNED", assertThrows(BulkIngest.RowRejectedException.class, () -> sink.apply(c, "t1", "alice", "row-2",
                    Map.of("scope", "other", "scheme", "HOA", "segment", "MAJOR", "code", "1"), null)).code());
            assertEquals("KEY_CHANGED", assertThrows(BulkIngest.RowRejectedException.class, () -> sink.apply(c, "t1", "alice", "row-1",
                    Map.of("scheme", "HOA", "segment", "MAJOR", "code", "2055"), ref)).code());
            assertEquals("FIELD_REQUIRED", assertThrows(BulkIngest.RowRejectedException.class, () -> sink.apply(c, "t1", "alice", "row-3",
                    Map.of("scheme", "HOA", "segment", "MAJOR"), null)).code());
        }
    }
}

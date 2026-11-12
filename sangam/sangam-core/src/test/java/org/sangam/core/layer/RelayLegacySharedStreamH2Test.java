// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.layer;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * SL-86 upgrade: the shared tenant key changed from '' to '-'. Outbox rows of a shared root written before the upgrade
 * stay under '' (their ROOT_SEQ row and audit chain are not re-keyed); new rows of the same root go to '-' with a new
 * root_seq that starts at 1. Per-root ordering must hold across the two: every unfinished '' delivery of a root and
 * destination is delivered before any '-' delivery of it. Without the rule, both line heads were claimed together.
 */
class RelayLegacySharedStreamH2Test {

    @Test
    void theLegacyStreamOfARootDrainsBeforeItsSharedKeyStream() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        try (Connection c = ds.getConnection()) {
            Schema.create(c, Dialect.POSTGRESQL);
        }
        Transactions tx = Transactions.of(ds);
        Outbox outbox = new Outbox(Dialect.POSTGRESQL);
        List<Outbox.Destination> dest = List.of(Outbox.Destination.ordered("feed"));
        tx.inTransaction(c -> {
            outbox.append(c, "", "case-1", "E", "{\"n\":\"legacy-1\"}", dest);   // written before the upgrade
            outbox.append(c, "", "case-1", "E", "{\"n\":\"legacy-2\"}", dest);
            outbox.append(c, TenantKey.SHARED, "case-1", "E", "{\"n\":\"new-1\"}", dest);   // after: root_seq 1 again
            outbox.append(c, "RJ", "case-1", "E", "{\"n\":\"other-tenant\"}", dest);   // a real tenant is independent
            return null;
        });

        List<String> published = new ArrayList<>();
        List<List<String>> cycles = new ArrayList<>();
        Relay relay = new Relay(Dialect.POSTGRESQL, tx, (d, e) -> published.add(e.payload()),
                new Relay.Config("n1", 100, Duration.ofSeconds(30), 10, Duration.ZERO, Duration.ZERO), null);
        for (int i = 0; i < 5; i++) {
            int before = published.size();
            relay.runOnce();
            cycles.add(List.copyOf(published.subList(before, published.size())));
        }
        assertEquals(List.of("{\"n\":\"legacy-1\"}", "{\"n\":\"other-tenant\"}"), sorted(cycles.get(0)),
                "cycle 1: the legacy head and the real tenant's head, never the new shared row");
        assertEquals(List.of("{\"n\":\"legacy-2\"}"), cycles.get(1));
        assertEquals(List.of("{\"n\":\"new-1\"}"), cycles.get(2), "the new shared stream continues after the legacy one");
        assertEquals(4, published.size());
    }

    private static List<String> sorted(List<String> l) {
        List<String> s = new ArrayList<>(l);
        s.sort(null);
        return s;
    }
}

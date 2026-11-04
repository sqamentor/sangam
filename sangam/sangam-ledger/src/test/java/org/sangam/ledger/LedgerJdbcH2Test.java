// SPDX-License-Identifier: Apache-2.0
package org.sangam.ledger;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.layer.Dialect;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fast H2 (PostgreSQL mode) checks of the JDBC ledger: no oversell, durable holds and freeze orders, the connector. */
class LedgerJdbcH2Test {

    static final Map<String, Object> DEF = Map.of("id", "BUDGET", "kind", "LIMIT", "currency", "INR",
            "stages", List.of("ALLOCATION", "RELEASE"));

    static JdbcDataSource db(String name) throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + name + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        try (Connection c = ds.getConnection()) {
            LedgerSchema.create(c, Dialect.POSTGRESQL);
        }
        return ds;
    }

    static LedgerService service(JdbcDataSource ds) {
        LedgerService s = new LedgerService(new JdbcLedgerStore(ds, Dialect.POSTGRESQL),
                new LedgerControlStore.JdbcStore(ds, Dialect.POSTGRESQL), null);
        s.define("T", List.of(LedgerDefinition.from(DEF)));
        return s;
    }

    @Test
    void noOversellAndDurableControlState() throws Exception {
        JdbcDataSource ds = db("ledger1");
        LedgerService s = service(ds);
        AccountKey k = new AccountKey("T", "BUDGET", "2026-27", "2054-00-001-01-00-13", "DDO-1", "RELEASE", null);
        assertTrue(s.apply(LedgerRequest.of("ALLOCATE", k, new BigDecimal("500"), "a")).applied());
        ExecutorService pool = Executors.newFixedThreadPool(8);
        AtomicInteger ok = new AtomicInteger();
        for (int i = 0; i < 80; i++) {
            int n = i;
            pool.submit(() -> {
                if (s.apply(LedgerRequest.of("COMMIT", k, BigDecimal.TEN, "c" + n)).applied()) {
                    ok.incrementAndGet();
                }
            });
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(50, ok.get());
        assertEquals(0, s.available(k).signum());

        LedgerRequest held = new LedgerRequest("COMMIT", k, null, new BigDecimal("40"), null, null, "h1", null, "u", null,
                false, false, true, Map.of("caseType", "BILL"));
        assertTrue(s.apply(held).held());
        s.freeze(new FreezeOrder("T", "F1", "BUDGET", null, "2054", null, null, "in-year cut", "fd", null));

        LedgerService restarted = service(ds); // a new node / a restart: state comes from the tables only
        assertEquals(1, restarted.held(k).size());
        assertEquals(1, restarted.freezes("T").size());
        assertEquals("FROZEN", restarted.apply(LedgerRequest.of("COMMIT", k, BigDecimal.ONE, "c-frozen")).reason());
        assertTrue(restarted.releaseFreeze("T", "F1"));
        restarted.apply(LedgerRequest.of("ALLOCATE", k, new BigDecimal("40"), "a2"));
        assertEquals(1, restarted.releaseHolds(k).size());
        assertTrue(restarted.held(k).isEmpty());
    }

    @Test
    void connectorUsesOperationIdAsReference() throws Exception {
        LedgerService s = service(db("ledger2"));
        LedgerConnector conn = new LedgerConnector(s, (t, d) -> "2026-27", null);
        UUID op = UUID.randomUUID();
        Map<String, Object> in = new LinkedHashMap<>(Map.of("ledgerId", "BUDGET", "headOfAccount", "2054-00-001-01-00-13",
                "ddoCode", "DDO-1", "amount", "100", "stage", "RELEASE"));
        assertInstanceOf(ConnectorResult.Success.class, conn.invoke(req(op, "allocate", in)));
        ConnectorResult enc = conn.invoke(req(UUID.randomUUID(), "encumber", Map.of("ledgerId", "BUDGET",
                "headOfAccount", "2054-00-001-01-00-13", "ddoCode", "DDO-1", "amount", "60")));
        assertInstanceOf(ConnectorResult.Success.class, enc);
        UUID pay = UUID.randomUUID();
        Map<String, Object> payIn = Map.of("ledgerId", "BUDGET", "headOfAccount", "2054-00-001-01-00-13", "ddoCode", "DDO-1",
                "amount", "60");
        assertInstanceOf(ConnectorResult.Success.class, conn.invoke(req(pay, "consume", payIn)));
        ConnectorResult again = conn.invoke(req(pay, "consume", payIn));
        assertTrue(((ConnectorResult.Success) again).duplicate(), "a retried step never posts twice");
        ConnectorResult avail = conn.invoke(req(UUID.randomUUID(), "available", payIn));
        assertEquals("40.000000", ((ConnectorResult.Success) avail).output().get("available"));
        ConnectorResult over = conn.invoke(req(UUID.randomUUID(), "encumber", Map.of("ledgerId", "BUDGET",
                "headOfAccount", "2054-00-001-01-00-13", "ddoCode", "DDO-1", "amount", "41")));
        assertInstanceOf(ConnectorResult.BusinessFailure.class, over);
        assertFalse(conn.lookup(req(UUID.randomUUID(), "consume", payIn)) instanceof org.sangam.core.integration.LookupResult.Found);
    }

    static ConnectorRequest req(UUID op, String operation, Map<String, Object> in) {
        return new ConnectorRequest("T", op, "att-1", 1, "root-1", "BK-1", operation, in, null);
    }
}

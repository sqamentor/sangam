// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.ports.PortsH2Engine;
import org.sangam.ports.support.EngineTx;

/**
 * On Oracle the skip-locked item select locks items chosen from the statement's snapshot, so it can return an item a
 * peer finished (DONE, committed) after that snapshot; running it again would execute the item twice. H2 cannot
 * reproduce the snapshot, so the peer's change is injected right before the claim UPDATE, on the same connection.
 */
class ProductBatchServiceSkipLockedFenceH2Test {

    private PortsH2Engine h2;

    @BeforeEach
    void setUp() {
        h2 = PortsH2Engine.start(null);
    }

    @AfterEach
    void tearDown() {
        h2.close();
    }

    @Test
    void anItemAPeerFinishedAfterTheSelectIsNotRunAgain() {
        Transactions tx = EngineTx.transactions(h2.engine);
        AtomicBoolean armed = new AtomicBoolean();
        AtomicBoolean raced = new AtomicBoolean();
        Transactions racing = new Transactions() {
            @Override
            public <T> T inTransaction(java.util.function.Function<Connection, T> work) {
                return tx.inTransaction(c -> work.apply(racing(c, armed, raced)));
            }

            @Override
            public <T> T read(org.sangam.core.api.ReadWork<T> work) {
                return tx.read(c -> work.read(racing(c, armed, raced)));
            }
        };
        AtomicInteger calls = new AtomicInteger();
        ProductBatchService batches = new ProductBatchService(Dialect.POSTGRESQL, racing, 3,
                ProductBatchService.FourEyesPolicy.of(Set.of(), 100));
        batches.register("count", (tenant, ref, cfg) -> {
            calls.incrementAndGet();
            return "ok";
        });
        String id = batches.create("t1", "count", List.of("a"), Map.of(), "maker");

        armed.set(true);
        int n = batches.runOnce(10);

        assertTrue(raced.get(), "the peer's finish was injected");
        assertEquals(0, n, "an item this worker did not claim is not counted");
        assertEquals(0, calls.get(), "the finished item is not executed again");
        List<ProductBatchService.Item> items = batches.items(id, null);
        assertEquals(1, items.size());
        assertEquals("DONE", items.get(0).state(), "left as the peer set it, not RUNNING");
        assertEquals(1, items.get(0).attempts(), "the peer's attempt only");
    }

    /** Before the first claim UPDATE, marks every item DONE on the same connection (as a committed peer would). */
    private static Connection racing(Connection real, AtomicBoolean armed, AtomicBoolean raced) {
        return (Connection) Proxy.newProxyInstance(ProductBatchServiceSkipLockedFenceH2Test.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (p, m, args) -> {
                    if (m.getName().equals("prepareStatement") && armed.get() && !raced.get()
                            && String.valueOf(args[0]).startsWith("UPDATE PORT_BATCH_ITEM SET state = 'RUNNING'")) {
                        raced.set(true);
                        Jdbc.update(real, "UPDATE PORT_BATCH_ITEM SET state = 'DONE', attempts = 1");
                    }
                    return invoke(real, m, args);
                });
    }

    private static Object invoke(Object target, Method m, Object[] args) throws Throwable {
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException ex) {
            throw ex.getCause();
        }
    }
}

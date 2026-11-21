// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.sla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.ports.PortsH2Engine;
import org.sangam.ports.sla.SlaService.SlaDefinition;
import org.sangam.ports.sla.SlaService.State;
import org.sangam.ports.support.EngineTx;

/**
 * On Oracle the sweep's skip-locked selects lock clocks chosen from the statement's snapshot, so they can return a clock
 * a peer warned, or a caller stopped, after that snapshot. No second warning/breach event may be emitted for it and a
 * stopped clock is not set BREACHED. H2 cannot reproduce the snapshot, so the peer's change is injected right before
 * each follow-up UPDATE, on the same connection.
 */
class SlaServiceSkipLockedFenceH2Test {

    private PortsH2Engine h2;
    private Transactions tx;

    @BeforeEach
    void setUp() {
        h2 = PortsH2Engine.start(null);
        tx = EngineTx.transactions(h2.engine);
    }

    @AfterEach
    void tearDown() {
        h2.close();
    }

    @Test
    void aClockAPeerWarnedOrStoppedAfterTheSelectGetsNoSecondEvent() {
        List<String> events = new ArrayList<>();
        SlaService.EventSink sink = (c, tenant, root, type, payload) -> events.add(type + ":" + root);
        SlaService plain = new SlaService(Dialect.POSTGRESQL, tx, sink, name -> null);
        String warned = tx.inTransaction(c -> plain.start(c, "t1", def("w", "P1D", "PT0S"), "process", "pi-w"));
        String stopped = tx.inTransaction(c -> plain.start(c, "t1", def("b", "PT0S", null), "process", "pi-b"));

        Map<String, String> races = new LinkedHashMap<>();
        // a peer warned the clock (and emitted its warning) after this sweep's select
        races.put("UPDATE SLA_INSTANCE SET warned_flag = 1", "UPDATE SLA_INSTANCE SET warned_flag = 1 WHERE id = '" + warned + "'");
        // a caller stopped the clock after this sweep's select
        races.put("UPDATE SLA_INSTANCE SET state = 'BREACHED'",
                "UPDATE SLA_INSTANCE SET state = 'MET', stopped_at = CURRENT_TIMESTAMP WHERE id = '" + stopped + "'");
        List<String> fired = new ArrayList<>();
        Transactions racing = new Transactions() {
            @Override
            public <T> T inTransaction(java.util.function.Function<Connection, T> work) {
                return tx.inTransaction(c -> work.apply(racing(c, races, fired)));
            }

            @Override
            public <T> T read(org.sangam.core.api.ReadWork<T> work) {
                return tx.read(c -> work.read(racing(c, races, fired)));
            }
        };
        SlaService sla = new SlaService(Dialect.POSTGRESQL, racing, sink, name -> null);

        int n = sla.sweep(100);

        assertEquals(List.copyOf(races.keySet()), fired, "both races were injected");
        assertEquals(List.of(), events, "no second warning, no breach of a stopped clock");
        assertEquals(0, n);
        assertTrue(plain.get(warned).orElseThrow().warned());
        SlaService.SlaClock s = plain.get(stopped).orElseThrow();
        assertEquals(State.MET, s.state(), "a stopped clock is not set BREACHED");
        assertNull(s.breachedAt());
    }

    private static SlaDefinition def(String key, String target, String warn) {
        return new SlaDefinition(key, target, warn, null, ZoneId.of("UTC"), "order");
    }

    /** Before the first statement starting with a key of {@code races}, runs its peer change on the same connection. */
    private static Connection racing(Connection real, Map<String, String> races, List<String> fired) {
        return (Connection) Proxy.newProxyInstance(SlaServiceSkipLockedFenceH2Test.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (p, m, args) -> {
                    if (m.getName().equals("prepareStatement")) {
                        String sql = String.valueOf(args[0]);
                        for (Map.Entry<String, String> r : races.entrySet()) {
                            if (sql.startsWith(r.getKey()) && !fired.contains(r.getKey())) {
                                fired.add(r.getKey());
                                Jdbc.update(real, r.getValue());
                            }
                        }
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

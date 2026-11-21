// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.support;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.sangam.core.api.EngineAdapter;
import org.sangam.core.api.ReadWork;
import org.sangam.core.layer.Dialect;

/**
 * PARKED-DEC R1: {@link EngineTxSupport#inTxWithStatus} is the write unit of the plain-Java host (inTx, runInTx and
 * withConnection run through it), so it runs in the engine's write command {@link EngineAdapter#inEngineTransaction},
 * never in the read twin {@link EngineAdapter#inEngineReadTransaction}. The read twins of TxSupport keep the read
 * command.
 */
class EngineTxSupportWriteCallbackTest {

    private final List<String> calls = new ArrayList<>();

    @SuppressWarnings("unchecked")
    private EngineAdapter recordingEngine() {
        return (EngineAdapter) Proxy.newProxyInstance(EngineAdapter.class.getClassLoader(),
                new Class<?>[] {EngineAdapter.class}, (p, m, args) -> {
                    calls.add(m.getName());
                    return switch (m.getName()) {
                        case "inEngineTransaction" -> ((Function<Connection, Object>) args[0]).apply(null);
                        case "inEngineReadTransaction" -> ((ReadWork<Object>) args[0]).read(null);
                        default -> throw new UnsupportedOperationException(m.getName());
                    };
                });
    }

    @Test
    void theWriteUnitRunsInTheEngineWriteCommand() {
        EngineTxSupport tx = new EngineTxSupport(recordingEngine(), null, Dialect.POSTGRESQL);
        assertEquals("w", tx.inTxWithStatus(status -> "w"));
        assertEquals("x", tx.inTx(() -> "x"));
        tx.runInTx(() -> { });
        assertEquals(List.of("inEngineTransaction", "inEngineTransaction", "inEngineTransaction"), calls);
    }

    @Test
    void theReadTwinsKeepTheEngineReadCommand() {
        EngineTxSupport tx = new EngineTxSupport(recordingEngine(), null, Dialect.POSTGRESQL);
        assertEquals("r", tx.inReadTx(() -> "r"));
        assertEquals("c", tx.withReadConnection(c -> "c"));
        assertEquals(List.of("inEngineReadTransaction", "inEngineReadTransaction"), calls);
    }
}

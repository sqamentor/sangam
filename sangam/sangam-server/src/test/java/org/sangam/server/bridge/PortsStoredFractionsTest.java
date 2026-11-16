// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.ports.support.Json;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.support.TypedVariables;

/**
 * Owner decision 381(4) follow-up: the ports runner of {@code PORT_BATCH} (PortsOpsBridge, the server's only batch
 * runner) reads a batch's stored configuration through the ports {@link Json} mapper and hands its parameters to
 * {@link TypedVariables}. A batch stored before the exact-decimal rule, and a {@code REST:} row of the default adapter,
 * carry fractions as plain JSON numbers; they are read as the exact decimal of their text and reach the engine, while a
 * binary float handed over in process is still refused by name. Before the fix the ports mapper read {@code 0.1} as a
 * Double and every such batch item failed with DECIMAL_FROM_FLOAT.
 */
class PortsStoredFractionsTest {

    static final String PRE_UPGRADE = "{\"filter\":null,\"params\":{\"variables\":{\"net\":1234.5,\"rate\":0.1,"
            + "\"wide\":12345678901234567890.123456789,\"lines\":[{\"qty\":2,\"price\":0.35}]}},\"itemKeyBase\":\"k\"}";

    @Test
    @SuppressWarnings("unchecked")
    void aStoredPreUpgradeBatchReachesTypedVariablesAsExactDecimals() {
        Map<String, Object> config = Json.map(PRE_UPGRADE);
        Map<String, Object> params = TypedVariables.fromJson((Map<String, Object>) config.get("params"));
        Map<String, Object> vars = (Map<String, Object>) params.get("variables");
        assertEquals(new BigDecimal("1234.5"), vars.get("net"));
        assertEquals(new BigDecimal("0.1"), vars.get("rate"));
        assertEquals(new BigDecimal("12345678901234567890.123456789"), vars.get("wide"));
        Map<String, Object> line = (Map<String, Object>) ((java.util.List<Object>) vars.get("lines")).get(0);
        assertEquals(new BigDecimal("0.35"), line.get("price"));
        assertEquals(2, ((Number) line.get("qty")).intValue());
        assertInstanceOf(BigDecimal.class, Json.plain(Json.tree("0.1")), "Json.plain is exact as well");
    }

    @Test
    void aBinaryFloatHandedOverInProcessIsStillRefusedByName() {
        ApiProblem p = assertThrows(ApiProblem.class, () -> TypedVariables.fromJson(Map.of("rate", 0.1d)));
        assertEquals(TypedVariables.DECIMAL_FROM_FLOAT, p.extensions().get("reason"));
    }
}

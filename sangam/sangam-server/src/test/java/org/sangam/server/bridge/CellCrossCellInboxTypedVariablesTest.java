// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.runtime.MessageCorrelationBuilder;
import org.operaton.bpm.engine.runtime.MessageCorrelationResult;
import org.sangam.integration.inbox.InboxHandler;

/**
 * Owner decision 381(4) follow-up: the cross-cell message's variables reach the correlation through TypedVariables:
 * a typed {@code Decimal} is the exact decimal with its scale, a plain fraction is the BigDecimal of its text, never a
 * Double. Before the fix the handler parsed the payload with a plain mapper and passed it on as is, so the typed form
 * arrived as a two-key map and {@code 0.1} as a Double.
 */
class CellCrossCellInboxTypedVariablesTest {

    @Test
    @SuppressWarnings("unchecked")
    void typedAndPlainFractionsArriveAsExactDecimals() {
        List<Map<String, Object>> set = new ArrayList<>();
        MessageCorrelationResult result = (MessageCorrelationResult) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {MessageCorrelationResult.class}, (p, m, a) -> null);
        MessageCorrelationBuilder[] builder = new MessageCorrelationBuilder[1];
        builder[0] = (MessageCorrelationBuilder) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {MessageCorrelationBuilder.class}, (p, m, a) -> switch (m.getName()) {
                    case "setVariables" -> {
                        set.add((Map<String, Object>) a[0]);
                        yield builder[0];
                    }
                    case "correlateAllWithResult" -> List.of(result);
                    default -> m.getReturnType().isInstance(builder[0]) ? builder[0] : null;
                });
        RuntimeService runtime = (RuntimeService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {RuntimeService.class}, (p, m, a) -> {
                    if ("createMessageCorrelation".equals(m.getName())) {
                        return builder[0];
                    }
                    throw new UnsupportedOperationException(m.getName());
                });
        String data = "{\"eventType\":\"x\",\"data\":{\"operationId\":\"op-1\",\"messageName\":\"paid\",\"businessKey\":\"BK\","
                + "\"variables\":{\"amount\":{\"type\":\"Decimal\",\"value\":\"1250.50\"},\"rate\":0.1,\"count\":3}}}";
        InboxHandler.Disposition d = new CellCrossCellInboxHandler(runtime).handle(null,
                new InboxHandler.Envelope("t1", "cell-b", "m-1", "x", null, null, data));

        assertEquals(InboxHandler.Disposition.PROCESSED, d);
        Map<String, Object> vars = set.get(0);
        assertEquals(new BigDecimal("1250.50"), vars.get("amount"), "the typed Decimal, scale kept");
        assertInstanceOf(BigDecimal.class, vars.get("rate"));
        assertEquals(new BigDecimal("0.1"), vars.get("rate"));
        assertEquals(3, ((Number) vars.get("count")).intValue());
        assertEquals("cell-b", vars.get("sangamXcellSource"));
    }
}

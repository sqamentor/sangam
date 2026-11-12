// SPDX-License-Identifier: Apache-2.0
package org.sangam.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.sangam.config.locale.FiscalYearNotConfiguredException;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;

/**
 * SL-125 (fail closed): when a step needs the current period and the tenant has no fiscal year, the ledger connector
 * answers a business failure {@code FISCAL_YEAR_NOT_CONFIGURED} with nothing posted. It never throws (a throw is an
 * unknown outcome that the invoker retries) and never invents a year. A step that names its own period is unaffected.
 */
class LedgerConnectorFiscalTest {

    static final Map<String, Object> RECEIVABLE = Map.of("id", "R", "kind", "RECEIVABLE", "currency", "USD",
            "stages", List.of("DEMAND"), "availability", Map.of("type", "NO_YEAR"));

    static LedgerService service() {
        LedgerService s = new LedgerService(new LedgerStore.InMemory(), null);
        s.define("T", List.of(LedgerDefinition.from(RECEIVABLE)));
        return s;
    }

    static ConnectorRequest req(String op, Map<String, Object> in) {
        return new ConnectorRequest("T", UUID.randomUUID(), "att-1", 1, "root-1", "BK-1", op, in, null);
    }

    static final java.util.function.BiFunction<String, java.time.LocalDate, String> NO_FISCAL = (t, d) -> {
        throw FiscalYearNotConfiguredException.forSubject("profile US");
    };

    @Test
    void theCurrentPeriodWithoutAFiscalYearIsABusinessFailureAndNothingIsPosted() {
        LedgerService s = service();
        LedgerConnector conn = new LedgerConnector(s, NO_FISCAL, null);
        for (Map<String, Object> in : List.of(Map.<String, Object>of("ledgerId", "R", "line", "CLAIM", "holder", "P-1", "amount", "1000"),
                Map.<String, Object>of("ledgerId", "R", "line", "CLAIM", "holder", "P-1", "amount", "1000", "period", "current"))) {
            ConnectorResult r = conn.invoke(req("allocate", in));
            ConnectorResult.BusinessFailure f = assertInstanceOf(ConnectorResult.BusinessFailure.class, r);
            assertEquals(FiscalYearNotConfiguredException.CODE, f.code());
            assertTrue(f.message().contains("locale.fiscal"), f.message());
        }
        AccountKey any = new AccountKey("T", "R", "OPEN", "CLAIM", "P-1", "DEMAND", null);
        assertEquals(0, BigDecimal.ZERO.compareTo(s.available(any)), "nothing was posted");
    }

    @Test
    void anExplicitPeriodNeverAsksTheResolver() {
        AtomicInteger asked = new AtomicInteger();
        LedgerConnector conn = new LedgerConnector(service(), (t, d) -> {
            asked.incrementAndGet();
            throw FiscalYearNotConfiguredException.forSubject("profile US");
        }, null);
        ConnectorResult r = conn.invoke(req("allocate", Map.of("ledgerId", "R", "line", "CLAIM", "holder", "P-1",
                "amount", "1000", "period", "OPEN")));
        assertInstanceOf(ConnectorResult.Success.class, r);
        assertEquals(0, asked.get());
    }
}

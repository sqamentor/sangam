// SPDX-License-Identifier: Apache-2.0
package org.sangam.billing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;

import org.junit.jupiter.api.Test;
import org.sangam.config.catalogue.NumberSeries;
import org.sangam.config.locale.FiscalYearNotConfiguredException;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.ledger.LedgerService;

/**
 * SL-127 (fail closed): a billing step that needs a period (invoice, penalty, waive) and names none takes the tenant's
 * current fiscal period from the resolver; without a fiscal year (or without a resolver) it is a business failure
 * {@code FISCAL_YEAR_NOT_CONFIGURED}, never the calendar year it used to invent. Steps without a period (arrears) and
 * steps with an explicit period never ask the resolver.
 */
class BillingConnectorPeriodTest {

    static ConnectorRequest req(String op, Map<String, Object> in) {
        return new ConnectorRequest("T", UUID.randomUUID(), "att-1", 1, "root-1", "BK-1", op, in, null);
    }

    static BillingConnector connector(BiFunction<String, LocalDate, String> resolver) {
        LedgerService l = BillingServiceTest.ledger();
        BillingService b = new BillingService(l, new TariffEngine(null), new NumberSeries.InMemory(), null);
        return new BillingConnector(b, l, null, (t, c) -> List.of(), resolver, null);
    }

    static final BiFunction<String, LocalDate, String> NO_FISCAL = (t, d) -> {
        throw FiscalYearNotConfiguredException.forSubject("profile T");
    };

    @Test
    void aPeriodStepWithoutAFiscalYearIsRefusedNeverACalendarYear() {
        for (BiFunction<String, LocalDate, String> resolver : java.util.Arrays.asList(NO_FISCAL, null)) {
            for (String op : List.of("waive", "penalty")) {
                for (Map<String, Object> in : List.of(Map.<String, Object>of("consumer", "C-1", "head", "WATER", "amount", "100",
                        "orderRef", "W-1", "tariffId", "DEFAULT"), Map.<String, Object>of("consumer", "C-1", "head", "WATER",
                        "amount", "100", "orderRef", "W-1", "tariffId", "DEFAULT", "period", "current"))) {
                    ConnectorResult r = connector(resolver).invoke(req(op, in));
                    ConnectorResult.BusinessFailure f = assertInstanceOf(ConnectorResult.BusinessFailure.class, r, op);
                    assertEquals(FiscalYearNotConfiguredException.CODE, f.code(), op + " " + f.message());
                }
            }
        }
    }

    @Test
    void stepsWithoutAPeriodOrWithAnExplicitOneNeverAskTheResolver() {
        List<String> asked = new ArrayList<>();
        BillingConnector c = connector((t, d) -> {
            asked.add(t);
            throw FiscalYearNotConfiguredException.forSubject("profile T");
        });
        assertInstanceOf(ConnectorResult.Success.class, c.invoke(req("arrears", Map.of("consumer", "C-1"))));
        ConnectorResult waive = c.invoke(req("waive", Map.of("consumer", "C-1", "head", "WATER", "amount", "0",
                "orderRef", "W-2", "period", "2026-27")));
        if (waive instanceof ConnectorResult.BusinessFailure f) {
            assertNotEquals(FiscalYearNotConfiguredException.CODE, f.code(), f.message());
        }
        assertTrue(asked.isEmpty(), "resolver asked for " + asked);
    }

    @Test
    void aStepWithoutAPeriodUsesTheResolversFiscalPeriod() {
        List<String> periods = new ArrayList<>();
        BillingConnector c = connector((t, d) -> {
            periods.add(t + "@" + d);
            return "2026-27";
        });
        c.invoke(req("waive", Map.of("consumer", "C-1", "head", "WATER", "amount", "0", "orderRef", "W-3")));
        assertEquals(1, periods.size(), periods.toString());
        assertTrue(periods.get(0).startsWith("T@"), periods.toString());
    }
}

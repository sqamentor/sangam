// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.billing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.sangam.config.locale.FiscalYearNotConfiguredException;
import org.sangam.ledger.LedgerService;
import org.sangam.ledger.LedgerStore;
import org.sangam.rest.modules.ledger.LedgerTenantDefinitions;
import org.sangam.rest.modules.ledger.LedgerTenantDefinitionsFiscalTest;
import org.sangam.rest.port.ApiProblem;

/**
 * SL-127 (fail closed): a refund without a period takes the tenant's current fiscal period (the same resolver as the
 * billing connector's invoice), never an invented calendar year; without a fiscal year it is refused with 422
 * {@code FISCAL_YEAR_NOT_CONFIGURED}. An explicit period is used as given.
 */
class BillingRefundPeriodTest {

    static LedgerTenantDefinitions defs() {
        return new LedgerTenantDefinitions(new LedgerService(new LedgerStore.InMemory(), null),
                LedgerTenantDefinitionsFiscalTest.profiles());
    }

    @Test
    void aRefundWithoutAPeriodAndWithoutAFiscalYearIsRefused() {
        for (String requested : new String[]{null, "", "current"}) {
            ApiProblem p = assertThrows(ApiProblem.class, () -> BillingController.refundPeriod(defs(), "NOFY", requested));
            assertEquals(422, p.status());
            assertEquals(FiscalYearNotConfiguredException.CODE, p.code());
        }
    }

    @Test
    void aRefundWithoutAPeriodUsesTheFiscalPeriodAndAnExplicitOneIsKept() {
        String fy = defs().period("FY", defs().ledger().today("FY"));
        assertEquals(fy, BillingController.refundPeriod(defs(), "FY", null));
        assertEquals("2025-26", BillingController.refundPeriod(defs(), "NOFY", "2025-26"));
    }
}

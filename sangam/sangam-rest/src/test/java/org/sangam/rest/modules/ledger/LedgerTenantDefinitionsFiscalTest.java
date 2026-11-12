// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.sangam.config.locale.FiscalYearNotConfiguredException;
import org.sangam.config.profile.InMemoryProfileRepository;
import org.sangam.config.profile.LayerModel;
import org.sangam.config.profile.ProfileDocument;
import org.sangam.config.profile.ProfileLoader;
import org.sangam.config.profile.ProfileResolver;
import org.sangam.ledger.LedgerService;
import org.sangam.ledger.LedgerStore;

/**
 * SL-125 (fail closed): the tenant period resolver never invents a year and never swallows the refusal. It refuses
 * without a profile resolver, for a tenant without a profile, and for a profile without {@code locale.fiscal}; with a
 * fiscal calendar it answers the label of the tenant's fiscal year.
 */
public class LedgerTenantDefinitionsFiscalTest {

    static final LocalDate DAY = LocalDate.of(2026, 11, 12);

    public static ProfileResolver profiles() {
        ProfileDocument noFiscal = ProfileLoader.parse("""
                profile: {id: NOFY, layer: jurisdiction, version: '1', effectiveFrom: '2026-01-01'}
                locale: {currency: USD}
                """);
        ProfileDocument withFiscal = ProfileLoader.parse("""
                profile: {id: FY, layer: jurisdiction, version: '1', effectiveFrom: '2026-01-01'}
                locale: {currency: KES, fiscal: {startMonth: 7, startDay: 1, label: END}}
                """);
        return new ProfileResolver(InMemoryProfileRepository.active(List.of(noFiscal, withFiscal)), LayerModel.open());
    }

    static LedgerService ledger() {
        return new LedgerService(new LedgerStore.InMemory(), null);
    }

    @Test
    void withoutAProfileResolverTheFiscalPeriodIsRefused() {
        FiscalYearNotConfiguredException e = assertThrows(FiscalYearNotConfiguredException.class,
                () -> new LedgerTenantDefinitions(ledger(), null).period("T1", DAY));
        assertEquals(FiscalYearNotConfiguredException.CODE, e.code());
        assertTrue(e.getMessage().contains("T1"), e.getMessage());
    }

    @Test
    void aTenantWithoutAProfileIsRefusedNamingTheTenant() {
        FiscalYearNotConfiguredException e = assertThrows(FiscalYearNotConfiguredException.class,
                () -> new LedgerTenantDefinitions(ledger(), profiles()).period("UNKNOWN", DAY));
        assertTrue(e.getMessage().contains("UNKNOWN") && e.getMessage().contains("no configuration profile"), e.getMessage());
    }

    @Test
    void aProfileWithoutFiscalIsRefusedNamingTheProfileAndThePath() {
        FiscalYearNotConfiguredException e = assertThrows(FiscalYearNotConfiguredException.class,
                () -> new LedgerTenantDefinitions(ledger(), profiles()).period("NOFY", DAY));
        assertTrue(e.getMessage().contains("NOFY") && e.getMessage().contains("locale.fiscal"), e.getMessage());
        assertThrows(FiscalYearNotConfiguredException.class,
                () -> new LedgerTenantDefinitions(ledger(), profiles()).periodResolver().apply("NOFY", DAY));
    }

    @Test
    void aConfiguredFiscalYearGivesItsLabel() {
        assertEquals("2027", new LedgerTenantDefinitions(ledger(), profiles()).period("FY", DAY));
    }
}

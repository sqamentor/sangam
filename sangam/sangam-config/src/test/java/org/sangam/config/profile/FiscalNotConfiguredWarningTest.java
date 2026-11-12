// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.sangam.config.common.Finding;
import org.sangam.config.locale.FiscalYearNotConfiguredException;

/**
 * SL-125: the resolver tells operators when a profile has no fiscal year (WARNING {@code FISCAL_NOT_CONFIGURED}, not an
 * error: the profile stays valid and activatable), and a present but incomplete {@code locale.fiscal} is an ERROR
 * ({@code FISCAL_INVALID}). The resolved locale refuses {@code fiscal()} naming the scope.
 */
class FiscalNotConfiguredWarningTest {

    static final LocalDate ON = LocalDate.of(2026, 11, 12);

    static ProfileDocument doc(String id, String locale) {
        return ProfileLoader.parse("""
                profile:
                  id: %s
                  layer: jurisdiction
                  version: '1'
                  effectiveFrom: '2026-01-01'
                locale:
                %s
                """.formatted(id, locale.indent(2).stripTrailing()));
    }

    static ResolvedProfile resolve(ProfileDocument d) {
        return new ProfileResolver(InMemoryProfileRepository.active(List.of(d)), LayerModel.open()).resolve(d.id(), ON);
    }

    static List<String> codes(ResolvedProfile p, Finding.Severity s) {
        return p.findings().stream().filter(f -> f.severity() == s).map(Finding::code).toList();
    }

    @Test
    void aProfileWithoutFiscalWarnsAndRefusesFiscalOperations() {
        ResolvedProfile p = resolve(doc("NOFY", "currency: USD"));
        assertFalse(p.hasErrors(), p.findings()::toString);
        assertEquals(List.of(ProfileResolver.FISCAL_NOT_CONFIGURED), codes(p, Finding.Severity.WARNING));
        assertEquals("locale.fiscal", p.findings().stream().filter(f -> f.code().equals(ProfileResolver.FISCAL_NOT_CONFIGURED))
                .findFirst().orElseThrow().path());
        assertFalse(p.locale().hasFiscal());
        FiscalYearNotConfiguredException e = assertThrows(FiscalYearNotConfiguredException.class, () -> p.locale().fiscal());
        assertTrue(e.getMessage().contains("NOFY"), e.getMessage());
        assertTrue(e.getMessage().contains("locale.fiscal"), e.getMessage());
        // the rest of the locale works
        assertEquals("USD", p.locale().money().currency().getCurrencyCode());
    }

    @Test
    void anIncompleteFiscalIsAnError() {
        ResolvedProfile p = resolve(doc("HALF", "currency: EUR\nfiscal: {startMonth: 7, label: END}"));
        assertTrue(codes(p, Finding.Severity.ERROR).contains(ProfileResolver.FISCAL_INVALID), p.findings()::toString);
        assertTrue(p.findings().stream().anyMatch(f -> f.message().contains("locale.fiscal.startDay")), p.findings()::toString);
        assertFalse(codes(p, Finding.Severity.WARNING).contains(ProfileResolver.FISCAL_NOT_CONFIGURED));
    }

    @Test
    void aCompleteFiscalHasNoFiscalFinding() {
        ResolvedProfile p = resolve(doc("FULL", "currency: KES\nfiscal: {startMonth: 7, startDay: 1, label: END}"));
        assertTrue(p.findings().stream().noneMatch(f -> f.code().startsWith("FISCAL_")), p.findings()::toString);
        assertTrue(p.locale().hasFiscal());
        assertEquals("2027", p.locale().fiscal().yearOf(ON).label());
    }
}

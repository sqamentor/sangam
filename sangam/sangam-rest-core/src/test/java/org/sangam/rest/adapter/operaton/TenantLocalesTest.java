// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.sangam.config.common.Finding;
import org.sangam.config.profile.ResolvedProfile;
import org.sangam.rest.port.WorkspacePort;

/**
 * M-045: {@code /me} carries the tenant's profile locale (currency, zone, grouping), each part only when the profile
 * declares it, and nothing when the tenant has no profile or no locale section. The values here are test data, not
 * defaults.
 */
class TenantLocalesTest {

    static final LocalDate DAY = LocalDate.of(2026, 11, 12);
    static final ObjectMapper JSON = new ObjectMapper();

    static ResolvedProfile profile(Map<String, Object> data, List<Finding> findings) {
        return new ResolvedProfile("t1", null, DAY, data, List.of("base@1"), "base@1#000000000000", Map.of(), findings);
    }

    static TenantLocales locales(ResolvedProfile p) {
        return new TenantLocales((tenant, day) -> p, tenant -> DAY);
    }

    @Test
    void aDeclaredLocaleIsReturnedPartByPart() throws Exception {
        WorkspacePort.MeLocale l = locales(profile(Map.of("locale", Map.of("currency", "EUR", "zone", "Europe/Berlin",
                "grouping", Map.of("first", 3, "rest", 2))), List.of())).of("t1");
        assertEquals("EUR", l.currency());
        assertEquals("Europe/Berlin", l.zone());
        assertEquals(new WorkspacePort.NumberGrouping(3, 2), l.grouping());
        assertEquals("{\"currency\":\"EUR\",\"zone\":\"Europe/Berlin\",\"grouping\":{\"first\":3,\"rest\":2}}",
                JSON.writeValueAsString(l));
    }

    @Test
    void onlyTheDeclaredPartsArePresent() throws Exception {
        WorkspacePort.MeLocale l = locales(profile(Map.of("locale", Map.of("zone", "America/Lima")), List.of())).of("t1");
        assertNull(l.currency());
        assertNull(l.grouping());
        assertEquals("{\"zone\":\"America/Lima\"}", JSON.writeValueAsString(l), "no assumed currency or grouping");
    }

    @Test
    void noProfileNoLocaleSectionOrAnInvalidOneGivesNoLocale() {
        assertNull(locales(profile(Map.of(), List.of(new Finding(Finding.Severity.ERROR, "PROFILE_NOT_FOUND", "",
                "no profile", null)))).of("t1"));
        assertNull(locales(profile(Map.of("ledgers", List.of()), List.of())).of("t1"));
        assertNull(locales(profile(Map.of("locale", Map.of("units", Map.of("K", 1000))), List.of())).of("t1"),
                "a locale without currency, zone or grouping");
        assertNull(locales(profile(Map.of("locale", Map.of("currency", "NOT-A-CODE")), List.of())).of("t1"),
                "an invalid section never breaks /me");
        assertNull(locales(profile(Map.of("locale", Map.of("zone", "Europe/Berlin")), List.of())).of(null));
    }

    @Test
    void meWithoutALocaleHasNoLocaleField() throws Exception {
        WorkspacePort.Me me = new WorkspacePort.Me("t1", null, "p", "p", "p", List.of(), List.of(), List.of(), null, null,
                "OIDC", List.of(), null);
        assertNull(me.locale());
        assertFalse(JSON.writeValueAsString(me).contains("locale"));
        WorkspacePort.Me with = new WorkspacePort.Me("t1", null, "p", "p", "p", List.of(), List.of(), List.of(), null,
                null, "OIDC", List.of(), null, new WorkspacePort.MeLocale("EUR", null, null));
        assertTrue(JSON.writeValueAsString(with).contains("\"locale\":{\"currency\":\"EUR\"}"));
    }

    /** Owner option (b) 2026-11-19: sandbox appears on /me only for a demo sandbox tenant. */
    @Test
    void meCarriesSandboxOnlyForASandboxTenant() throws Exception {
        WorkspacePort.Me plain = new WorkspacePort.Me("t1", null, "p", "p", "p", List.of(), List.of(), List.of(), null,
                null, "OIDC", List.of(), null);
        assertNull(plain.sandbox());
        assertFalse(JSON.writeValueAsString(plain).contains("sandbox"));
        WorkspacePort.Me sandbox = new WorkspacePort.Me("t1", null, "p", "p", "p", List.of(), List.of(), List.of(),
                null, null, "OIDC", List.of(), null, null, Boolean.TRUE);
        assertTrue(JSON.writeValueAsString(sandbox).contains("\"sandbox\":true"));
    }
}

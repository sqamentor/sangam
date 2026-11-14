// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import java.time.LocalDate;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.sangam.config.common.Tree;
import org.sangam.config.locale.LocaleSettings;
import org.sangam.config.locale.NumberGrouping;
import org.sangam.config.profile.ResolvedProfile;
import org.sangam.rest.port.WorkspacePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * M-045: the {@code locale} of {@code /me} from the tenant's resolved profile (sangam-config {@link LocaleSettings}):
 * currency (ISO 4217), zone (IANA) and digit grouping, each only when the profile's {@code locale} section declares it,
 * so the UI never formats with a value the tenant did not configure (no domain or country default, SL-104). Absent
 * (null) when the tenant has no profile, the profile has no {@code locale} section, or the section is invalid (logged;
 * {@code /me} itself must still answer).
 */
final class TenantLocales {

    private static final Logger LOG = LoggerFactory.getLogger(TenantLocales.class);

    private final BiFunction<String, LocalDate, ResolvedProfile> profiles;
    private final Function<String, LocalDate> today;

    /**
     * @param profiles the profile resolver ({@code ProfileResolver::resolve})
     * @param today    the tenant's business date
     */
    TenantLocales(BiFunction<String, LocalDate, ResolvedProfile> profiles, Function<String, LocalDate> today) {
        this.profiles = profiles;
        this.today = today;
    }

    /** The tenant's locale, or null (see the class comment). */
    WorkspacePort.MeLocale of(String tenantId) {
        if (tenantId == null) {
            return null;
        }
        try {
            ResolvedProfile p = profiles.apply(tenantId, today.apply(tenantId));
            if (p == null || p.findings().stream().anyMatch(f -> "PROFILE_NOT_FOUND".equals(f.code()))) {
                return null;
            }
            return fromSection(Tree.map(p.get("locale")));
        } catch (RuntimeException e) {
            LOG.warn("tenant {}: the profile locale cannot be read for /me: {}", tenantId, e.toString());
            return null;
        }
    }

    /** The declared parts of one profile {@code locale} section; null when it declares none of them. */
    static WorkspacePort.MeLocale fromSection(Map<String, Object> locale) {
        if (locale == null || locale.isEmpty()) {
            return null;
        }
        LocaleSettings parsed = LocaleSettings.from(locale);   // validates zone and currency
        String currency = locale.get("currency") == null ? null : parsed.money().currency().getCurrencyCode();
        String zone = locale.get("zone") == null ? null : parsed.zone().getId();
        WorkspacePort.NumberGrouping grouping = null;
        if (locale.get("grouping") != null) {
            NumberGrouping g = NumberGrouping.from(Tree.map(locale.get("grouping")));
            grouping = new WorkspacePort.NumberGrouping(g.first(), g.rest());
        }
        return currency == null && zone == null && grouping == null ? null
                : new WorkspacePort.MeLocale(currency, zone, grouping);
    }
}

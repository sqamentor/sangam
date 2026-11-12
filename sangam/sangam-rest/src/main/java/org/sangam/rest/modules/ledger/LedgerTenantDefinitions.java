// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.ledger;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

import org.sangam.config.locale.FiscalYearNotConfiguredException;
import org.sangam.config.profile.ProfileResolver;
import org.sangam.config.profile.ResolvedProfile;
import org.sangam.ledger.LedgerDefinition;
import org.sangam.ledger.LedgerService;

/**
 * Registers a tenant's ledger definitions (its resolved profile section {@code ledgers}, sangam-config) with the
 * {@link LedgerService} before the first use, and re-registers when the tenant's profile binding changes (a newly
 * activated profile version). Also resolves the current fiscal period label of a tenant (the ledger connector's
 * period resolver). Shared by the REST endpoints and the server's ledger connector, so both see the same definitions.
 */
public final class LedgerTenantDefinitions {

    private final LedgerService ledger;
    private final ProfileResolver profiles;
    /** tenant → profile binding the definitions were registered from (a cache; the profile is the source). */
    private final Map<String, String> registered = new ConcurrentHashMap<>();

    public LedgerTenantDefinitions(LedgerService ledger, ProfileResolver profiles) {
        this.ledger = ledger;
        this.profiles = profiles;
    }

    public LedgerService ledger() {
        return ledger;
    }

    /** Registers the tenant's definitions if its profile binding changed since the last call. */
    public void ensure(String tenant) {
        if (profiles == null || tenant == null) {
            return;
        }
        ResolvedProfile p;
        try {
            p = profiles.resolve(tenant, ledger.today(tenant));
        } catch (RuntimeException e) {
            return;   // tenant without a profile: definitions must come from elsewhere (definition() then fails clearly)
        }
        String binding = p.binding() == null ? "-" : p.binding();
        if (binding.equals(registered.get(tenant))) {
            return;
        }
        List<LedgerDefinition> defs = LedgerDefinition.all(p.get("ledgers"));
        if (!defs.isEmpty()) {
            // SL-98: a money ledger without its currency is refused here, naming the ledger and the profile
            ledger.define(tenant, binding + " (tenant " + tenant + ")", defs);
        }
        registered.put(tenant, binding);
    }

    /** The ledger ids defined for the tenant by its profile (empty without a profile). */
    public List<String> ids(String tenant) {
        if (profiles == null) {
            return List.of();
        }
        try {
            return LedgerDefinition.all(profiles.resolve(tenant, ledger.today(tenant)).get("ledgers")).stream()
                    .map(LedgerDefinition::id).toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /**
     * Current period label of the tenant on {@code day} (its fiscal calendar), e.g. {@code 2026-27}. SL-125 (fail
     * closed): never an invented year. Refuses with {@link FiscalYearNotConfiguredException} when there is no profile
     * resolver, when the tenant has no profile, or when its profile has no (complete) {@code locale.fiscal}.
     */
    public String period(String tenant, LocalDate day) {
        if (profiles == null) {
            throw new FiscalYearNotConfiguredException("no configuration profile resolver: the fiscal period of tenant "
                    + tenant + " cannot be resolved; fiscal operations are refused");
        }
        ResolvedProfile p = profiles.resolve(tenant, day);
        if (p.findings().stream().anyMatch(f -> "PROFILE_NOT_FOUND".equals(f.code()))) {
            throw new FiscalYearNotConfiguredException("tenant " + tenant + " has no configuration profile on " + day
                    + ", so it has no fiscal year (" + FiscalYearNotConfiguredException.PATH + "); fiscal operations are refused");
        }
        return p.locale().fiscal().yearOf(day).label();
    }

    public BiFunction<String, LocalDate, String> periodResolver() {
        return this::period;
    }
}

// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.locale;

import org.sangam.config.profile.ProfileResolver;
import org.sangam.config.profile.ResolvedProfile;
import org.sangam.core.time.BusinessClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * {@link BusinessClock} from the tenant's resolved profile: {@code locale.zone} (default UTC, see
 * {@link LocaleSettings}).
 *
 * <h2>Resolving the zone without knowing the date</h2>
 * Resolving a profile needs an as-of date, and the as-of date is what we want to compute. The rule:
 * <ol>
 *   <li>Resolve the profile on the <b>UTC date</b> of the instant and read its zone {@code z1}.</li>
 *   <li>Compute the local date {@code d1} of the instant in {@code z1}. If {@code d1} equals the UTC date, done.</li>
 *   <li>Otherwise (the tenant is already in the next day, or still in the previous one) resolve again on {@code d1}
 *       and read {@code z2}. If the instant's date in {@code z2} is still {@code d1}, use {@code z2}: it is the zone
 *       in force on the tenant's own local day, which is exactly the self-consistent answer. If not (only possible
 *       when a zone change on that very day would move the date back across the boundary), keep {@code z1}.</li>
 * </ol>
 * Why this is correct: a zone is an offset of at most ±18h, so the tenant's local date is the UTC date or one day
 * either side. Profile documents are effective per calendar day; for every day on which the zone does not change,
 * steps 1-2 already give the zone of the tenant's day. On the day a profile's zone changes (effective date {@code D})
 * step 3 re-reads the zone on the tenant's own local date, so from local 00:00 of {@code D} the new zone is used
 * even while the UTC date is still {@code D-1}; a fixed point is preferred, and the previous zone is the
 * deterministic tie-break when none exists.
 *
 * <h2>Fallback</h2>
 * A null tenant, or a tenant with no active profile, is in UTC; the missing profile is logged once per tenant.
 * Other resolution failures (store down, invalid zone id) propagate: a wrong business date is worse than an error.
 */
public final class ProfileBusinessClock implements BusinessClock {

    private static final Logger LOG = LoggerFactory.getLogger(ProfileBusinessClock.class);

    private final Supplier<ProfileResolver> resolver;
    private final Clock clock;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public ProfileBusinessClock(ProfileResolver resolver, Clock clock) {
        this(() -> resolver, clock);
    }

    /** Lazy resolver (may supply null: every tenant is then in UTC). */
    public ProfileBusinessClock(Supplier<ProfileResolver> resolver, Clock clock) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Clock clock() {
        return clock;
    }

    @Override
    public ZoneId zone(String tenantId) {
        return zoneAt(tenantId, clock.instant());
    }

    /** The tenant's business zone at an instant (see the class comment). */
    public ZoneId zoneAt(String tenantId, Instant at) {
        ProfileResolver r = resolver.get();
        if (tenantId == null || tenantId.isBlank() || r == null) {
            return ZoneOffset.UTC;
        }
        LocalDate utcDate = at.atZone(ZoneOffset.UTC).toLocalDate();
        ZoneId z1 = zoneOn(r, tenantId, utcDate);
        if (z1 == null) {
            if (warned.add(tenantId)) {
                LOG.warn("Tenant {} has no active profile on {}: business dates in UTC", tenantId, utcDate);
            }
            return ZoneOffset.UTC;
        }
        LocalDate d1 = at.atZone(z1).toLocalDate();
        if (d1.equals(utcDate)) {
            return z1;
        }
        ZoneId z2 = zoneOn(r, tenantId, d1);
        return z2 != null && at.atZone(z2).toLocalDate().equals(d1) ? z2 : z1;
    }

    private static ZoneId zoneOn(ProfileResolver r, String tenant, LocalDate date) {
        ResolvedProfile p = r.resolve(tenant, date);
        boolean missing = p.findings().stream().anyMatch(f -> "PROFILE_NOT_FOUND".equals(f.code()));
        return missing ? null : p.locale().zone();
    }
}

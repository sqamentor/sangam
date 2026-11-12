// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import org.sangam.config.locale.BusinessCalendar;
import org.sangam.packs.port.TenantWorkingDays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The packs' working days from the tenant's profile business calendar (SL-34): zone, weekend, gazetted holidays and
 * extra working days of the calendar named by {@code sangam.packs.calendar} (default {@code government}) in the
 * tenant's resolved profile, so a holiday added to the profile moves every pack SLA counted in working days. A tenant
 * whose profile does not resolve keeps the packs' default calendar (Saturday and Sunday off, in the tenant's business
 * zone: UTC without a profile). That is logged as a WARN once per tenant (SL-104), never silently.
 */
public final class ProfileWorkingDays implements TenantWorkingDays {

    private static final Logger LOG = LoggerFactory.getLogger(ProfileWorkingDays.class);

    private final Function<String, BusinessCalendar> calendarOf;
    private final java.util.Set<String> warned = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.function.Consumer<String> warn;

    /** {@code calendarOf}: tenant → the profile calendar in force today (may throw when the profile does not resolve). */
    public ProfileWorkingDays(Function<String, BusinessCalendar> calendarOf) {
        this(calendarOf, LOG::warn);
    }

    /** {@code warn} receives the once-per-tenant message (the WARN log in production). */
    ProfileWorkingDays(Function<String, BusinessCalendar> calendarOf, java.util.function.Consumer<String> warn) {
        this.calendarOf = Objects.requireNonNull(calendarOf, "calendarOf");
        this.warn = Objects.requireNonNull(warn, "warn");
    }

    @Override
    public Optional<Days> of(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return Optional.empty();
        }
        BusinessCalendar c;
        try {
            c = calendarOf.apply(tenantId);
        } catch (RuntimeException e) {
            if (warned.add(tenantId)) {
                warn.accept("SL-104: no profile calendar for tenant " + tenantId + " (" + e + "): its pack SLAs use the"
                        + " default working days (Saturday and Sunday off) in its business zone, UTC without a profile");
            }
            return Optional.empty();
        }
        return c == null ? Optional.empty() : Optional.of(new Days(c.zone(), c::isBusinessDay));
    }
}

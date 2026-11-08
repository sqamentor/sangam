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
 * whose profile does not resolve keeps the packs' default calendar (logged at debug).
 */
public final class ProfileWorkingDays implements TenantWorkingDays {

    private static final Logger LOG = LoggerFactory.getLogger(ProfileWorkingDays.class);

    private final Function<String, BusinessCalendar> calendarOf;

    /** {@code calendarOf}: tenant → the profile calendar in force today (may throw when the profile does not resolve). */
    public ProfileWorkingDays(Function<String, BusinessCalendar> calendarOf) {
        this.calendarOf = Objects.requireNonNull(calendarOf, "calendarOf");
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
            LOG.debug("no profile calendar for tenant {}: {}", tenantId, e.toString());
            return Optional.empty();
        }
        return c == null ? Optional.empty() : Optional.of(new Days(c.zone(), c::isBusinessDay));
    }
}

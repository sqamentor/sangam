// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.support;

import org.sangam.core.time.BusinessClock;

import java.time.Clock;
import java.time.LocalDate;
import java.util.function.Supplier;

/**
 * Default business dates of the REST layer: "today" of the request's tenant from the {@link BusinessClock} bean
 * (the tenant's profile zone), never the JVM zone. Without that bean (config module off) business dates are UTC.
 * The bean is looked up at use time.
 */
public final class BusinessDates {

    private static final BusinessClock UTC = BusinessClock.utc(Clock.systemUTC());

    private final Supplier<BusinessClock> clock;

    public BusinessDates(Supplier<BusinessClock> clock) {
        this.clock = clock == null ? () -> null : clock;
    }

    /** UTC business dates for every tenant. */
    public static BusinessDates utc() {
        return new BusinessDates(() -> UTC);
    }

    public BusinessClock clock() {
        BusinessClock b = clock.get();
        return b != null ? b : UTC;
    }

    public LocalDate today(String tenantId) {
        return clock().today(tenantId);
    }

    /** {@code date}, or today of the tenant when null. */
    public LocalDate orToday(LocalDate date, String tenantId) {
        return date != null ? date : today(tenantId);
    }
}

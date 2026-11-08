// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.port;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The tenant's working days as configured outside the packs (SL-34): its zone and which dates are working days
 * (weekend, gazetted holidays, extra working days). {@code sangam-server} answers it from the tenant's resolved profile
 * calendar, so a holiday added to the profile moves every pack SLA that counts working days. Empty means "no tenant
 * configuration": the calendar then uses its own defaults.
 */
@FunctionalInterface
public interface TenantWorkingDays {

    /** No tenant configuration: every tenant uses the calendar's own defaults. */
    TenantWorkingDays NONE = tenantId -> Optional.empty();

    /** The working-day rule in force for {@code tenantId}, or empty. */
    Optional<Days> of(String tenantId);

    /** A zone and a working-day test. */
    record Days(ZoneId zone, Predicate<LocalDate> working) {
        public Days {
            Objects.requireNonNull(zone, "zone");
            Objects.requireNonNull(working, "working");
        }
    }
}

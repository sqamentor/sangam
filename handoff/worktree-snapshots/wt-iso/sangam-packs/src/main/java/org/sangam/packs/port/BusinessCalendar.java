// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.port;

import java.time.Duration;
import java.time.Instant;

/**
 * Business calendar for SLA and statutory clocks (GP-06, R31, R79).
 *
 * <p>SLA specifications used by the packs:
 * <ul>
 *   <li>{@code 3WD}: three working days (weekends and tenant holidays skipped, deadline at close of business);</li>
 *   <li>{@code 30D}: thirty calendar days (RTI s7(1), appeal windows);</li>
 *   <li>{@code 48H}: forty-eight clock hours (RTI life and liberty proviso);</li>
 *   <li>{@code 8WH}: eight working hours;</li>
 *   <li>an ISO-8601 duration such as {@code P15D} or {@code PT4H} (calendar time).</li>
 * </ul>
 * All instants are UTC (A-12); the calendar applies the tenant's zone (IST by default) only to find working time.
 */
public interface BusinessCalendar {

    /** The instant at which an SLA of {@code spec} started at {@code from} falls due. */
    Instant due(String tenantId, Instant from, String spec);

    /** Working time elapsed between two instants (used to pause and resume statutory clocks, GP-45). */
    Duration workingTimeBetween(String tenantId, Instant from, Instant to);

    /** True on a working day of the tenant. */
    boolean isWorkingDay(String tenantId, Instant at);
}

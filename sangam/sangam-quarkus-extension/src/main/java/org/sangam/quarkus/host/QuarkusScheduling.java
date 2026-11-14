// SPDX-License-Identifier: Apache-2.0
package org.sangam.quarkus.host;

import jakarta.inject.Singleton;

import org.sangam.assembly.host.HostSeams;
import org.sangam.assembly.host.HostScheduler;

/**
 * Scheduling seam of the Quarkus host (design note section 10.1, decision 279). Its presence means the host runs
 * Sangam's periodic work on the assembly's standalone scheduler ({@link HostScheduler#standalone}), which the runtime
 * stops last; no Quarkus scheduler extension is part of the evaluated closure. An optional bean: without it (an
 * operator's {@code quarkus.arc.exclude-types}) the host declines the seam ({@link HostSeams.Builder#withoutScheduler}),
 * {@code scheduling} is missing and the start is refused.
 */
@Singleton
public class QuarkusScheduling {
}

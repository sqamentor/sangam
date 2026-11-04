// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.engine;

import java.time.Instant;

/**
 * The only clock product and engine code may read (D-10, W-7). Operaton's {@code ClockUtil} is static and JVM-wide;
 * the engine module wraps it in exactly one implementation, and nothing else in Sangam calls {@code ClockUtil}.
 * Leases and claims that must agree across nodes use the database clock (A-1) instead.
 */
public interface SangamClock {

    /** Current engine time. */
    Instant now();

    /** Wall clock of this JVM. */
    SangamClock SYSTEM = Instant::now;
}

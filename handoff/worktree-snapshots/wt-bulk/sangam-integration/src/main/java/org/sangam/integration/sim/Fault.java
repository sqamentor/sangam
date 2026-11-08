// SPDX-License-Identifier: Apache-2.0
// Ported from spike/simulators org.sangam.spike.sim.Fault. Modified by Sangam.
package org.sangam.integration.sim;

import java.time.Duration;
import java.util.Objects;

/**
 * One injected fault. A fault applies to every route unless {@link #onRoute(String)} narrows it.
 *
 * <ul>
 *   <li>{@link Kind#DELAY}: wait before answering (the request is still processed).</li>
 *   <li>{@link Kind#DROP_RESPONSE}: process the request (the effect happens), then drop the
 *       response: HTTP closes the connection without a status line; file simulators write no
 *       status file; the SMTP sink closes the connection after the DATA content. This is the
 *       "lost response" / UNKNOWN_OUTCOME case (A-2).</li>
 *   <li>{@link Kind#DUPLICATE}: the request is delivered to the business logic twice (network
 *       or transport duplicate). The caller sees the first answer.</li>
 *   <li>{@link Kind#ERROR}: answer with an error status WITHOUT processing (no effect).</li>
 * </ul>
 */
public record Fault(Kind kind, long delayMillis, int status, String route) {

    public enum Kind { DELAY, DROP_RESPONSE, DUPLICATE, ERROR }

    public Fault {
        Objects.requireNonNull(kind, "kind");
    }

    public static Fault delay(Duration d) {
        return new Fault(Kind.DELAY, d.toMillis(), 0, null);
    }

    public static Fault dropResponse() {
        return new Fault(Kind.DROP_RESPONSE, 0, 0, null);
    }

    public static Fault duplicate() {
        return new Fault(Kind.DUPLICATE, 0, 0, null);
    }

    public static Fault error(int status) {
        return new Fault(Kind.ERROR, 0, status, null);
    }

    /** Restrict this fault to routes whose name contains {@code routePart}. */
    public Fault onRoute(String routePart) {
        return new Fault(kind, delayMillis, status, routePart);
    }

    public boolean matches(String routeName) {
        return route == null || (routeName != null && routeName.contains(route));
    }
}

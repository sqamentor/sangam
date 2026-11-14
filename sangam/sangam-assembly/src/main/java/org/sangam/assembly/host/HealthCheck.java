// SPDX-License-Identifier: Apache-2.0
package org.sangam.assembly.host;

import java.util.Map;
import java.util.Objects;

/**
 * Health seam (design note section 10.1): the checks live in the assembly (database, engine, relay backlog, lease
 * ownership, capability state); a host only maps {@link SangamRuntime#health()} to its own endpoint (Spring actuator,
 * MicroProfile Health, ...). Without a health seam there is no endpoint, but the capability check still gates start-up.
 */
public interface HealthCheck {

    String name();

    Result check();

    enum Status {
        UP, DOWN
    }

    /** One check's outcome; {@code details} are plain strings so every host renders them unchanged. */
    record Result(String name, Status status, Map<String, String> details) {
        public Result {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(status, "status");
            details = details == null ? Map.of() : Map.copyOf(details);
        }

        public static Result up(String name, Map<String, String> details) {
            return new Result(name, Status.UP, details);
        }

        public static Result down(String name, Map<String, String> details) {
            return new Result(name, Status.DOWN, details);
        }
    }
}

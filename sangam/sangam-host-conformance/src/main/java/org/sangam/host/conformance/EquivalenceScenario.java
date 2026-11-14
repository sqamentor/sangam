// SPDX-License-Identifier: Apache-2.0
package org.sangam.host.conformance;

import java.util.Objects;

/**
 * One cross-host equivalence scenario (M-150): a fixed operation with fixed inputs and rule versions, run through a
 * host's own ports, giving a {@link BusinessResult}.
 *
 * @param name lower-case scenario name, the prefix of its golden results
 * @param area what it proves (money, authorization, audit, duplicate prevention)
 * @param run  the operation
 */
public record EquivalenceScenario(String name, String area, Body run) {

    /** The operation of a scenario. */
    @FunctionalInterface
    public interface Body {
        BusinessResult run(HostHarness host) throws Exception;
    }

    public EquivalenceScenario {
        if (name == null || !name.matches("[a-z][a-z0-9-]*")) {
            throw new IllegalArgumentException("scenario names are lower-case letters, digits and '-': " + name);
        }
        Objects.requireNonNull(area, "area");
        Objects.requireNonNull(run, "run");
    }

    @Override
    public String toString() {
        return name + " (" + area + ")";
    }
}

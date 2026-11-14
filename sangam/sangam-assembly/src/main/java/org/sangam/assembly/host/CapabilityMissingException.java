// SPDX-License-Identifier: Apache-2.0
package org.sangam.assembly.host;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A required capability is missing: start-up or the affected operation is refused (design note section 10.1, "the rule
 * for every seam"). The message starts with {@value #CODE}, followed by each missing capability and what its absence
 * blocks. Never caught to fall back to a weaker behaviour.
 */
public final class CapabilityMissingException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    /** The problem code a host renders for this refusal. */
    public static final String CODE = "CAPABILITY_MISSING";

    private final transient List<Capability> missing;

    public CapabilityMissingException(String what, List<Capability> missing) {
        super(message(what, missing));
        this.missing = List.copyOf(missing);
    }

    /** The missing capabilities, in the order they were reported. */
    public List<Capability> missing() {
        return missing;
    }

    private static String message(String what, List<Capability> missing) {
        if (missing == null || missing.isEmpty()) {
            throw new IllegalArgumentException("a refusal names at least one missing capability");
        }
        return CODE + ": " + what + " refused; missing " + missing.stream()
                .map(c -> c.id() + " (" + c.whenMissing() + ")").collect(Collectors.joining(", "));
    }
}

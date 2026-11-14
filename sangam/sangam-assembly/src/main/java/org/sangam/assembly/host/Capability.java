// SPDX-License-Identifier: Apache-2.0
package org.sangam.assembly.host;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One capability of the capability matrix (design note section 3.1 and the seams of section 10.1). A host supplies it
 * (through a seam) or the assembly builds it from collaborators the host passed in; {@link CapabilitySet} decides once
 * at start-up what is missing.
 *
 * @param id          stable lower-case id, used in {@code CAPABILITY_MISSING} messages and health details
 * @param kind        {@link Kind#REQUIRED} (fail closed) or {@link Kind#CONVENIENCE} (may be absent, reported)
 * @param seam        the host SPI seam or assembly area that provides it
 * @param whenMissing what happens when it is missing; for a required capability always a refusal, never a fallback
 */
public record Capability(String id, Kind kind, String seam, String whenMissing) {

    /** REQUIRED: missing blocks start-up or the affected operations. CONVENIENCE: missing is reported only. */
    public enum Kind {
        REQUIRED, CONVENIENCE
    }

    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]*");

    public Capability {
        Objects.requireNonNull(kind, "kind");
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("capability id must be lower-case letters, digits and '-': " + id);
        }
        if (seam == null || seam.isBlank()) {
            throw new IllegalArgumentException("capability " + id + " names no seam");
        }
        if (whenMissing == null || whenMissing.isBlank()) {
            throw new IllegalArgumentException("capability " + id + " does not say what happens when it is missing");
        }
    }

    public boolean required() {
        return kind == Kind.REQUIRED;
    }

    @Override
    public String toString() {
        return id;
    }
}

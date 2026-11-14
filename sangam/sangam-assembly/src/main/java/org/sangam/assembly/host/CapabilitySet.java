// SPDX-License-Identifier: Apache-2.0
package org.sangam.assembly.host;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The capabilities of one running Sangam, resolved once at start-up from what the assembly needs and what the host
 * supplied (design note sections 3 and 10.1). A missing required capability refuses start-up
 * ({@link #refuseStartIfMissing}) or the affected operation ({@link #require}); a missing convenience is only reported
 * ({@link #report}). There is no default and no fallback for a required capability.
 */
public final class CapabilitySet {

    private static final Comparator<Capability> BY_ID = Comparator.comparing(Capability::id);

    private final Set<Capability> needed;
    private final Set<Capability> supplied;

    private CapabilitySet(Set<Capability> needed, Set<Capability> supplied) {
        this.needed = needed;
        this.supplied = supplied;
    }

    /**
     * @param needed   the capabilities the enabled features need (usually {@link Capabilities#required()} plus the
     *                 conveniences the host offers)
     * @param supplied the capabilities the host's seams and the assembled collaborators provide
     */
    public static CapabilitySet resolve(Collection<Capability> needed, Collection<Capability> supplied) {
        Objects.requireNonNull(needed, "needed");
        Objects.requireNonNull(supplied, "supplied");
        return new CapabilitySet(Set.copyOf(new LinkedHashSet<>(needed)), Set.copyOf(new LinkedHashSet<>(supplied)));
    }

    public boolean has(Capability c) {
        return supplied.contains(c);
    }

    /** Needed required capabilities that were not supplied, sorted by id. */
    public List<Capability> missingRequired() {
        return needed.stream().filter(Capability::required).filter(c -> !supplied.contains(c)).sorted(BY_ID).toList();
    }

    /** Needed conveniences that were not supplied, sorted by id. */
    public List<Capability> missingConveniences() {
        return needed.stream().filter(c -> !c.required()).filter(c -> !supplied.contains(c)).sorted(BY_ID).toList();
    }

    /** Refuses start-up when any needed required capability is missing, naming every one of them. */
    public void refuseStartIfMissing() {
        List<Capability> missing = missingRequired();
        if (!missing.isEmpty()) {
            throw new CapabilityMissingException("start-up", missing);
        }
    }

    /** Refuses {@code operation} unless {@code capability} was supplied (the per-operation form of fail-closed). */
    public void require(Capability capability, String operation) {
        Objects.requireNonNull(capability, "capability");
        if (!supplied.contains(capability)) {
            throw new CapabilityMissingException(operation, List.of(capability));
        }
    }

    /** One line per needed capability, sorted by id: {@code id KIND supplied|MISSING - what happens when missing}. */
    public List<String> report() {
        List<String> out = new ArrayList<>();
        needed.stream().sorted(BY_ID).forEach(c -> out.add(c.id() + " " + c.kind() + " "
                + (supplied.contains(c) ? "supplied" : "MISSING - " + c.whenMissing())));
        return List.copyOf(out);
    }
}

// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.engine;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * Owner decision 385 (2), layer 2: a DSC document slot (a variable a pack manifest declares in {@code documentSlots},
 * decision 269) is set only by the server's document route (decisions 267 and 268). Every caller write path into the
 * engine (task completion and return, worker completion, variable set, resubmission, process start and modification,
 * message correlation, signals, inbound messages) passes the names of the variables it writes to {@link #refuse},
 * which throws {@link Refused} ({@value #CODE}) for a declared slot name before anything is written; the REST adapters
 * answer it as {@code 422 DOCUMENT_SLOT_VARIABLE}.
 *
 * <p>Generic: the names are data. {@code sangam-server} installs the slot names of its pack registry at start
 * ({@link #install}); before that, and in a host that installs nothing, no name is declared. This layer is defence in
 * depth: the DSC gate itself treats a slot value the route did not bind as an empty slot (layer 1,
 * {@code org.sangam.packs.port.DocumentSlotBindings}), fail closed in every host.
 */
public final class DocumentSlotNames {

    /** The refusal code, also the first word of the message. */
    public static final String CODE = "DOCUMENT_SLOT_VARIABLE";

    private static volatile Supplier<Set<String>> names = Set::of;

    private DocumentSlotNames() {
    }

    /** A caller tried to write a declared DSC document slot. */
    public static final class Refused extends IllegalArgumentException {
        private final Set<String> variables;

        Refused(Set<String> variables) {
            super(CODE + ": " + describe(variables));
            this.variables = Set.copyOf(variables);
        }

        /** The declared slot names the caller tried to write. */
        public Set<String> variables() {
            return variables;
        }

        /** The message without the code. */
        public String detail() {
            return describe(variables);
        }
    }

    /** Installs the source of the declared slot names (the server: its pack registry, read at each call). */
    public static void install(Supplier<Set<String>> slotNames) {
        names = Objects.requireNonNull(slotNames, "slotNames");
    }

    /** Restores the default (no name declared); tests. */
    public static void reset() {
        names = Set::of;
    }

    /** The declared slot names now; a source that fails or answers null throws, so the write is refused (fail closed). */
    public static Set<String> names() {
        Set<String> s = names.get();
        if (s == null) {
            throw new IllegalStateException(CODE + ": the declared DSC document slot names are unknown");
        }
        return s;
    }

    /** Throws {@link Refused} when any of {@code variableNames} is a declared slot; null or empty passes. */
    public static void refuse(Collection<String> variableNames) {
        if (variableNames == null || variableNames.isEmpty()) {
            return;
        }
        Set<String> declared = names();
        Set<String> hit = new TreeSet<>();
        for (String n : variableNames) {
            if (n != null && declared.contains(n)) {
                hit.add(n);
            }
        }
        if (!hit.isEmpty()) {
            throw new Refused(hit);
        }
    }

    private static String describe(Set<String> variables) {
        return "variable " + String.join(", ", new TreeSet<>(variables)) + " is a DSC document slot: only the document"
                + " route sets it, by uploading the document with its documentType on an open task of the case (owner"
                + " decisions 267, 268 and 385)";
    }
}

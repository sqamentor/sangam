// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.engine;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Engine-owned state (owner spec SEQ-PARALLEL point 9, increment I-1): variables that only the engine itself may
 * write. Multi-instance counters ({@code nrOfInstances}, {@code nrOfActiveInstances}, {@code nrOfCompletedInstances},
 * {@code loopCounter}) decide when a multi-instance body completes; a caller that writes them changes the outcome of a
 * collection or an approval join. A name prefix is not the protection: the engine adapter refuses these names on every
 * caller-facing write path (process, task, form and external-task services and their builders) and lets only the
 * engine's own execution writes through.
 *
 * <p>Engine-neutral: no engine class is referenced. The refusal text follows the platform's coded form
 * {@code "422 ENGINE_OWNED_VARIABLE: ..."}, which the REST layer maps to an RFC 9457 problem with that code.
 */
public final class EngineOwnedVariables {

    /** Problem code of a refused write. */
    public static final String CODE = "ENGINE_OWNED_VARIABLE";

    /** Names the engine writes for every multi-instance body (BPMN 2.0 section 10.2.8 and the Operaton runtime). */
    public static final Set<String> NAMES = Set.of("nrOfInstances", "nrOfActiveInstances", "nrOfCompletedInstances",
            "loopCounter");

    private EngineOwnedVariables() {
    }

    public static boolean isEngineOwned(String name) {
        return name != null && NAMES.contains(name);
    }

    /** The engine-owned names among {@code names}, sorted; empty when the write may proceed. */
    public static Set<String> engineOwnedAmong(Collection<?> names) {
        Set<String> hit = new TreeSet<>();
        if (names != null) {
            for (Object n : names) {
                if (n instanceof String s && isEngineOwned(s)) {
                    hit.add(s);
                }
            }
        }
        return hit;
    }

    /** The coded refusal message for a non-empty set of names, or {@code null} when nothing is engine-owned. */
    public static String refusal(Collection<?> names) {
        Set<String> hit = engineOwnedAmong(names);
        if (hit.isEmpty()) {
            return null;
        }
        return "422 " + CODE + ": " + String.join(", ", hit)
                + (hit.size() == 1 ? " is" : " are") + " owned by the engine and cannot be written by a caller";
    }

    /** {@link #refusal(Collection)} over the keys of a variable map. */
    public static String refusal(Map<?, ?> variables) {
        return variables == null ? null : refusal(variables.keySet());
    }
}

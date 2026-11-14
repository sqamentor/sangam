// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.policy.external;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which commands use the external policy check, and how (owner decision 166, M-083). Built from two configured lists of
 * command types, each of which may hold {@value #ALL} for every command:
 * <ul>
 *   <li>a command named in the required list is {@link ExternalPolicyRequirement#REQUIRED};</li>
 *   <li>else a command named in the best-effort list is {@link ExternalPolicyRequirement#BEST_EFFORT};</li>
 *   <li>else {@value #ALL} in the required list makes it REQUIRED, and {@value #ALL} in the best-effort list makes it
 *       BEST_EFFORT;</li>
 *   <li>else it is {@link ExternalPolicyRequirement#NONE} (not checked).</li>
 * </ul>
 * A configuration that cannot be read one way only is refused at start-up instead of guessed: a command type named in
 * both lists, {@value #ALL} in both lists, or a command type the catalogue does not know (a misspelt name would silently
 * leave a command unchecked).
 */
public final class ExternalPolicyCommands {

    public static final String ALL = "*";

    private final Map<String, ExternalPolicyRequirement> named;
    private final ExternalPolicyRequirement others;

    private ExternalPolicyCommands(Map<String, ExternalPolicyRequirement> named, ExternalPolicyRequirement others) {
        this.named = Map.copyOf(named);
        this.others = others;
    }

    /**
     * @param knownCommandTypes every command type of the command catalogue
     * @param required          command types (or {@value #ALL}) that fail closed when the service is unavailable
     * @param bestEffort        command types (or {@value #ALL}) that continue when the service is unavailable
     */
    public static ExternalPolicyCommands of(Collection<String> knownCommandTypes, Collection<String> required,
                                            Collection<String> bestEffort) {
        Set<String> known = new TreeSet<>(knownCommandTypes == null ? Set.of() : knownCommandTypes);
        Set<String> req = clean(required);
        Set<String> best = clean(bestEffort);
        if (req.contains(ALL) && best.contains(ALL)) {
            throw new IllegalArgumentException("'" + ALL + "' may stand in the required or the best-effort command list,"
                    + " not in both");
        }
        Set<String> both = new TreeSet<>(req);
        both.retainAll(best);
        both.remove(ALL);
        if (!both.isEmpty()) {
            throw new IllegalArgumentException("command types in both the required and the best-effort list: " + both);
        }
        Set<String> unknown = new TreeSet<>();
        Map<String, ExternalPolicyRequirement> named = new LinkedHashMap<>();
        for (String c : req) {
            if (!c.equals(ALL)) {
                if (!known.contains(c)) {
                    unknown.add(c);
                }
                named.put(c, ExternalPolicyRequirement.REQUIRED);
            }
        }
        for (String c : best) {
            if (!c.equals(ALL)) {
                if (!known.contains(c)) {
                    unknown.add(c);
                }
                named.put(c, ExternalPolicyRequirement.BEST_EFFORT);
            }
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("unknown command types in the external policy configuration: " + unknown);
        }
        ExternalPolicyRequirement others = req.contains(ALL) ? ExternalPolicyRequirement.REQUIRED
                : best.contains(ALL) ? ExternalPolicyRequirement.BEST_EFFORT : ExternalPolicyRequirement.NONE;
        return new ExternalPolicyCommands(named, others);
    }

    public ExternalPolicyRequirement requirement(String commandType) {
        if (commandType == null) {
            return others;
        }
        return named.getOrDefault(commandType, others);
    }

    /** True when no command is checked at all. */
    public boolean none() {
        return others == ExternalPolicyRequirement.NONE && named.isEmpty();
    }

    private static Set<String> clean(Collection<String> values) {
        Set<String> out = new TreeSet<>();
        if (values != null) {
            for (String v : values) {
                if (v != null && !v.isBlank()) {
                    out.add(v.strip());
                }
            }
        }
        return out;
    }
}

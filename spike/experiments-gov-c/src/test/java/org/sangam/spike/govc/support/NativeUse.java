package org.sangam.spike.govc.support;

import java.util.LinkedHashSet;
import java.util.Set;

/** Collects the engine-native API names a scenario used (thread-local; worker threads report to the scenario). */
public final class NativeUse {

    private static volatile Set<String> current;

    private NativeUse() {
    }

    static synchronized void begin() {
        current = new LinkedHashSet<>();
    }

    static synchronized Set<String> end() {
        Set<String> s = current == null ? Set.of() : current;
        current = null;
        return s;
    }

    /** Records one native API (for example "ManagementService.setJobDuedate"). */
    public static synchronized void record(String api) {
        if (current != null) {
            current.add(api);
        }
    }
}

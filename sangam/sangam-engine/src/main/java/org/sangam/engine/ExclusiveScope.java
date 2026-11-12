// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.externaltask.LockedExternalTask;

/**
 * VI.6 {@code sangam:exclusive} scope keys of a fetched external task, split out of {@link OperatonEngineAdapter}
 * (X2-04 class-size ratchet; behaviour unchanged).
 */
final class ExclusiveScope {

    private ExclusiveScope() {
    }

    /** {@code execution:<group>} → {@code root:group}; {@code resource:<variable>} → {@code res:<value>}. */
    static String of(String decl, String rootId, LockedExternalTask t) {
        if (decl.startsWith("execution")) {
            String group = decl.contains(":") ? decl.substring(decl.indexOf(':') + 1) : t.getActivityId();
            return rootId + ":" + group;
        }
        if (decl.startsWith("resource:")) {
            Object v = t.getVariables().get(decl.substring("resource:".length()).trim());
            return v == null ? null : "res:" + v;
        }
        throw new ProcessEngineException("VI.6: unknown sangam:exclusive mode '" + decl + "'");
    }
}

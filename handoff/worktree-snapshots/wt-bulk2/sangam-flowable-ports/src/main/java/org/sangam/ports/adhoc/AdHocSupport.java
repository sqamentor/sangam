// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.adhoc;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.impl.pvm.PvmActivity;
import org.operaton.bpm.engine.impl.pvm.process.ActivityImpl;
import org.operaton.bpm.engine.impl.pvm.runtime.PvmExecutionImpl;

/** PVM helpers shared by the ad-hoc behaviour and its commands. */
final class AdHocSupport {

    private AdHocSupport() {
    }

    static boolean isAdHoc(PvmActivity a) {
        return a != null && a.getActivityBehavior() instanceof AdHocSubProcessActivityBehavior;
    }

    /** The ad-hoc activity the execution is in (its own activity, or the nearest enclosing ad-hoc flow scope). */
    static PvmActivity adHocActivityOf(PvmExecutionImpl exec) {
        PvmActivity a = exec.getActivity();
        while (a != null) {
            if (isAdHoc(a)) {
                return a;
            }
            a = a instanceof ActivityImpl ai ? ai.getParentFlowScopeActivity() : null;
        }
        throw new ProcessEngineException("execution " + exec.getId() + " is not inside an ad-hoc sub-process");
    }

    static String adHocInstanceIdOf(PvmExecutionImpl exec, PvmActivity adHoc) {
        return exec.getActivity() == adHoc ? exec.getActivityInstanceId() : exec.getParentActivityInstanceId();
    }
}

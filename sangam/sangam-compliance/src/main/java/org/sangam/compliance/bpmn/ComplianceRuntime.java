// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.bpmn;

import org.sangam.compliance.SangamCompliance;

/** Holder that lets class-based BPMN delegates reach the wired services (one engine per JVM, ARCHITECTURE §1.6). */
public final class ComplianceRuntime {

    private static volatile ComplianceSteps steps;

    private ComplianceRuntime() {
    }

    public static void install(SangamCompliance compliance) {
        steps = new ComplianceSteps(compliance);
    }

    public static ComplianceSteps steps() {
        ComplianceSteps s = steps;
        if (s == null) {
            throw new IllegalStateException("ComplianceRuntime.install(...) was not called");
        }
        return s;
    }
}

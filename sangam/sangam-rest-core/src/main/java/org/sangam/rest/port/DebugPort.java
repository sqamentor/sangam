// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

import java.util.List;

/**
 * Non-production model debugger (R80, FLOWABLE-INTO-OPERATON FC-44, EC-13): breakpoints and single stepping in sandbox tenants. It must be
 * impossible to enable on a production profile: every call returns {@code 403 DEBUGGER_DISABLED} there.
 */
public interface DebugPort {

    record Status(boolean enabled, String profile, String sandboxTenant) {
    }

    record BreakpointsRequest(List<String> activityIds) {
    }

    record StepResult(String executed, String jobId, List<String> activeActivityIds) {
    }

    Status status(RequestContext ctx);

    List<String> breakpoints(RequestContext ctx, String processInstanceId);

    List<String> setBreakpoints(RequestContext ctx, String processInstanceId, BreakpointsRequest request);

    StepResult step(RequestContext ctx, String processInstanceId);
}

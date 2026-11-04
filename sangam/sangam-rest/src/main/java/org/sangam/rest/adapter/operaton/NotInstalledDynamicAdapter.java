// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.DynamicPort;
import org.sangam.rest.port.RequestContext;

/**
 * Default {@link DynamicPort} when sangam-flowable-ports is not installed. Operaton 2.2 has no ad-hoc sub-process
 * behaviour (deploy fails with ENGINE-09005, FC-01) and no instance-level model injection (FC-15); both need the
 * WS-PORTS parse listener / model-variant machinery, so every call answers {@code 501 CAPABILITY_NOT_INSTALLED}.
 */
public class NotInstalledDynamicAdapter implements DynamicPort {

    private static final String MODULE = "sangam-flowable-ports";
    private static final String AD_HOC = "ad-hoc sub-process (FC-01)";
    private static final String INJECTION = "dynamic injection (FC-15)";

    @Override
    public AdHocState adHocState(RequestContext ctx, String processInstanceId, String adHocActivityId) {
        throw ApiProblem.notInstalled(AD_HOC, MODULE);
    }

    @Override
    public AdHocState startAdHocActivity(RequestContext ctx, String processInstanceId, String adHocActivityId,
                                         StartAdHocActivityRequest request) {
        throw ApiProblem.notInstalled(AD_HOC, MODULE);
    }

    @Override
    public AdHocState completeAdHoc(RequestContext ctx, String processInstanceId, String adHocActivityId, String reason) {
        throw ApiProblem.notInstalled(AD_HOC, MODULE);
    }

    @Override
    public InjectionResult inject(RequestContext ctx, String processInstanceId, InjectionRequest request) {
        throw ApiProblem.notInstalled(INJECTION, MODULE);
    }
}

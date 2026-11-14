// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.host;

import org.sangam.host.conformance.HostConformanceKit;
import org.sangam.host.conformance.HostHarness;

/**
 * M-144 / M-150: the Spring standalone host (the supported baseline) runs the host conformance kit and the cross-host
 * equivalence scenarios. Every later host runs the same kit through its own harness and must give the same golden
 * results. Written for the test phase (not run).
 */
class SpringHostConformanceTest extends HostConformanceKit {

    @Override
    protected HostHarness newHarness() {
        return new SpringHostHarness();
    }
}

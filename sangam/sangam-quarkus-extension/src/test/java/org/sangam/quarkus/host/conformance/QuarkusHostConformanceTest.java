// SPDX-License-Identifier: Apache-2.0
package org.sangam.quarkus.host.conformance;

import org.sangam.host.conformance.HostConformanceKit;
import org.sangam.host.conformance.HostHarness;

/**
 * M-150 on Quarkus: the host conformance kit (equivalence with the golden results, capability coverage, thinness) on
 * the Quarkus host. Expected while M-143 supplies five seams only: the class start is refused with
 * {@code CAPABILITY_MISSING} (eleven required capabilities missing), so the equivalence scenarios do not pass, and
 * coverage reports the four always-supplied seams as gaps of a non-baseline host. Written for the test phase (not
 * run).
 */
class QuarkusHostConformanceTest extends HostConformanceKit {

    @Override
    protected HostHarness newHarness() {
        return new QuarkusHostHarness();
    }
}

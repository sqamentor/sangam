// SPDX-License-Identifier: Apache-2.0
package org.sangam.quarkus.host.conformance;

import org.sangam.host.conformance.HostFailClosedKit;
import org.sangam.host.conformance.HostHarness;

/**
 * M-150 on Quarkus, fail closed: for every removable required capability a fresh Quarkus host started without it
 * refuses, and the refusal names that capability ({@code CAPABILITY_MISSING}, before the engine is built). Run on its
 * own, not together with {@link QuarkusHostConformanceTest} (one engine per JVM). Written for the test phase (not run).
 */
class QuarkusHostFailClosedTest extends HostFailClosedKit {

    @Override
    protected HostHarness newHarness() {
        return new QuarkusHostHarness();
    }
}

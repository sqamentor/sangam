// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.assembly.host.Capabilities;
import org.sangam.assembly.host.Capability;
import org.sangam.host.conformance.HostFailClosedKit;
import org.sangam.host.conformance.HostHarness;

/**
 * M-144: the Spring host refuses to start without each required capability it can take away (decision 75 for the
 * security module), and its gaps are explicit. Written for the test phase (not run).
 */
class SpringHostFailClosedTest extends HostFailClosedKit {

    @Override
    protected HostHarness newHarness() {
        return new SpringHostHarness();
    }

    @Test
    void everyRequiredCapabilityIsRemovableOrAnExplicitGap() {
        SpringHostHarness h = new SpringHostHarness();
        Map<Capability, String> gaps = h.capabilityGaps();
        for (Capability c : Capabilities.required()) {
            assertTrue(h.removableCapabilities().contains(c) ^ gaps.containsKey(c), c.id());
        }
        assertEquals(Capabilities.required().size(), h.removableCapabilities().size() + gaps.size());
    }

    @Test
    void aGapCannotBeTakenAwayAndPretendToBeProven() {
        SpringHostHarness h = new SpringHostHarness();
        assertThrows(IllegalArgumentException.class, () -> h.withoutCapability(Capabilities.SHARED_TRANSACTION));
    }

    @Test
    void theContractVersionIsReadFromTheServedContract() {
        assertEquals("0.1.0", SpringHostHarness.contractVersion());
    }
}

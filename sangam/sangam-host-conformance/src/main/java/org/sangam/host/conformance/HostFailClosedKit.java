// SPDX-License-Identifier: Apache-2.0
package org.sangam.host.conformance;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.util.Comparator;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.sangam.assembly.host.Capability;

/**
 * Fail closed on every removable required capability (M-144, design note section 10.2 "Fail-closed" row): for each one,
 * a fresh host started without it must refuse to start, and the refusal must be the one for that capability
 * ({@link HostHarness#refusalMarker}), so a start that fails for another reason never counts. A start that succeeds is
 * the silent downgrade the owner's directive forbids, and fails the test.
 *
 * <p>Each case starts its own host and stops it afterwards; run this class on its own, not while a
 * {@link HostConformanceKit} of the same host holds the engine (one engine per JVM).
 */
public abstract class HostFailClosedKit {

    /** A fresh, not yet started harness of the host under test. */
    protected abstract HostHarness newHarness();

    @TestFactory
    public Stream<DynamicTest> everyRemovableRequiredCapabilityRefusesTheStart() {
        return newHarness().removableCapabilities().stream().sorted(Comparator.comparing(Capability::id))
                .map(c -> dynamicTest("without " + c.id(), () -> refusesWithout(c)));
    }

    private void refusesWithout(Capability c) {
        HostHarness h = newHarness();
        h.withoutCapability(c);
        String marker = h.refusalMarker(c);
        Throwable refused = null;
        try {
            h.start();
        } catch (RuntimeException | Error e) {
            refused = e;
        } finally {
            h.stop();
        }
        if (refused == null) {
            fail(h.hostName() + " started without the required capability " + c.id() + " (" + c.whenMissing() + ")");
        }
        assertTrue(chainContains(refused, marker), h.hostName() + " refused the start without " + c.id()
                + ", but not for that reason (expected '" + marker + "'): " + refused);
    }

    @Test
    public void onlyRequiredCapabilitiesAreRemovedAndEachHasAMarker() {
        HostHarness h = newHarness();
        for (Capability c : h.removableCapabilities()) {
            assertTrue(c.required(), c.id() + " is not a required capability");
            String marker = h.refusalMarker(c);
            assertTrue(marker != null && !marker.isBlank(), c.id() + " has no refusal marker");
        }
    }

    /** True when {@code t}'s message or a cause's message contains {@code marker}. */
    static boolean chainContains(Throwable t, String marker) {
        for (Throwable x = t; x != null; x = x.getCause() == x ? null : x.getCause()) {
            if (x.getMessage() != null && x.getMessage().contains(marker)) {
                return true;
            }
            for (Throwable s : x.getSuppressed()) {
                if (chainContains(s, marker)) {
                    return true;
                }
            }
        }
        return false;
    }
}

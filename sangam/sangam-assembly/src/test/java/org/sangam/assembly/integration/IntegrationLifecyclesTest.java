// SPDX-License-Identifier: Apache-2.0
package org.sangam.assembly.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.assembly.host.Capabilities;
import org.sangam.assembly.host.CapabilityMissingException;
import org.sangam.assembly.host.HostSeams;
import org.sangam.assembly.host.Lifecycle;
import org.sangam.assembly.host.SangamProperties;
import org.sangam.assembly.host.SangamRuntime;
import org.sangam.assembly.integration.IntegrationLifecycles.Service;

/**
 * Extraction phase 3, owner decision 315: the integration services start in the order of the Spring host's
 * {@code IntegrationServices} and stop in reverse, whatever order a host hands them over in; without the scheduling
 * seam nothing is assembled; switched off, they are assembled and closed but never started.
 */
class IntegrationLifecyclesTest {

    private final List<String> events = new ArrayList<>();

    private static HostSeams seams() {
        return HostSeams.builder("test").properties(SangamProperties.empty(), false).lifecycleHook().build();
    }

    private IntegrationLifecycles.Builder add(IntegrationLifecycles.Builder b, Service... services) {
        for (Service s : services) {
            b.add(s, () -> events.add("start " + s), () -> events.add("stop " + s));
        }
        return b;
    }

    private static SangamRuntime runtime(HostSeams seams, List<Lifecycle> components) {
        // only the capabilities these seams supply: the order is under test here, not the catalogue
        return seams.runtime(List.of(Capabilities.SCHEDULING, Capabilities.LIFECYCLE), components, List.of());
    }

    @Test
    void theOrderIsTheSpringHostsWhateverOrderTheHostAddsThemIn() {
        HostSeams seams = seams();
        List<Lifecycle> components = add(IntegrationLifecycles.builder(seams, SangamProperties.empty()),
                Service.WORKER, Service.MAINTENANCE, Service.INBOX, Service.RECONCILIATION, Service.RELAY).build();
        SangamRuntime runtime = runtime(seams, components);
        runtime.start();
        assertEquals(List.of("start INBOX", "start RELAY", "start RECONCILIATION", "start MAINTENANCE", "start WORKER"),
                events);
        events.clear();
        runtime.stop(Duration.ZERO);
        assertEquals(List.of("stop WORKER", "stop MAINTENANCE", "stop RECONCILIATION", "stop RELAY", "stop INBOX"),
                events);
    }

    @Test
    void theFirstPhaseIsTheSpringHostsAndTheStandaloneSchedulerStopsLast() {
        assertEquals(Integer.MAX_VALUE - 100, IntegrationLifecycles.PHASE);
        assertEquals(IntegrationLifecycles.PHASE, Service.INBOX.phase());
        HostSeams seams = seams();
        SangamRuntime runtime = runtime(seams, add(IntegrationLifecycles.builder(seams, SangamProperties.empty()),
                Service.INBOX, Service.RELAY, Service.RECONCILIATION, Service.MAINTENANCE).build());
        List<String> order = runtime.startOrder();
        assertEquals("sangam-scheduler-test", order.get(0), "started first, so stopped last");
        assertEquals(List.of("sangam-integration-inbox", "sangam-integration-relay",
                "sangam-integration-reconciliation", "sangam-integration-maintenance"), order.subList(1, 5));
    }

    @Test
    void withoutTheSchedulingSeamNothingIsAssembled() {
        HostSeams declined = HostSeams.builder("test").properties(SangamProperties.empty(), false).lifecycleHook()
                .withoutScheduler().build();
        CapabilityMissingException e = assertThrows(CapabilityMissingException.class,
                () -> IntegrationLifecycles.builder(declined, SangamProperties.empty()));
        assertTrue(e.getMessage().contains(Capabilities.SCHEDULING.id() + " ("
                + Capabilities.SCHEDULING.whenMissing() + ")"), e.getMessage());
    }

    @Test
    void theWorkerIsOptionalTheOtherFourAreNot() {
        HostSeams seams = seams();
        assertEquals(4, add(IntegrationLifecycles.builder(seams, SangamProperties.empty()), Service.INBOX,
                Service.RELAY, Service.RECONCILIATION, Service.MAINTENANCE).build().size());
        for (Service left : new Service[] {Service.INBOX, Service.RELAY, Service.RECONCILIATION, Service.MAINTENANCE}) {
            IntegrationLifecycles.Builder b = IntegrationLifecycles.builder(seams, SangamProperties.empty());
            for (Service s : Service.values()) {
                if (s != left) {
                    add(b, s);
                }
            }
            IllegalStateException e = assertThrows(IllegalStateException.class, b::build);
            assertTrue(e.getMessage().contains(left.componentName()), e.getMessage());
        }
    }

    @Test
    void aServiceIsAddedOnce() {
        IntegrationLifecycles.Builder b = add(IntegrationLifecycles.builder(seams(), SangamProperties.empty()),
                Service.INBOX);
        assertThrows(IllegalStateException.class, () -> add(b, Service.INBOX));
    }

    @Test
    void switchedOffTheServicesAreClosedButNeverStarted() {
        HostSeams seams = seams();
        SangamProperties off = SangamProperties.of(Map.of(IntegrationLifecycles.BACKGROUND_ENABLED, "false"));
        SangamRuntime runtime = runtime(seams, add(IntegrationLifecycles.builder(seams, off), Service.INBOX,
                Service.RELAY, Service.RECONCILIATION, Service.MAINTENANCE).build());
        runtime.start();
        assertEquals(List.of(), events);
        runtime.stop(Duration.ZERO);
        assertEquals(List.of("stop MAINTENANCE", "stop RECONCILIATION", "stop RELAY", "stop INBOX"), events);
    }

    @Test
    void aCheckedFailureOfOneStopStillStopsTheOthers() {
        HostSeams seams = seams();
        IntegrationLifecycles.Builder b = IntegrationLifecycles.builder(seams, SangamProperties.empty());
        add(b, Service.INBOX, Service.RECONCILIATION, Service.MAINTENANCE);
        b.add(Service.RELAY, () -> events.add("start RELAY"), () -> {
            throw new java.io.IOException("socket");
        });
        SangamRuntime runtime = runtime(seams, b.build());
        runtime.start();
        events.clear();
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> runtime.stop(Duration.ZERO));
        assertTrue(e.getMessage().contains("sangam-integration-relay"), e.getMessage());
        assertEquals(List.of("stop MAINTENANCE", "stop RECONCILIATION", "stop INBOX"), events);
    }
}

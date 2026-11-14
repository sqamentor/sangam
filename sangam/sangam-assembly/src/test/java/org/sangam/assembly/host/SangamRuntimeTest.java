// SPDX-License-Identifier: Apache-2.0
package org.sangam.assembly.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** M-144 lifecycle seam: order decided by the assembly, fail closed before anything starts. Not run (test phase). */
class SangamRuntimeTest {

    private final List<String> events = new ArrayList<>();

    private Lifecycle component(String name, int phase) {
        return Lifecycle.of(name, phase, () -> events.add("start " + name), d -> events.add("stop " + name + " " + d));
    }

    private static CapabilitySet complete() {
        return CapabilitySet.resolve(Capabilities.required(), Capabilities.required());
    }

    @Test
    void startsByPhaseThenNameAndStopsInReverse() {
        SangamRuntime rt = SangamRuntime.of(complete(),
                List.of(component("relay", 20), component("drainer", 20), component("leases", 10)), List.of());
        assertEquals(List.of("leases", "drainer", "relay"), rt.startOrder());
        rt.start();
        assertEquals(SangamRuntime.State.RUNNING, rt.state());
        rt.stop(Duration.ofSeconds(5));
        assertEquals(List.of("start leases", "start drainer", "start relay", "stop relay PT5S", "stop drainer PT5S",
                "stop leases PT5S"), events);
        assertEquals(SangamRuntime.State.STOPPED, rt.state());
    }

    @Test
    void aMissingRequiredCapabilityStartsNothing() {
        CapabilitySet missing = CapabilitySet.resolve(Capabilities.required(), List.of());
        SangamRuntime rt = SangamRuntime.of(missing, List.of(component("relay", 1)), List.of());
        assertThrows(CapabilityMissingException.class, rt::start);
        assertEquals(List.of(), events);
        assertEquals(SangamRuntime.State.FAILED, rt.state());
        assertThrows(IllegalStateException.class, rt::start, "never a second attempt on the same runtime");
    }

    @Test
    void aFailingStartStopsWhatAlreadyRunsInReverse() {
        RuntimeException boom = new RuntimeException("boom");
        Lifecycle failing = Lifecycle.of("c", 3, () -> {
            throw boom;
        }, d -> events.add("stop c"));
        SangamRuntime rt = SangamRuntime.of(complete(), List.of(component("a", 1), component("b", 2), failing),
                List.of());
        RuntimeException e = assertThrows(RuntimeException.class, rt::start);
        assertSame(boom, e);
        assertEquals(List.of("start a", "start b", "stop b PT0S", "stop a PT0S"), events);
        assertEquals(SangamRuntime.State.FAILED, rt.state());
    }

    @Test
    void everyComponentIsStoppedEvenWhenOneStopFails() {
        Lifecycle bad = Lifecycle.of("bad", 2, () -> events.add("start bad"), d -> {
            throw new IllegalStateException("stuck");
        });
        SangamRuntime rt = SangamRuntime.of(complete(), List.of(component("a", 1), bad, component("c", 3)), List.of());
        rt.start();
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> rt.stop(Duration.ZERO));
        assertTrue(e.getMessage().contains("bad"), e.getMessage());
        assertEquals(List.of("start a", "start bad", "start c", "stop c PT0S", "stop a PT0S"), events);
    }

    @Test
    void namesAreUniqueAndTheDrainIsNotNegative() {
        assertThrows(IllegalArgumentException.class,
                () -> SangamRuntime.of(complete(), List.of(component("x", 1), component("x", 2)), List.of()));
        HealthCheck h = new HealthCheck() {
            @Override
            public String name() {
                return SangamRuntime.CAPABILITIES_CHECK;
            }

            @Override
            public Result check() {
                return Result.up(name(), Map.of());
            }
        };
        assertThrows(IllegalArgumentException.class, () -> SangamRuntime.of(complete(), List.of(), List.of(h)));
        SangamRuntime rt = SangamRuntime.of(complete(), List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> rt.stop(Duration.ofSeconds(-1)));
    }

    @Test
    void healthReportsTheCapabilitiesFirstAndAThrowingCheckAsDown() {
        HealthCheck throwing = new HealthCheck() {
            @Override
            public String name() {
                return "database";
            }

            @Override
            public Result check() {
                throw new IllegalStateException("no connection");
            }
        };
        List<Capability> needed = new ArrayList<>(Capabilities.required());
        needed.add(Capabilities.HEALTH);
        CapabilitySet caps = CapabilitySet.resolve(needed, List.of(Capabilities.AUDIT_CHAIN));
        List<HealthCheck.Result> health = SangamRuntime.of(caps, List.of(), List.of(throwing)).health();
        assertEquals(SangamRuntime.CAPABILITIES_CHECK, health.get(0).name());
        assertEquals(HealthCheck.Status.DOWN, health.get(0).status());
        assertEquals("MISSING", health.get(0).details().get("idempotency-store"));
        assertEquals("absent", health.get(0).details().get("health"));
        assertEquals(new HealthCheck.Result("database", HealthCheck.Status.DOWN, Map.of("error", "no connection")),
                health.get(1));
        assertEquals(HealthCheck.Status.UP, SangamRuntime.of(complete(), List.of(), List.of()).health().get(0).status());
    }
}

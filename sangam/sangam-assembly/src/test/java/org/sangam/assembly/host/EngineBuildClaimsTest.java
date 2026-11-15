// SPDX-License-Identifier: Apache-2.0
package org.sangam.assembly.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Owner decision 376 (G7 phase 3 blocker 5): what needs the engine is claimed only after the build succeeded, the
 * capability set is settled at start-up, and a refused start starts nothing (the job executor is the runtime's last
 * component, so it never starts before the check).
 */
class EngineBuildClaimsTest {

    private static final List<Capability> NEEDED = List.of(Capabilities.CONFIG_SOURCE,
            Capabilities.OUTBOX_RELAY_INBOX);

    private static HostSeams seams() {
        return HostSeams.builder("claims").properties(SangamProperties.of(Map.of()), false).withoutScheduler().build();
    }

    private static Lifecycle recording(String name, int phase, List<String> log) {
        return Lifecycle.of(name, phase, () -> log.add("start " + name), d -> log.add("stop " + name));
    }

    @Test
    void onlyTheEngineDependentCapabilityIsClaimedAfterTheBuild() {
        assertEquals(Set.of(Capabilities.OUTBOX_RELAY_INBOX), EngineBuildClaims.AFTER_ENGINE_BUILD);
        IllegalArgumentException early = assertThrows(IllegalArgumentException.class,
                () -> new EngineBuildClaims<String>().expect(Capabilities.AUDIT_CHAIN, e -> List.of()));
        assertTrue(early.getMessage().startsWith("audit-chain does not need the engine"), early.getMessage());
        IllegalArgumentException late = assertThrows(IllegalArgumentException.class,
                () -> EngineBuildClaims.requireClaimableBeforeBuild(List.of(Capabilities.IDEMPOTENCY_STORE,
                        Capabilities.OUTBOX_RELAY_INBOX)));
        assertTrue(late.getMessage().startsWith("outbox-relay-inbox needs the built engine"), late.getMessage());
        EngineBuildClaims.requireClaimableBeforeBuild(List.of(Capabilities.AUDIT_CHAIN, Capabilities.IDEMPOTENCY_STORE));
    }

    @Test
    void anExpectedCapabilityIsPendingBeforeTheBuildAndMissingAtStartUntilTheBuildClaimsIt() {
        EngineBuildClaims<String> claims = new EngineBuildClaims<>();
        assertEquals(List.of(Capabilities.OUTBOX_RELAY_INBOX),
                claims.beforeBuild(NEEDED, seams().capabilities()).missingRequired(), "nothing expects it: missing");
        claims.expect(Capabilities.OUTBOX_RELAY_INBOX, engine -> List.of());
        assertEquals(List.of(), claims.beforeBuild(NEEDED, seams().capabilities()).missingRequired(), "pending");
        assertEquals(List.of(Capabilities.OUTBOX_RELAY_INBOX),
                claims.atStart(NEEDED, seams().capabilities()).missingRequired(), "not built: missing at start");
        claims.engineBuilt("engine");
        assertEquals(Set.of(), claims.pending());
        assertEquals(List.of(), claims.atStart(NEEDED, seams().capabilities()).missingRequired());
        assertThrows(IllegalStateException.class, () -> claims.engineBuilt("again"));
        assertThrows(IllegalStateException.class,
                () -> claims.expect(Capabilities.OUTBOX_RELAY_INBOX, engine -> List.of()));
    }

    @Test
    void aRefusedStartStartsNothingAndTheBuiltServicesStartWithTheFixedOnesInPhaseOrder() {
        List<String> log = new ArrayList<>();
        EngineBuildClaims<String> claims = new EngineBuildClaims<>();
        List<String> seen = new ArrayList<>();
        claims.expect(Capabilities.OUTBOX_RELAY_INBOX, engine -> {
            seen.add(engine);
            return List.of(recording("inbox", 10, log), recording("relay", 11, log));
        });
        HostSeams seams = seams();
        Lifecycle jobs = recording("jobs", Integer.MAX_VALUE, log);
        SangamRuntime notBuilt = seams.runtime(claims, NEEDED, List.of(jobs), List.of());
        CapabilityMissingException refused = assertThrows(CapabilityMissingException.class, notBuilt::start);
        assertEquals(List.of(Capabilities.OUTBOX_RELAY_INBOX), refused.missing());
        assertEquals(List.of(), log, "a refused start starts nothing, the job executor least of all");

        claims.engineBuilt("engine");
        SangamRuntime rt = seams.runtime(claims, NEEDED, List.of(jobs), List.of());
        assertEquals(List.of("inbox", "relay", "jobs"), rt.startOrder());
        rt.start();
        assertEquals(List.of("engine"), seen);
        assertEquals(List.of("start inbox", "start relay", "start jobs"), log, "the job executor starts last");
        rt.stop(Duration.ZERO);
        assertEquals(List.of("start inbox", "start relay", "start jobs", "stop jobs", "stop relay", "stop inbox"), log);
    }

    @Test
    void aFailedAssemblyAfterTheBuildLeavesTheCapabilityMissingAndNamesTheCause() {
        EngineBuildClaims<String> claims = new EngineBuildClaims<>();
        claims.expect(Capabilities.OUTBOX_RELAY_INBOX, engine -> {
            throw new IllegalStateException("no reconciliation engine");
        });
        claims.engineBuilt("engine");
        List<String> log = new ArrayList<>();
        SangamRuntime rt = seams().runtime(claims, NEEDED, List.of(recording("jobs", Integer.MAX_VALUE, log)),
                List.of());
        CapabilityMissingException refused = assertThrows(CapabilityMissingException.class, rt::start);
        assertTrue(refused.getMessage().startsWith(CapabilityMissingException.CODE), refused.getMessage());
        assertEquals(List.of(Capabilities.OUTBOX_RELAY_INBOX), refused.missing());
        assertEquals(1, refused.getSuppressed().length);
        assertEquals("no reconciliation engine", refused.getSuppressed()[0].getCause().getMessage());
        assertEquals(SangamRuntime.State.FAILED, rt.state());
        assertEquals(List.of(), log);
        assertFalse(claims.claimed().contains(Capabilities.OUTBOX_RELAY_INBOX));
    }

    @Test
    void aSettledComponentNamedLikeAFixedOneRefusesTheStart() {
        EngineBuildClaims<String> claims = new EngineBuildClaims<>();
        List<String> log = new ArrayList<>();
        claims.expect(Capabilities.OUTBOX_RELAY_INBOX, engine -> List.of(recording("jobs", 1, log)));
        claims.engineBuilt("engine");
        SangamRuntime rt = seams().runtime(claims, NEEDED, List.of(recording("jobs", Integer.MAX_VALUE, log)),
                List.of());
        assertThrows(IllegalArgumentException.class, rt::start);
        assertEquals(SangamRuntime.State.FAILED, rt.state());
        assertEquals(List.of(), log);
    }
}

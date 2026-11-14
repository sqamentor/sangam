// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.policy.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Owner decision 166 (M-083): the per-command requirement table and the safe-token rule of the decision. An ambiguous
 * or misspelt configuration stops the start-up rather than leaving a command silently unchecked. Written for the test
 * phase (not run here).
 */
class ExternalPolicyCommandsTest {

    static final List<String> KNOWN = List.of("task.complete", "process.start", "process.cancel");

    @Test
    void namedEntriesWinOverTheWildcard() {
        ExternalPolicyCommands c = ExternalPolicyCommands.of(KNOWN, List.of("*"), List.of("process.cancel"));
        assertEquals(ExternalPolicyRequirement.REQUIRED, c.requirement("task.complete"));
        assertEquals(ExternalPolicyRequirement.BEST_EFFORT, c.requirement("process.cancel"));

        ExternalPolicyCommands d = ExternalPolicyCommands.of(KNOWN, List.of("task.complete"), List.of("*"));
        assertEquals(ExternalPolicyRequirement.REQUIRED, d.requirement("task.complete"));
        assertEquals(ExternalPolicyRequirement.BEST_EFFORT, d.requirement("process.start"));
    }

    @Test
    void anUnlistedCommandIsNotChecked() {
        ExternalPolicyCommands c = ExternalPolicyCommands.of(KNOWN, List.of("task.complete"), List.of());
        assertEquals(ExternalPolicyRequirement.NONE, c.requirement("process.start"));
        assertFalse(c.none());
        assertTrue(ExternalPolicyCommands.of(KNOWN, List.of(" ", ""), null).none());
    }

    @Test
    void ambiguousOrUnknownEntriesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> ExternalPolicyCommands.of(KNOWN, List.of("*"), List.of("*")));
        assertThrows(IllegalArgumentException.class,
                () -> ExternalPolicyCommands.of(KNOWN, List.of("task.complete"), List.of("task.complete")));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ExternalPolicyCommands.of(KNOWN, List.of("task.compelte"), List.of()));
        assertTrue(e.getMessage().contains("task.compelte"));
    }

    @Test
    void remoteValuesReachResponsesOnlyAsSafeTokens() {
        assertEquals("OUTSIDE_HOURS", ExternalPolicyDecision.deny(" OUTSIDE_HOURS ", null).reasonCode());
        assertEquals(ExternalPolicyDecision.UNSPECIFIED, ExternalPolicyDecision.deny("<script>x</script>", null).reasonCode());
        assertEquals(ExternalPolicyDecision.UNSPECIFIED, ExternalPolicyDecision.deny("a".repeat(65), null).reasonCode());
        assertEquals(ExternalPolicyDecision.UNSPECIFIED, ExternalPolicyDecision.deny(null, null).reasonCode());
        assertNull(ExternalPolicyDecision.allowContinue("has space").policyId());
        assertNull(ExternalPolicyDecision.allowContinue("p").reasonCode(), "an allow carries no reason");
    }

    @Test
    void theRequestNeedsTheServerSideIdentity() {
        assertThrows(IllegalArgumentException.class, () -> new ExternalPolicyRequest(null, "u1", null, null,
                "task.complete", "POST", "/x", null));
        assertThrows(IllegalArgumentException.class, () -> new ExternalPolicyRequest("t1", " ", null, null,
                "task.complete", "POST", "/x", null));
        assertThrows(IllegalArgumentException.class, () -> new ExternalPolicyRequest("t1", "u1", null, null,
                null, "POST", "/x", null));
    }
}

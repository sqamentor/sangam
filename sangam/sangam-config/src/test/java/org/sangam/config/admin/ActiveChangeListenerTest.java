// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.sangam.config.common.ConfigException;
import org.sangam.config.profile.InMemoryProfileRepository;
import org.sangam.config.profile.ProfileRepository;

/**
 * DEC-225-4: a listener is told after each change to the profiles in force (activation, retirement, one call per
 * import), when the resolver already resolves the new chain; a refused activation tells nobody; a failing listener
 * neither undoes the change nor stops the next listener.
 */
class ActiveChangeListenerTest {

    @Test
    void listenersSeeEachChangeInForceAndTheNewChain() {
        InMemoryProfileRepository repo = new InMemoryProfileRepository();
        // the service's own resolver: the listener must see it already invalidated
        org.sangam.config.profile.ProfileResolver resolver = new org.sangam.config.profile.ProfileResolver(repo,
                org.sangam.config.profile.LayerModel.open());
        ConfigAdminService a = new ConfigAdminService(repo, resolver,
                new org.sangam.config.profile.ProfileValidator(org.sangam.config.profile.LayerModel.open()),
                new ConfigAdminService.InMemoryChangeLog(), java.time.Clock.fixed(
                        LocalDate.of(2026, 11, 5).atStartOfDay().toInstant(java.time.ZoneOffset.UTC),
                        java.time.ZoneOffset.UTC));
        List<String> seen = new ArrayList<>();
        a.onActiveChange(changes -> {
            throw new IllegalStateException("a failing listener");
        });
        a.onActiveChange(changes -> changes.forEach(r -> seen.add(r.action() + " " + r.profileId() + "@" + r.version()
                + " x=" + resolver.resolve("ZZ", LocalDate.of(2026, 11, 5)).get("x"))));

        a.importActive(List.of(ActivationOwnScopeTest.doc("ZZ", "1", List.of()),
                ActivationOwnScopeTest.doc("YY", "1", List.of())), "release");
        assertEquals(List.of("IMPORTED ZZ@1 x=1", "IMPORTED YY@1 x=1"), seen);

        ActivationOwnScopeTest.approve(a, repo, ActivationOwnScopeTest.doc("ZZ", "2", List.of()));
        assertEquals(2, seen.size(), "draft, submit and approve change nothing in force");
        a.activate("ZZ", "2", "admin", List.of());
        assertEquals("ACTIVATED ZZ@2 x=2", seen.get(2));
        assertEquals(ProfileRepository.Status.ACTIVE, repo.get("ZZ", "2").orElseThrow().status(),
                "the failing listener did not undo the activation");

        a.retire("ZZ", "2", "admin", "withdrawn");
        assertEquals("RETIRED ZZ@2 x=1", seen.get(3));

        ActivationOwnScopeTest.approve(a, repo, ActivationOwnScopeTest.doc("ZZ", "3", List.of("NO_SUCH_BASE")));
        assertThrows(ConfigException.class, () -> a.activate("ZZ", "3", "admin", List.of()));
        assertEquals(4, seen.size(), "a refused activation is not a change in force");
        a.importActive(List.of(), "release");
        assertEquals(4, seen.size(), "an empty import is not a change");
    }
}

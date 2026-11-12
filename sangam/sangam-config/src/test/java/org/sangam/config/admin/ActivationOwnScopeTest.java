// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.config.common.ConfigException;
import org.sangam.config.profile.InMemoryProfileRepository;
import org.sangam.config.profile.LayerModel;
import org.sangam.config.profile.ProfileDocument;
import org.sangam.config.profile.ProfileRepository;
import org.sangam.config.profile.ProfileResolver;
import org.sangam.config.profile.ProfileValidator;

/**
 * SL-121: activation always validates the document's own scope (an office overlay as that office of its scope), even
 * when the caller lists no scopes (REST {@code activate} with no body); a refusal is recorded in the change register
 * (SL-120: the change marker moves for the other nodes).
 */
class ActivationOwnScopeTest {

    static final LocalDate FROM = LocalDate.of(2026, 4, 1);

    static ProfileDocument doc(String id, String version, List<String> ext) {
        return new ProfileDocument(id, "government", version, FROM, null, ext, List.of(), Map.of(), "test", Map.of(),
                List.of(), Map.of("x", version));
    }

    static ConfigAdminService.InMemoryChangeLog log = new ConfigAdminService.InMemoryChangeLog();

    static ConfigAdminService admin(InMemoryProfileRepository repo) {
        log = new ConfigAdminService.InMemoryChangeLog();
        return new ConfigAdminService(repo, new ProfileResolver(repo, LayerModel.open()),
                new ProfileValidator(LayerModel.open()), log,
                Clock.fixed(LocalDate.of(2026, 11, 5).atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
    }

    static void approve(ConfigAdminService a, InMemoryProfileRepository repo, ProfileDocument d) {
        a.saveDraft(d, "maker");
        a.submit(d.id(), d.version(), "maker");
        a.approve(d.id(), d.version(), "checker", repo.get(d.id(), d.version()).orElseThrow().hash());
    }

    @Test
    void theOwnScopeIsValidatedWithNoScopesListed() {
        InMemoryProfileRepository repo = new InMemoryProfileRepository();
        ConfigAdminService a = admin(repo);
        approve(a, repo, doc("ZZ", "1", List.of("NO_SUCH_BASE")));
        ConfigException e = assertThrows(ConfigException.class, () -> a.activate("ZZ", "1", "admin", List.of()));
        assertEquals("ACTIVATION_REFUSED", e.code());
        assertTrue(e.findings().stream().anyMatch(f -> f.code().equals("EXTENDS_MISSING")), String.valueOf(e.findings()));
        assertEquals(ProfileRepository.Status.APPROVED, repo.get("ZZ", "1").orElseThrow().status());
        assertTrue(a.history("ZZ").stream().anyMatch(r -> r.action().equals("ACTIVATION_REFUSED")
                && "EXTENDS_MISSING".equals(r.reason())), "the refusal is in the change register");
        assertThrows(ConfigException.class, () -> a.activate("ZZ", "1", "admin", null), "null scopes: the same");
    }

    @Test
    void anOfficeOverlayIsValidatedAsThatOfficeOfItsScope() {
        InMemoryProfileRepository repo = new InMemoryProfileRepository();
        ConfigAdminService a = admin(repo);
        approve(a, repo, doc("ZZ", "1", List.of()));
        a.activate("ZZ", "1", "admin", List.of());
        approve(a, repo, doc("ZZ/OFF-1", "1", List.of("NO_SUCH_BASE")));
        ConfigException e = assertThrows(ConfigException.class, () -> a.activate("ZZ/OFF-1", "1", "admin", List.of()));
        assertTrue(e.findings().stream().anyMatch(f -> f.code().equals("EXTENDS_MISSING")), String.valueOf(e.findings()));

        approve(a, repo, doc("ZZ/OFF-2", "1", List.of()));
        assertEquals(List.of(), a.activate("ZZ/OFF-2", "1", "admin", List.of()).stream()
                .filter(f -> f.isError()).toList());
        assertEquals(ProfileRepository.Status.ACTIVE, repo.get("ZZ/OFF-2", "1").orElseThrow().status());
    }
}

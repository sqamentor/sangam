// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.sangam.config.common.Finding;

/**
 * SL-119: {@link ProfileResolver#resolveBinding} recomposes a case's stored binding from the exact document versions it
 * names, whatever is active today. SL-120: a repository change marker that moves (a change committed by another node)
 * makes the resolver drop its cache without any local {@code invalidate()}.
 */
class ProfilePinningTest {

    static final LocalDate FROM = LocalDate.of(2026, 4, 1);
    static final LocalDate AS_OF = LocalDate.of(2026, 11, 5);

    static ProfileDocument doc(String id, String version, List<String> ext, List<Map<String, Object>> adopt,
                               Map<String, Object> data) {
        return new ProfileDocument(id, "government", version, FROM, null, ext, adopt, Map.of(), "test", Map.of(),
                List.of(), data);
    }

    /** A shared store as several nodes see it: the marker moves when any node records a change. */
    static final class SharedStore implements ProfileRepository {
        final InMemoryProfileRepository delegate = new InMemoryProfileRepository();
        final AtomicLong changes = new AtomicLong();

        @Override
        public void save(ProfileDocument doc, Status status) {
            delegate.save(doc, status);
        }

        @Override
        public void setStatus(String id, String version, Status status) {
            delegate.setStatus(id, version, status);
        }

        @Override
        public Optional<Stored> get(String id, String version) {
            return delegate.get(id, version);
        }

        @Override
        public List<Stored> versions(String id) {
            return delegate.versions(id);
        }

        @Override
        public List<String> ids() {
            return delegate.ids();
        }

        @Override
        public String changeMarker() {
            return Long.toString(changes.get());
        }
    }

    static SharedStore store() {
        SharedStore s = new SharedStore();
        s.save(doc("BASE", "1", List.of(), List.of(), Map.of("locale", Map.of("currency", "INR"), "x", "base"))
                .withVersion("1", FROM, null), ProfileRepository.Status.ACTIVE);
        s.save(doc("DONOR", "1", List.of(), List.of(), Map.of("rulebooks", Map.of("gfr", "donor-1"), "y", "never")),
                ProfileRepository.Status.ACTIVE);
        s.save(doc("ZZ", "1", List.of("BASE"), List.of(Map.of("profile", "DONOR", "sections", List.of("rulebooks"))),
                Map.of("x", "zz-1")), ProfileRepository.Status.ACTIVE);
        return s;
    }

    @Test
    void aStoredBindingResolvesToTheVersionsItNamesAfterANewVersionIsActive() {
        SharedStore s = store();
        ProfileResolver r = new ProfileResolver(s, LayerModel.open());
        ResolvedProfile v1 = r.resolve("ZZ", AS_OF);
        assertTrue(v1.binding().contains("DONOR@1[rulebooks]"), v1.binding());
        assertEquals("donor-1", v1.get("rulebooks.gfr"));

        s.save(doc("ZZ", "2", List.of("BASE"), List.of(Map.of("profile", "DONOR", "sections", List.of("rulebooks"))),
                Map.of("x", "zz-2")), ProfileRepository.Status.ACTIVE);
        s.save(doc("DONOR", "2", List.of(), List.of(), Map.of("rulebooks", Map.of("gfr", "donor-2"))),
                ProfileRepository.Status.ACTIVE);
        s.changes.incrementAndGet();
        assertEquals("zz-2", r.resolve("ZZ", AS_OF).get("x"), "today's versions");

        ResolvedProfile pinned = r.resolveBinding("ZZ", v1.binding(), AS_OF);
        assertTrue(pinned.findings().stream().noneMatch(ProfileResolver::isBindingError), String.valueOf(pinned.findings()));
        assertEquals(v1.binding(), pinned.binding());
        assertEquals(v1.data(), pinned.data());
        assertEquals("zz-1", pinned.get("x"));
        assertEquals("donor-1", pinned.get("rulebooks.gfr"), "adopted sections from the bound donor version");
        assertEquals(null, pinned.get("y"), "only the adopted sections");

        s.setStatus("ZZ", "1", ProfileRepository.Status.RETIRED);
        s.changes.incrementAndGet();
        assertEquals("zz-1", r.resolveBinding("ZZ", v1.binding(), AS_OF).get("x"), "a retired version still governs its cases");
    }

    @Test
    void aBindingThatCannotBeRecomposedCarriesABindingError() {
        SharedStore s = store();
        ProfileResolver r = new ProfileResolver(s, LayerModel.open());
        String b = r.resolve("ZZ", AS_OF).binding();
        assertEquals(List.of("BINDING_INVALID"), codes(r.resolveBinding("ZZ", "no-hash", AS_OF)));
        assertEquals(List.of("BINDING_VERSION_MISSING"), codes(r.resolveBinding("ZZ", b.replace("ZZ@1", "ZZ@9"), AS_OF)));
        String tampered = b.substring(0, b.lastIndexOf('#') + 1) + "000000000000";
        assertEquals(List.of("BINDING_MISMATCH"), codes(r.resolveBinding("ZZ", tampered, AS_OF)));
    }

    @Test
    void aChangeMarkerMovedByAnotherNodeDropsTheCache() {
        SharedStore s = store();
        ProfileResolver nodeB = new ProfileResolver(s, LayerModel.open());
        assertEquals("zz-1", nodeB.resolve("ZZ", AS_OF).get("x"));
        // node A activates version 2 in the shared store; node B's resolver is never invalidated locally
        s.save(doc("ZZ", "2", List.of("BASE"), List.of(), Map.of("x", "zz-2")), ProfileRepository.Status.ACTIVE);
        assertEquals("zz-1", nodeB.resolve("ZZ", AS_OF).get("x"), "marker unchanged: the cached resolution stands");
        s.changes.incrementAndGet();   // node A's change record commits
        assertEquals("zz-2", nodeB.resolve("ZZ", AS_OF).get("x"), "marker moved: node B resolves again");
        assertFalse(nodeB.resolve("ZZ", AS_OF).binding().contains("ZZ@1"));
    }

    static List<String> codes(ResolvedProfile p) {
        return p.findings().stream().filter(ProfileResolver::isBindingError).map(Finding::code).toList();
    }
}

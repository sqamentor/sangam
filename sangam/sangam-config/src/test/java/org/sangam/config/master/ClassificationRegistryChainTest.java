// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.master;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.config.common.Finding;

/**
 * SL-124: master codes are searched through the reference scopes of the chain the caller holds (a running case's
 * pinned chain), not through the chain in force today; {@link ClassificationRegistry#referenceScopesOf} turns a
 * resolved chain into those scopes, most specific first.
 */
class ClassificationRegistryChainTest {

    static final LocalDate AS_OF = LocalDate.of(2026, 11, 5);
    static final ClassificationScheme SCHEME = ClassificationScheme.from(Map.of("id", "HOA", "segments",
            List.of(Map.of("id", "major", "width", 4, "master", true))));

    static MasterCode major(String scope, String code) {
        return new MasterCode(scope, "HOA", "major", code, null, Map.of("en", code), Map.of(), null, null, "test");
    }

    @Test
    void referenceScopesFollowTheGivenChain() {
        assertEquals(List.of("ZZ", "DONOR", "BASE"),
                ClassificationRegistry.referenceScopesOf(List.of("BASE@1", "DONOR@1[rulebooks]", "ZZ@2")));

        MasterStore.InMemory store = new MasterStore.InMemory();
        store.put(major("OLD_BASE", "2054"));   // the base the case's bound version extends
        store.put(major("NEW_BASE", "2202"));   // the base today's version extends
        ClassificationRegistry reg = new ClassificationRegistry(store).withReferenceScopes(s -> List.of("NEW_BASE"));
        ClassificationRegistry.Code old = reg.parse(SCHEME, "2054");
        ClassificationRegistry.Code today = reg.parse(SCHEME, "2202");

        // the registry's own default (today's chain)
        assertTrue(Finding.anyError(reg.validate("ZZ", SCHEME, old, AS_OF)));
        assertFalse(Finding.anyError(reg.validate("ZZ", SCHEME, today, AS_OF)));

        // the pinned chain given by the caller
        List<String> pinned = ClassificationRegistry.referenceScopesOf(List.of("OLD_BASE@1", "ZZ@1"));
        assertFalse(Finding.anyError(reg.validate("ZZ", pinned, SCHEME, old, AS_OF, id -> SCHEME)),
                "the code of the bound chain is valid for the running case");
        assertEquals(List.of("CODE_UNKNOWN"), Finding.errors(reg.validate("ZZ", pinned, SCHEME, today, AS_OF, id -> SCHEME))
                .stream().map(Finding::code).toList(), "today's chain does not leak into the pinned read");
        assertTrue(reg.lookup("ZZ", pinned, "HOA", "major", null, "2054", AS_OF).isPresent());
        assertTrue(reg.lookup("ZZ", pinned, "HOA", "major", null, "2202", AS_OF).isEmpty());
    }
}

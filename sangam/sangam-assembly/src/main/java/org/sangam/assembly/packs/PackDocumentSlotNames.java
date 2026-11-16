// SPDX-License-Identifier: Apache-2.0
package org.sangam.assembly.packs;

import java.util.Set;
import java.util.TreeSet;

import org.sangam.core.engine.DocumentSlotNames;
import org.sangam.packs.spi.PackRegistry;

/**
 * Owner decision 385 (2), layer 2, and decision 387 (2) (decision 390 (5)): the installer of the DSC slot names, in plain
 * Java so any host can run it. Every slot variable any pack in the registry declares (manifest {@code documentSlots})
 * is a name every caller write path refuses ({@link DocumentSlotNames}). The names are read on each call, so a pack a
 * plugin adds later is covered at once. The Spring host installs it ({@code PacksWiring}); the Quarkus host installs no
 * pack runtime yet and stays fail closed on the DSC steps.
 */
public final class PackDocumentSlotNames {

    private PackDocumentSlotNames() {
    }

    /** Every slot variable the packs in {@code packs} declare. */
    public static Set<String> slotNames(PackRegistry packs) {
        Set<String> names = new TreeSet<>();
        packs.all().forEach(p -> p.descriptor().documentSlots().forEach(s -> names.add(s.slot())));
        return Set.copyOf(names);
    }

    /** Installs the names of {@code packs} for every caller write path of this process. */
    public static void install(PackRegistry packs) {
        java.util.Objects.requireNonNull(packs, "packs");
        DocumentSlotNames.install(() -> slotNames(packs));
    }
}

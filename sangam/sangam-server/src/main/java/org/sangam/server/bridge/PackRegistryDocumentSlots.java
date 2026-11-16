// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.sangam.packs.spi.PackRegistry;
import org.sangam.rest.port.PackDocumentSlotsPort;

/**
 * Owner decision 269: {@link PackDocumentSlotsPort} over sangam-packs' {@link PackRegistry}: the pairs are the pack
 * manifest's {@code documentSlots} ({@link org.sangam.packs.spi.PackDescriptor#documentSlots()}), the same source the
 * document route fills the slots from ({@link OperatonDocumentSlots}).
 */
public final class PackRegistryDocumentSlots implements PackDocumentSlotsPort {

    private final PackRegistry packs;

    public PackRegistryDocumentSlots(PackRegistry packs) {
        this.packs = packs;
    }

    @Override
    public Optional<List<DocumentSlot>> documentSlots(String packId) {
        if (packId == null || packId.isBlank()) {
            return Optional.empty();
        }
        return packs.find(packId).map(p -> p.descriptor().documentSlots().stream()
                .map(s -> new DocumentSlot(s.documentType(), s.slot())).toList());
    }

    /**
     * Owner decision 385 (2), layer 2: every slot variable any pack in {@code packs} declares, the names the caller write
     * paths refuse ({@link org.sangam.core.engine.DocumentSlotNames}). Read on each call, so a pack a plugin adds later
     * is covered at once.
     */
    public static Set<String> slotNames(PackRegistry packs) {
        Set<String> names = new TreeSet<>();
        packs.all().forEach(p -> p.descriptor().documentSlots().forEach(s -> names.add(s.slot())));
        return Set.copyOf(names);
    }
}

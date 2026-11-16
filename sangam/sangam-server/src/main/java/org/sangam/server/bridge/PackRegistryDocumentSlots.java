// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.util.List;
import java.util.Optional;

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
}

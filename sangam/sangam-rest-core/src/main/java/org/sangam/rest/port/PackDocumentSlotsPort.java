// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

import java.util.List;
import java.util.Optional;

/**
 * Owner decision 269: the documentType-to-slot pairs a pack declares for its DSC steps (manifest {@code documentSlots}),
 * read from the pack itself (there is no server table). The server binds it with sangam-packs' registry; without the
 * packs module there is no binding and {@code GET /packs/{id}/document-slots} answers {@code 501}.
 */
public interface PackDocumentSlotsPort {

    record DocumentSlot(String documentType, String slot) {
    }

    /** The pairs of the pack in manifest order (empty when it declares none); empty when no such pack is registered. */
    Optional<List<DocumentSlot>> documentSlots(String packId);
}

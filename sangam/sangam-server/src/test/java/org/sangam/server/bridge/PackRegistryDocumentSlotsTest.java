// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.sangam.packs.spi.PackRegistry;
import org.sangam.rest.port.PackDocumentSlotsPort.DocumentSlot;

/**
 * Round-5 lane w2 item 3 (decision 269): the read route's pairs are the pack manifest's {@code documentSlots}, read
 * from the discovered packs (the same source the document route fills the slots from); an unknown or blank pack id is
 * empty, which the controller answers with 404.
 */
class PackRegistryDocumentSlotsTest {

    private final PackRegistryDocumentSlots slots = new PackRegistryDocumentSlots(PackRegistry.discover());

    @Test
    void theCorePacksPairsComeFromItsManifestInOrder() {
        assertEquals(Optional.of(List.of(new DocumentSlot("NOTING", "notingDocumentId"),
                new DocumentSlot("SANCTION_NOTE", "sanctionDocumentId"))), slots.documentSlots("sangam.core"));
    }

    @Test
    void anUnknownOrBlankPackIsEmpty() {
        assertTrue(slots.documentSlots("no.such.pack").isEmpty());
        assertTrue(slots.documentSlots(" ").isEmpty());
        assertTrue(slots.documentSlots(null).isEmpty());
    }
}

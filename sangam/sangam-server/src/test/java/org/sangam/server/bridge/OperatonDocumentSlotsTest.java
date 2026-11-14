// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.sangam.packs.spi.PackDeployer;
import org.sangam.packs.spi.PackDescriptor;
import org.sangam.packs.spi.PackRegistry;

/**
 * Owner decisions 267 to 269: the pack of an instance is read from its deployment name {@code pack:<id>@<version>}
 * (PackDeployer), and the slot pairs come from the manifest; the core pack's pairs are the ones its DSC steps' guard
 * reads (sangam-core-recommendation notingDocumentId NOTING, sangam-core-sanction sanctionDocumentId SANCTION_NOTE).
 */
class OperatonDocumentSlotsTest {

    @Test
    void thePackIdIsTheDeploymentNameWithoutPrefixAndVersion() {
        assertEquals(Optional.of("sangam.core"), OperatonDocumentSlots.packId("pack:sangam.core@1.0.1"));
        assertEquals(Optional.of("gov.p1.bill-payment"),
                OperatonDocumentSlots.packId("pack:gov.p1.bill-payment@2026.11.2"));
        assertEquals(Optional.empty(), OperatonDocumentSlots.packId("pack:@1.0.0"));
        assertEquals(Optional.empty(), OperatonDocumentSlots.packId("pack:no-version"));
        assertEquals(Optional.empty(), OperatonDocumentSlots.packId("app:sangam.core@1.0.1"));
        assertEquals(Optional.empty(), OperatonDocumentSlots.packId(null));
        assertEquals(PackDeployer.SOURCE, OperatonDocumentSlots.SOURCE);
    }

    @Test
    void theCorePackDeclaresTheSlotsItsDscStepsRead() {
        PackDescriptor core = PackRegistry.discover().find("sangam.core").orElseThrow().descriptor();
        assertEquals(Optional.of("notingDocumentId"), core.slotFor("NOTING"));
        assertEquals(Optional.of("sanctionDocumentId"), core.slotFor("SANCTION_NOTE"));
        assertEquals(Optional.empty(), core.slotFor("INVOICE"), "an unknown type has no slot");
    }
}

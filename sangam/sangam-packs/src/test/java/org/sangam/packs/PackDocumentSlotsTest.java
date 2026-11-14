// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.operaton.bpm.model.bpmn.Bpmn;
import org.operaton.bpm.model.bpmn.BpmnModelInstance;
import org.operaton.bpm.model.bpmn.instance.operaton.OperatonField;
import org.operaton.bpm.model.bpmn.instance.operaton.OperatonTaskListener;

import org.sangam.packs.core.DscSignatureGuardListener;
import org.sangam.packs.spi.ManifestPack;
import org.sangam.packs.spi.PackDescriptor;
import org.sangam.packs.spi.PackDescriptor.DocumentSlot;
import org.sangam.packs.spi.PackRegistry;
import org.sangam.packs.spi.SangamPack;

/**
 * Decision 269: each pack declares its DSC documentType-to-slot pairs in its manifest ({@code documentSlots}); the server
 * reads them, there is no server table. The core pack publishes NOTING = notingDocumentId and SANCTION_NOTE =
 * sanctionDocumentId, and every pack's manifest pairs are exactly those its own DSC gates use.
 */
class PackDocumentSlotsTest {

    @Test
    void theCorePackPublishesTheNotingAndSanctionNoteSlots() {
        PackDescriptor core = PackRegistry.discover().find("sangam.core").orElseThrow().descriptor();
        assertEquals(List.of(new DocumentSlot("NOTING", "notingDocumentId"),
                new DocumentSlot("SANCTION_NOTE", "sanctionDocumentId")), core.documentSlots());
        assertEquals(Optional.of("notingDocumentId"), core.slotFor("NOTING"));
        assertEquals(Optional.of("sanctionDocumentId"), core.slotFor("SANCTION_NOTE"));
        assertEquals(Optional.empty(), core.slotFor("OTHER"));
    }

    @Test
    void everyPacksManifestPairsAreExactlyThoseOfItsOwnDscGates() throws Exception {
        int gated = 0;
        for (SangamPack p : PackRegistry.discover().all()) {
            Set<DocumentSlot> fromModels = new LinkedHashSet<>();
            for (PackDescriptor.PackResource r : p.descriptor().resources(PackDescriptor.PackResource.Kind.BPMN)) {
                try (InputStream in = p.open(r.path())) {
                    BpmnModelInstance m = Bpmn.readModelFromStream(in);
                    for (OperatonTaskListener tl : m.getModelElementsByType(OperatonTaskListener.class)) {
                        if (!DscSignatureGuardListener.class.getName().equals(tl.getOperatonClass())
                                || !"create".equals(tl.getOperatonEvent())) {
                            continue;
                        }
                        String type = null;
                        String slot = null;
                        for (OperatonField f : tl.getOperatonFields()) {
                            if ("documentType".equals(f.getOperatonName())) {
                                type = f.getOperatonStringValue() != null ? f.getOperatonStringValue()
                                        : f.getOperatonString().getTextContent();
                            } else if ("documentSlot".equals(f.getOperatonName())) {
                                slot = f.getOperatonStringValue() != null ? f.getOperatonStringValue()
                                        : f.getOperatonString().getTextContent();
                            }
                        }
                        fromModels.add(new DocumentSlot(type.trim(), slot.trim()));
                    }
                }
            }
            if (!fromModels.isEmpty()) {
                gated++;
            }
            assertEquals(fromModels, new LinkedHashSet<>(p.descriptor().documentSlots()),
                    p.descriptor().id() + ": manifest documentSlots must equal its DSC gates");
        }
        assertTrue(gated >= 1, "at least the core pack has DSC gates");
    }

    @Test
    void aRepeatedTypeOrSlotOrAMalformedEntryIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> load(
                "[{\"documentType\":\"NOTING\",\"slot\":\"a\"},{\"documentType\":\"NOTING\",\"slot\":\"b\"}]"));
        assertThrows(IllegalArgumentException.class, () -> load(
                "[{\"documentType\":\"NOTING\",\"slot\":\"a\"},{\"documentType\":\"SANCTION_NOTE\",\"slot\":\"a\"}]"));
        assertThrows(IllegalArgumentException.class, () -> load("[{\"documentType\":\"NOTING\"}]"));
        assertEquals(List.of(), load("[]").documentSlots());
        assertEquals(List.of(), new PackDescriptor("t", "1.0.0", Map.of("en", "t"), "test", "", List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), List.of(), List.of())
                .documentSlots(), "the constructor without slots declares none");
    }

    private static PackDescriptor load(String slots) {
        byte[] manifest = ("{\"id\":\"t.pack\",\"version\":\"1.0.0\",\"documentSlots\":" + slots + "}")
                .getBytes(StandardCharsets.UTF_8);
        ClassLoader cl = new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                return "t/pack.json".equals(name) ? new ByteArrayInputStream(manifest) : null;
            }
        };
        return ManifestPack.load(cl, "t/pack.json").descriptor();
    }
}

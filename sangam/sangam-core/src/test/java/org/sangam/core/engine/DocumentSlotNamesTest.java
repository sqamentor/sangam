// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.engine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Owner decision 385 (2), layer 2: the declared DSC document slot names are refused on every caller write. */
class DocumentSlotNamesTest {

    @AfterEach
    void reset() {
        DocumentSlotNames.reset();
    }

    @Test
    void aDeclaredSlotNameIsRefusedByNameWithItsCode() {
        DocumentSlotNames.install(() -> Set.of("sanctionDocumentId", "notingDocumentId"));
        DocumentSlotNames.Refused e = assertThrows(DocumentSlotNames.Refused.class,
                () -> DocumentSlotNames.refuse(List.of("amount", "sanctionDocumentId", "notingDocumentId")));
        assertEquals(Set.of("sanctionDocumentId", "notingDocumentId"), e.variables());
        assertTrue(e.getMessage().startsWith("DOCUMENT_SLOT_VARIABLE: variable notingDocumentId, sanctionDocumentId"),
                e.getMessage());
        assertTrue(e.detail().contains("document route"), e.detail());
        assertFalse(e.detail().contains("DOCUMENT_SLOT_VARIABLE"), "the detail carries no code");
    }

    @Test
    void otherNamesNullAndEmptyPass() {
        DocumentSlotNames.install(() -> Set.of("sanctionDocumentId"));
        assertDoesNotThrow(() -> DocumentSlotNames.refuse(List.of("amount", "sanctionDocumentIdX")));
        assertDoesNotThrow(() -> DocumentSlotNames.refuse(null));
        assertDoesNotThrow(() -> DocumentSlotNames.refuse(List.of()));
        assertDoesNotThrow(() -> DocumentSlotNames.refuse(Arrays.asList((String) null)));
    }

    @Test
    void theNamesAreReadAtEachCall() {
        java.util.Set<String> live = new java.util.concurrent.CopyOnWriteArraySet<>();
        DocumentSlotNames.install(() -> Set.copyOf(live));
        assertDoesNotThrow(() -> DocumentSlotNames.refuse(List.of("lateSlot")));
        live.add("lateSlot");
        assertThrows(DocumentSlotNames.Refused.class, () -> DocumentSlotNames.refuse(List.of("lateSlot")));
    }

    @Test
    void aFailingSourceRefusesTheWrite() {
        DocumentSlotNames.install(() -> {
            throw new IllegalStateException("registry down");
        });
        assertThrows(IllegalStateException.class, () -> DocumentSlotNames.refuse(List.of("amount")));
        DocumentSlotNames.install(() -> null);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> DocumentSlotNames.refuse(List.of("amount")));
        assertTrue(e.getMessage().startsWith("DOCUMENT_SLOT_VARIABLE"), e.getMessage());
    }

    @Test
    void decision387CheckedRefusesASlotKeyAndOtherwiseReturnsTheSameMap() {
        DocumentSlotNames.install(() -> Set.of("sanctionDocumentId"));
        java.util.Map<String, Object> slot = java.util.Map.of("amount", 1, "sanctionDocumentId", "d-1");
        DocumentSlotNames.Refused e = assertThrows(DocumentSlotNames.Refused.class,
                () -> DocumentSlotNames.checked(slot));
        assertEquals(Set.of("sanctionDocumentId"), e.variables());
        java.util.Map<String, Object> other = java.util.Map.of("amount", 1);
        org.junit.jupiter.api.Assertions.assertSame(other, DocumentSlotNames.checked(other));
        org.junit.jupiter.api.Assertions.assertNull(DocumentSlotNames.checked(null));
    }

    @Test
    void byDefaultNoNameIsDeclared() {
        assertEquals(Set.of(), DocumentSlotNames.names());
        assertThrows(NullPointerException.class, () -> DocumentSlotNames.install(null));
    }
}

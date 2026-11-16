// SPDX-License-Identifier: Apache-2.0
package org.sangam.assembly.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.engine.DocumentSlotNames;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.packs.port.DocumentSlotBindings;
import org.sangam.packs.spi.PackRegistry;

/**
 * Owner decision 387 (2) (decision 390 (5)): the packs' DSC slot ports are plain Java in sangam-assembly, so any host can
 * install them: the slot-name installer covers every slot the packs on the class path declare, and the document-route
 * binding check is built from core types only. The binding check's behaviour on a database is covered on the Spring
 * host by {@code DscSlotBindingH2Test}.
 */
class PackDocumentSlotNamesTest {

    @AfterEach
    void reset() {
        DocumentSlotNames.reset();
    }

    @Test
    void theInstallerCoversEverySlotThePacksDeclare() {
        PackRegistry registry = PackRegistry.discover();
        Set<String> names = PackDocumentSlotNames.slotNames(registry);
        assertTrue(names.containsAll(Set.of("sanctionDocumentId", "notingDocumentId")), names::toString);
        registry.all().forEach(p -> p.descriptor().documentSlots()
                .forEach(s -> assertTrue(names.contains(s.slot()), p.descriptor().id() + " " + s.slot())));
        PackDocumentSlotNames.install(registry);
        assertEquals(names, DocumentSlotNames.names());
        assertThrows(NullPointerException.class, () -> PackDocumentSlotNames.install(null));
    }

    @Test
    void theBindingCheckIsPlainJavaAndRefusesWhatItCannotRead() {
        DocumentSlotBindings b = new AuditDocumentSlotBindings(new org.sangam.core.layer.Transactions() {
            @Override
            public <T> T inTransaction(java.util.function.Function<java.sql.Connection, T> work) {
                throw new IllegalStateException("no database in this test");
            }
        }, java.util.Optional::empty, new AuditChain(Dialect.POSTGRESQL));
        // a malformed question is unbound without asking the database; one it cannot answer fails, never binds
        assertEquals(false, b.isBound("t", java.util.List.of(), "sanctionDocumentId", "SANCTION_NOTE", "doc-1"));
        assertThrows(IllegalStateException.class,
                () -> b.isBound("t", java.util.List.of("pi-1"), "sanctionDocumentId", "SANCTION_NOTE", "doc-1"));
        assertThrows(NullPointerException.class,
                () -> new AuditDocumentSlotBindings(null, java.util.Optional::empty, new AuditChain(Dialect.POSTGRESQL)));
        assertThrows(NullPointerException.class, () -> new AuditDocumentSlotBindings(
                new org.sangam.core.layer.Transactions() {
                    @Override
                    public <T> T inTransaction(java.util.function.Function<java.sql.Connection, T> work) {
                        throw new IllegalStateException("unused");
                    }
                }, null, new AuditChain(Dialect.POSTGRESQL)));
    }
}

// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.core.layer.TenantKey;
import org.sangam.packs.port.PackAuditEvent;

/**
 * RM-49: a pack audit event without a tenant is refused with a clear error before anything is appended; it is never
 * charged to a default tenant or chained under an empty one. The sink is built without a chain or a transaction
 * helper, so any attempt to append would fail with a NullPointerException instead of the refusal.
 */
class PacksAuditSinkTest {

    static PackAuditEvent event(String tenant) {
        return new PackAuditEvent("SANCTION_APPROVED", tenant, "GRANT-42", "pi-7", "person-1", "seat-1", null,
                "delegation", "p3@1.0.0", "hash", "op-1", Map.of("level", 2), Instant.parse("2026-11-12T10:00:00Z"));
    }

    @Test
    void anEventWithoutATenantIsRefusedBeforeAnythingIsAppended() {
        PacksAuditSink sink = new PacksAuditSink(null, null);
        for (String none : new String[]{null, "", "   ", TenantKey.SHARED}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> sink.record(event(none)),
                    "tenant '" + none + "' must be refused, not appended");
            assertTrue(e.getMessage().contains("SANCTION_APPROVED"), e.getMessage());
            assertTrue(e.getMessage().contains("GRANT-42") && e.getMessage().contains("pi-7"), e.getMessage());
            assertTrue(e.getMessage().contains("names no tenant"), e.getMessage());
        }
    }

    @Test
    void anEventWithATenantKeepsItsOwnTenant() {
        assertEquals("tenant-a", PacksAuditSink.requireTenant(event("tenant-a")));
    }
}

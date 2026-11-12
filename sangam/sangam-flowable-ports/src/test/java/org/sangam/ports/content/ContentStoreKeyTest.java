// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.content;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * SG-03, owner decision Q10(b) (2026-11-12): content stored without a tenant keeps the {@code _shared} store-key
 * segment, and the {@code CONTENT_ITEM} schema is unchanged. This pins the segment rule so a change to it is a
 * deliberate decision, not a side effect (existing objects are addressed by the keys already written).
 */
class ContentStoreKeyTest {

    @Test
    void contentWithoutATenantUsesTheSharedSegment() {
        assertEquals("_shared", ContentService.safe(null));
        assertEquals("_shared", ContentService.safe(""));
        assertEquals("_shared", ContentService.safe("   "));
    }

    @Test
    void aTenantIdIsUsedAsItIsWhenItHoldsOnlySafeCharacters() {
        assertEquals("t1", ContentService.safe("t1"));
        assertEquals("RJ-IFMS_2.0", ContentService.safe("RJ-IFMS_2.0"));
    }

    @Test
    void noPathSeparatorOrOtherSyntaxReachesTheKey() {
        assertEquals("a_b", ContentService.safe("a/b"));
        assertEquals("a_b", ContentService.safe("a\\b"));
        assertEquals(".._x", ContentService.safe("../x"));
        assertEquals("t_x_y", ContentService.safe("t x:y"));
    }
}

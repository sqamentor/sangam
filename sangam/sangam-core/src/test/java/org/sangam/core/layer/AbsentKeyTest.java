// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.layer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** SL-84: the stored form of an absent key part is non-empty (Oracle '' = NULL) and never aliases a real value. */
class AbsentKeyTest {

    @Test
    void absentIsStoredAsTheNonEmptyReservedValue() {
        assertEquals(AbsentKey.NONE, AbsentKey.encode(null));
        assertEquals(AbsentKey.NONE, AbsentKey.encode(""));
        assertFalse(AbsentKey.NONE.isEmpty(), "Oracle stores '' as NULL: the absent key must not be empty");
        assertFalse(AbsentKey.NONE.contains("%") || AbsentKey.NONE.contains("_"), "no LIKE wildcard in the absent key");
    }

    @Test
    void realValuesPassThroughAndRoundTrip() {
        for (String v : new String[]{"2054", "2054-00", " ", "--", "-x", "x-"}) {
            assertEquals(v, AbsentKey.encode(v));
            assertEquals(v, AbsentKey.decode(AbsentKey.encode(v)));
        }
        assertNull(AbsentKey.decode(AbsentKey.encode(null)));
        assertNull(AbsentKey.decode(AbsentKey.encode("")));
    }

    @Test
    void theReservedValueCannotBeARealValue() {
        assertThrows(IllegalArgumentException.class, () -> AbsentKey.encode(AbsentKey.NONE));
    }

    @Test
    void rowsWrittenWithTheOldEmptyFormReadAsAbsent() {
        assertNull(AbsentKey.decode(""));
        assertNull(AbsentKey.decode(null));
    }
}

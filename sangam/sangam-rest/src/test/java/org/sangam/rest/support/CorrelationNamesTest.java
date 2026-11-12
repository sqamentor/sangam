// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.sangam.rest.port.ApiProblem;

/** SL-80: one naming rule for correlation parameters given as JSON pointers. */
class CorrelationNamesTest {

    @Test
    void topLevelAndNestedPointersAreDottedPaths() {
        assertEquals("billId", CorrelationNames.fromPointer("/billId", "f"));
        assertEquals("bill.id", CorrelationNames.fromPointer("/bill/id", "f"));
        assertEquals("a.b.c", CorrelationNames.fromPointer("/a/b/c", "f"));
        // RFC 6901 unescaping: ~0 is '~'; "~01" is the literal "~1"
        assertEquals("a~b", CorrelationNames.fromPointer("/a~0b", "f"));
        assertEquals("x.~1", CorrelationNames.fromPointer("/x/~01", "f"));
    }

    @Test
    void twoNestedPointersWithTheSameLeafNoLongerCollide() {
        // the JDBC adapter used the last segment only: both were "id"
        assertTrue(!CorrelationNames.fromPointer("/order/id", "f").equals(CorrelationNames.fromPointer("/bill/id", "f")));
    }

    @Test
    void pointersWithoutAnUnambiguousNameAreRefusedNamingTheField() {
        for (String bad : new String[]{"/a.b", "/a~1b", "/a//b", "/", "a/b", "", null, "/a/"}) {
            ApiProblem p = assertThrows(ApiProblem.class, () -> CorrelationNames.fromPointer(bad, "businessKeyPointer"),
                    String.valueOf(bad));
            assertEquals(400, p.status());
            assertEquals("VALIDATION_FAILED", p.code());
            assertEquals("businessKeyPointer", p.extensions().get("field"));
        }
    }
}

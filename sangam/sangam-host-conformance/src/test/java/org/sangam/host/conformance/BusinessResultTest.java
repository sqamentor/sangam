// SPDX-License-Identifier: Apache-2.0
package org.sangam.host.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** M-150 canonical business results: host artefacts (number types, map order) never make two hosts differ. Not run. */
class BusinessResultTest {

    @Test
    void integralNumbersReadTheSameWhateverTheirJavaType() {
        assertEquals("1500", BusinessResult.canonical(1500L));
        assertEquals("1500", BusinessResult.canonical(1500));
        assertEquals("1500", BusinessResult.canonical(new BigDecimal("1500.00")));
        assertEquals("1500", BusinessResult.canonical(BigInteger.valueOf(1500)));
        assertEquals("0", BusinessResult.canonical(new BigDecimal("0.000")));
        assertEquals("12.5", BusinessResult.canonical(new BigDecimal("12.50")));
        assertEquals("-7", BusinessResult.canonical((short) -7));
    }

    @Test
    void binaryFloatingPointAndRawBytesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> BusinessResult.canonical(1.5d));
        assertThrows(IllegalArgumentException.class, () -> BusinessResult.canonical(1.5f));
        assertThrows(IllegalArgumentException.class, () -> BusinessResult.canonical(new byte[] {1}));
    }

    @Test
    void mapsAreSortedAndListsKeepTheirOrder() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("z", 1L);
        m.put("a", List.of(2, "x"));
        assertEquals("{a=[2,x],z=1}", BusinessResult.canonical(m));
        assertEquals("[{feeMinor=1500}]", BusinessResult.canonical(List.of(Map.of("feeMinor", 1500L))));
        assertEquals("null", BusinessResult.canonical(null));
        assertEquals("true", BusinessResult.canonical(true));
    }

    @Test
    void aBuiltResultEqualsItsCanonicalForm() {
        BusinessResult built = BusinessResult.builder().put("feeMinor", 2500L).put("ended", false).build();
        assertEquals(BusinessResult.ofCanonical(Map.of("feeMinor", "2500", "ended", "false")), built);
        assertNotEquals(BusinessResult.ofCanonical(Map.of("feeMinor", "2500")), built);
    }

    @Test
    void namesAreRecordedOnceAndWellFormed() {
        BusinessResult.Builder b = BusinessResult.builder().put("a", 1);
        assertThrows(IllegalArgumentException.class, () -> b.put("a", 2));
        assertThrows(IllegalArgumentException.class, () -> b.put("has space", 2));
        assertThrows(IllegalArgumentException.class, () -> b.put(".lead", 2));
        assertThrows(NullPointerException.class, () -> b.put(null, 2));
    }
}

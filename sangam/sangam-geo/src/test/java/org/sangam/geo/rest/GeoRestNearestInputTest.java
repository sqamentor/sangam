// SPDX-License-Identifier: Apache-2.0
package org.sangam.geo.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Transactions;
import org.sangam.geo.GeoException;
import org.sangam.geo.GeoService;
import org.sangam.geo.index.SpatialIndex;

/**
 * The request parameters of {@code GET /geo/nearest} are checked where they enter ({@link GeoRestService#nearest}):
 * {@code maxKm} must be finite and not negative and is capped at half the Earth's circumference; {@code tags} is split
 * in linear time.
 */
class GeoRestNearestInputTest {

    private static final GeoCaller CALLER = new GeoCaller("t1", "alice", Set.of());

    /** Thrown by the store behind the service: seeing it means the request passed the boundary checks. */
    private static final class ReachedTheStore extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    /** A service whose store and index fail on first use. */
    private static GeoRestService service() {
        Transactions tx = (Transactions) Proxy.newProxyInstance(GeoRestNearestInputTest.class.getClassLoader(),
                new Class<?>[]{Transactions.class}, (p, m, a) -> {
                    throw new ReachedTheStore();
                });
        SpatialIndex index = (SpatialIndex) Proxy.newProxyInstance(GeoRestNearestInputTest.class.getClassLoader(),
                new Class<?>[]{SpatialIndex.class}, (p, m, a) -> {
                    throw new ReachedTheStore();
                });
        return new GeoRestService(new GeoService(tx, index, null, null), null, null);
    }

    // ------------------------------------------------------------------ maxKm

    @Test
    void aNonFiniteOrNegativeMaxKmIsRefusedBeforeTheStoreIsUsed() {
        GeoRestService rest = service();
        for (double bad : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1, -0.001,
                -1e308, -Double.MIN_VALUE}) {
            for (String objectType : new String[]{"clinic", null}) {
                GeoException e = assertThrows(GeoException.class,
                        () -> rest.nearest(CALLER, "wards", objectType, 26.9, 75.8, 5, bad, null), String.valueOf(bad));
                assertEquals("INVALID_REQUEST", e.code(), String.valueOf(bad));
                assertEquals(400, e.status());
            }
        }
    }

    @Test
    void aHugeMaxKmIsCappedAtHalfTheEarthsCircumference() {
        double capMeters = GeoRestService.MAX_SEARCH_KM * 1000;
        assertEquals(20_015_114.0, capMeters, 1.0, "pi times the mean Earth radius");
        for (double huge : new double[]{1e308, Double.MAX_VALUE, 1e9, GeoRestService.MAX_SEARCH_KM + 1}) {
            double m = GeoRestService.searchMeters(huge);
            assertTrue(Double.isFinite(m), "1e308 km times 1000 overflowed to infinity before");
            assertEquals(capMeters, m, 0.0, String.valueOf(huge));
        }
        // and such a request is served, not refused
        GeoRestService rest = service();
        assertThrows(ReachedTheStore.class, () -> rest.nearest(CALLER, "wards", null, 26.9, 75.8, 5, 1e308, null));
        assertThrows(ReachedTheStore.class, () -> rest.nearest(CALLER, null, "clinic", 26.9, 75.8, 5, 1e308, null));
    }

    @Test
    void anOrdinaryMaxKmIsKeptAndZeroStaysTheDefault() {
        assertEquals(0.0, GeoRestService.searchMeters(0), 0.0, "0 = not given: the service applies its default radius");
        assertEquals(0.0, GeoRestService.searchMeters(-0.0), 0.0);
        assertEquals(2500.0, GeoRestService.searchMeters(2.5), 0.0);
        assertEquals(GeoRestService.MAX_SEARCH_KM * 1000, GeoRestService.searchMeters(GeoRestService.MAX_SEARCH_KM), 0.0);
        GeoRestService rest = service();
        assertThrows(ReachedTheStore.class, () -> rest.nearest(CALLER, "wards", null, 26.9, 75.8, 5, 0, null));
    }

    // ------------------------------------------------------------------ tags

    @Test
    void tagsAreSplitOnCommasStrippedAndEmptyItemsDropped() {
        assertEquals(List.of("a", "b"), new ArrayList<>(GeoRestService.tagSet(" a , b ")));
        assertEquals(List.of("b", "a"), new ArrayList<>(GeoRestService.tagSet("b,a,b")), "order kept, duplicates once");
        assertEquals(List.of("a", "b c"), new ArrayList<>(GeoRestService.tagSet(",a,, ,b c,")));
        assertEquals(Set.of(), GeoRestService.tagSet(null));
        assertEquals(Set.of(), GeoRestService.tagSet(""));
        assertEquals(Set.of(), GeoRestService.tagSet("   "));
        assertEquals(Set.of(), GeoRestService.tagSet(" , ,, "));
    }

    @Test
    void aMillionSpacesBeforeATagAreSplitInLinearTime() {
        String tags = " ".repeat(1_000_000) + "x";
        Set<String> out = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> GeoRestService.tagSet(tags));
        assertEquals(Set.of("x"), out);
        String many = (" ".repeat(1000) + ",").repeat(1000) + "y";
        assertEquals(Set.of("y"), assertTimeoutPreemptively(Duration.ofSeconds(5), () -> GeoRestService.tagSet(many)));
    }
}

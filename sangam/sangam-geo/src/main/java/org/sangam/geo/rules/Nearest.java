// SPDX-License-Identifier: Apache-2.0
package org.sangam.geo.rules;

import org.sangam.geo.model.GeoMath;
import org.sangam.geo.model.GeoPoint;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Nearest-N over any located items (field units, offices, depots, facilities). The item source is the caller's
 * (dispatch keeps unit positions; registries keep office points), so this is a pure ranking function usable from
 * dispatch, DMN (through the FEEL function) and REST.
 */
public final class Nearest {

    private Nearest() {
    }

    /** A located candidate. {@code tags} are matched against required skills/capabilities. */
    public record Located(String id, GeoPoint position, Set<String> tags, Map<String, Object> attributes) {
        public Located {
            tags = tags == null ? Set.of() : Set.copyOf(tags);
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }

    public record Ranked(Located item, double distanceMeters, double bearingDeg) {
    }

    /**
     * @param requiredTags all must be present on a candidate (empty = no filter)
     * @param maxMeters    0 or negative = unbounded
     */
    public static List<Ranked> rank(GeoPoint origin, Collection<Located> candidates, Set<String> requiredTags, int n,
                                    double maxMeters, Predicate<Located> extraFilter) {
        List<Ranked> out = new ArrayList<>();
        for (Located c : candidates) {
            if (c.position() == null) {
                continue;
            }
            if (requiredTags != null && !requiredTags.isEmpty() && !c.tags().containsAll(requiredTags)) {
                continue;
            }
            if (extraFilter != null && !extraFilter.test(c)) {
                continue;
            }
            double d = GeoMath.haversineMeters(origin, c.position());
            if (maxMeters > 0 && d > maxMeters) {
                continue;
            }
            out.add(new Ranked(c, d, GeoMath.bearingDeg(origin, c.position())));
        }
        out.sort(Comparator.comparingDouble(Ranked::distanceMeters).thenComparing(r -> r.item().id()));
        return n > 0 && out.size() > n ? List.copyOf(out.subList(0, n)) : List.copyOf(out);
    }
}

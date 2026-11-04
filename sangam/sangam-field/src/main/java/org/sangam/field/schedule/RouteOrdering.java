// SPDX-License-Identifier: Apache-2.0
package org.sangam.field.schedule;

import org.sangam.geo.model.GeoMath;
import org.sangam.geo.model.GeoPoint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Route list (N05): orders a day's assignments by greedy nearest-neighbour from a start point, then improves it with
 * 2-opt. Due dates break ties first. This is a route list for the inspector, not route optimisation on a road graph
 * (that stays outside Sangam). Assignments without a location go last in due order.
 */
public final class RouteOrdering {

    public record Stop(Assignment assignment, double legMeters, double cumulativeMeters) {
    }

    private RouteOrdering() {
    }

    public static List<Stop> order(GeoPoint start, List<Assignment> items) {
        List<Assignment> located = new ArrayList<>(items.stream().filter(a -> a.location() != null).toList());
        List<Assignment> rest = items.stream().filter(a -> a.location() == null)
                .sorted(Comparator.comparing(Assignment::dueAt, Comparator.nullsLast(Comparator.naturalOrder()))).toList();
        List<Assignment> route = new ArrayList<>();
        GeoPoint cur = start;
        while (!located.isEmpty()) {
            GeoPoint from = cur;
            Assignment next = located.stream().min(Comparator.<Assignment>comparingDouble(a -> from == null ? 0
                            : GeoMath.haversineMeters(from, a.location()))
                    .thenComparing(Assignment::dueAt, Comparator.nullsLast(Comparator.naturalOrder()))).orElseThrow();
            route.add(next);
            located.remove(next);
            cur = next.location();
        }
        twoOpt(start, route);
        List<Stop> out = new ArrayList<>();
        double total = 0;
        GeoPoint prev = start;
        for (Assignment a : route) {
            double leg = prev == null ? 0 : GeoMath.haversineMeters(prev, a.location());
            total += leg;
            out.add(new Stop(a, leg, total));
            prev = a.location();
        }
        for (Assignment a : rest) {
            out.add(new Stop(a, 0, total));
        }
        return out;
    }

    private static void twoOpt(GeoPoint start, List<Assignment> r) {
        boolean improved = true;
        int guard = 0;
        while (improved && guard++ < 50) {
            improved = false;
            for (int i = 0; i < r.size() - 1; i++) {
                for (int j = i + 1; j < r.size(); j++) {
                    GeoPoint a = i == 0 ? start : r.get(i - 1).location();
                    if (a == null) {
                        continue;
                    }
                    GeoPoint b = r.get(i).location(), c = r.get(j).location();
                    GeoPoint d = j + 1 < r.size() ? r.get(j + 1).location() : null;
                    double before = GeoMath.haversineMeters(a, b) + (d == null ? 0 : GeoMath.haversineMeters(c, d));
                    double after = GeoMath.haversineMeters(a, c) + (d == null ? 0 : GeoMath.haversineMeters(b, d));
                    if (after + 1e-6 < before) {
                        java.util.Collections.reverse(r.subList(i, j + 1));
                        improved = true;
                    }
                }
            }
        }
    }
}

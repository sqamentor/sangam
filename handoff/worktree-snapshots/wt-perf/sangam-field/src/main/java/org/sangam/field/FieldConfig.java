// SPDX-License-Identifier: Apache-2.0
package org.sangam.field;

import java.util.Map;

/**
 * Module configuration. Every domain-specific value (unit kinds, speeds, SLA targets by priority) is data supplied by
 * the deployment or a pack; the defaults are neutral.
 *
 * @param offerTimeoutSeconds   how long a unit has to accept a dispatch offer before re-dispatch
 * @param arrivalRadiusMeters   a position ping within this radius of the job marks the unit ON_SCENE
 * @param roadFactor            straight-line to road distance multiplier for ETA
 * @param defaultSpeedKmh       ETA speed when the unit kind has none configured
 * @param speedKmhByUnitKind    ETA speed per unit kind (data)
 * @param slaSecondsByPriority  response-time target per job priority (data); 0 = none
 * @param maxCandidates         candidate units considered per dispatch round
 * @param maxCandidateMeters    candidate search radius (0 = unbounded)
 * @param maxClockSkewSeconds   default device-clock skew tolerated against server receipt
 * @param pullPageSize          change-feed page size for device pull
 * @param overdueGraceSeconds   grace after due date before an assignment becomes OVERDUE
 */
public record FieldConfig(int offerTimeoutSeconds, double arrivalRadiusMeters, double roadFactor, double defaultSpeedKmh,
                          Map<String, Double> speedKmhByUnitKind, Map<String, Long> slaSecondsByPriority,
                          int maxCandidates, double maxCandidateMeters, long maxClockSkewSeconds, int pullPageSize,
                          long overdueGraceSeconds) {

    public FieldConfig {
        offerTimeoutSeconds = offerTimeoutSeconds <= 0 ? 60 : offerTimeoutSeconds;
        arrivalRadiusMeters = arrivalRadiusMeters <= 0 ? 150 : arrivalRadiusMeters;
        roadFactor = roadFactor < 1 ? 1.3 : roadFactor;
        defaultSpeedKmh = defaultSpeedKmh <= 0 ? 30 : defaultSpeedKmh;
        speedKmhByUnitKind = speedKmhByUnitKind == null ? Map.of() : Map.copyOf(speedKmhByUnitKind);
        slaSecondsByPriority = slaSecondsByPriority == null ? Map.of() : Map.copyOf(slaSecondsByPriority);
        maxCandidates = maxCandidates <= 0 ? 5 : maxCandidates;
        maxClockSkewSeconds = maxClockSkewSeconds <= 0 ? 300 : maxClockSkewSeconds;
        pullPageSize = pullPageSize <= 0 ? 200 : pullPageSize;
        overdueGraceSeconds = Math.max(0, overdueGraceSeconds);
    }

    public static FieldConfig defaults() {
        return new FieldConfig(0, 0, 0, 0, null, null, 0, 0, 0, 0, 0);
    }

    public double speedKmh(String unitKind) {
        Double v = unitKind == null ? null : speedKmhByUnitKind.get(unitKind);
        return v == null || v <= 0 ? defaultSpeedKmh : v;
    }

    public long slaSeconds(String priority) {
        Long v = priority == null ? null : slaSecondsByPriority.get(priority);
        return v == null ? 0 : v;
    }

    /** Builds a config from a flat map (Spring properties, pack JSON). */
    @SuppressWarnings("unchecked")
    public static FieldConfig from(Map<String, Object> m) {
        if (m == null) {
            return defaults();
        }
        return new FieldConfig(intOf(m.get("offerTimeoutSeconds")), dbl(m.get("arrivalRadiusMeters")), dbl(m.get("roadFactor")),
                dbl(m.get("defaultSpeedKmh")), (Map<String, Double>) FieldJson.MAPPER.convertValue(
                        m.getOrDefault("speedKmhByUnitKind", Map.of()), Map.class),
                toLongs((Map<String, Object>) m.getOrDefault("slaSecondsByPriority", Map.of())), intOf(m.get("maxCandidates")),
                dbl(m.get("maxCandidateMeters")), (long) dbl(m.get("maxClockSkewSeconds")), intOf(m.get("pullPageSize")),
                (long) dbl(m.get("overdueGraceSeconds")));
    }

    private static Map<String, Long> toLongs(Map<String, Object> in) {
        Map<String, Long> out = new java.util.HashMap<>();
        in.forEach((k, v) -> out.put(k, (long) dbl(v)));
        return out;
    }

    private static int intOf(Object o) {
        return (int) dbl(o);
    }

    private static double dbl(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o instanceof String s && !s.isBlank()) {
            return Double.parseDouble(s);
        }
        return 0;
    }
}

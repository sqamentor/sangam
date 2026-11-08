// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry.asset;

import org.sangam.registry.RegistryException;
import org.sangam.registry.support.Maps;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An asset class, as configuration (ADR-D-20):
 * <pre>{@code
 * { "classKey": "vehicle", "parentClass": "movable", "objectType": "asset-vehicle", "title": "Vehicle",
 *   "depreciation": { "method": "STRAIGHT_LINE", "usefulLifeMonths": 96, "residualPercent": 5 },
 *   "meters": [ { "meterType": "ODOMETER", "unit": "km", "cumulative": true, "rolloverAt": 1000000 } ],
 *   "maintenancePlans": [ { "planKey": "service", "trigger": "METER", "meterType": "ODOMETER",
 *                           "meterInterval": 10000, "workType": "PREVENTIVE", "tasks": ["oil", "filters"] } ],
 *   "workOrderSeries": "WO", "disposeTransition": "archive" }
 * }</pre>
 * Settings missing on a class are inherited from its parent class; meters and plans are merged by key (the child
 * wins).
 */
public record AssetClassDefinition(String classKey, String parentClass, String objectType, String title,
                                   DepreciationParams depreciation, List<MeterSpec> meters, List<PlanSpec> plans,
                                   String workOrderSeries, String disposeTransition, Map<String, Object> extensions) {

    static final Set<String> TRIGGERS = Set.of("TIME", "METER", "CONDITION");

    /** A meter every asset of the class gets. */
    public record MeterSpec(String meterType, String unit, boolean cumulative, BigDecimal rolloverAt) {
        static MeterSpec from(Map<String, Object> m) {
            return new MeterSpec(Maps.required(m, "meterType"), Maps.str(m, "unit"), Maps.bool(m, "cumulative", true),
                    Maps.decimal(m, "rolloverAt"));
        }

        Map<String, Object> toMap() {
            return Maps.of("meterType", meterType, "unit", unit, "cumulative", cumulative, "rolloverAt", rolloverAt);
        }
    }

    /** A maintenance plan template; instantiated per asset. */
    public record PlanSpec(String planKey, String trigger, Integer intervalDays, String meterType,
                           BigDecimal meterInterval, int leadDays, String workType, Object tasks,
                           String workOrderSeries) {
        public static PlanSpec from(Map<String, Object> m) {
            String trigger = Maps.str(m, "trigger", "TIME").toUpperCase();
            if (!TRIGGERS.contains(trigger)) {
                throw RegistryException.invalid("plan trigger must be one of " + TRIGGERS);
            }
            Integer days = Maps.integer(m, "intervalDays");
            BigDecimal mi = Maps.decimal(m, "meterInterval");
            String meterType = Maps.str(m, "meterType");
            if ("TIME".equals(trigger) && (days == null || days <= 0)) {
                throw RegistryException.invalid("TIME plan needs a positive intervalDays");
            }
            if ("METER".equals(trigger) && (meterType == null || mi == null || mi.signum() <= 0)) {
                throw RegistryException.invalid("METER plan needs meterType and a positive meterInterval");
            }
            return new PlanSpec(Maps.required(m, "planKey"), trigger, days, meterType, mi,
                    Maps.integer(m, "leadDays", 0), Maps.str(m, "workType", "PREVENTIVE").toUpperCase(), m.get("tasks"),
                    Maps.str(m, "workOrderSeries"));
        }

        public Map<String, Object> toMap() {
            return Maps.of("planKey", planKey, "trigger", trigger, "intervalDays", intervalDays, "meterType", meterType,
                    "meterInterval", meterInterval, "leadDays", leadDays, "workType", workType, "tasks", tasks,
                    "workOrderSeries", workOrderSeries);
        }
    }

    public static AssetClassDefinition from(Map<String, Object> m) {
        String key = Maps.required(m, "classKey");
        if (key.length() > 64) {
            throw RegistryException.invalid("classKey longer than 64 characters");
        }
        String parent = Maps.str(m, "parentClass");
        if (key.equals(parent)) {
            throw RegistryException.invalid("an asset class cannot be its own parent");
        }
        List<MeterSpec> meters = new ArrayList<>();
        for (Map<String, Object> x : Maps.maps(m, "meters")) {
            meters.add(MeterSpec.from(x));
        }
        List<PlanSpec> plans = new ArrayList<>();
        for (Map<String, Object> x : Maps.maps(m, "maintenancePlans")) {
            plans.add(PlanSpec.from(x));
        }
        return new AssetClassDefinition(key, parent, Maps.str(m, "objectType"), Maps.str(m, "title", key),
                DepreciationParams.from(Maps.map(m, "depreciation")), List.copyOf(meters), List.copyOf(plans),
                Maps.str(m, "workOrderSeries"), Maps.str(m, "disposeTransition"), Maps.map(m, "extensions"));
    }

    /** The effective definition with the parent's settings inherited. */
    public AssetClassDefinition inherit(AssetClassDefinition parent) {
        if (parent == null) {
            return this;
        }
        Map<String, MeterSpec> ms = new LinkedHashMap<>();
        parent.meters.forEach(x -> ms.put(x.meterType(), x));
        meters.forEach(x -> ms.put(x.meterType(), x));
        Map<String, PlanSpec> ps = new LinkedHashMap<>();
        parent.plans.forEach(x -> ps.put(x.planKey(), x));
        plans.forEach(x -> ps.put(x.planKey(), x));
        Map<String, Object> ext = new LinkedHashMap<>(parent.extensions == null ? Map.of() : parent.extensions);
        if (extensions != null) {
            ext.putAll(extensions);
        }
        return new AssetClassDefinition(classKey, parentClass, objectType != null ? objectType : parent.objectType, title,
                depreciation.inherit(parent.depreciation), List.copyOf(ms.values()), List.copyOf(ps.values()),
                workOrderSeries != null ? workOrderSeries : parent.workOrderSeries,
                disposeTransition != null ? disposeTransition : parent.disposeTransition, ext);
    }

    public Map<String, Object> toMap() {
        return Maps.of("classKey", classKey, "parentClass", parentClass, "objectType", objectType, "title", title,
                "depreciation", depreciation.toMap(), "meters", meters.stream().map(MeterSpec::toMap).toList(),
                "maintenancePlans", plans.stream().map(PlanSpec::toMap).toList(), "workOrderSeries", workOrderSeries,
                "disposeTransition", disposeTransition,
                "extensions", extensions == null || extensions.isEmpty() ? null : extensions);
    }
}

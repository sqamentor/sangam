// SPDX-License-Identifier: Apache-2.0
package org.sangam.programme.config;

import org.sangam.programme.ProgrammeException;
import org.sangam.programme.support.Db;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An indicator definition (N15 {@code Indicator}: formula, unit and roll-up rule). Definitions come only from
 * configuration (data); the code knows no domain indicator.
 *
 * @param key                stable key, used in formulas
 * @param unit               unit code from the configuration's unit list (count, km, percent, currency ...)
 * @param frequency          reporting frequency
 * @param kind               PHYSICAL, FINANCIAL, OUTPUT or OUTCOME (physical vs financial progress)
 * @param aggregation        roll-up rule along the hierarchy
 * @param formula            for {@link Aggregation#FORMULA}: arithmetic over other indicator keys
 * @param weightIndicator    for {@link Aggregation#WEIGHTED_AVG}: indicator whose value is the weight
 * @param direction          HIGHER_BETTER or LOWER_BETTER (variance sign)
 * @param cumulative         true when the reported value is cumulative-to-date (non-decreasing check)
 * @param validation         rule map: required, min, max, integer, maxOfTarget (ratio), nonDecreasing
 * @param parentIndicatorKey indicator hierarchy (a sub-indicator contributes to its parent)
 * @param weight             weight in the parent indicator / programme physical progress
 */
public record IndicatorDefinition(String key, int version, String name, String unit, Frequency frequency, String kind,
                                  Aggregation aggregation, String formula, String weightIndicator, String direction,
                                  boolean cumulative, int decimals, Map<String, Object> validation,
                                  String parentIndicatorKey, BigDecimal weight, boolean active) {

    public enum Frequency {
        WEEKLY, FORTNIGHTLY, MONTHLY, QUARTERLY, HALF_YEARLY, ANNUAL;

        /** Number of periods in a fiscal year (WEEKLY: 52; FORTNIGHTLY: 26). */
        public int perYear() {
            return switch (this) {
                case WEEKLY -> 52;
                case FORTNIGHTLY -> 26;
                case MONTHLY -> 12;
                case QUARTERLY -> 4;
                case HALF_YEARLY -> 2;
                case ANNUAL -> 1;
            };
        }

        public int months() {
            return switch (this) {
                case MONTHLY -> 1;
                case QUARTERLY -> 3;
                case HALF_YEARLY -> 6;
                case ANNUAL -> 12;
                default -> 0;
            };
        }
    }

    public enum Aggregation { SUM, AVG, MIN, MAX, LAST, WEIGHTED_AVG, FORMULA }

    public static final String PHYSICAL = "PHYSICAL";
    public static final String FINANCIAL = "FINANCIAL";

    public IndicatorDefinition {
        Db.requireText(key, "indicator key");
        if (!key.matches("[A-Za-z_][A-Za-z0-9_.]*")) {
            throw new ProgrammeException(ProgrammeException.Code.INVALID_CONFIG, "indicator key must be an identifier: " + key);
        }
        Db.requireText(name, "indicator name");
        Db.requireText(unit, "indicator unit");
        kind = kind == null ? "OUTPUT" : kind;
        direction = direction == null ? "HIGHER_BETTER" : direction;
        validation = validation == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(validation));
        if (aggregation == Aggregation.FORMULA && (formula == null || formula.isBlank())) {
            throw new ProgrammeException(ProgrammeException.Code.INVALID_CONFIG, "FORMULA indicator without formula: " + key);
        }
        if (aggregation == Aggregation.WEIGHTED_AVG && (weightIndicator == null || weightIndicator.isBlank())) {
            throw new ProgrammeException(ProgrammeException.Code.INVALID_CONFIG, "WEIGHTED_AVG indicator without weightIndicator: " + key);
        }
        if (!"HIGHER_BETTER".equals(direction) && !"LOWER_BETTER".equals(direction)) {
            throw new ProgrammeException(ProgrammeException.Code.INVALID_CONFIG, "direction must be HIGHER_BETTER or LOWER_BETTER: " + key);
        }
    }

    public boolean lowerIsBetter() {
        return "LOWER_BETTER".equals(direction);
    }

    /** Parses one configuration entry. */
    public static IndicatorDefinition fromMap(Map<String, Object> m) {
        try {
            return new IndicatorDefinition(Db.str(m.get("key")), Db.intVal(m, "version", 1), Db.str(m.get("name")),
                    Db.str(m.get("unit")), Frequency.valueOf(Db.str(m, "frequency", "MONTHLY")), Db.str(m, "kind", "OUTPUT"),
                    Aggregation.valueOf(Db.str(m, "aggregation", "SUM")), Db.str(m.get("formula")), Db.str(m.get("weightIndicator")),
                    Db.str(m, "direction", "HIGHER_BETTER"), Db.boolVal(m, "cumulative", false), Db.intVal(m, "decimals", 2),
                    Db.obj(m.get("validation")), Db.str(m.get("parent")), Db.dec(m.get("weight")), Db.boolVal(m, "active", true));
        } catch (IllegalArgumentException e) {
            throw new ProgrammeException(ProgrammeException.Code.INVALID_CONFIG, "invalid indicator " + m.get("key") + ": " + e.getMessage());
        }
    }

    public Map<String, Object> toMap() {
        return Db.kv("key", key, "version", version, "name", name, "unit", unit, "frequency", frequency.name(), "kind", kind,
                "aggregation", aggregation.name(), "formula", formula, "weightIndicator", weightIndicator, "direction", direction,
                "cumulative", cumulative, "decimals", decimals, "validation", validation, "parent", parentIndicatorKey,
                "weight", weight == null ? null : weight.toPlainString(), "active", active);
    }
}

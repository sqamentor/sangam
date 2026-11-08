// SPDX-License-Identifier: Apache-2.0
package org.sangam.programme.config;

import org.sangam.programme.ProgrammeException;
import org.sangam.programme.support.Db;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * N15 {@code ReturnDefinition}: a periodic return / progress report, i.e. a C03 form version plus validation (built-in
 * rules and an optional DMN), the indicators it collects, the due offset, reminders and the number of approval levels.
 *
 * @param items           indicators collected; {@code required} per item
 * @param rules           cross-indicator rules: {@code {code, expr, severity, message}}; {@code expr} is a comparison
 *                        such as {@code "a <= b + c"} evaluated by the {@code FormulaEvaluator}
 * @param dueOffsetDays   due date = period end + offset
 * @param reminderDays    days relative to the due date when reminders go out (negative = before)
 * @param approvalLevels  accept levels after validation (1 = one checker)
 * @param reportingLevel  hierarchy level of the offices that report (data, e.g. a level type of the org model)
 * @param processKey      optional BPMN process started per return (GP-09 one instance per office)
 */
public record ReturnDefinition(String key, int version, String name, IndicatorDefinition.Frequency frequency,
                               String programmeId, List<Item> items, List<Map<String, Object>> rules, String formKey,
                               Integer formVersion, String validationDmnKey, int dueOffsetDays, List<Integer> reminderDays,
                               int approvalLevels, String reportingLevel, String processKey, boolean active) {

    public record Item(String indicatorKey, boolean required, String label) {
    }

    public ReturnDefinition {
        Db.requireText(key, "return key");
        Db.requireText(name, "return name");
        items = List.copyOf(items == null ? List.of() : items);
        rules = List.copyOf(rules == null ? List.of() : rules);
        reminderDays = List.copyOf(reminderDays == null ? List.of() : reminderDays);
        if (items.isEmpty()) {
            throw new ProgrammeException(ProgrammeException.Code.INVALID_CONFIG, "return " + key + " collects no indicator");
        }
        if (approvalLevels < 1 || approvalLevels > 9) {
            throw new ProgrammeException(ProgrammeException.Code.INVALID_CONFIG, "approvalLevels must be 1..9: " + key);
        }
    }

    public static ReturnDefinition fromMap(Map<String, Object> m) {
        List<Item> items = new ArrayList<>();
        for (Object o : Db.arr(m.get("indicators"))) {
            if (o instanceof Map<?, ?>) {
                Map<String, Object> im = Db.obj(o);
                items.add(new Item(Db.str(im.get("key")), Db.boolVal(im, "required", true), Db.str(im.get("label"))));
            } else {
                items.add(new Item(String.valueOf(o), true, null));
            }
        }
        List<Map<String, Object>> rules = new ArrayList<>();
        Db.arr(m.get("rules")).forEach(r -> rules.add(Db.obj(r)));
        List<Integer> reminders = new ArrayList<>();
        Db.arr(m.get("reminderDays")).forEach(r -> reminders.add(Integer.parseInt(String.valueOf(r))));
        try {
            return new ReturnDefinition(Db.str(m.get("key")), Db.intVal(m, "version", 1), Db.str(m.get("name")),
                    IndicatorDefinition.Frequency.valueOf(Db.str(m, "frequency", "MONTHLY")), Db.str(m.get("programmeId")), items, rules,
                    Db.str(m.get("formKey")), m.get("formVersion") == null ? null : Db.intVal(m, "formVersion", 1),
                    Db.str(m.get("validationDmnKey")), Db.intVal(m, "dueOffsetDays", 10), reminders, Db.intVal(m, "approvalLevels", 1),
                    Db.str(m.get("reportingLevel")), Db.str(m.get("processKey")), Db.boolVal(m, "active", true));
        } catch (IllegalArgumentException e) {
            throw new ProgrammeException(ProgrammeException.Code.INVALID_CONFIG, "invalid return definition " + m.get("key"));
        }
    }

    public Map<String, Object> toMap() {
        List<Map<String, Object>> its = new ArrayList<>();
        items.forEach(i -> its.add(Db.kv("key", i.indicatorKey(), "required", i.required(), "label", i.label())));
        return Db.kv("key", key, "version", version, "name", name, "frequency", frequency.name(), "programmeId", programmeId,
                "indicators", its, "rules", rules, "formKey", formKey, "formVersion", formVersion, "validationDmnKey", validationDmnKey,
                "dueOffsetDays", dueOffsetDays, "reminderDays", reminderDays, "approvalLevels", approvalLevels,
                "reportingLevel", reportingLevel, "processKey", processKey, "active", active);
    }
}

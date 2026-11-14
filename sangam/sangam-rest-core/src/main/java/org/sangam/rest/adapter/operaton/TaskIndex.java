// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.delegate.DelegateTask;
import org.operaton.bpm.engine.runtime.VariableInstance;
import org.operaton.bpm.engine.task.TaskQuery;
import org.operaton.bpm.engine.variable.Variables;
import org.operaton.bpm.engine.variable.type.ValueType;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.TaskPort;

/**
 * M-042 (decision 82, Operaton parity P41): the typed task index behind {@code GET /tasks?sortBy=amount|reference}.
 *
 * <p><b>Values.</b> When a user task is created, a per-process rule copies the task's amount, currency and reference
 * into three platform task-local variables: {@value #AMOUNT} (long, minor units of the currency, SD-398),
 * {@value #CURRENCY} (ISO 4217 code) and {@value #REFERENCE} (string). The rule lives where per-process task
 * configuration already lives: a {@code sangam.ops.task-listeners-json} rule whose {@code handler} is
 * {@value #HANDLER}, with a {@code taskIndex} object naming the source variables:
 * <pre>{"id":"bill-index","events":["create"],"processKey":"bill-.*","handler":"sangamTaskIndex",
 *  "taskIndex":{"amount":"billAmountMinor","currency":"billCurrency","reference":"billNo"}}</pre>
 * {@code currencyCode} names a fixed currency instead of {@code currency}. The values are a snapshot at creation; the
 * "sangam" prefix keeps them out of the payload hash and out of caller writes (RESERVED_VARIABLE).
 *
 * <p><b>Sort.</b> Operaton orders by a typed task variable ({@code orderByTaskVariable}), inside the same task query
 * as the visibility predicate, so no second query and no schema change. Amounts in different currencies do not
 * compare: {@code sortBy=amount} needs the single-currency filter {@code currency}, which also leaves out every task
 * without an indexed amount.
 */
public final class TaskIndex {

    public static final String AMOUNT = "sangamTaskAmount";
    public static final String CURRENCY = "sangamTaskCurrency";
    public static final String REFERENCE = "sangamTaskReference";
    /** The handler bean name a {@code sangam.ops.task-listeners-json} rule names. */
    public static final String HANDLER = "sangamTaskIndex";
    /** Longest reference kept (a reference, not a document). */
    static final int MAX_REFERENCE = 255;

    private static final Pattern CURRENCY_CODE = Pattern.compile("[A-Z]{3}");
    private static final Pattern VARIABLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]{0,254}");
    private static final Pattern INTEGRAL = Pattern.compile("-?[0-9]{1,18}");

    private TaskIndex() {
    }

    /** The indexed values of one task; any may be null. */
    public record Values(Long amount, String currency, String reference) {
        static final Values NONE = new Values(null, null, null);

        TaskPort.Task applyTo(TaskPort.Task t) {
            return amount == null && currency == null && reference == null ? t : t.withIndex(amount, currency, reference);
        }
    }

    /** Where one rule reads the values: source variable names, or a fixed currency. */
    public record Mapping(String amount, String currency, String currencyCode, String reference) {

        public Mapping {
            for (String v : new String[]{amount, currency, reference}) {
                if (v != null && !VARIABLE.matcher(v).matches()) {
                    throw new IllegalArgumentException("taskIndex variable name is not valid: " + v);
                }
            }
            if (currencyCode != null && !CURRENCY_CODE.matcher(currencyCode).matches()) {
                throw new IllegalArgumentException("taskIndex.currencyCode must be an ISO 4217 code: " + currencyCode);
            }
            if (currency != null && currencyCode != null) {
                throw new IllegalArgumentException("taskIndex names either currency or currencyCode, not both");
            }
            if (amount != null && currency == null && currencyCode == null) {
                throw new IllegalArgumentException("taskIndex.amount needs currency or currencyCode: an amount without"
                        + " its currency cannot be sorted");
            }
            if (amount == null && reference == null) {
                throw new IllegalArgumentException("taskIndex names neither amount nor reference");
            }
        }

        /** The {@code taskIndex} object of a rule. */
        public static Mapping of(Object taskIndex) {
            if (!(taskIndex instanceof Map<?, ?> m)) {
                throw new IllegalArgumentException("a " + HANDLER + " rule needs a taskIndex object");
            }
            for (Object k : m.keySet()) {
                if (!List.of("amount", "currency", "currencyCode", "reference").contains(String.valueOf(k))) {
                    throw new IllegalArgumentException("unknown taskIndex member " + k);
                }
            }
            return new Mapping(text(m.get("amount")), text(m.get("currency")), text(m.get("currencyCode")),
                    text(m.get("reference")));
        }

        /** The values of a task whose variables {@code lookup} reads (task scope first, then its parents). */
        public Values extract(Function<String, Object> lookup) {
            Long a = amount == null ? null : amountOf(amount, lookup.apply(amount));
            String c = currencyCode != null ? currencyCode : currency == null ? null : currencyOf(lookup.apply(currency));
            if (a != null && c == null) {
                throw new IllegalStateException("task index: amount " + amount + " is set but its currency is not");
            }
            String r = reference == null ? null : referenceOf(lookup.apply(reference));
            return new Values(a, a == null ? null : c, r);
        }
    }

    /** Integral minor units only: a decimal or fractional value has no defined minor-unit scale here. */
    static Long amountOf(String name, Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Long || v instanceof Integer || v instanceof Short) {
            return ((Number) v).longValue();
        }
        if (v instanceof BigInteger b && b.bitLength() < 64) {
            return b.longValue();
        }
        if (v instanceof BigDecimal d) {
            try {
                return d.longValueExact();
            } catch (ArithmeticException e) {
                // falls through: not integral or out of range
            }
        }
        if (v instanceof String s && INTEGRAL.matcher(s.trim()).matches()) {
            return Long.parseLong(s.trim());
        }
        throw new IllegalStateException("task index: " + name + " must hold integral minor units, not "
                + v.getClass().getSimpleName());
    }

    static String currencyOf(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim().toUpperCase(java.util.Locale.ROOT);
        if (!CURRENCY_CODE.matcher(s).matches()) {
            throw new IllegalStateException("task index: currency is not an ISO 4217 code: " + v);
        }
        return s;
    }

    static String referenceOf(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        if (s.length() > MAX_REFERENCE) {
            throw new IllegalStateException("task index: reference longer than " + MAX_REFERENCE + " characters");
        }
        return s.isEmpty() ? null : s;
    }

    /** Writes the values as typed task-local variables (none when the rule found nothing). */
    public static void write(DelegateTask task, Values v) {
        if (v.amount() != null) {
            task.setVariableLocal(AMOUNT, Variables.longValue(v.amount()));
            task.setVariableLocal(CURRENCY, Variables.stringValue(v.currency()));
        }
        if (v.reference() != null) {
            task.setVariableLocal(REFERENCE, Variables.stringValue(v.reference()));
        }
    }

    /** The task-create recorder of the {@value #HANDLER} rules: rule id to mapping. */
    public static final class Recorder {
        private final Map<String, Mapping> rules;

        public Recorder(Map<String, Mapping> rules) {
            this.rules = Map.copyOf(rules);
        }

        public Map<String, Mapping> rules() {
            return rules;
        }

        public void record(DelegateTask task, String ruleId) {
            Mapping m = rules.get(ruleId);
            if (m == null) {
                throw new IllegalStateException("rule " + ruleId + " names " + HANDLER + " but has no taskIndex");
            }
            write(task, m.extract(task::getVariable));
        }
    }

    // ------------------------------------------------------------------ query side

    /** The currency filter (and its rule: an amount sort needs one currency); returns {@code q}. */
    static TaskQuery filter(TaskQuery q, TaskPort.TaskFilter f) {
        String currency = f.currency();
        if (currency != null && !CURRENCY_CODE.matcher(currency).matches()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "currency must be an ISO 4217 code such as INR");
        }
        if (currency == null && sortsByAmount(f.sortBy())) {
            throw ApiProblem.badRequest("VALIDATION_FAILED",
                    "sortBy=amount needs the currency filter: amounts in different currencies do not compare");
        }
        if (currency != null) {
            q.taskVariableValueEquals(CURRENCY, currency);
        }
        return q;
    }

    static boolean sortsByAmount(String sortBy) {
        if (sortBy == null) {
            return false;
        }
        for (String k : sortBy.split(",")) {
            if ("amount".equals(k.trim())) {
                return true;
            }
        }
        return false;
    }

    /** Orders by the typed index variable of {@code key} (amount or reference). */
    static void orderBy(TaskQuery q, String key) {
        if ("amount".equals(key)) {
            q.orderByTaskVariable(AMOUNT, ValueType.LONG);
        } else {
            q.orderByTaskVariable(REFERENCE, ValueType.STRING);
        }
    }

    /** {@code row} with its index values from {@link #read} (unchanged when it has none). */
    static TaskPort.Task apply(Map<String, Values> index, TaskPort.Task row) {
        return index.getOrDefault(row.id(), Values.NONE).applyTo(row);
    }

    /** The index values of a page of tasks, in one variable query. */
    static Map<String, Values> read(RuntimeService runtime, Collection<org.operaton.bpm.engine.task.Task> page) {
        if (page.isEmpty()) {
            return Map.of();
        }
        String[] ids = page.stream().map(org.operaton.bpm.engine.task.Task::getId).toArray(String[]::new);
        Map<String, Map<String, Object>> byTask = new HashMap<>();
        for (VariableInstance v : OperatonSupport.call(() -> runtime.createVariableInstanceQuery().taskIdIn(ids)
                .variableNameIn(AMOUNT, CURRENCY, REFERENCE).list())) {
            byTask.computeIfAbsent(v.getTaskId(), k -> new LinkedHashMap<>()).put(v.getName(), v.getValue());
        }
        Map<String, Values> out = new HashMap<>();
        byTask.forEach((id, m) -> out.put(id, new Values(m.get(AMOUNT) instanceof Number n ? n.longValue() : null,
                m.get(CURRENCY) instanceof String s ? s : null, m.get(REFERENCE) instanceof String s ? s : null)));
        return out;
    }

    private static String text(Object o) {
        return o == null || String.valueOf(o).isBlank() ? null : String.valueOf(o).trim();
    }
}

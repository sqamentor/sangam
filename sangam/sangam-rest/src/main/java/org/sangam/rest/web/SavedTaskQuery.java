// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.TaskPort;

/**
 * Owner decision 406: the query of a saved task list (TEAM_TASK_FILTER) and how it is run. A saved query uses the
 * criteria of {@code GET /api/v1/tasks} by the same names, so running a list is that endpoint with the list's criteria
 * added: the caller's tenant and task visibility (OperatonTaskAdapter) still apply, and a list can only narrow what the
 * caller already sees.
 * <p>
 * Every key must be one the task list applies: an unknown key is refused (422 TASK_FILTER_INVALID) when a list is
 * saved, so a stored list never silently drops a condition. When the list is run, a request criterion that the list
 * also sets to another value is refused (422 TASK_FILTER_CONFLICT) instead of one of them being ignored; sortBy,
 * sortOrder and variableNames are display choices, so the request's values win and variable names are merged.
 */
final class SavedTaskQuery {

    static final Set<String> STRING_KEYS = Set.of("assignee", "candidateGroup", "processDefinitionKey",
            "taskDefinitionKey", "businessKey", "state", "sortBy", "sortOrder", "currency");
    static final Set<String> BOOLEAN_KEYS = Set.of("mine", "unassigned", "overdue");
    /** Lane R9-filters: whole numbers (JSON integers in the int range). */
    static final Set<String> INT_KEYS = Set.of("priority", "minPriority", "maxPriority");
    /** Lane R9-filters: ISO-8601 date-times with offset, as GET /tasks takes them. */
    static final Set<String> DATE_KEYS = Set.of("dueBefore", "dueAfter");
    static final String VARIABLE_NAMES = "variableNames";
    static final Set<String> KEYS;
    private static final int MAX_VALUE = 255;
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    static {
        Set<String> all = new java.util.TreeSet<>(STRING_KEYS);
        all.addAll(BOOLEAN_KEYS);
        all.addAll(INT_KEYS);
        all.addAll(DATE_KEYS);
        all.add(VARIABLE_NAMES);
        KEYS = java.util.Collections.unmodifiableSet(all);
    }

    private SavedTaskQuery() {
    }

    /** Validates a query to be saved; throws 422 TASK_FILTER_INVALID naming the first offending key. */
    static void check(Map<String, Object> query) {
        for (Map.Entry<String, Object> e : query.entrySet()) {
            String key = e.getKey() == null ? "" : e.getKey();
            String k = key.toLowerCase(Locale.ROOT);
            if (k.startsWith("tenant") || k.startsWith("withouttenant")) {
                throw invalid("query criterion '" + key
                        + "' is not allowed: a task list is always limited to the caller's organisation");
            }
            if (!KEYS.contains(key)) {
                throw invalid("query criterion '" + key + "' is not supported; supported criteria: " + KEYS);
            }
            Object v = e.getValue();
            if (BOOLEAN_KEYS.contains(key)) {
                if (!(v instanceof Boolean)) {
                    throw invalid("query criterion '" + key + "' must be true or false");
                }
            } else if (INT_KEYS.contains(key)) {
                if (integer(v) == null) {
                    throw invalid("query criterion '" + key + "' must be a whole number");
                }
            } else if (DATE_KEYS.contains(key)) {
                if (!(v instanceof String s) || date(s) == null) {
                    throw invalid("query criterion '" + key
                            + "' must be an ISO-8601 date-time with offset, such as 2026-11-17T10:00:00Z");
                }
            } else if (VARIABLE_NAMES.equals(key)) {
                if (!(v instanceof List<?> l) || l.isEmpty() || l.size() > TaskPort.MAX_CARD_VARIABLES
                        || l.stream().anyMatch(x -> !(x instanceof String s) || s.isBlank() || s.length() > MAX_VALUE)) {
                    throw invalid("query criterion 'variableNames' must be a list of 1 to "
                            + TaskPort.MAX_CARD_VARIABLES + " non-blank names");
                }
            } else {
                if (!(v instanceof String s) || s.isBlank() || s.length() > MAX_VALUE) {
                    throw invalid("query criterion '" + key + "' must be a non-blank string of at most " + MAX_VALUE
                            + " characters");
                }
                if ("state".equals(key) && !s.equalsIgnoreCase("ACTIVE") && !s.equalsIgnoreCase("SUSPENDED")) {
                    throw invalid("query criterion 'state' must be ACTIVE or SUSPENDED");
                }
                if ("currency".equals(key) && !CURRENCY.matcher(s).matches()) {
                    throw invalid("query criterion 'currency' must be an ISO 4217 code (three capital letters)");
                }
            }
        }
        Integer min = integer(query.get("minPriority"));
        Integer max = integer(query.get("maxPriority"));
        if (min != null && max != null && min > max) {
            throw invalid("query criterion 'minPriority' must not be greater than 'maxPriority'");
        }
        java.time.Instant after = query.get("dueAfter") instanceof String s ? date(s) : null;
        java.time.Instant before = query.get("dueBefore") instanceof String s ? date(s) : null;
        if (after != null && before != null && !after.isBefore(before)) {
            throw invalid("query criterion 'dueAfter' must be before 'dueBefore'");
        }
    }

    /** The int value of a JSON whole number (Integer, Long or Short in the int range), else null. */
    private static Integer integer(Object v) {
        if (v instanceof Integer i) {
            return i;
        }
        if ((v instanceof Long || v instanceof Short) && ((Number) v).longValue() == ((Number) v).intValue()) {
            return ((Number) v).intValue();
        }
        return null;
    }

    private static java.time.Instant date(String s) {
        try {
            return java.time.OffsetDateTime.parse(s).toInstant();
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    /**
     * The task list criteria of a run: the saved query combined with the request's own criteria. The saved query is
     * checked again (a list must never run with a condition left out).
     */
    static TaskPort.TaskFilter combine(Map<String, Object> saved, TaskPort.TaskFilter req) {
        check(saved);
        Map<String, Object> q = new LinkedHashMap<>(saved);
        String assignee = str(q, "assignee", req.assignee());
        Boolean mine = bool(q, "mine", req.mine());
        Boolean unassigned = bool(q, "unassigned", req.unassigned());
        String candidateGroup = str(q, "candidateGroup", req.candidateGroup());
        String businessKey = str(q, "businessKey", req.businessKey());
        String processDefinitionKey = str(q, "processDefinitionKey", req.processDefinitionKey());
        String taskDefinitionKey = str(q, "taskDefinitionKey", req.taskDefinitionKey());
        String state = str(q, "state", req.state());
        String currency = str(q, "currency", req.currency());
        String sortBy = req.sortBy() != null ? req.sortBy() : (String) q.get("sortBy");
        String sortOrder = req.sortOrder() != null ? req.sortOrder() : (String) q.get("sortOrder");
        Set<String> names = new LinkedHashSet<>();
        if (q.get(VARIABLE_NAMES) instanceof List<?> l) {
            l.forEach(x -> names.add((String) x));
        }
        names.addAll(req.variableNames());
        Boolean overdue = bool(q, "overdue", req.overdue());
        return new TaskPort.TaskFilter(assignee, mine, unassigned, candidateGroup, req.seatId(),
                req.processInstanceId(), req.caseInstanceId(), businessKey, processDefinitionKey, taskDefinitionKey,
                state, sortBy, sortOrder, new ArrayList<>(names), currency, num(q, "priority", req.priority()),
                num(q, "minPriority", req.minPriority()), num(q, "maxPriority", req.maxPriority()),
                when(q, "dueBefore", req.dueBefore()), when(q, "dueAfter", req.dueAfter()), overdue);
    }

    private static Integer num(Map<String, Object> saved, String key, Integer requested) {
        Integer s = integer(saved.get(key));
        if (s == null) {
            return requested;
        }
        if (requested != null && !requested.equals(s)) {
            throw conflict(key);
        }
        return s;
    }

    private static java.time.Instant when(Map<String, Object> saved, String key, java.time.Instant requested) {
        java.time.Instant s = saved.get(key) instanceof String v ? date(v) : null;
        if (s == null) {
            return requested;
        }
        if (requested != null && !requested.equals(s)) {
            throw conflict(key);
        }
        return s;
    }

    private static String str(Map<String, Object> saved, String key, String requested) {
        Object s = saved.get(key);
        if (s == null) {
            return requested;
        }
        if (requested != null && !"state".equals(key) && !Objects.equals(requested, s)
                || requested != null && "state".equals(key) && !requested.equalsIgnoreCase((String) s)) {
            throw conflict(key);
        }
        return (String) s;
    }

    private static Boolean bool(Map<String, Object> saved, String key, Boolean requested) {
        Object s = saved.get(key);
        if (s == null) {
            return requested;
        }
        if (requested != null && !requested.equals(s)) {
            throw conflict(key);
        }
        return (Boolean) s;
    }

    private static ApiProblem conflict(String key) {
        return ApiProblem.unprocessable("TASK_FILTER_CONFLICT", "criterion '" + key
                + "' is set by the task list to another value; remove it from the request or use another list")
                .with("criterion", key);
    }

    private static ApiProblem invalid(String detail) {
        return ApiProblem.unprocessable("TASK_FILTER_INVALID", detail);
    }
}

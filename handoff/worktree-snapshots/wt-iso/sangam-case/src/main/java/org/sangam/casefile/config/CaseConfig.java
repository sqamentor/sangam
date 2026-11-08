// SPDX-License-Identifier: Apache-2.0
package org.sangam.casefile.config;

import org.sangam.casefile.CaseFileException;
import org.sangam.casefile.support.Db;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Case types, reference kinds and committee kinds: every piece of domain knowledge of this module is configuration
 * data (SANGAM.md X.0e), parsed and validated here. Packs ship them as JSON; tenants override them through
 * {@link CaseConfigRegistry} (versioned, hash-pinned, R43).
 */
public final class CaseConfig {

    private CaseConfig() {
    }

    public static final String CASE_TYPE = "CASE_TYPE";
    public static final String REFERENCE_KIND = "REFERENCE_KIND";
    public static final String COMMITTEE_KIND = "COMMITTEE_KIND";

    /** Number series: {@code pattern} tokens {prefix}, {yyyy}, {yy}, {mm}, {seq:N}, {org}; reset NEVER/YEARLY/MONTHLY. */
    public record Numbering(String series, String pattern, String prefix, String reset) {
        static Numbering parse(Object o, String fallbackSeries, String fallbackPrefix) {
            Map<String, Object> m = Db.obj(o);
            String reset = Db.str(m, "reset", "YEARLY").toUpperCase();
            if (!Set.of("NEVER", "YEARLY", "MONTHLY").contains(reset)) {
                throw config("numbering.reset must be NEVER, YEARLY or MONTHLY");
            }
            String pattern = Db.str(m, "pattern", "{prefix}/{yyyy}/{seq:6}");
            if (!pattern.contains("{seq")) {
                throw config("numbering.pattern needs a {seq} token");
            }
            return new Numbering(Db.str(m, "series", fallbackSeries), pattern, Db.str(m, "prefix", fallbackPrefix), reset);
        }
    }

    /** A custom field of a case type or a reference kind (validated on every write). */
    public record Field(String name, String type, boolean required, List<String> values, int maxLength, boolean sensitive) {
        static final Set<String> TYPES = Set.of("string", "text", "number", "integer", "boolean", "date", "datetime", "enum", "ref");

        static Field parse(Object o) {
            Map<String, Object> m = Db.obj(o);
            String name = Db.requireText(Db.str(m, "name", null), "field.name");
            String type = Db.str(m, "type", "string");
            if (!TYPES.contains(type)) {
                throw config("field " + name + ": unknown type " + type);
            }
            List<String> values = Db.strings(m.get("values"));
            if ("enum".equals(type) && values.isEmpty()) {
                throw config("field " + name + ": enum needs values");
            }
            return new Field(name, type, Db.boolVal(m, "required", false), values, Db.intVal(m, "maxLength", 4000),
                    Db.boolVal(m, "sensitive", false));
        }

        /** Checks one value; returns an error message or {@code null}. */
        public String check(Object v) {
            if (v == null || v.toString().isBlank()) {
                return required ? name + " is required" : null;
            }
            String s = v.toString();
            try {
                switch (type) {
                    case "number" -> new BigDecimal(s);
                    case "integer" -> Long.parseLong(s);
                    case "boolean" -> {
                        if (!(v instanceof Boolean) && !s.equals("true") && !s.equals("false")) {
                            return name + " must be true or false";
                        }
                    }
                    case "date" -> java.time.LocalDate.parse(s);
                    case "datetime" -> java.time.Instant.parse(s);
                    case "enum" -> {
                        if (!values.contains(s)) {
                            return name + " must be one of " + values;
                        }
                    }
                    default -> {
                        if (s.length() > maxLength) {
                            return name + " is longer than " + maxLength;
                        }
                    }
                }
            } catch (RuntimeException e) {
                return name + " is not a valid " + type;
            }
            return null;
        }
    }

    /** A party role (complainant, respondent, witness ... are data). {@code max <= 0} means unbounded. */
    public record Role(String key, String name, int min, int max, boolean sensitive) {
        static Role parse(Object o) {
            Map<String, Object> m = Db.obj(o);
            String key = Db.requireText(Db.str(m, "key", null), "role.key");
            return new Role(key, Db.str(m, "name", key), Db.intVal(m, "min", 0), Db.intVal(m, "max", 0),
                    Db.boolVal(m, "sensitive", false));
        }
    }

    /** A stage and the stages it may move to; a terminal stage closes the case. */
    public record Stage(String key, String name, List<String> next, boolean terminal, List<String> requiredRoles) {
        static Stage parse(Object o) {
            Map<String, Object> m = Db.obj(o);
            String key = Db.requireText(Db.str(m, "key", null), "stage.key");
            return new Stage(key, Db.str(m, "name", key), Db.strings(m.get("next")), Db.boolVal(m, "terminal", false),
                    Db.strings(m.get("requiredRoles")));
        }
    }

    /**
     * One escalation step. {@code after} is an ISO-8601 offset from the due time (negative = before due);
     * {@code target}: {@code OWNER}, {@code SUPERIOR} (the seat directory's escalation seat) or {@code SEAT:<id>}.
     */
    public record EscalationStep(String after, String target, String messageKind) {
        static EscalationStep parse(Object o) {
            Map<String, Object> m = Db.obj(o);
            String after = Db.str(m, "after", "PT0S");
            Db.periodAndDuration(after);
            String target = Db.str(m, "target", "SUPERIOR");
            if (!target.equals("OWNER") && !target.equals("SUPERIOR") && !target.startsWith("SEAT:")) {
                throw config("escalation target must be OWNER, SUPERIOR or SEAT:<id>");
            }
            return new EscalationStep(after, target, Db.str(m, "messageKind", "DEADLINE_ESCALATED"));
        }

        public Map<String, Object> toMap() {
            return Db.kv("after", after, "target", target, "messageKind", messageKind);
        }
    }

    /** A deadline the case type starts on open or on entering a stage. */
    public record DeadlineRule(String kind, String period, String calendar, String warnBefore, String onStage,
                               List<EscalationStep> ladder) {
        static DeadlineRule parse(Object o) {
            Map<String, Object> m = Db.obj(o);
            String kind = Db.requireText(Db.str(m, "kind", null), "deadline.kind");
            String period = Db.requireText(Db.str(m, "period", null), "deadline.period");
            Db.periodAndDuration(period);
            String warn = Db.str(m, "warnBefore", null);
            if (warn != null) {
                Db.periodAndDuration(warn);
            }
            return new DeadlineRule(kind, period, Db.str(m, "calendar", null), warn, Db.str(m, "onStage", null),
                    CaseConfig.ladder(m.get("ladder")));
        }
    }

    static List<EscalationStep> ladder(Object o) {
        List<EscalationStep> out = new ArrayList<>();
        for (Object s : Db.arr(o)) {
            out.add(EscalationStep.parse(s));
        }
        return out;
    }

    /** N04 case type: fields, roles, stages, milestones, deadlines, numbering, folders, CMMN binding. */
    public record CaseType(String key, int version, String name, Numbering numbering, List<Field> fields, List<Role> roles,
                           List<List<String>> conflictingRoles, List<Stage> stages, String initialStage,
                           List<String> milestones, List<DeadlineRule> deadlines, List<String> duplicateRoles,
                           List<String> folders, String cmmnCaseKey, String defaultConfidentiality,
                           Map<String, Object> raw) {

        public Role role(String roleKey) {
            return roles.stream().filter(r -> r.key().equals(roleKey)).findFirst().orElse(null);
        }

        public Stage stage(String stageKey) {
            return stages.stream().filter(s -> s.key().equals(stageKey)).findFirst().orElse(null);
        }

        public Field field(String name) {
            return fields.stream().filter(f -> f.name().equals(name)).findFirst().orElse(null);
        }

        /** True when the two roles may not be held by the same party (on one case, or across cases of this type). */
        public boolean conflicting(String a, String b) {
            for (List<String> pair : conflictingRoles) {
                if (pair.contains(a) && pair.contains(b) && !a.equals(b)) {
                    return true;
                }
            }
            return false;
        }
    }

    public static CaseType caseType(Map<String, Object> m, int version) {
        String key = Db.requireText(Db.str(m, "key", null), "key");
        List<Field> fields = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Object f : Db.arr(m.get("fields"))) {
            Field fd = Field.parse(f);
            if (!names.add(fd.name())) {
                throw config("duplicate field " + fd.name());
            }
            fields.add(fd);
        }
        List<Role> roles = new ArrayList<>();
        for (Object r : Db.arr(m.get("roles"))) {
            roles.add(Role.parse(r));
        }
        Set<String> roleKeys = new HashSet<>();
        roles.forEach(r -> roleKeys.add(r.key()));
        List<List<String>> conflicts = new ArrayList<>();
        for (Object p : Db.arr(m.get("conflictingRoles"))) {
            List<String> pair = Db.strings(p);
            for (String r : pair) {
                if (!roleKeys.contains(r)) {
                    throw config("conflictingRoles: unknown role " + r);
                }
            }
            conflicts.add(pair);
        }
        List<Stage> stages = new ArrayList<>();
        for (Object s : Db.arr(m.get("stages"))) {
            stages.add(Stage.parse(s));
        }
        Set<String> stageKeys = new HashSet<>();
        stages.forEach(s -> stageKeys.add(s.key()));
        for (Stage s : stages) {
            for (String n : s.next()) {
                if (!stageKeys.contains(n)) {
                    throw config("stage " + s.key() + ": unknown next stage " + n);
                }
            }
            for (String r : s.requiredRoles()) {
                if (!roleKeys.contains(r)) {
                    throw config("stage " + s.key() + ": unknown required role " + r);
                }
            }
        }
        String initial = Db.str(m, "initialStage", stages.isEmpty() ? null : stages.get(0).key());
        if (initial != null && !stageKeys.contains(initial)) {
            throw config("initialStage " + initial + " is not a stage");
        }
        List<DeadlineRule> deadlines = new ArrayList<>();
        for (Object d : Db.arr(m.get("deadlines"))) {
            DeadlineRule r = DeadlineRule.parse(d);
            if (r.onStage() != null && !stageKeys.contains(r.onStage())) {
                throw config("deadline " + r.kind() + ": unknown stage " + r.onStage());
            }
            deadlines.add(r);
        }
        List<String> dupRoles = Db.strings(Db.obj(m.get("duplicateCheck")).get("roles"));
        for (String r : dupRoles) {
            if (!roleKeys.contains(r)) {
                throw config("duplicateCheck: unknown role " + r);
            }
        }
        return new CaseType(key, version, Db.str(m, "name", key), Numbering.parse(m.get("numbering"), "case:" + key, key),
                fields, roles, conflicts, stages, initial, Db.strings(m.get("milestones")), deadlines, dupRoles,
                Db.strings(m.get("folders")), Db.str(m, "cmmnCaseKey", null), Db.str(m, "confidentiality", "NORMAL"),
                new LinkedHashMap<>(m));
    }

    /**
     * N16 reference kind (any "reference with a deadline": a legislative question, an assurance, a dignitary reference,
     * an outside forum's direction, an oversight body's letter ... are all data).
     */
    public record ReferenceKind(String key, int version, String name, Numbering numbering, String replyPeriod,
                                String calendar, String warnBefore, List<EscalationStep> ladder, List<Field> fields,
                                boolean replyApproval, String caseType, String processKey, String duplicateWindow,
                                List<String> sourceCategories, String followUpKind, boolean extensionAllowed,
                                int maxExtensions, Map<String, Object> raw) {

        public Field field(String name) {
            return fields.stream().filter(f -> f.name().equals(name)).findFirst().orElse(null);
        }
    }

    public static ReferenceKind referenceKind(Map<String, Object> m, int version) {
        String key = Db.requireText(Db.str(m, "key", null), "key");
        String period = Db.requireText(Db.str(m, "replyPeriod", null), "replyPeriod");
        Db.periodAndDuration(period);
        String warn = Db.str(m, "warnBefore", null);
        if (warn != null) {
            Db.periodAndDuration(warn);
        }
        String dupWindow = Db.str(m, "duplicateWindow", null);
        if (dupWindow != null) {
            Db.periodAndDuration(dupWindow);
        }
        List<Field> fields = new ArrayList<>();
        for (Object f : Db.arr(m.get("fields"))) {
            fields.add(Field.parse(f));
        }
        return new ReferenceKind(key, version, Db.str(m, "name", key), Numbering.parse(m.get("numbering"), "ref:" + key, key),
                period, Db.str(m, "calendar", null), warn, ladder(m.get("ladder")), fields,
                Db.boolVal(m, "replyApproval", false), Db.str(m, "caseType", null), Db.str(m, "processKey", null), dupWindow,
                Db.strings(m.get("sourceCategories")), Db.str(m, "followUpKind", null),
                Db.boolVal(m, "extensionAllowed", true), Db.intVal(m, "maxExtensions", 2), new LinkedHashMap<>(m));
    }

    /**
     * Committee kind: default quorum rule ({@code ALL}, {@code MAJORITY}, a number, or a percentage like {@code 50%}),
     * whether the chair must be present, notice period, member roles, minutes approval and the action-item process.
     */
    public record CommitteeKind(String key, int version, String name, String quorumRule, boolean chairRequired,
                                int minNoticeHours, List<String> memberRoles, boolean minutesApprovalByChair,
                                String actionPeriod, String actionCalendar, List<EscalationStep> actionLadder,
                                String actionProcessKey, Numbering meetingNumbering, Map<String, Object> raw) {
    }

    public static CommitteeKind committeeKind(Map<String, Object> m, int version) {
        String key = Db.requireText(Db.str(m, "key", null), "key");
        String quorum = Db.str(m, "quorumRule", "MAJORITY");
        quorumRequired(quorum, 1);
        String actionPeriod = Db.str(m, "actionPeriod", "P30D");
        Db.periodAndDuration(actionPeriod);
        List<String> roles = Db.strings(m.get("memberRoles"));
        if (roles.isEmpty()) {
            roles = List.of("CHAIR", "MEMBER", "MEMBER_SECRETARY", "INVITEE");
        }
        return new CommitteeKind(key, version, Db.str(m, "name", key), quorum, Db.boolVal(m, "chairRequired", true),
                Db.intVal(m, "minNoticeHours", 72), roles, Db.boolVal(m, "minutesApprovalByChair", true), actionPeriod,
                Db.str(m, "actionCalendar", null), ladder(m.get("actionLadder")), Db.str(m, "actionProcessKey", null),
                Numbering.parse(m.get("meetingNumbering"), "meeting:" + key, key), new LinkedHashMap<>(m));
    }

    /** Members needed for quorum out of {@code votingMembers}. */
    public static int quorumRequired(String rule, int votingMembers) {
        String r = rule == null ? "MAJORITY" : rule.trim().toUpperCase();
        try {
            if (r.equals("ALL")) {
                return votingMembers;
            }
            if (r.equals("MAJORITY")) {
                return votingMembers / 2 + 1;
            }
            if (r.endsWith("%")) {
                double pct = Double.parseDouble(r.substring(0, r.length() - 1));
                if (pct <= 0 || pct > 100) {
                    throw config("quorum percentage must be in (0, 100]");
                }
                return (int) Math.ceil(votingMembers * pct / 100.0);
            }
            int n = Integer.parseInt(r);
            if (n < 1) {
                throw config("quorum must be at least 1");
            }
            return Math.min(n, Math.max(votingMembers, 1));
        } catch (NumberFormatException e) {
            throw config("quorumRule must be ALL, MAJORITY, a number or a percentage");
        }
    }

    static CaseFileException config(String message) {
        return new CaseFileException(CaseFileException.Code.INVALID_CONFIG, message);
    }
}

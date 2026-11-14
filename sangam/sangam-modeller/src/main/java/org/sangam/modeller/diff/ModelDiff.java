// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.diff;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * M-125 (§3.3d L1, R43 comparison): semantic diff of two versions of one model resource, for reviews and PR checks.
 * Instead of a raw text diff it reports what a reviewer means by a change:
 * <ul>
 *   <li>{@link Level#ELEMENT}: an element (task, gateway, event, decision, rule, form field ...) added, removed or
 *       changed in type;</li>
 *   <li>{@link Level#FLOW}: a sequence or message flow, association or DMN requirement added, removed or re-wired
 *       ({@code sourceRef}, {@code targetRef}, {@code default}, {@code attachedToRef}, {@code href});</li>
 *   <li>{@link Level#EXPRESSION}: a condition, script, FEEL text, timer, input/output mapping or form expression
 *       changed;</li>
 *   <li>{@link Level#PROPERTY}: any other attribute, text or child-order change (name, hit policy, assignee, label,
 *       required ...).</li>
 * </ul>
 * Elements are matched by their {@code id} (BPMN, DMN, CMMN) or by the {@code id} / {@code key} of form components,
 * so a moved or re-laid-out element is not a change: diagram interchange (BPMNDI, DMNDI, CMMNDI, DC, DI) and
 * modeller-tool attributes ({@code exporter}, {@code exporterVersion}, the Camunda/Operaton modeler namespace) are
 * ignored. The result is deterministic (sorted), so the same two inputs always give the same report.
 *
 * <p>Read-only and pure: no database, no engine. XML is parsed with DOCTYPE declarations refused and external
 * entities disabled. The CLI ({@code tools/sangam-cli}), the REST endpoint and the UI compare view call
 * {@link #compare(String, byte[], byte[])} and render {@link Result#toJson()} or {@link Result#toText()}.
 */
public final class ModelDiff {

    /** What a change is about; see the class comment. Declared in report order. */
    public enum Level { ELEMENT, FLOW, EXPRESSION, PROPERTY }

    /** Added, removed or changed. */
    public enum Kind { ADDED, REMOVED, CHANGED }

    /** The resource formats this diff understands. */
    public enum Format { BPMN, DMN, CMMN, FORM }

    /**
     * One semantic change.
     *
     * @param level       what the change is about
     * @param kind        added, removed or changed
     * @param elementId   id of the element the change belongs to ({@code (root)} when the model root has none)
     * @param elementType the element's type (BPMN/DMN/CMMN local name, or a form component's {@code type})
     * @param name        the element's display name in the newer version (else the older), may be empty
     * @param path        the property inside the element: {@code @attr}, {@code child[1]/@attr},
     *                    {@code child[1]/#text}, {@code #order}, a JSON path; empty for a whole element
     * @param before      the old value, null when added
     * @param after       the new value, null when removed
     */
    public record Change(Level level, Kind kind, String elementId, String elementType, String name, String path,
                         String before, String after) {
        public Change {
            Objects.requireNonNull(level, "level");
            Objects.requireNonNull(kind, "kind");
            elementId = elementId == null ? "" : elementId;
            elementType = elementType == null ? "" : elementType;
            name = name == null ? "" : name;
            path = path == null ? "" : path;
        }
    }

    static final Comparator<Change> ORDER = Comparator.comparing(Change::level)
            .thenComparing(Change::elementId).thenComparing(Change::path).thenComparing(Change::kind);

    /** The diff of one resource. {@link #changes()} is sorted by level, element id, path and kind. */
    public record Result(String resource, Format format, List<Change> changes) {
        public Result {
            List<Change> sorted = new ArrayList<>(changes);
            sorted.sort(ORDER);
            changes = List.copyOf(sorted);
        }

        public boolean hasChanges() {
            return !changes.isEmpty();
        }

        /** Number of changes per level (every level present, zero when none). */
        public Map<Level, Integer> summary() {
            Map<Level, Integer> m = new EnumMap<>(Level.class);
            for (Level l : Level.values()) {
                m.put(l, 0);
            }
            for (Change c : changes) {
                m.merge(c.level(), 1, Integer::sum);
            }
            return m;
        }

        /** The report as a JSON-ready map (CLI {@code --json}, REST body). */
        public Map<String, Object> toJson() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("resource", resource);
            out.put("format", format.name());
            out.put("changed", hasChanges());
            Map<String, Integer> s = new LinkedHashMap<>();
            summary().forEach((k, v) -> s.put(k.name(), v));
            out.put("summary", s);
            List<Map<String, Object>> list = new ArrayList<>();
            for (Change c : changes) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("level", c.level().name());
                m.put("kind", c.kind().name());
                m.put("elementId", c.elementId());
                m.put("elementType", c.elementType());
                m.put("name", c.name());
                m.put("path", c.path());
                m.put("before", c.before());
                m.put("after", c.after());
                list.add(m);
            }
            out.put("changes", list);
            return out;
        }

        /** One line per change, for a terminal or a PR comment: {@code + ELEMENT userTask approve "Approve"}. */
        public String toText() {
            StringBuilder b = new StringBuilder();
            b.append(resource).append(" (").append(format.name()).append("): ");
            b.append(changes.size()).append(changes.size() == 1 ? " change" : " changes").append('\n');
            for (Change c : changes) {
                b.append(switch (c.kind()) {
                    case ADDED -> "+ ";
                    case REMOVED -> "- ";
                    case CHANGED -> "~ ";
                });
                b.append(c.level().name()).append(' ').append(c.elementType()).append(' ').append(c.elementId());
                if (!c.name().isEmpty()) {
                    b.append(" \"").append(c.name()).append('"');
                }
                if (!c.path().isEmpty()) {
                    b.append(' ').append(c.path());
                }
                if (c.kind() == Kind.CHANGED && (c.before() != null || c.after() != null)) {
                    b.append(": ").append(shorten(c.before())).append(" -> ").append(shorten(c.after()));
                }
                b.append('\n');
            }
            return b.toString();
        }

        private static String shorten(String v) {
            if (v == null) {
                return "(none)";
            }
            String one = v.replaceAll("\\s+", " ").strip();
            return one.length() <= 120 ? "\"" + one + "\"" : "\"" + one.substring(0, 117) + "...\"";
        }
    }

    private ModelDiff() {
    }

    /**
     * Compares two versions of the resource {@code resource}. The format comes from the name ({@code .bpmn},
     * {@code .bpmn20.xml}, {@code .dmn}, {@code .cmmn}, {@code .json}) or, when the name does not tell, from the content.
     * Either side may be null or empty: an absent version (a new or deleted file) reports every element of the other
     * as added or removed.
     *
     * @throws IllegalArgumentException when both sides are empty, a side cannot be read as the detected format, or the
     *                                  two sides are of different formats
     */
    public static Result compare(String resource, byte[] before, byte[] after) {
        String name = resource == null ? "" : resource;
        if (!nonEmpty(before) && !nonEmpty(after)) {
            throw new IllegalArgumentException("nothing to compare: both versions of " + name + " are empty");
        }
        Format f = detect(name, nonEmpty(before) ? before : after);
        if (nonEmpty(before) && nonEmpty(after) && detect(name, after) != f) {
            throw new IllegalArgumentException("the two versions of " + name + " are different model formats");
        }
        List<Change> changes = f == Format.FORM
                ? FormJsonDiff.compare(before, after)
                : XmlModelDiff.compare(name, before, after);
        return new Result(name, f, changes);
    }

    /** The format of a resource, from its name first, else its content. */
    public static Format detect(String resource, byte[] content) {
        String n = resource == null ? "" : resource.toLowerCase(Locale.ROOT);
        if (n.endsWith(".bpmn") || n.endsWith(".bpmn20.xml") || n.endsWith(".bpmn2")) {
            return Format.BPMN;
        }
        if (n.endsWith(".dmn") || n.endsWith(".dmn11.xml")) {
            return Format.DMN;
        }
        if (n.endsWith(".cmmn") || n.endsWith(".cmmn11.xml")) {
            return Format.CMMN;
        }
        if (n.endsWith(".json")) {
            return Format.FORM;
        }
        if (!nonEmpty(content)) {
            throw new IllegalArgumentException("cannot tell the model format of " + resource + " (no content)");
        }
        String head = new String(content, 0, Math.min(content.length, 4096), StandardCharsets.UTF_8).stripLeading();
        if (head.startsWith("﻿")) {
            head = head.substring(1).stripLeading();
        }
        if (head.startsWith("{")) {
            return Format.FORM;
        }
        if (head.contains("/spec/BPMN/")) {
            return Format.BPMN;
        }
        if (head.contains("/spec/DMN/") || head.contains("schema/1.0/dmn")) {
            return Format.DMN;
        }
        if (head.contains("/spec/CMMN/")) {
            return Format.CMMN;
        }
        throw new IllegalArgumentException("cannot tell the model format of " + resource);
    }

    static boolean nonEmpty(byte[] b) {
        return b != null && b.length > 0;
    }
}

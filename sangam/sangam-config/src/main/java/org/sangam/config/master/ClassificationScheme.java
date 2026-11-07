// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.master;

import org.sangam.config.common.Tree;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A segmented code scheme from configuration (a chart of accounts, a budget classification, a product hierarchy, a
 * diagnosis code system). Segments are ordered from the most general; each has a width and may be optional, may be
 * validated against master codes, may be owned by another layer (e.g. read-only reference segments) and may depend on
 * its parent segment (the code is unique only under its parent).
 *
 * <pre>
 * classification:
 *   schemes:
 *     - id: HOA
 *       separator: "-"
 *       segments:
 *         - {id: major, width: 4, master: true, owner: reference}
 *         - {id: subMajor, width: 2, master: true, parent: major, owner: reference}
 *         - {id: minor, width: 3, master: true, parent: subMajor, owner: reference}
 *         - {id: subHead, width: 2}
 *         - {id: detailed, width: 2, optional: true}
 *         - {id: object, width: 2, master: true, scheme: OBJECT}
 *       render: "{major}-{subMajor}-{minor}-({subHead})-{detailed}-{object}"   # optional display template
 *       derive:                       # attribute derivation rules, first match per attribute
 *         - {segment: major, prefix: "2", set: {section: REVENUE, nature: EXPENDITURE}}
 *       # optional per segment: hierarchy (parent-child validation), see ClassificationRegistry#validate:
 *       #   {path: [major, subMajor], separator: "-", noneCode: "00", openRuleFlag: minorRule,
 *       #    anyParentFlag: standard, templateWildcard: "*", templateChild: minor}
 *       attributes: [chargedVoted]    # attributes that a full code line carries outside the segments
 * </pre>
 */
public record ClassificationScheme(String id, String separator, List<Segment> segments, String render,
                                   List<Map<String, Object>> derive, List<String> attributes) {

    /**
     * @param master   validate this segment against registered master codes
     * @param parent   the segment this one is nested under (code unique per parent value)
     * @param scheme   validate against another scheme's codes (e.g. a shared object-code list)
     * @param owner    the layer that owns the codes (other layers may not add codes)
     * @param pattern  optional regex the segment value must match (otherwise digits of {@code width})
     */
    public record Segment(String id, int width, boolean optional, boolean master, String parent, String scheme,
                          String owner, String pattern, Map<String, Object> hierarchy) {
    }

    public static ClassificationScheme from(Map<String, Object> m) {
        List<Segment> segs = new ArrayList<>();
        for (Map<String, Object> s : Tree.maps(m.get("segments"))) {
            segs.add(new Segment(Tree.str(s.get("id"), null), Tree.integer(s.get("width"), 0),
                    Tree.bool(s.get("optional"), false), Tree.bool(s.get("master"), false), Tree.str(s.get("parent"), null),
                    Tree.str(s.get("scheme"), null), Tree.str(s.get("owner"), null), Tree.str(s.get("pattern"), null),
                    Tree.map(s.get("hierarchy"))));
        }
        return new ClassificationScheme(Tree.str(m.get("id"), null), Tree.str(m.get("separator"), "-"), List.copyOf(segs),
                Tree.str(m.get("render"), null), Tree.maps(m.get("derive")), Tree.strings(m.get("attributes")));
    }

    public Segment segment(String id) {
        for (Segment s : segments) {
            if (s.id().equals(id)) {
                return s;
            }
        }
        return null;
    }

    public int totalWidth() {
        return segments.stream().mapToInt(Segment::width).sum();
    }
}

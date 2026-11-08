// SPDX-License-Identifier: Apache-2.0
package org.sangam.onboarding.deps;

import java.util.ArrayList;
import java.util.List;

/**
 * A version range in the npm / Cargo style used by pack and plugin descriptors:
 * <ul>
 *   <li>{@code *} or blank: any version;</li>
 *   <li>comparators joined by spaces (AND): {@code >=1.2.0 <2.0.0};</li>
 *   <li>alternatives joined by {@code ||} (OR): {@code ^1.4 || ^2.0};</li>
 *   <li>{@code ^1.2.3} = {@code >=1.2.3 <2.0.0} ({@code ^0.y.z} = {@code <0.(y+1).0});</li>
 *   <li>{@code ~1.2.3} = {@code >=1.2.3 <1.3.0};</li>
 *   <li>{@code 1.2.x} / {@code 1.x} wildcards; a bare version is an exact match.</li>
 * </ul>
 * Pre-release versions satisfy a range only if a comparator names a pre-release of the same major.minor.patch
 * (semver convention), so {@code 0.1.0-SNAPSHOT} matches {@code >=0.1.0-0}.
 */
public final class VersionRange {

    private record Comparator(String op, SemVer v) {
        boolean test(SemVer x) {
            int c = x.compareTo(v);
            return switch (op) {
                case ">" -> c > 0;
                case ">=" -> c >= 0;
                case "<" -> c < 0;
                case "<=" -> c <= 0;
                default -> c == 0;
            };
        }
    }

    private final String text;
    private final List<List<Comparator>> alternatives;

    private VersionRange(String text, List<List<Comparator>> alternatives) {
        this.text = text;
        this.alternatives = alternatives;
    }

    public static final VersionRange ANY = new VersionRange("*", List.of(List.of()));

    public static VersionRange parse(String s) {
        if (s == null || s.isBlank() || s.trim().equals("*")) {
            return ANY;
        }
        List<List<Comparator>> alts = new ArrayList<>();
        for (String alt : s.split("\\|\\|")) {
            List<Comparator> all = new ArrayList<>();
            String[] tokens = alt.trim().split("\\s+");
            for (int i = 0; i < tokens.length; i++) {
                String t = tokens[i];
                if (t.isEmpty()) {
                    continue;
                }
                // allow ">= 1.2.0" with a space after the operator
                if (t.matches("[<>]=?|=") && i + 1 < tokens.length) {
                    t = t + tokens[++i];
                }
                all.addAll(expand(t));
            }
            alts.add(all);
        }
        return new VersionRange(s.trim(), alts);
    }

    private static List<Comparator> expand(String t) {
        if (t.equals("*") || t.equalsIgnoreCase("x")) {
            return List.of();
        }
        if (t.startsWith("^")) {
            SemVer v = SemVer.parse(t.substring(1));
            SemVer upper = v.major() > 0 ? v.nextMajor() : v.minor() > 0 ? v.nextMinor() : v.nextPatch();
            return List.of(new Comparator(">=", v), new Comparator("<", upper));
        }
        if (t.startsWith("~")) {
            SemVer v = SemVer.parse(t.substring(1));
            return List.of(new Comparator(">=", v), new Comparator("<", v.nextMinor()));
        }
        for (String op : List.of(">=", "<=", ">", "<", "=")) {
            if (t.startsWith(op)) {
                return List.of(new Comparator(op, SemVer.parse(t.substring(op.length()))));
            }
        }
        String[] parts = t.split("\\.");
        if (parts.length < 3 || t.contains("x") || t.contains("*")) {
            boolean wild = parts.length < 3 || parts[parts.length - 1].matches("[xX*]");
            if (wild) {
                if (parts.length == 1 || parts[1].matches("[xX*]")) {
                    SemVer v = SemVer.parse(parts[0]);
                    return List.of(new Comparator(">=", v), new Comparator("<", v.nextMajor()));
                }
                SemVer v = SemVer.parse(parts[0] + "." + parts[1]);
                return List.of(new Comparator(">=", v), new Comparator("<", v.nextMinor()));
            }
        }
        return List.of(new Comparator("=", SemVer.parse(t)));
    }

    public boolean contains(SemVer v) {
        for (List<Comparator> alt : alternatives) {
            boolean ok = true;
            for (Comparator c : alt) {
                if (!c.test(v)) {
                    ok = false;
                    break;
                }
            }
            if (ok && v.isPreRelease()) {
                ok = alt.stream().anyMatch(c -> c.v().isPreRelease() && c.v().major() == v.major()
                        && c.v().minor() == v.minor() && c.v().patch() == v.patch());
            }
            if (ok) {
                return true;
            }
        }
        return false;
    }

    /** Lenient check for a version string; an unparseable version never matches a constrained range. */
    public boolean contains(String version) {
        if (this == ANY) {
            return true;
        }
        SemVer v = SemVer.tryParse(version);
        return v != null && contains(v);
    }

    public boolean isAny() {
        return this == ANY || alternatives.stream().allMatch(List::isEmpty);
    }

    @Override
    public String toString() {
        return text;
    }
}

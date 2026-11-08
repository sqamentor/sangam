// SPDX-License-Identifier: Apache-2.0
package org.sangam.onboarding.deps;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A semantic version ({@code MAJOR.MINOR.PATCH[-pre][+build]}, semver.org 2.0.0 precedence). Lenient on input: a
 * missing minor or patch is 0 ({@code 2.2} = {@code 2.2.0}), a {@code -SNAPSHOT} is a pre-release, and a leading
 * {@code v} is ignored. Build metadata is kept but never compared.
 */
public record SemVer(int major, int minor, int patch, String preRelease, String build) implements Comparable<SemVer> {

    private static final Pattern P = Pattern.compile(
            "v?(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?(?:-([0-9A-Za-z.-]+))?(?:\\+([0-9A-Za-z.-]+))?");

    public SemVer {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("negative version component");
        }
        preRelease = preRelease == null || preRelease.isBlank() ? null : preRelease;
        build = build == null || build.isBlank() ? null : build;
    }

    public static SemVer parse(String s) {
        if (s == null) {
            throw new IllegalArgumentException("version is null");
        }
        Matcher m = P.matcher(s.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("not a semantic version: '" + s + "'");
        }
        return new SemVer(Integer.parseInt(m.group(1)), m.group(2) == null ? 0 : Integer.parseInt(m.group(2)),
                m.group(3) == null ? 0 : Integer.parseInt(m.group(3)), m.group(4), m.group(5));
    }

    /** Parses, or returns null for an unparseable value (used for foreign version strings). */
    public static SemVer tryParse(String s) {
        try {
            return parse(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public boolean isPreRelease() {
        return preRelease != null;
    }

    public SemVer withoutPreRelease() {
        return new SemVer(major, minor, patch, null, null);
    }

    public SemVer nextMajor() {
        return new SemVer(major + 1, 0, 0, null, null);
    }

    public SemVer nextMinor() {
        return new SemVer(major, minor + 1, 0, null, null);
    }

    public SemVer nextPatch() {
        return new SemVer(major, minor, patch + 1, null, null);
    }

    @Override
    public int compareTo(SemVer o) {
        int c = Integer.compare(major, o.major);
        if (c == 0) {
            c = Integer.compare(minor, o.minor);
        }
        if (c == 0) {
            c = Integer.compare(patch, o.patch);
        }
        if (c != 0) {
            return c;
        }
        if (Objects.equals(preRelease, o.preRelease)) {
            return 0;
        }
        if (preRelease == null) {
            return 1; // a release ranks above its pre-releases
        }
        if (o.preRelease == null) {
            return -1;
        }
        String[] a = preRelease.split("\\.");
        String[] b = o.preRelease.split("\\.");
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            boolean na = a[i].matches("\\d+");
            boolean nb = b[i].matches("\\d+");
            int x;
            if (na && nb) {
                x = Long.compare(Long.parseLong(a[i]), Long.parseLong(b[i]));
            } else if (na) {
                x = -1;
            } else if (nb) {
                x = 1;
            } else {
                x = a[i].compareTo(b[i]);
            }
            if (x != 0) {
                return x;
            }
        }
        return Integer.compare(a.length, b.length);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SemVer v && compareTo(v) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(major, minor, patch, preRelease);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch + (preRelease == null ? "" : "-" + preRelease)
                + (build == null ? "" : "+" + build);
    }
}

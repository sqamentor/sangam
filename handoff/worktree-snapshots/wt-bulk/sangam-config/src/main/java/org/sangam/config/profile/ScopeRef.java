// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.profile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * An n-level configuration scope: dot-separated codes from the most general to the most specific, for example
 * {@code RJ} (level 1), {@code RJ.PWD} (level 2), {@code ACME.EU.DE.BERLIN} (level 4). The scope is also the engine
 * tenant id. An office overlay inside a scope is addressed as {@code <scope>/<office>}.
 */
public record ScopeRef(List<String> levels) {

    private static final Pattern CODE = Pattern.compile("[A-Z0-9][A-Z0-9_-]{0,47}");

    public ScopeRef {
        if (levels == null || levels.isEmpty()) {
            throw new IllegalArgumentException("scope needs at least one level");
        }
        List<String> n = new ArrayList<>();
        for (String l : levels) {
            String c = l == null ? "" : l.trim().toUpperCase(Locale.ROOT);
            if (!CODE.matcher(c).matches()) {
                throw new IllegalArgumentException("invalid scope code: " + l);
            }
            n.add(c);
        }
        levels = List.copyOf(n);
    }

    public static ScopeRef parse(String scope) {
        if (scope == null || scope.isBlank()) {
            throw new IllegalArgumentException("scope required");
        }
        return new ScopeRef(List.of(scope.trim().split("\\.")));
    }

    public int depth() {
        return levels.size();
    }

    public String root() {
        return levels.get(0);
    }

    /** Level i (1-based) code, or null. */
    public String level(int i) {
        return i >= 1 && i <= levels.size() ? levels.get(i - 1) : null;
    }

    /** Prefix ids from the root to this scope: {@code [RJ, RJ.PWD]}. */
    public List<String> prefixes() {
        List<String> out = new ArrayList<>();
        StringBuilder b = new StringBuilder();
        for (String l : levels) {
            if (!b.isEmpty()) {
                b.append('.');
            }
            b.append(l);
            out.add(b.toString());
        }
        return out;
    }

    public ScopeRef parent() {
        return depth() == 1 ? null : new ScopeRef(levels.subList(0, depth() - 1));
    }

    public ScopeRef child(String code) {
        List<String> l = new ArrayList<>(levels);
        l.add(code);
        return new ScopeRef(l);
    }

    public String id() {
        return String.join(".", levels);
    }

    public String officeId(String office) {
        return id() + "/" + office.trim().toUpperCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return id();
    }
}

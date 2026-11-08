// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.profile;

import org.sangam.config.common.Finding;
import org.sangam.config.common.Tree;
import org.sangam.config.locale.LocaleSettings;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The effective configuration of one scope (and optional office) on one date: the merged data tree, the chain of
 * documents it came from, the binding string a case stores at start, the open placeholders and the findings.
 *
 * @param binding  {@code id@version>id@version...#hash12}: stored on the case as {@code profileVersion}, so a running
 *                 case keeps the configuration in force at its start (GP-19 semantics)
 */
public record ResolvedProfile(String scope, String office, LocalDate asOf, Map<String, Object> data,
                              List<String> chain, String binding, Map<String, String> placeholders,
                              List<Finding> findings) {

    public Object get(String path) {
        return Tree.get(data, path);
    }

    public String str(String path, String def) {
        return Tree.str(get(path), def);
    }

    public boolean bool(String path, boolean def) {
        return Tree.bool(get(path), def);
    }

    public int integer(String path, int def) {
        return Tree.integer(get(path), def);
    }

    /** Decimal value; amount unit suffixes ({@code 10L}, {@code 5M}) are resolved with the profile locale. */
    public BigDecimal amount(String path, BigDecimal def) {
        Object v = get(path);
        if (v == null) {
            return def;
        }
        BigDecimal b = locale().money().parse(v);
        return b == null ? def : b;
    }

    public Map<String, Object> map(String path) {
        return Tree.map(get(path));
    }

    public List<Map<String, Object>> list(String path) {
        return Tree.maps(get(path));
    }

    public List<String> strings(String path) {
        return Tree.strings(get(path));
    }

    public LocaleSettings locale() {
        return LocaleSettings.from(map("locale"));
    }

    /** True if the path (or one of its ancestors) is an unverified placeholder. */
    public boolean isPlaceholder(String path) {
        for (String p : placeholders.keySet()) {
            if (path.equals(p) || path.startsWith(p + ".")) {
                return true;
            }
        }
        return false;
    }

    public boolean hasErrors() {
        return Finding.anyError(findings);
    }
}

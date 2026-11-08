// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.master;

import org.sangam.config.common.EffectiveDated;
import org.sangam.config.common.Tree;
import org.sangam.config.profile.ProfileLoader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bulk seed loader for master codes from CSV or YAML.
 *
 * <p>CSV header (UTF-8, comma, double quotes for fields with commas; {@code #} lines are comments):
 * {@code scheme,segment,parent,code,name_en,name_hi,flags,validFrom,validTo,source,status}. {@code flags} is
 * {@code k=v;k=v}. Any further {@code name_<lang>} column adds a language. YAML: a list of maps with the same keys.
 */
public final class SeedLoader {

    private SeedLoader() {
    }

    public static List<MasterCode> csv(String scope, String text) {
        List<MasterCode> out = new ArrayList<>();
        String[] header = null;
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            List<String> cells = split(line);
            if (header == null) {
                header = cells.toArray(new String[0]);
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < header.length && i < cells.size(); i++) {
                row.put(header[i].trim(), cells.get(i));
            }
            out.add(row(scope, row));
        }
        return out;
    }

    public static List<MasterCode> yaml(String scope, String text) {
        List<MasterCode> out = new ArrayList<>();
        for (Object o : ProfileLoader.parseList(text)) {
            out.add(row(scope, Tree.map(o)));
        }
        return out;
    }

    public static int load(MasterStore store, List<MasterCode> codes) {
        for (MasterCode c : codes) {
            store.put(c);
        }
        return codes.size();
    }

    private static MasterCode row(String scope, Map<String, Object> r) {
        Map<String, String> name = new LinkedHashMap<>();
        Map<String, Object> flags = new LinkedHashMap<>();
        r.forEach((k, v) -> {
            if (k.startsWith("name_") && v != null && !v.toString().isBlank()) {
                name.put(k.substring(5), v.toString());
            }
        });
        Object f = r.get("flags");
        if (f instanceof Map<?, ?> m) {
            flags.putAll(Tree.map(m));
        } else if (f != null) {
            for (String kv : f.toString().split(";")) {
                int eq = kv.indexOf('=');
                if (eq > 0) {
                    flags.put(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
                }
            }
        }
        String status = Tree.str(r.get("status"), null);
        if (status != null && !status.isBlank()) {
            flags.put("evidence", status);
        }
        String parent = Tree.str(r.get("parent"), null);
        return new MasterCode(scope, Tree.str(r.get("scheme"), null), Tree.str(r.get("segment"), null),
                Tree.str(r.get("code"), null), parent == null || parent.isBlank() ? null : parent, name, flags,
                EffectiveDated.date(r.get("validFrom")), EffectiveDated.date(r.get("validTo")), Tree.str(r.get("source"), null));
    }

    static List<String> split(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (q) {
                if (ch == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else if (ch == '"') {
                    q = false;
                } else {
                    cur.append(ch);
                }
            } else if (ch == '"') {
                q = true;
            } else if (ch == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(cur.toString());
        return out;
    }
}

// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.profile;

import org.sangam.config.common.Tree;
import org.sangam.core.layer.CanonicalJson;
import org.sangam.core.layer.Hashes;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One versioned, effective-dated configuration document (a YAML / JSON file or a DB row). Domain knowledge lives only
 * in such documents (ADR-D-20).
 *
 * <p>File shape:
 * <pre>
 * profile:      {id, layer, version, effectiveFrom, effectiveTo, extends: [ids], adopt: [{profile, sections}], name, source}
 * placeholders: [{path, reason}]   # unverified values: configuration placeholders to confirm before go-live
 * locked:       [path, ...]        # paths no later document in a chain may override
 * &lt;section&gt;:    ...                # every other top-level key is a data section
 * </pre>
 *
 * @param id      a scope id ({@code RJ}, {@code RJ.PWD}), an office overlay ({@code RJ.PWD/DIV-1}) or a shared
 *                document referenced through {@code extends} ({@code IN}, {@code IN.PRESET-GENERAL})
 * @param extendsIds shared documents this one builds on, lowest precedence first (resolved before this document)
 * @param adopt   sections taken from another document ("adopted from": {@code [{profile: PB, sections: [rulebooks]}]})
 */
public record ProfileDocument(String id, String layer, String version, LocalDate effectiveFrom, LocalDate effectiveTo,
                              List<String> extendsIds, List<Map<String, Object>> adopt, Map<String, String> name,
                              String source, Map<String, String> placeholders, List<String> locked,
                              Map<String, Object> data) {

    public ProfileDocument {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("profile.id required");
        }
        if (layer == null || layer.isBlank()) {
            throw new IllegalArgumentException("profile.layer required for " + id);
        }
        layer = LayerModel.norm(layer);
        version = version == null ? "0" : version;
        extendsIds = extendsIds == null ? List.of() : List.copyOf(extendsIds);
        adopt = adopt == null ? List.of() : List.copyOf(adopt);
        name = name == null ? Map.of() : Map.copyOf(name);
        placeholders = placeholders == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(placeholders));
        locked = locked == null ? List.of() : List.copyOf(locked);
        data = data == null ? Map.of() : Collections.unmodifiableMap(Tree.copyMap(data));
    }

    public boolean isEffective(LocalDate asOf) {
        return (effectiveFrom == null || !asOf.isBefore(effectiveFrom)) && (effectiveTo == null || asOf.isBefore(effectiveTo));
    }

    /** {@code id@version}. */
    public String ref() {
        return id + "@" + version;
    }

    /** SHA-256 hex over the canonical JSON of the whole document; an approval binds to it. */
    public String contentHash() {
        return Hashes.hex(Hashes.sha256(CanonicalJson.write(toTree())));
    }

    public Object get(String path) {
        return Tree.get(data, path);
    }

    /** The document in file shape, for storage and export. */
    public Map<String, Object> toTree() {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("id", id);
        header.put("layer", layer);
        header.put("version", version);
        header.put("effectiveFrom", effectiveFrom == null ? null : effectiveFrom.toString());
        header.put("effectiveTo", effectiveTo == null ? null : effectiveTo.toString());
        header.put("extends", extendsIds);
        header.put("adopt", adopt);
        header.put("name", name);
        header.put("source", source);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("profile", header);
        List<Map<String, Object>> ph = new ArrayList<>();
        placeholders.forEach((p, r) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", p);
            m.put("reason", r);
            ph.add(m);
        });
        out.put("placeholders", ph);
        out.put("locked", locked);
        out.putAll(data);
        return out;
    }

    /** A copy with another version and effective window (used by the admin service when publishing). */
    public ProfileDocument withVersion(String newVersion, LocalDate from, LocalDate to) {
        return new ProfileDocument(id, layer, newVersion, from, to, extendsIds, adopt, name, source, placeholders, locked,
                data);
    }
}

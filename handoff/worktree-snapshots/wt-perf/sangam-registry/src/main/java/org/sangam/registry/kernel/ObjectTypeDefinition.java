// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry.kernel;

import org.sangam.registry.RegistryException;
import org.sangam.registry.support.Maps;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A configured object type: everything domain-specific about a registered object is here, as data (ADR-D-20).
 * Example (a pack's JSON):
 * <pre>{@code
 * { "typeKey": "trade-licence", "kind": "INSTRUMENT", "title": "Trade licence",
 *   "schema": { "fields": [ {"name": "premises", "type": "STRING", "required": true, "public": true},
 *                           {"name": "category", "type": "CODE", "values": ["A","B"], "immutable": true} ] },
 *   "validity": { "period": "P1Y", "alignTo": "FISCAL_YEAR_END", "fiscalStartMonth": 4,
 *                 "renewalWindow": "P30D", "grace": "P30D" },
 *   "numbering": { "series": "TL", "scopeAttribute": "office", "assignOn": "ISSUE" },
 *   "verification": { "publicFields": ["premises"], "showHolderName": true },
 *   "documents": { "ISSUE": "tpl-trade-licence", "RENEWAL": "tpl-trade-licence", "publishChannel": "locker" },
 *   "transferable": true }
 * }</pre>
 * The lifecycle defaults by kind: {@link Lifecycle#instrument()} for instruments, {@link Lifecycle#simple()} otherwise.
 */
public record ObjectTypeDefinition(String typeKey, int version, ObjectKind kind, String title, ObjectSchema schema,
                                   Lifecycle lifecycle, ValidityRule validity, Numbering numbering,
                                   Verification verification, Map<String, String> documents, String publishChannel,
                                   boolean transferable, List<String> parentTypes, Map<String, Object> extensions) {

    /** Numbering: series key, the attribute whose value is the series scope (office, unit), and when to assign. */
    public record Numbering(String series, String scopeAttribute, String assignOn) {
        public static Numbering from(Map<String, Object> m) {
            if (m == null || m.isEmpty() || Maps.str(m, "series") == null) {
                return null;
            }
            return new Numbering(Maps.str(m, "series"), Maps.str(m, "scopeAttribute"),
                    Maps.str(m, "assignOn", "CREATE").toUpperCase());
        }

        Map<String, Object> toMap() {
            return Maps.of("series", series, "scopeAttribute", scopeAttribute, "assignOn", assignOn);
        }
    }

    /** Public verification: which attributes appear and whether the holder name is shown (masked by the privacy port). */
    public record Verification(List<String> publicFields, boolean showHolderName, boolean qr) {
        static Verification from(Map<String, Object> m, ObjectSchema schema) {
            List<String> pf = Maps.strings(m, "publicFields");
            return new Verification(pf.isEmpty() ? schema.publicFields() : pf, Maps.bool(m, "showHolderName", true),
                    Maps.bool(m, "qr", true));
        }

        Map<String, Object> toMap() {
            return Maps.of("publicFields", publicFields, "showHolderName", showHolderName, "qr", qr);
        }
    }

    public static ObjectTypeDefinition from(Map<String, Object> m, int version) {
        String key = Maps.required(m, "typeKey");
        if (key.length() > 64 || !key.matches("[A-Za-z0-9._:-]+")) {
            throw RegistryException.invalid("typeKey must be 1..64 characters of [A-Za-z0-9._:-]");
        }
        ObjectKind kind;
        try {
            kind = ObjectKind.valueOf(Maps.str(m, "kind", "RECORD").toUpperCase());
        } catch (IllegalArgumentException e) {
            throw RegistryException.invalid("unknown kind: " + Maps.str(m, "kind"));
        }
        ObjectSchema schema = ObjectSchema.from(Maps.map(m, "schema"));
        Lifecycle fallback = kind == ObjectKind.INSTRUMENT ? Lifecycle.instrument() : Lifecycle.simple();
        Map<String, Object> docs = Maps.map(m, "documents");
        Map<String, String> templates = new LinkedHashMap<>();
        docs.forEach((k, v) -> {
            if (!"publishChannel".equals(k) && v != null) {
                templates.put(k.toUpperCase(), v.toString());
            }
        });
        return new ObjectTypeDefinition(key, version, kind, Maps.str(m, "title", key), schema,
                Lifecycle.from(Maps.map(m, "lifecycle"), fallback), ValidityRule.from(Maps.map(m, "validity")),
                Numbering.from(Maps.map(m, "numbering")), Verification.from(Maps.map(m, "verification"), schema),
                Map.copyOf(templates), Maps.str(docs, "publishChannel"), Maps.bool(m, "transferable", false),
                Maps.strings(m, "parentTypes"), Maps.map(m, "extensions"));
    }

    public Map<String, Object> toMap() {
        Map<String, Object> docs = new LinkedHashMap<>(documents);
        if (publishChannel != null) {
            docs.put("publishChannel", publishChannel);
        }
        return Maps.of("typeKey", typeKey, "version", version, "kind", kind.name(), "title", title,
                "schema", schema.toMap(), "lifecycle", lifecycle.toMap(), "validity", validity.toMap(),
                "numbering", numbering == null ? null : numbering.toMap(), "verification", verification.toMap(),
                "documents", docs, "transferable", transferable, "parentTypes", parentTypes,
                "extensions", extensions == null || extensions.isEmpty() ? null : extensions);
    }

    /** The output template for a purpose (ISSUE, RENEWAL, DUPLICATE, AMENDMENT, RECEIPT ...), falling back to ISSUE. */
    public String template(String purpose) {
        String t = documents.get(purpose.toUpperCase());
        return t != null ? t : documents.get("ISSUE");
    }
}

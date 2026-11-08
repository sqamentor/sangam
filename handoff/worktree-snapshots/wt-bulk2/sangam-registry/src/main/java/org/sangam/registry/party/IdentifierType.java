// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry.party;

import org.sangam.registry.RegistryException;
import org.sangam.registry.support.Codes;
import org.sangam.registry.support.Maps;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * An identifier type, as configuration (any jurisdiction's or organisation's identifiers):
 * <pre>{@code
 * { "typeKey": "national-id", "label": "National identity number", "pattern": "[0-9]{12}",
 *   "normalise": ["STRIP_SPACES", "DIGITS_ONLY"], "checksum": "VERHOEFF", "unique": true, "sensitive": true,
 *   "matchWeight": 12, "appliesTo": "PERSON" }
 * }</pre>
 * Normalisation steps run in order ({@code UPPER}, {@code LOWER}, {@code STRIP_SPACES}, {@code STRIP_PUNCT},
 * {@code DIGITS_ONLY}, {@code TRIM}); the pattern and checksum ({@code NONE}, {@code LUHN}, {@code VERHOEFF},
 * {@code MOD11}, {@code MOD97}) are checked on the normalised value. A {@code unique} type allows one active party per
 * value; a {@code sensitive} type is stored only as a keyed hash, a mask and a vault token.
 */
public record IdentifierType(String typeKey, String label, String pattern, List<String> normalise, String checksum,
                             boolean unique, boolean sensitive, double matchWeight, String appliesTo) {

    public static IdentifierType from(Map<String, Object> m) {
        String key = Maps.required(m, "typeKey");
        if (key.length() > 64) {
            throw RegistryException.invalid("identifier typeKey longer than 64 characters");
        }
        String applies = Maps.str(m, "appliesTo", "ANY").toUpperCase();
        if (!List.of("ANY", "PERSON", "ORGANISATION").contains(applies)) {
            throw RegistryException.invalid("appliesTo must be ANY, PERSON or ORGANISATION");
        }
        String checksum = Maps.str(m, "checksum", "NONE").toUpperCase();
        if (!List.of("NONE", "LUHN", "VERHOEFF", "MOD11", "MOD97").contains(checksum)) {
            throw RegistryException.invalid("unknown checksum " + checksum);
        }
        String pattern = Maps.str(m, "pattern");
        if (pattern != null) {
            Pattern.compile(pattern);
        }
        Object w = m.get("matchWeight");
        double weight = w == null ? 8.0 : Double.parseDouble(w.toString());
        return new IdentifierType(key, Maps.str(m, "label", key), pattern,
                Maps.strings(m, "normalise").stream().map(String::toUpperCase).toList(), checksum,
                Maps.bool(m, "unique", false), Maps.bool(m, "sensitive", false), weight, applies);
    }

    public Map<String, Object> toMap() {
        return Maps.of("typeKey", typeKey, "label", label, "pattern", pattern, "normalise", normalise,
                "checksum", checksum, "unique", unique, "sensitive", sensitive, "matchWeight", matchWeight,
                "appliesTo", appliesTo);
    }

    /** A permissive type for keys that are not configured (only when the catalogue allows it). */
    public static IdentifierType open(String key) {
        return new IdentifierType(key, key, null, List.of("TRIM"), "NONE", false, false, 4.0, "ANY");
    }

    public String normaliseValue(String raw) {
        if (raw == null) {
            throw RegistryException.invalid("identifier value is required");
        }
        String v = raw;
        for (String step : normalise) {
            v = switch (step) {
                case "UPPER" -> v.toUpperCase();
                case "LOWER" -> v.toLowerCase();
                case "STRIP_SPACES" -> v.replaceAll("\\s+", "");
                case "STRIP_PUNCT" -> v.replaceAll("[\\p{P}\\p{S}]+", "");
                case "DIGITS_ONLY" -> v.replaceAll("\\D+", "");
                case "TRIM" -> v.trim();
                default -> throw RegistryException.invalid("unknown normalisation step " + step);
            };
        }
        v = v.trim();
        if (v.isEmpty()) {
            throw RegistryException.invalid(typeKey + ": empty after normalisation");
        }
        return v;
    }

    /** Normalises and validates; returns the normalised value. */
    public String validate(String raw) {
        String v = normaliseValue(raw);
        if (pattern != null && !Pattern.compile(pattern).matcher(v).matches()) {
            throw RegistryException.invalid(typeKey + ": value does not match the configured format");
        }
        boolean ok = switch (checksum) {
            case "LUHN" -> Codes.luhnValid(v);
            case "VERHOEFF" -> Codes.verhoeffValid(v);
            case "MOD11" -> Codes.mod11Valid(v);
            case "MOD97" -> Codes.mod97Valid(v);
            default -> true;
        };
        if (!ok) {
            throw RegistryException.invalid(typeKey + ": check digit is wrong");
        }
        return v;
    }

    public boolean appliesTo(String partyKind) {
        return "ANY".equals(appliesTo) || appliesTo.equals(partyKind);
    }
}

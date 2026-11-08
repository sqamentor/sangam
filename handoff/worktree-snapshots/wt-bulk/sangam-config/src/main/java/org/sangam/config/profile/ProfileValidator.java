// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.profile;

import org.sangam.config.common.Finding;
import org.sangam.config.common.Tree;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Profile linter (IFMS-STATES 6.5 step 3, generalised): document structure, then the resolved chain, then pluggable
 * checks contributed by other components (classification references, document-type chains, connector bindings).
 * Rules a deployment wants beyond these are data: {@code validation.required: [paths]} and
 * {@code validation.enums: {path: [allowed values]}} in any profile of the chain.
 */
public final class ProfileValidator {

    /** A pluggable check over a resolved profile (e.g. "every document type's classification patterns resolve"). */
    @FunctionalInterface
    public interface Check {
        List<Finding> check(ResolvedProfile profile);
    }

    private static final Pattern ID = Pattern.compile("[A-Z0-9][A-Z0-9_.\\-/]{0,127}");

    private final LayerModel layers;
    private final List<Check> checks = new ArrayList<>();

    public ProfileValidator(LayerModel layers) {
        this.layers = layers == null ? LayerModel.open() : layers;
    }

    public ProfileValidator with(Check c) {
        checks.add(c);
        return this;
    }

    /** Checks one document on its own. */
    public List<Finding> validateDocument(ProfileDocument d) {
        List<Finding> out = new ArrayList<>();
        if (!ID.matcher(d.id()).matches()) {
            out.add(Finding.error("ID_INVALID", "profile.id", "invalid id '" + d.id() + "'", null));
        }
        if (!layers.known(d.layer())) {
            out.add(Finding.error("LAYER_UNKNOWN", "profile.layer", "layer '" + d.layer() + "' not in the layer model", null));
        }
        if (d.effectiveFrom() != null && d.effectiveTo() != null && !d.effectiveTo().isAfter(d.effectiveFrom())) {
            out.add(Finding.error("DATES_INVALID", "profile.effectiveTo", "effectiveTo must be after effectiveFrom", null));
        }
        if (d.id().equals(d.extendsIds().stream().filter(d.id()::equals).findFirst().orElse(null))) {
            out.add(Finding.error("EXTENDS_SELF", "profile.extends", "a profile cannot extend itself", null));
        }
        for (String p : d.placeholders().keySet()) {
            if (Tree.get(d.data(), p) == null && !p.contains("*")) {
                out.add(Finding.warn("PLACEHOLDER_NO_VALUE", p, "placeholder path has no value in the document", null));
            }
        }
        rowDates(d.data(), "", out);
        return out;
    }

    /** Checks the resolved chain: resolver findings, data-declared rules, plug-in checks. */
    public List<Finding> validateResolved(ResolvedProfile r) {
        List<Finding> out = new ArrayList<>(r.findings());
        for (String req : r.strings("validation.required")) {
            if (r.get(req) == null) {
                out.add(Finding.error("REQUIRED_MISSING", req, "required by validation.required", null));
            }
        }
        for (Map.Entry<String, Object> e : r.map("validation.enums").entrySet()) {
            Object v = r.get(e.getKey());
            List<String> allowed = Tree.strings(e.getValue());
            if (v != null && !allowed.contains(v.toString())) {
                out.add(Finding.error("ENUM_INVALID", e.getKey(), "'" + v + "' not in " + allowed, null));
            }
        }
        for (Check c : checks) {
            out.addAll(c.check(r));
        }
        return out;
    }

    public List<Finding> validate(ProfileResolver resolver, String scope, String office, LocalDate asOf) {
        return validateResolved(resolver.resolve(scope, office, asOf));
    }

    private static void rowDates(Object v, String path, List<Finding> out) {
        if (v instanceof Map<?, ?> m) {
            Object f = m.get("effectiveFrom");
            Object t = m.get("effectiveTo");
            if (f != null && t != null) {
                try {
                    if (!LocalDate.parse(t.toString()).isAfter(LocalDate.parse(f.toString()))) {
                        out.add(Finding.error("ROW_DATES_INVALID", path, "effectiveTo must be after effectiveFrom", null));
                    }
                } catch (RuntimeException e) {
                    out.add(Finding.error("ROW_DATE_FORMAT", path, "dates must be ISO yyyy-mm-dd", null));
                }
            }
            for (Map.Entry<?, ?> e : m.entrySet()) {
                rowDates(e.getValue(), path.isEmpty() ? e.getKey().toString() : path + "." + e.getKey(), out);
            }
        } else if (v instanceof List<?> l) {
            for (int i = 0; i < l.size(); i++) {
                rowDates(l.get(i), path + "." + i, out);
            }
        }
    }
}
